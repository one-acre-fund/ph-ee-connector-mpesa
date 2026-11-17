package org.mifos.connector.mpesa.payout;

import lombok.Getter;

/**
 * A payout that can never be sent to Safaricom as requested. Nothing was sent, so the payout is failed with this reason
 * instead of raising an incident.
 */
@Getter
public class PayoutValidationException extends RuntimeException {

    public static final String INVALID_AMOUNT = "INVALID_AMOUNT";
    public static final String INVALID_MSISDN = "INVALID_MSISDN";
    public static final String INVALID_CURRENCY = "INVALID_CURRENCY";
    public static final String INVALID_REMARKS = "INVALID_REMARKS";
    public static final String INVALID_REQUEST = "INVALID_REQUEST";
    public static final String PAYOUT_NOT_ENABLED = "PAYOUT_NOT_ENABLED";

    private final String errorCode;

    public PayoutValidationException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
