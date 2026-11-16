package org.mifos.connector.mpesa.payout;

import lombok.RequiredArgsConstructor;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.mifos.connector.mpesa.utility.MpesaProps;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.regex.Pattern;

import static org.mifos.connector.mpesa.payout.PayoutValidationException.INVALID_AMOUNT;
import static org.mifos.connector.mpesa.payout.PayoutValidationException.INVALID_CURRENCY;
import static org.mifos.connector.mpesa.payout.PayoutValidationException.INVALID_MSISDN;
import static org.mifos.connector.mpesa.payout.PayoutValidationException.INVALID_REQUEST;
import static org.mifos.connector.mpesa.payout.PayoutValidationException.PAYOUT_NOT_ENABLED;

/**
 * Builds the B2C request for a payout from the channel payout request, rejecting anything Safaricom would not accept.
 *
 * <p>Expected payout request:
 * <pre>
 * {
 *   "payee": [{"key": "MSISDN", "value": "254712345678"}, {"key": "ACCOUNTID", "value": "123456"}],
 *   "amount": {"amount": "1500", "currency": "KES"},
 *   "remarks": "Refund of overpayment"
 * }
 * </pre>
 */
@Component
@RequiredArgsConstructor
public class PayoutRequestBuilder {

    private static final String MSISDN_KEY = "MSISDN";
    private static final String CURRENCY = "KES";
    // B2C only pays Kenyan M-Pesa numbers: 254 followed by 9 digits
    private static final Pattern MSISDN_PATTERN = Pattern.compile("254\\d{9}");
    private static final int MIN_REMARKS = 2;
    private static final int MAX_TEXT = 100;
    static final String DEFAULT_REMARKS = "Payout";

    @Value("${mpesa.local.host}")
    private final String host;
    @Value("${mpesa.local.payout-result}")
    private final String resultPath;
    @Value("${mpesa.local.payout-timeout}")
    private final String timeoutPath;
    @Value("${mpesa.local.payout-status-result}")
    private final String statusResultPath;
    @Value("${mpesa.local.payout-status-timeout}")
    private final String statusTimeoutPath;
    private final PayoutCallbackSigner callbackSigner;

    /**
     * The status query for a payout, found by its OriginatorConversationID (the transaction id).
     */
    public B2CTransactionStatusRequestDTO buildStatusQuery(String transactionId, MpesaProps.MPESA account) {
        MpesaProps.B2C b2c = account.getB2c();
        B2CTransactionStatusRequestDTO dto = new B2CTransactionStatusRequestDTO();
        dto.setInitiator(b2c.getInitiatorName());
        dto.setSecurityCredential(b2c.getSecurityCredential());
        dto.setOriginalConversationId(transactionId);
        dto.setPartyA(Long.parseLong(b2c.getShortCode().trim()));
        var token = callbackSigner.sign(transactionId);
        dto.setResultUrl(host + statusResultPath + "/" + token);
        dto.setQueueTimeOutUrl(host + statusTimeoutPath + "/" + token);
        dto.setRemarks("Payout status check");
        dto.setOccasion(transactionId);
        return dto;
    }

    public B2CPaymentRequestDTO build(String transactionId, String payoutRequestJson, MpesaProps.MPESA account) {
        if (account == null || account.getB2c() == null || !account.getB2c().isEnabled()) {
            throw new PayoutValidationException(PAYOUT_NOT_ENABLED, "B2C payouts are not enabled for account "
                    + (account == null ? null : account.getName()));
        }
        if (!callbackSigner.isConfigured()) {
            // Without the secret the result callbacks could not be authenticated, so nothing is sent
            throw new PayoutValidationException(PAYOUT_NOT_ENABLED, "B2C payouts need mpesa.payout.callback-secret "
                    + "(MPESA_PAYOUT_CALLBACK_SECRET) to authenticate Safaricom's result callbacks");
        }
        MpesaProps.B2C b2c = account.getB2c();
        JSONObject request;
        try {
            request = new JSONObject(payoutRequestJson);
        } catch (JSONException | NullPointerException e) {
            throw new PayoutValidationException(INVALID_REQUEST, "Payout request is missing or not JSON");
        }

        B2CPaymentRequestDTO dto = new B2CPaymentRequestDTO();
        dto.setOriginatorConversationId(transactionId);
        dto.setInitiatorName(b2c.getInitiatorName());
        dto.setSecurityCredential(b2c.getSecurityCredential());
        dto.setCommandId(b2c.getCommandId());
        dto.setAmount(amount(request.optJSONObject("amount"), b2c));
        dto.setPartyA(Long.parseLong(b2c.getShortCode().trim()));
        dto.setPartyB(msisdn(request.optJSONArray("payee")));
        dto.setRemarks(remarks(request.opt("remarks")));
        dto.setOccasion(accountId(request.optJSONArray("payee")));
        // Each payout's callback URLs carry its own token, so a result can only be posted by whoever got the request
        var token = callbackSigner.sign(transactionId);
        dto.setResultUrl(host + resultPath + "/" + token);
        dto.setQueueTimeOutUrl(host + timeoutPath + "/" + token);
        return dto;
    }

