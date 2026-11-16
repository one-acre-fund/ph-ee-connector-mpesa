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

    private ZeebeMocks zeebe;
    private PayoutStore store;
    private MeterRegistry registry;
    private PayoutResultHandler handler;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        zeebe = new ZeebeMocks();
        store = new InMemoryPayoutStore();
        store.save(new PayoutRecord(TRANSACTION_ID, "fineract", 1500, 254708374149L, 0));
        registry = new SimpleMeterRegistry();
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            ((Consumer<MeterRegistry>) invocation.getArgument(0)).accept(registry);
            return null;
        }).when(provider).ifAvailable(any());
        handler = new PayoutResultHandler(zeebe.client, store, Duration.ofSeconds(5), Duration.ofHours(1), provider);
    }

    @Test
    void onPaymentResult_success_shouldPublishOnceWithReceiptAndRecordBalance() {
        handler.onPaymentResult(paymentResult(TRANSACTION_ID, 0, "1500"));

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
        handler.onPaymentResult(paymentResult(TRANSACTION_ID, 2001, null));

        Map<String, Object> published = zeebe.publishedVariables();
        assertEquals(true, published.get(PAYOUT_FAILED));
        assertEquals("2001", published.get(ERROR_CODE));
        assertEquals("The initiator information is invalid.", published.get(ERROR_DESCRIPTION));
    }

    @Test
    void onPaymentResult_forUnknownPayout_shouldNotPublish() {
        handler.onPaymentResult(paymentResult("someone-else", 0, "1500"));

        verify(zeebe.client, never()).newPublishMessageCommand();
    }

    @Test
    void onPaymentResult_withDifferentAmount_shouldNotPublish() {
        handler.onPaymentResult(paymentResult(TRANSACTION_ID, 0, "15000"));

        verify(zeebe.client, never()).newPublishMessageCommand();
    }

    @Test
    void onPaymentResult_unreadable_shouldNotPublish() {
        handler.onPaymentResult("<html>oops</html>");
        handler.onPaymentResult(null);

        verify(zeebe.client, never()).newPublishMessageCommand();
    }

    @Test
    @SuppressWarnings("unchecked")
    void onPaymentResult_whenAlreadyPublished_shouldIgnoreTheDuplicate() {
        when(zeebe.future.join(anyLong(), any(TimeUnit.class)))
                .thenThrow(new ClientStatusException(Status.ALREADY_EXISTS, null));

        handler.onPaymentResult(paymentResult(TRANSACTION_ID, 0, "1500"));

        verify(zeebe.publish).send();
    }

    @Test
    @SuppressWarnings("unchecked")
    void onPaymentResult_whenPublishFails_shouldThrowSoTheCallbackIsAnsweredWithAnError() {
        when(zeebe.future.join(anyLong(), any(TimeUnit.class)))
                .thenThrow(new ClientStatusException(Status.UNAVAILABLE, null));

        assertThrows(ClientStatusException.class,
                () -> handler.onPaymentResult(paymentResult(TRANSACTION_ID, 0, "1500")));
    }

    @Test
    void onStatusResult_completed_shouldPublishSuccess() {
        handler.onStatusResult(statusResult(TRANSACTION_ID, "Completed", "1500"));

        verify(zeebe.publish).messageId(PAYOUT_RESULT_MESSAGE + "-" + TRANSACTION_ID);
        Map<String, Object> published = zeebe.publishedVariables();
        assertEquals(false, published.get(PAYOUT_FAILED));
        assertEquals("MBN31H462N", published.get(SERVER_TRANSACTION_RECEIPT_NUMBER));
    }

    @Test
    void onStatusResult_notCompleted_shouldLeaveThePayoutUnknown() {
        handler.onStatusResult(statusResult(TRANSACTION_ID, "Pending", "1500"));
        handler.onStatusResult(statusResult(TRANSACTION_ID, "Completed", "1500").replace("\"ResultCode\":0",
                "\"ResultCode\":\"SFC_IC0003\""));

        verify(zeebe.client, never()).newPublishMessageCommand();
    }

    @Test
    void onStatusResult_withDifferentAmount_shouldNotPublish() {
        handler.onStatusResult(statusResult(TRANSACTION_ID, "Completed", "10"));

        verify(zeebe.client, never()).newPublishMessageCommand();
    }

    @Test
    void onStatusResult_forAnotherTransaction_shouldNotPublish() {
        String body = statusResult(TRANSACTION_ID, "Completed", "1500")
                .replace("{\"Key\":\"OriginatorConversationID\",\"Value\":\"" + TRANSACTION_ID + "\"}",
                        "{\"Key\":\"OriginatorConversationID\",\"Value\":\"other\"}");

        handler.onStatusResult(body);

        verify(zeebe.client, never()).newPublishMessageCommand();
    }

    @Test
    void onPaymentResult_withoutReceipt_shouldUseTheTransactionId() {
        handler.onPaymentResult(paymentResult(TRANSACTION_ID, 0, "1500")
                .replace("{\"Key\":\"TransactionReceipt\",\"Value\":\"NLJ41HAY6Q\"},", ""));

        assertEquals("NLJ41HAY6Q", zeebe.publishedVariables().get(SERVER_TRANSACTION_RECEIPT_NUMBER));
    }

    @Test
    void onPaymentResult_withoutBalance_shouldPublishWithoutRecordingIt() {
        handler.onPaymentResult(paymentResult(TRANSACTION_ID, 0, "1500")
                .replace("{\"Key\":\"B2CUtilityAccountAvailableFunds\",\"Value\":10116.00},", ""));

        assertEquals(false, zeebe.publishedVariables().get(PAYOUT_FAILED));
        assertEquals(0, registry.find("mpesa.b2c.utility.balance").gauges().size());
    }

    @Test
    void onPaymentResult_withUnreadableBalance_shouldPublishWithoutRecordingIt() {
        handler.onPaymentResult(paymentResult(TRANSACTION_ID, 0, "1500")
                .replace("\"Value\":10116.00", "\"Value\":\"n/a\""));

        assertEquals(false, zeebe.publishedVariables().get(PAYOUT_FAILED));
        assertEquals(0, registry.find("mpesa.b2c.utility.balance").gauges().size());
    }

    @Test
    void onPaymentResult_forPayoutWithoutAccount_shouldPublishWithoutRecordingTheBalance() {
        store.save(new PayoutRecord("tx-no-account", null, 1500, 254708374149L, 0));

        handler.onPaymentResult(paymentResult("tx-no-account", 0, "1500"));

        assertEquals(false, zeebe.publishedVariables().get(PAYOUT_FAILED));
        assertEquals(0, registry.find("mpesa.b2c.utility.balance").gauges().size());
    }

    @Test
    void onPaymentResult_withUnreadableOrMissingAmount_shouldNotPublish() {
        handler.onPaymentResult(paymentResult(TRANSACTION_ID, 0, "\"lots\""));
        handler.onPaymentResult(paymentResult(TRANSACTION_ID, 0, "1500")
                .replace("{\"Key\":\"TransactionAmount\",\"Value\":1500},", ""));

        verify(zeebe.client, never()).newPublishMessageCommand();
    }

    @Test
    void onPaymentResult_withoutOriginatorConversationId_shouldNotPublish() {
        handler.onPaymentResult(paymentResult(TRANSACTION_ID, 0, "1500")
                .replace("\"OriginatorConversationID\":\"" + TRANSACTION_ID + "\",", ""));

        verify(zeebe.client, never()).newPublishMessageCommand();
    }

    @Test
    void onStatusResult_unreadable_shouldNotPublish() {
        handler.onStatusResult("<html>Bad Gateway</html>");

        verify(zeebe.client, never()).newPublishMessageCommand();
    }

    @Test
    void onStatusResult_withoutOccasion_shouldFindThePayoutByItsOriginatorConversationId() {
        handler.onStatusResult(statusResult(TRANSACTION_ID, "Completed", "1500")
                .replace("\"ReferenceData\":{\"ReferenceItem\":{\"Key\":\"Occasion\",\"Value\":\"" + TRANSACTION_ID
                        + "\"}},", ""));

        assertEquals("MBN31H462N", zeebe.publishedVariables().get(SERVER_TRANSACTION_RECEIPT_NUMBER));
    }

    @Test
    void onStatusResult_withoutQueriedId_shouldTrustTheOccasion() {
        handler.onStatusResult(statusResult(TRANSACTION_ID, "Completed", "1500")
                .replace("{\"Key\":\"OriginatorConversationID\",\"Value\":\"" + TRANSACTION_ID + "\"},", ""));

        assertEquals(false, zeebe.publishedVariables().get(PAYOUT_FAILED));
    }

    @Test
    void onStatusResult_failedQuery_shouldLeaveThePayoutUnknown() {
        handler.onStatusResult(statusResult(TRANSACTION_ID, "Completed", "1500")
                .replace("\"ResultCode\":0", "\"ResultCode\":2001"));

        verify(zeebe.client, never()).newPublishMessageCommand();
    }

    @Test
    void onStatusResult_forUnknownPayout_shouldNotPublish() {
        handler.onStatusResult(statusResult("someone-else", "Completed", "1500"));

        verify(zeebe.client, never()).newPublishMessageCommand();
    }
}
