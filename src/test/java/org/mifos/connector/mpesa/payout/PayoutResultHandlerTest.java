package org.mifos.connector.mpesa.payout;

import io.camunda.zeebe.client.api.command.ClientStatusException;
import io.grpc.Status;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mifos.connector.mpesa.zeebe.ZeebeMocks;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.ERROR_CODE;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.ERROR_DESCRIPTION;
import static org.mifos.connector.mpesa.payout.PayoutTestData.TRANSACTION_ID;
import static org.mifos.connector.mpesa.payout.PayoutTestData.paymentResult;
import static org.mifos.connector.mpesa.payout.PayoutTestData.statusResult;
import static org.mifos.connector.mpesa.payout.PayoutTestData.token;
import static org.mifos.connector.mpesa.payout.PayoutVariables.PAYOUT_CONVERSATION_ID;
import static org.mifos.connector.mpesa.payout.PayoutVariables.PAYOUT_FAILED;
import static org.mifos.connector.mpesa.payout.PayoutVariables.PAYOUT_RECEIVER_NAME;
import static org.mifos.connector.mpesa.payout.PayoutVariables.PAYOUT_RESULT_MESSAGE;
import static org.mifos.connector.mpesa.zeebe.ZeebeVariables.SERVER_TRANSACTION_RECEIPT_NUMBER;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PayoutResultHandlerTest {

    private static final String TOKEN = token(TRANSACTION_ID);

    private ZeebeMocks zeebe;
    private PayoutStore store;
    private MeterRegistry registry;
    private PayoutResultHandler handler;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        zeebe = new ZeebeMocks();
        store = PayoutTestData.memoryStore();
        store.save(new PayoutRecord(TRANSACTION_ID, "fineract", 1500, 254708374149L, 0));
        registry = new SimpleMeterRegistry();
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            ((Consumer<MeterRegistry>) invocation.getArgument(0)).accept(registry);
            return null;
        }).when(provider).ifAvailable(any());
        handler = new PayoutResultHandler(zeebe.client, store, Duration.ofSeconds(5), Duration.ofHours(1), provider,
                PayoutTestData.SIGNER);
    }

    @Test
    void onPaymentResult_success_shouldPublishOnceWithReceiptAndRecordBalance() {
        handler.onPaymentResult(paymentResult(TRANSACTION_ID, 0, "1500"), TOKEN);

        verify(zeebe.publishStep1).messageName(PAYOUT_RESULT_MESSAGE);
        verify(zeebe.publishStep2).correlationKey(TRANSACTION_ID);
        verify(zeebe.publish).messageId(PAYOUT_RESULT_MESSAGE + "-" + TRANSACTION_ID);
        verify(zeebe.publish).timeToLive(Duration.ofHours(1));
        Map<String, Object> published = zeebe.publishedVariables();
        assertEquals(false, published.get(PAYOUT_FAILED));
        assertEquals("NLJ41HAY6Q", published.get(SERVER_TRANSACTION_RECEIPT_NUMBER));
        assertEquals("254708374149 - John Doe", published.get(PAYOUT_RECEIVER_NAME));
        assertEquals("AG_20191219_00005797af5d7d75f652", published.get(PAYOUT_CONVERSATION_ID));
        assertEquals(10116.0, registry.get("mpesa.b2c.utility.balance").tag("account", "fineract").gauge().value());
    }

    @Test
    void onPaymentResult_failure_shouldPublishFailureWithSafaricomCode() {
        handler.onPaymentResult(paymentResult(TRANSACTION_ID, 2001, null), TOKEN);

        Map<String, Object> published = zeebe.publishedVariables();
        assertEquals(true, published.get(PAYOUT_FAILED));
        assertEquals("2001", published.get(ERROR_CODE));
        assertEquals("The initiator information is invalid.", published.get(ERROR_DESCRIPTION));
    }

    @Test
    void onPaymentResult_forUnknownPayout_shouldNotPublish() {
        handler.onPaymentResult(paymentResult("someone-else", 0, "1500"), token("someone-else"));

        verify(zeebe.client, never()).newPublishMessageCommand();
    }

    @Test
    void onPaymentResult_withDifferentAmount_shouldNotPublish() {
        handler.onPaymentResult(paymentResult(TRANSACTION_ID, 0, "15000"), TOKEN);

        verify(zeebe.client, never()).newPublishMessageCommand();
    }

    @Test
    void onPaymentResult_unreadable_shouldNotPublish() {
        handler.onPaymentResult("<html>oops</html>", TOKEN);
        handler.onPaymentResult(null, TOKEN);

        verify(zeebe.client, never()).newPublishMessageCommand();
    }

    @Test
    @SuppressWarnings("unchecked")
    void onPaymentResult_whenAlreadyPublished_shouldIgnoreTheDuplicate() {
        when(zeebe.future.join(anyLong(), any(TimeUnit.class)))
                .thenThrow(new ClientStatusException(Status.ALREADY_EXISTS, null));

        handler.onPaymentResult(paymentResult(TRANSACTION_ID, 0, "1500"), TOKEN);

        verify(zeebe.publish).send();
    }

    @Test
    @SuppressWarnings("unchecked")
    void onPaymentResult_whenPublishFails_shouldThrowSoTheCallbackIsAnsweredWithAnError() {
        when(zeebe.future.join(anyLong(), any(TimeUnit.class)))
                .thenThrow(new ClientStatusException(Status.UNAVAILABLE, null));

        assertThrows(ClientStatusException.class,
                () -> handler.onPaymentResult(paymentResult(TRANSACTION_ID, 0, "1500"), token(TRANSACTION_ID)));
    }

    @Test
    void onStatusResult_completed_shouldPublishSuccess() {
        handler.onStatusResult(statusResult(TRANSACTION_ID, "Completed", "1500"), TOKEN);

        verify(zeebe.publish).messageId(PAYOUT_RESULT_MESSAGE + "-" + TRANSACTION_ID);
        Map<String, Object> published = zeebe.publishedVariables();
        assertEquals(false, published.get(PAYOUT_FAILED));
        assertEquals("MBN31H462N", published.get(SERVER_TRANSACTION_RECEIPT_NUMBER));
    }

    @Test
    void onStatusResult_notCompleted_shouldLeaveThePayoutUnknown() {
        handler.onStatusResult(statusResult(TRANSACTION_ID, "Pending", "1500"), TOKEN);
        handler.onStatusResult(statusResult(TRANSACTION_ID, "Completed", "1500").replace("\"ResultCode\":0",
                "\"ResultCode\":\"SFC_IC0003\""), TOKEN);

        verify(zeebe.client, never()).newPublishMessageCommand();
    }

    @Test
    void onStatusResult_withDifferentAmount_shouldNotPublish() {
        handler.onStatusResult(statusResult(TRANSACTION_ID, "Completed", "10"), TOKEN);

        verify(zeebe.client, never()).newPublishMessageCommand();
    }

    @Test
    void onStatusResult_forAnotherTransaction_shouldNotPublish() {
        String body = statusResult(TRANSACTION_ID, "Completed", "1500")
                .replace("{\"Key\":\"OriginatorConversationID\",\"Value\":\"" + TRANSACTION_ID + "\"}",
                        "{\"Key\":\"OriginatorConversationID\",\"Value\":\"other\"}");

        handler.onStatusResult(body, TOKEN);

        verify(zeebe.client, never()).newPublishMessageCommand();
    }

    @Test
    void onPaymentResult_withoutReceipt_shouldUseTheTransactionId() {
        handler.onPaymentResult(paymentResult(TRANSACTION_ID, 0, "1500")
                .replace("{\"Key\":\"TransactionReceipt\",\"Value\":\"NLJ41HAY6Q\"},", ""), TOKEN);

        assertEquals("NLJ41HAY6Q", zeebe.publishedVariables().get(SERVER_TRANSACTION_RECEIPT_NUMBER));
    }

    @Test
    void onPaymentResult_withoutBalance_shouldPublishWithoutRecordingIt() {
        handler.onPaymentResult(paymentResult(TRANSACTION_ID, 0, "1500")
                .replace("{\"Key\":\"B2CUtilityAccountAvailableFunds\",\"Value\":10116.00},", ""), TOKEN);

        assertEquals(false, zeebe.publishedVariables().get(PAYOUT_FAILED));
        assertEquals(0, registry.find("mpesa.b2c.utility.balance").gauges().size());
    }

    @Test
    void onPaymentResult_withUnreadableBalance_shouldPublishWithoutRecordingIt() {
        handler.onPaymentResult(paymentResult(TRANSACTION_ID, 0, "1500")
                .replace("\"Value\":10116.00", "\"Value\":\"n/a\""), TOKEN);

        assertEquals(false, zeebe.publishedVariables().get(PAYOUT_FAILED));
        assertEquals(0, registry.find("mpesa.b2c.utility.balance").gauges().size());
    }

    @Test
    void onPaymentResult_forPayoutWithoutAccount_shouldPublishWithoutRecordingTheBalance() {
        store.save(new PayoutRecord("tx-no-account", null, 1500, 254708374149L, 0));

        handler.onPaymentResult(paymentResult("tx-no-account", 0, "1500"), token("tx-no-account"));

        assertEquals(false, zeebe.publishedVariables().get(PAYOUT_FAILED));
        assertEquals(0, registry.find("mpesa.b2c.utility.balance").gauges().size());
    }

    @Test
    void onPaymentResult_withUnreadableOrMissingAmount_shouldNotPublish() {
        handler.onPaymentResult(paymentResult(TRANSACTION_ID, 0, "\"lots\""), TOKEN);
        handler.onPaymentResult(paymentResult(TRANSACTION_ID, 0, "1500")
                .replace("{\"Key\":\"TransactionAmount\",\"Value\":1500},", ""), TOKEN);

        verify(zeebe.client, never()).newPublishMessageCommand();
    }

    @Test
    void onPaymentResult_withoutOriginatorConversationId_shouldNotPublish() {
        handler.onPaymentResult(paymentResult(TRANSACTION_ID, 0, "1500")
                .replace("\"OriginatorConversationID\":\"" + TRANSACTION_ID + "\",", ""), TOKEN);

        verify(zeebe.client, never()).newPublishMessageCommand();
    }

    @Test
    void onStatusResult_unreadable_shouldNotPublish() {
        handler.onStatusResult("<html>Bad Gateway</html>", TOKEN);

        verify(zeebe.client, never()).newPublishMessageCommand();
    }

    @Test
    void onStatusResult_withoutOccasion_shouldFindThePayoutByItsOriginatorConversationId() {
        handler.onStatusResult(statusResult(TRANSACTION_ID, "Completed", "1500")
                .replace("\"ReferenceData\":{\"ReferenceItem\":{\"Key\":\"Occasion\",\"Value\":\"" + TRANSACTION_ID
                        + "\"}},", ""), TOKEN);

        assertEquals("MBN31H462N", zeebe.publishedVariables().get(SERVER_TRANSACTION_RECEIPT_NUMBER));
    }

    @Test
    void onStatusResult_withoutQueriedId_shouldTrustTheOccasion() {
        handler.onStatusResult(statusResult(TRANSACTION_ID, "Completed", "1500")
                .replace("{\"Key\":\"OriginatorConversationID\",\"Value\":\"" + TRANSACTION_ID + "\"},", ""), TOKEN);

        assertEquals(false, zeebe.publishedVariables().get(PAYOUT_FAILED));
    }

    @Test
    void onStatusResult_failedQuery_shouldLeaveThePayoutUnknown() {
        handler.onStatusResult(statusResult(TRANSACTION_ID, "Completed", "1500")
                .replace("\"ResultCode\":0", "\"ResultCode\":2001"), TOKEN);

        verify(zeebe.client, never()).newPublishMessageCommand();
    }

    @Test
    void onStatusResult_forUnknownPayout_shouldNotPublish() {
        handler.onStatusResult(statusResult("someone-else", "Completed", "1500"), token("someone-else"));

        verify(zeebe.client, never()).newPublishMessageCommand();
    }

    @Test
    void onPaymentResult_withWrongOrMissingToken_shouldNotPublish() {
        // A forged failure for a known transaction id must not fail a payout that may have been paid
        handler.onPaymentResult(paymentResult(TRANSACTION_ID, 2001, null), "forged");
        handler.onPaymentResult(paymentResult(TRANSACTION_ID, 2001, null), null);
        handler.onPaymentResult(paymentResult(TRANSACTION_ID, 2001, null), token("another-transaction"));

        verify(zeebe.client, never()).newPublishMessageCommand();
    }

    @Test
    void onStatusResult_withWrongToken_shouldNotPublish() {
        handler.onStatusResult(statusResult(TRANSACTION_ID, "Completed", "1500"), "forged");

        verify(zeebe.client, never()).newPublishMessageCommand();
    }

    @Test
    void onPaymentResult_withoutResultCode_shouldLeaveThePayoutUnknown() {
        // A result without a code proves nothing: it must not be published as a failure
        handler.onPaymentResult("{\"Result\":{\"OriginatorConversationID\":\"" + TRANSACTION_ID + "\"}}", TOKEN);
        handler.onPaymentResult("{\"Result\":{\"ResultCode\":null,\"OriginatorConversationID\":\""
                + TRANSACTION_ID + "\"}}", TOKEN);

        verify(zeebe.client, never()).newPublishMessageCommand();
    }

    @Test
    void onPaymentResult_withAlphanumericFailureCode_shouldPublishTheFailure() {
        handler.onPaymentResult(paymentResult(TRANSACTION_ID, 2001, null)
                .replace("\"ResultCode\":2001", "\"ResultCode\":\"SFC_IC0003\""), TOKEN);

        assertEquals(true, zeebe.publishedVariables().get(PAYOUT_FAILED));
        assertEquals("SFC_IC0003", zeebe.publishedVariables().get(ERROR_CODE));
    }
}
