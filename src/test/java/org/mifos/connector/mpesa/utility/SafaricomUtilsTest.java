package org.mifos.connector.mpesa.utility;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.util.json.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mifos.connector.common.channel.dto.TransactionChannelC2BRequestDTO;
import org.mifos.connector.mpesa.dto.BuyGoodsPaymentRequestDTO;
import org.mifos.connector.mpesa.dto.StkCallback;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mifos.connector.mpesa.safaricom.config.SafaricomProperties.MPESA_BUY_GOODS_TRANSACTION_TYPE;
import static org.mockito.Mockito.mock;
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
