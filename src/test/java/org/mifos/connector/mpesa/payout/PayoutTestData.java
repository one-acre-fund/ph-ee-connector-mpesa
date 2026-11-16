package org.mifos.connector.mpesa.payout;

import org.mifos.connector.mpesa.utility.MpesaProps;

final class PayoutTestData {

    static final String TRANSACTION_ID = "tx-payout-1";

    private PayoutTestData() {
    }

    static MpesaProps.MPESA account() {
        MpesaProps.MPESA account = new MpesaProps.MPESA();
        account.setName("fineract");
        account.setBusinessShortCode("174379");
        account.setTill("600000");
        account.setAuthHost("https://sandbox.safaricom.co.ke/oauth/v1/generate");
        account.setApiHost("https://sandbox.safaricom.co.ke");
        account.setClientKey("stk-key");
        account.setClientSecret("stk-secret");
        MpesaProps.B2C b2c = new MpesaProps.B2C();
        b2c.setEnabled(true);
        b2c.setShortCode("600997");
        b2c.setInitiatorName("testapi");
        b2c.setSecurityCredential("encrypted-credential");
        account.setB2c(b2c);
        return account;
    }

    static String payoutRequest(String msisdn, String amount) {
        return "{\"payee\":[{\"key\":\"MSISDN\",\"value\":\"" + msisdn + "\"},{\"key\":\"ACCOUNTID\",\"value\":\"ACC-1\"}],"
                + "\"amount\":{\"amount\":\"" + amount + "\",\"currency\":\"KES\"},\"remarks\":\"Refund of overpayment\"}";
    }

    static PayoutRequestBuilder requestBuilder() {
        return new PayoutRequestBuilder("https://connector", "/payout/result", "/payout/timeout",
                "/payout/status/result", "/payout/status/timeout");
    }

    static String paymentResult(String transactionId, int resultCode, String amount) {
        String parameters = resultCode != 0 ? "" : ",\"ResultParameters\":{\"ResultParameter\":["
                + "{\"Key\":\"TransactionAmount\",\"Value\":" + amount + "},"
                + "{\"Key\":\"TransactionReceipt\",\"Value\":\"NLJ41HAY6Q\"},"
                + "{\"Key\":\"B2CRecipientIsRegisteredCustomer\",\"Value\":\"Y\"},"
                + "{\"Key\":\"B2CChargesPaidAccountAvailableFunds\",\"Value\":-4510.00},"
                + "{\"Key\":\"ReceiverPartyPublicName\",\"Value\":\"254708374149 - John Doe\"},"
                + "{\"Key\":\"TransactionCompletedDateTime\",\"Value\":\"19.12.2019 11:45:50\"},"
                + "{\"Key\":\"B2CUtilityAccountAvailableFunds\",\"Value\":10116.00},"
                + "{\"Key\":\"B2CWorkingAccountAvailableFunds\",\"Value\":900000.00}]}";
        return "{\"Result\":{\"ResultType\":0,\"ResultCode\":" + resultCode + ","
                + "\"ResultDesc\":\"" + (resultCode == 0 ? "The service request is processed successfully."
                : "The initiator information is invalid.") + "\","
                + "\"OriginatorConversationID\":\"" + transactionId + "\","
                + "\"ConversationID\":\"AG_20191219_00005797af5d7d75f652\","
                + "\"TransactionID\":\"NLJ41HAY6Q\"" + parameters
                + ",\"ReferenceData\":{\"ReferenceItem\":{\"Key\":\"QueueTimeoutURL\","
                + "\"Value\":\"https://internalsandbox.safaricom.co.ke/mpesa/b2cresults/v1/submit\"}}}}";
    }

    static String statusResult(String occasion, String status, String amount) {
        return "{\"Result\":{\"ConversationID\":\"AG_20180223_0000493344ae97d86f75\","
                + "\"OriginatorConversationID\":\"3213-416199-2\","
                + "\"ReferenceData\":{\"ReferenceItem\":{\"Key\":\"Occasion\",\"Value\":\"" + occasion + "\"}},"
                + "\"ResultCode\":0,\"ResultDesc\":\"The service request is processed successfully.\","
                + "\"ResultParameters\":{\"ResultParameter\":["
                + "{\"Key\":\"DebitPartyName\",\"Value\":\"600310 - Safaricom333\"},"
                + "{\"Key\":\"DebitPartyName\",\"Value\":\"254708374149 - John Doe\"},"
                + "{\"Key\":\"OriginatorConversationID\",\"Value\":\"" + occasion + "\"},"
                + "{\"Key\":\"ReasonType\",\"Value\":\"Business Payment to Customer via API\"},"
                + "{\"Key\":\"TransactionStatus\",\"Value\":\"" + status + "\"},"
                + "{\"Key\":\"FinalisedTime\",\"Value\":20180223054112},"
                + "{\"Key\":\"Amount\",\"Value\":" + amount + "},"
                + "{\"Key\":\"ReceiptNo\",\"Value\":\"MBN31H462N\"}]},"
                + "\"ResultType\":0,\"TransactionID\":\"MBN0000000\"}}";
    }
}
