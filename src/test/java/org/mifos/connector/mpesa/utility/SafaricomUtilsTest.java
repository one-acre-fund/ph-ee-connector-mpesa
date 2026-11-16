package org.mifos.connector.mpesa.utility;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.util.json.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mifos.connector.common.channel.dto.TransactionChannelC2BRequestDTO;
import org.mifos.connector.common.gsma.dto.GsmaParty;
import org.mifos.connector.common.mojaloop.dto.MoneyData;
import org.mifos.connector.mpesa.dto.BuyGoodsPaymentRequestDTO;
import org.mifos.connector.mpesa.dto.StkCallback;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mifos.connector.mpesa.safaricom.config.SafaricomProperties.MPESA_BUY_GOODS_TRANSACTION_TYPE;
import static org.mifos.connector.mpesa.utility.ChannelRequestValidationException.INVALID_AMOUNT;
import static org.mifos.connector.mpesa.utility.ChannelRequestValidationException.INVALID_MSISDN;
import static org.mifos.connector.mpesa.utility.ChannelRequestValidationException.INVALID_PAYER;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class SafaricomUtilsTest {

    private static final String SUCCESS_CALLBACK = "{\"Body\":{\"stkCallback\":{\"MerchantRequestID\":\"29115-34620561-1\","
            + "\"CheckoutRequestID\":\"ws_CO_1\",\"ResultCode\":0,\"ResultDesc\":\"Processed\","
            + "\"CallbackMetadata\":{\"Item\":[{\"Name\":\"Amount\",\"Value\":1.0},"
            + "{\"Name\":\"MpesaReceiptNumber\",\"Value\":\"RKTQDM7W6S\"},{\"Name\":\"PhoneNumber\",\"Value\":254708374149}]}}}}";

    private SafaricomUtils safaricomUtils;
    private MpesaUtils mpesaUtils;

    @BeforeEach
    void setUp() {
        mpesaUtils = mock(MpesaUtils.class);
        safaricomUtils = new SafaricomUtils();
        ReflectionTestUtils.setField(safaricomUtils, "mpesaUtils", mpesaUtils);
        ReflectionTestUtils.setField(safaricomUtils, "host", "https://connector");
        ReflectionTestUtils.setField(safaricomUtils, "callbackEndpoint", "/buygoods/callback");
    }

    @Test
    void channelRequestConvertor_withMsisdnFirst_shouldBuildBuyGoodsRequest() throws Exception {
        when(mpesaUtils.getMpesaProperties("roster", "tx-1")).thenReturn(props());

        BuyGoodsPaymentRequestDTO dto = safaricomUtils.channelRequestConvertor(
                channelRequest("MSISDN", "254708374149", "ACCOUNTID", "12345"), "tx-1", "roster");

        assertEquals(254708374149L, dto.getPartyA());
        assertEquals(254708374149L, dto.getPhoneNumber());
        assertEquals(600000L, dto.getPartyB());
        assertEquals(174379L, dto.getBusinessShortCode());
        assertEquals(10L, dto.getAmount());
        assertEquals("https://connector/buygoods/callback", dto.getCallBackURL());
        assertEquals(MPESA_BUY_GOODS_TRANSACTION_TYPE, dto.getTransactionType());
        assertEquals("Payment from account id12345", dto.getTransactionDesc());
        assertEquals("Payment to 174379", dto.getAccountReference());
        assertEquals(safaricomUtils.getPassword("174379", "passkey", dto.getTimestamp()), dto.getPassword());
    }

    @Test
    void channelRequestConvertor_withAccountIdFirst_shouldTakePayerFromSecondParty() throws Exception {
        when(mpesaUtils.getMpesaProperties("roster", "tx-1")).thenReturn(props());

        BuyGoodsPaymentRequestDTO dto = safaricomUtils.channelRequestConvertor(
                channelRequest("ACCOUNTID", "12345", "MSISDN", "254708374149"), "tx-1", "roster");

        assertEquals(254708374149L, dto.getPartyA());
    }

    @Test
    void getStkCallback_shouldParseCallbackFields() throws Exception {
        StkCallback callback = SafaricomUtils.getStkCallback(json(SUCCESS_CALLBACK));

        assertEquals("29115-34620561-1", callback.getMerchantRequestId());
        assertEquals("ws_CO_1", callback.getCheckoutRequestId());
        assertEquals(0L, callback.getResultCode());
        assertEquals("Processed", callback.getResultDesc());
    }

    @Test
    void getTransactionId_shouldReturnReceiptNumber() throws Exception {
        assertEquals("RKTQDM7W6S", SafaricomUtils.getTransactionId(json(SUCCESS_CALLBACK)));
    }

    @Test
    void getPassword_shouldBase64EncodeShortCodePassKeyAndTimestampWithoutNewlines() {
        String password = safaricomUtils.getPassword("174379\n", "pass\nkey", "20261005094948\n");

        assertEquals("174379passkey20261005094948",
                new String(Base64.getDecoder().decode(password), StandardCharsets.UTF_8));
    }

    @Test
    void getTimestamp_shouldBeFourteenDigits() {
        assertTrue(String.valueOf(safaricomUtils.getTimestamp()).matches("\\d{14}"));
    }

    @Test
    void channelRequestConvertor_concurrentPayments_shouldEachUseTheirOwnAccount() throws Exception {
        MpesaProps.MPESA paygops = props();
        paygops.setName("paygops");
        paygops.setBusinessShortCode("888888");
        paygops.setTill("777777");
        paygops.setPassKey("paygops-passkey");
        when(mpesaUtils.getMpesaProperties("roster", "tx-1")).thenReturn(props());
        when(mpesaUtils.getMpesaProperties("paygops", "tx-2")).thenReturn(paygops);
        SafaricomUtils utils = spy(safaricomUtils);
        CountDownLatch rosterLoaded = new CountDownLatch(1);
        CountDownLatch secondDone = new CountDownLatch(1);
        // The roster payment pauses after loading its account until the paygops payment is fully built.
        doAnswer(invocation -> {
            if ("roster-thread".equals(Thread.currentThread().getName())) {
                rosterLoaded.countDown();
                assertTrue(secondDone.await(5, TimeUnit.SECONDS));
            }
            return 20261005094948L;
        }).when(utils).getTimestamp();
        TransactionChannelC2BRequestDTO request = channelRequest("MSISDN", "254708374149", "ACCOUNTID", "12345");
        ExecutorService pool = Executors.newSingleThreadExecutor(r -> new Thread(r, "roster-thread"));
        try {
            Future<BuyGoodsPaymentRequestDTO> roster =
                    pool.submit(() -> utils.channelRequestConvertor(request, "tx-1", "roster"));
            assertTrue(rosterLoaded.await(5, TimeUnit.SECONDS));
            BuyGoodsPaymentRequestDTO paygopsRequest = utils.channelRequestConvertor(request, "tx-2", "paygops");
            secondDone.countDown();
            BuyGoodsPaymentRequestDTO rosterRequest = roster.get(5, TimeUnit.SECONDS);

            assertEquals(888888L, paygopsRequest.getBusinessShortCode());
            assertEquals(174379L, rosterRequest.getBusinessShortCode());
            assertEquals(600000L, rosterRequest.getPartyB());
            assertEquals(utils.getPassword("174379", "passkey", "20261005094948"), rosterRequest.getPassword());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void getTimestamp_calledConcurrently_shouldAlwaysBeFourteenDigits() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Long>> timestamps = new ArrayList<>();
            for (int i = 0; i < 2000; i++) {
                timestamps.add(pool.submit(safaricomUtils::getTimestamp));
            }
            for (Future<Long> timestamp : timestamps) {
                assertTrue(String.valueOf(timestamp.get()).matches("\\d{14}"));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"1315.5", "9.5", "0.01", "100.001"})
    void channelRequestConvertor_withDecimalAmount_shouldRejectWithInvalidAmount(String amount) {
        when(mpesaUtils.getMpesaProperties("roster", "tx-1")).thenReturn(props());
        assertRejected(INVALID_AMOUNT, request(amount, party("MSISDN", "254708374149"), party("ACCOUNTID", "12345")));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "abc", "0", "-5", "0.00"})
    void channelRequestConvertor_withMissingOrNonPositiveAmount_shouldRejectWithInvalidAmount(String amount) {
        when(mpesaUtils.getMpesaProperties("roster", "tx-1")).thenReturn(props());
        assertRejected(INVALID_AMOUNT, request(amount, party("MSISDN", "254708374149"), party("ACCOUNTID", "12345")));
    }

    @Test
    void channelRequestConvertor_withoutAmount_shouldRejectWithInvalidAmount() {
        when(mpesaUtils.getMpesaProperties("roster", "tx-1")).thenReturn(props());
        TransactionChannelC2BRequestDTO request = request("10", party("MSISDN", "254708374149"));
        request.setAmount(null);
        assertRejected(INVALID_AMOUNT, request);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1315", "1315.00", " 1315 ", "1315.0"})
    void channelRequestConvertor_withWholeAmount_shouldConvert(String amount) {
        when(mpesaUtils.getMpesaProperties("roster", "tx-1")).thenReturn(props());
        BuyGoodsPaymentRequestDTO dto = safaricomUtils.channelRequestConvertor(
                request(amount, party("MSISDN", "254708374149"), party("ACCOUNTID", "12345")), "tx-1", "roster");
        assertEquals(1315L, dto.getAmount());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "+null", "null", "+", "2547abc", "+254 708 374 149", "1234567890123456"})
    void channelRequestConvertor_withMissingOrInvalidMsisdn_shouldRejectWithInvalidMsisdn(String msisdn) {
        when(mpesaUtils.getMpesaProperties("roster", "tx-1")).thenReturn(props());
        assertRejected(INVALID_MSISDN, request("10", party("MSISDN", msisdn), party("ACCOUNTID", "12345")));
    }

    @Test
    void channelRequestConvertor_withoutMsisdnParty_shouldRejectWithInvalidMsisdn() {
        when(mpesaUtils.getMpesaProperties("roster", "tx-1")).thenReturn(props());
        assertRejected(INVALID_MSISDN, request("10", party("ACCOUNTID", "12345")));
    }

    @Test
    void channelRequestConvertor_withoutPayer_shouldRejectWithInvalidPayer() {
        when(mpesaUtils.getMpesaProperties("roster", "tx-1")).thenReturn(props());
        assertRejected(INVALID_PAYER, request("10"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"+254708374149", "254708374149", " 254708374149 "})
    void channelRequestConvertor_withOrWithoutPlus_shouldConvertMsisdn(String msisdn) {
        when(mpesaUtils.getMpesaProperties("roster", "tx-1")).thenReturn(props());
        BuyGoodsPaymentRequestDTO dto = safaricomUtils.channelRequestConvertor(
                request("10", party("MSISDN", msisdn), party("ACCOUNTID", "12345")), "tx-1", "roster");
        assertEquals(254708374149L, dto.getPartyA());
        assertEquals(254708374149L, dto.getPhoneNumber());
    }

    @Test
    void channelRequestConvertor_withNonKenyanMsisdn_shouldConvert() {
        when(mpesaUtils.getMpesaProperties("roster", "tx-1")).thenReturn(props());
        BuyGoodsPaymentRequestDTO dto = safaricomUtils.channelRequestConvertor(
                request("10", party("MSISDN", "+256712345678"), party("ACCOUNTID", "12345")), "tx-1", "roster");
        assertEquals(256712345678L, dto.getPartyA());
    }

    @Test
    void channelRequestConvertor_withOnlyMsisdnParty_shouldConvert() {
        when(mpesaUtils.getMpesaProperties("roster", "tx-1")).thenReturn(props());
        BuyGoodsPaymentRequestDTO dto = safaricomUtils.channelRequestConvertor(
                request("10", party("MSISDN", "254708374149")), "tx-1", "roster");
        assertEquals(254708374149L, dto.getPartyA());
    }

    private void assertRejected(String expectedCode, TransactionChannelC2BRequestDTO request) {
        ChannelRequestValidationException e = assertThrows(ChannelRequestValidationException.class,
                () -> safaricomUtils.channelRequestConvertor(request, "tx-1", "roster"));
        assertEquals(expectedCode, e.getErrorCode());
    }

    private static TransactionChannelC2BRequestDTO request(String amount, GsmaParty... payer) {
        TransactionChannelC2BRequestDTO request = new TransactionChannelC2BRequestDTO();
        MoneyData money = new MoneyData();
        money.setAmount(amount);
        money.setCurrency("KES");
        request.setAmount(money);
        request.setPayer(payer);
        return request;
    }

    private static GsmaParty party(String key, String value) {
        GsmaParty party = new GsmaParty();
        party.setKey(key);
        party.setValue(value);
        return party;
    }

    private static MpesaProps.MPESA props() {
        MpesaProps.MPESA props = new MpesaProps.MPESA();
        props.setName("roster");
        props.setBusinessShortCode("174379");
        props.setTill("600000");
        props.setPassKey("passkey");
        return props;
    }

    private static TransactionChannelC2BRequestDTO channelRequest(String key1, String value1, String key2, String value2)
            throws Exception {
        String json = "{\"payer\":[{\"key\":\"" + key1 + "\",\"value\":\"" + value1 + "\"},"
                + "{\"key\":\"" + key2 + "\",\"value\":\"" + value2 + "\"}],"
                + "\"amount\":{\"amount\":\" 10 \",\"currency\":\"KES\"}}";
        return new ObjectMapper().readValue(json, TransactionChannelC2BRequestDTO.class);
    }

    private static JsonObject json(String value) throws Exception {
        return new ObjectMapper().readValue(value, JsonObject.class);
    }
}