    static long amount(JSONObject amount, MpesaProps.B2C b2c) {
        Object currency = amount == null ? null : amount.opt("currency");
        if (currency != null && currency != JSONObject.NULL && !CURRENCY.equalsIgnoreCase(String.valueOf(currency))) {
            throw new PayoutValidationException(INVALID_CURRENCY, "Currency '" + currency + "' is not supported, "
                    + "M-Pesa pays out in " + CURRENCY);
        }
        Object raw = amount == null ? null : amount.opt("amount");
        if (!(raw instanceof String text) || text.isBlank()) {
            throw new PayoutValidationException(INVALID_AMOUNT, "Amount is missing");
        }
        long value;
        try {
            value = new BigDecimal(text.trim()).stripTrailingZeros().longValueExact();
        } catch (NumberFormatException e) {
            throw new PayoutValidationException(INVALID_AMOUNT, "Amount '" + raw + "' is not a number");
        } catch (ArithmeticException e) {
            throw new PayoutValidationException(INVALID_AMOUNT,
                    "Amount '" + raw + "' must be a whole number, M-Pesa does not accept decimal amounts");
        }
        if (value < b2c.getMinAmount() || value > b2c.getMaxAmount()) {
            throw new PayoutValidationException(INVALID_AMOUNT, "Amount " + value + " must be between "
                    + b2c.getMinAmount() + " and " + b2c.getMaxAmount());
        }
        return value;
    }

    static long msisdn(JSONArray payee) {
        String value = payeeValue(payee, true);
        String msisdn = value == null ? "" : value.trim();
        if (msisdn.startsWith("+")) {
            msisdn = msisdn.substring(1);
        }
        if (!MSISDN_PATTERN.matcher(msisdn).matches()) {
            throw new PayoutValidationException(INVALID_MSISDN,
                    "Payee MSISDN '" + value + "' is missing or invalid, it must be 254 followed by 9 digits");
        }
        return Long.parseLong(msisdn);
    }

    /**
     * Remarks are optional in the scheme-neutral payout request, but B2C needs 2 to 100 characters: fall back to a
     * default and cut long remarks rather than failing a payout over its note.
     */
    static String remarks(Object raw) {
        var remarks = raw instanceof String text ? text.trim() : "";
        if (remarks.length() < MIN_REMARKS) {
            return DEFAULT_REMARKS;
        }
        return remarks.length() > MAX_TEXT ? remarks.substring(0, MAX_TEXT) : remarks;
    }

    private static String accountId(JSONArray payee) {
        String accountId = payeeValue(payee, false);
        if (accountId == null || accountId.isBlank()) {
            return null;
        }
        accountId = accountId.trim();
        return accountId.length() > MAX_TEXT ? accountId.substring(0, MAX_TEXT) : accountId;
    }

    /**
     * The value of the MSISDN entry when {@code msisdn} is true, otherwise of the first other entry.
     */
    private static String payeeValue(JSONArray payee, boolean msisdn) {
        if (payee == null) {
            return null;
        }
        for (int i = 0; i < payee.length(); i++) {
            JSONObject party = payee.optJSONObject(i);
            if (party == null || !(party.opt("key") instanceof String)) {
                continue;
            }
            boolean isMsisdn = MSISDN_KEY.equalsIgnoreCase(party.getString("key"));
            Object value = party.opt("value");
            if (isMsisdn == msisdn) {
                return value instanceof String text ? text : null;
            }
        }
        return null;
    }
}
