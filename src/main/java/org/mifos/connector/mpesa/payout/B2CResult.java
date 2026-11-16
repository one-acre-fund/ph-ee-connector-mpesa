package org.mifos.connector.mpesa.payout;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The {@code Result} Safaricom posts to a ResultURL, for a B2C payment or a transaction status query.
 * {@code ResultParameters.ResultParameter} and {@code ReferenceData.ReferenceItem} are flattened into maps; either can be
 * a single object or an array.
 */
public record B2CResult(String resultCode, String resultDesc, String originatorConversationId, String conversationId,
                        String transactionId, Map<String, String> parameters, Map<String, String> referenceItems) {

    public static final String SUCCESS = "0";

    // Numeric (0, 2001...) or Safaricom's alphanumeric codes (e.g. SFC_IC0003)
    private static final Pattern RESULT_CODE = Pattern.compile("[A-Za-z0-9_.-]+");

    /**
     * @throws org.json.JSONException when the body is not a Safaricom result, including one without a valid
     *                                ResultCode: a missing code proves nothing about the payout, so it must not be
     *                                read as a failure
     */
    public static B2CResult parse(String body) {
        var result = new JSONObject(body).getJSONObject("Result");
        return new B2CResult(
                resultCode(result.opt("ResultCode")),
                result.optString("ResultDesc", ""),
                result.optString("OriginatorConversationID", null),
                result.optString("ConversationID", null),
                result.optString("TransactionID", null),
                keyValues(result.optJSONObject("ResultParameters"), "ResultParameter"),
                keyValues(result.optJSONObject("ReferenceData"), "ReferenceItem"));
    }

    private static String resultCode(Object code) {
        if (code instanceof Integer || code instanceof Long) {
            return String.valueOf(code);
        }
        if (code instanceof String text && RESULT_CODE.matcher(text.trim()).matches()) {
            return text.trim();
        }
        throw new JSONException("ResultCode is missing or malformed: " + code);
    }

    public boolean isSuccess() {
        return SUCCESS.equals(resultCode);
    }

    public String parameter(String key) {
        return parameters.get(key);
    }

    public String referenceData(String key) {
        return referenceItems.get(key);
    }

    private static Map<String, String> keyValues(JSONObject container, String itemName) {
        if (container == null || !container.has(itemName)) {
            return Map.of();
        }
        var values = new LinkedHashMap<String, String>();
        var items = container.get(itemName);
        if (items instanceof JSONArray array) {
            array.forEach(item -> put(values, item));
        } else {
            put(values, items);
        }
        // Map.copyOf would reject the "null" values Safaricom sometimes sends; keep them readable instead
        return Collections.unmodifiableMap(values);
    }

    private static void put(Map<String, String> values, Object item) {
        if (item instanceof JSONObject keyValue && keyValue.has("Key")) {
            // Keep the first value: the status query repeats some keys (e.g. DebitPartyName)
            values.putIfAbsent(keyValue.getString("Key"), String.valueOf(keyValue.opt("Value")));
        }
    }
}
