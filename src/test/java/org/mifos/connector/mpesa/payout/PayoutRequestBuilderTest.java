package org.mifos.connector.mpesa.payout;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mifos.connector.mpesa.utility.MpesaProps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mifos.connector.mpesa.payout.PayoutTestData.TRANSACTION_ID;
import static org.mifos.connector.mpesa.payout.PayoutTestData.account;
import static org.mifos.connector.mpesa.payout.PayoutTestData.payoutRequest;
import static org.mifos.connector.mpesa.payout.PayoutValidationException.INVALID_AMOUNT;
import static org.mifos.connector.mpesa.payout.PayoutValidationException.INVALID_CURRENCY;
import static org.mifos.connector.mpesa.payout.PayoutValidationException.INVALID_MSISDN;
import static org.mifos.connector.mpesa.payout.PayoutValidationException.INVALID_REQUEST;
import static org.mifos.connector.mpesa.payout.PayoutValidationException.PAYOUT_NOT_ENABLED;

class PayoutRequestBuilderTest {

    private final PayoutRequestBuilder builder = PayoutTestData.requestBuilder();

    @Test
    void build_shouldUseTransactionIdAsOriginatorConversationIdAndAccountB2cSettings() {
        B2CPaymentRequestDTO dto = builder.build(TRANSACTION_ID, payoutRequest("254708374149", "1500"), account());

        assertEquals(TRANSACTION_ID, dto.getOriginatorConversationId());
        assertEquals("testapi", dto.getInitiatorName());
        assertEquals("encrypted-credential", dto.getSecurityCredential());
        assertEquals("BusinessPayment", dto.getCommandId());
        assertEquals(1500L, dto.getAmount());
        assertEquals(600997L, dto.getPartyA());
        assertEquals(254708374149L, dto.getPartyB());
        assertEquals("Refund of overpayment", dto.getRemarks());
        assertEquals("ACC-1", dto.getOccasion());
        assertEquals("https://connector/payout/result", dto.getResultUrl());
        assertEquals("https://connector/payout/timeout", dto.getQueueTimeOutUrl());
    }

