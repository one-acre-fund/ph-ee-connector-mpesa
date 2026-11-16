package org.mifos.connector.mpesa.payout;

import org.json.JSONException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class B2CResultTest {

    @Test
    void parse_successfulPayment_shouldFlattenResultParameters() {
        B2CResult result = B2CResult.parse(PayoutTestData.paymentResult("tx-1", 0, "1500"));

        assertTrue(result.isSuccess());
        assertEquals("tx-1", result.originatorConversationId());
        assertEquals("AG_20191219_00005797af5d7d75f652", result.conversationId());
        assertEquals("NLJ41HAY6Q", result.transactionId());
        assertEquals("1500", result.parameter("TransactionAmount"));
        assertEquals("NLJ41HAY6Q", result.parameter("TransactionReceipt"));
        assertEquals("254708374149 - John Doe", result.parameter("ReceiverPartyPublicName"));
        assertEquals("10116.00", result.parameter("B2CUtilityAccountAvailableFunds"));
        assertEquals("https://internalsandbox.safaricom.co.ke/mpesa/b2cresults/v1/submit",
                result.referenceData("QueueTimeoutURL"));
    }

    @Test
    void parse_failedPayment_shouldHaveNoParameters() {
        B2CResult result = B2CResult.parse(PayoutTestData.paymentResult("tx-1", 2001, null));

        assertFalse(result.isSuccess());
        assertEquals("2001", result.resultCode());
        assertEquals("The initiator information is invalid.", result.resultDesc());
        assertNull(result.parameter("TransactionAmount"));
    }

    @Test
    void parse_statusResult_shouldKeepFirstOfRepeatedKeysAndReadOccasion() {
        B2CResult result = B2CResult.parse(PayoutTestData.statusResult("tx-1", "Completed", "300"));

        assertEquals("600310 - Safaricom333", result.parameter("DebitPartyName"));
        assertEquals("Completed", result.parameter("TransactionStatus"));
        assertEquals("tx-1", result.referenceData("Occasion"));
    }

    @Test
    void parse_withSingleResultParameterObject_shouldReadIt() {
        B2CResult result = B2CResult.parse("{\"Result\":{\"ResultCode\":0,\"ResultParameters\":"
                + "{\"ResultParameter\":{\"Key\":\"TransactionAmount\",\"Value\":10}}}}");

        assertEquals("10", result.parameter("TransactionAmount"));
    }

    @Test
    void parse_withEmptyContainers_shouldHaveNoParameters() {
        B2CResult result = B2CResult.parse("{\"Result\":{\"ResultCode\":0,\"ResultParameters\":{},"
                + "\"ReferenceData\":{\"Other\":1}}}");

        assertNull(result.parameter("TransactionAmount"));
        assertNull(result.referenceData("Occasion"));
    }

    @Test
    void parse_shouldSkipItemsThatAreNotKeyValues() {
        B2CResult result = B2CResult.parse("{\"Result\":{\"ResultCode\":0,\"ResultParameters\":{\"ResultParameter\":"
                + "[\"text\",{\"Value\":1},{\"Key\":\"Amount\",\"Value\":10}]}}}");

        assertEquals(Map.of("Amount", "10"), result.parameters());
    }

    @Test
    void parse_withoutResult_shouldThrow() {
        assertThrows(JSONException.class, () -> B2CResult.parse("{\"Body\":{}}"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"", ",\"ResultCode\":null", ",\"ResultCode\":\" \"",
            ",\"ResultCode\":{\"a\":1}", ",\"ResultCode\":1.5", ",\"ResultCode\":\"0; drop\""})
    void parse_withoutValidResultCode_shouldThrow(String resultCode) {
        assertThrows(JSONException.class, () -> B2CResult.parse("{\"Result\":{\"ResultDesc\":\"x\"" + resultCode + "}}"));
    }

    @Test
    void parse_withStringResultCodes_shouldKeepThem() {
        assertEquals("0", B2CResult.parse("{\"Result\":{\"ResultCode\":\" 0 \"}}").resultCode());
        assertEquals("SFC_IC0003", B2CResult.parse("{\"Result\":{\"ResultCode\":\"SFC_IC0003\"}}").resultCode());
        assertEquals("2001", B2CResult.parse("{\"Result\":{\"ResultCode\":2001}}").resultCode());
        assertEquals("12345678901", B2CResult.parse("{\"Result\":{\"ResultCode\":12345678901}}").resultCode());
    }
}
