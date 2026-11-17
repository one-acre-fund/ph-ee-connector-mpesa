package org.mifos.connector.mpesa.payout;

import org.json.JSONObject;

/**
 * What was sent for a payout, kept so a result posted by Safaricom can be checked against it.
 */
public record PayoutRecord(String transactionId, String account, long amount, long msisdn, long sentAtMillis) {

    String toJson() {
        return new JSONObject()
                .put("transactionId", transactionId)
                .put("account", account)
                .put("amount", amount)
                .put("msisdn", msisdn)
                .put("sentAtMillis", sentAtMillis)
                .toString();
    }

    static PayoutRecord fromJson(String json) {
        var object = new JSONObject(json);
        return new PayoutRecord(object.getString("transactionId"), object.optString("account", null),
                object.getLong("amount"), object.getLong("msisdn"), object.getLong("sentAtMillis"));
    }
}