    @Test
    void toString_shouldMaskTheSecurityCredential() {
        B2CPaymentRequestDTO dto = builder.build(TRANSACTION_ID, payoutRequest("254708374149", "1500"), account());

        assertTrue(!dto.toString().contains("encrypted-credential"), dto.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1500", "1500.00", " 1500 "})
    void build_withWholeAmount_shouldConvert(String amount) {
        assertEquals(1500L, builder.build(TRANSACTION_ID, payoutRequest("254708374149", amount), account()).getAmount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1315.5", "9.5", "abc", "", " ", "9", "250001", "0", "-10"})
    void build_withInvalidAmount_shouldReject(String amount) {
        assertRejected(INVALID_AMOUNT, payoutRequest("254708374149", amount));
    }

    @Test
    void build_withAmountLimitsFromAccount_shouldApplyThem() {
        MpesaProps.MPESA account = account();
        account.getB2c().setMaxAmount(1000);

        PayoutValidationException e = assertThrows(PayoutValidationException.class,
                () -> builder.build(TRANSACTION_ID, payoutRequest("254708374149", "1001"), account));
        assertEquals(INVALID_AMOUNT, e.getErrorCode());
    }

    @Test
    void build_withOtherCurrency_shouldReject() {
        assertRejected(INVALID_CURRENCY, payoutRequest("254708374149", "100").replace("KES", "UGX"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"+254708374149", "254708374149", " 254708374149 "})
    void build_withKenyanMsisdn_shouldConvert(String msisdn) {
        assertEquals(254708374149L, builder.build(TRANSACTION_ID, payoutRequest(msisdn, "100"), account()).getPartyB());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"+null", "null", "0708374149", "256708374149", "25470837414", "2547083741490",
            "+254 708 374 149"})
    void build_withInvalidMsisdn_shouldReject(String msisdn) {
        assertRejected(INVALID_MSISDN, payoutRequest(msisdn, "100"));
    }

    @Test
    void build_withoutMsisdn_shouldReject() {
        assertRejected(INVALID_MSISDN, "{\"payee\":[{\"key\":\"ACCOUNTID\",\"value\":\"1\"}],"
                + "\"amount\":{\"amount\":\"100\",\"currency\":\"KES\"},\"remarks\":\"ok\"}");
    }

    @Test
    void build_withoutAccountId_shouldLeaveOccasionEmpty() {
        B2CPaymentRequestDTO dto = builder.build(TRANSACTION_ID, "{\"payee\":[{\"key\":\"MSISDN\",\"value\":\"254708374149\"}],"
                + "\"amount\":{\"amount\":\"100\",\"currency\":\"KES\"},\"remarks\":\"ok\"}", account());

        assertNull(dto.getOccasion());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"remarks\":\"x\"", "\"remarks\":\"   \"", "\"remarks\":null", "\"other\":1"})
    void build_withMissingOrShortRemarks_shouldUseDefault(String remarks) {
        // remarks are optional in the scheme-neutral payout request
        B2CPaymentRequestDTO dto = builder.build("txn-1", payoutRequest("254708374149", "100")
                .replace("\"remarks\":\"Refund of overpayment\"", remarks), account());
        assertEquals(PayoutRequestBuilder.DEFAULT_REMARKS, dto.getRemarks());
    }

    @Test
    void build_withTooLongRemarks_shouldCutTo100() {
        B2CPaymentRequestDTO dto = builder.build("txn-1", payoutRequest("254708374149", "100")
                .replace("Refund of overpayment", "x".repeat(255)), account());
        assertEquals("x".repeat(100), dto.getRemarks());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"not json"})
    void build_withUnreadableRequest_shouldReject(String request) {
        assertRejected(INVALID_REQUEST, request);
    }

    @Test
    void build_whenB2cDisabled_shouldReject() {
        MpesaProps.MPESA account = account();
        account.getB2c().setEnabled(false);

        PayoutValidationException e = assertThrows(PayoutValidationException.class,
                () -> builder.build(TRANSACTION_ID, payoutRequest("254708374149", "100"), account));
        assertEquals(PAYOUT_NOT_ENABLED, e.getErrorCode());
    }

    @Test
    void build_withoutAccount_shouldReject() {
        PayoutValidationException e = assertThrows(PayoutValidationException.class,
                () -> builder.build(TRANSACTION_ID, payoutRequest("254708374149", "100"), null));
        assertEquals(PAYOUT_NOT_ENABLED, e.getErrorCode());
    }

    @Test
    void build_withoutB2cSettings_shouldReject() {
        MpesaProps.MPESA account = account();
        account.setB2c(null);

        PayoutValidationException e = assertThrows(PayoutValidationException.class,
                () -> builder.build(TRANSACTION_ID, payoutRequest("254708374149", "100"), account));
        assertEquals(PAYOUT_NOT_ENABLED, e.getErrorCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"currency\":null", "\"other\":1"})
    void build_withoutCurrency_shouldAssumeKes(String currency) {
        B2CPaymentRequestDTO dto = builder.build(TRANSACTION_ID, payoutRequest("254708374149", "100")
                .replace("\"currency\":\"KES\"", currency), account());

        assertEquals(100L, dto.getAmount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"payee\":[{\"key\":\"MSISDN\",\"value\":\"254708374149\"}]}",
            "{\"payee\":[{\"key\":\"MSISDN\",\"value\":\"254708374149\"}],\"amount\":{\"amount\":100}}",
            "{\"payee\":[{\"key\":\"MSISDN\",\"value\":\"254708374149\"}],\"amount\":{\"amount\":\" \"}}"})
    void build_withoutTextAmount_shouldReject(String request) {
        assertRejected(INVALID_AMOUNT, request);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"amount\":{\"amount\":\"100\"}}",
            "{\"payee\":[\"MSISDN\",{\"value\":\"254708374149\"},{\"key\":1,\"value\":\"254708374149\"}],"
                    + "\"amount\":{\"amount\":\"100\"}}",
            "{\"payee\":[{\"key\":\"MSISDN\",\"value\":254708374149}],\"amount\":{\"amount\":\"100\"}}"})
    void build_withoutTextMsisdn_shouldReject(String request) {
        assertRejected(INVALID_MSISDN, request);
    }

    @Test
    void build_withBlankAccountId_shouldLeaveOccasionEmpty() {
        B2CPaymentRequestDTO dto = builder.build(TRANSACTION_ID, payoutRequest("254708374149", "100")
                .replace("\"value\":\"ACC-1\"", "\"value\":\"  \""), account());

        assertNull(dto.getOccasion());
    }

    @Test
    void build_withLongAccountId_shouldCutTheOccasionTo100() {
        B2CPaymentRequestDTO dto = builder.build(TRANSACTION_ID, payoutRequest("254708374149", "100")
                .replace("\"value\":\"ACC-1\"", "\"value\":\" " + "A".repeat(120) + " \""), account());

        assertEquals("A".repeat(100), dto.getOccasion());
    }

    @Test
    void buildStatusQuery_shouldQueryByOriginatorConversationIdAndReturnItAsOccasion() {
        B2CTransactionStatusRequestDTO dto = builder.buildStatusQuery(TRANSACTION_ID, account());

        assertEquals("TransactionStatusQuery", dto.getCommandId());
        assertEquals(TRANSACTION_ID, dto.getOriginalConversationId());
        assertEquals(TRANSACTION_ID, dto.getOccasion());
        assertEquals(600997L, dto.getPartyA());
        assertEquals("4", dto.getIdentifierType());
        assertEquals("testapi", dto.getInitiator());
        assertEquals("https://connector/payout/status/result", dto.getResultUrl());
        assertEquals("https://connector/payout/status/timeout", dto.getQueueTimeOutUrl());
    }

    private void assertRejected(String code, String request) {
        PayoutValidationException e = assertThrows(PayoutValidationException.class,
                () -> builder.build(TRANSACTION_ID, request, account()));
        assertEquals(code, e.getErrorCode(), e.getMessage());
    }
}
