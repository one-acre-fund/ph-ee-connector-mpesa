package org.mifos.connector.mpesa.utility;

import lombok.Getter;

/**
 * Thrown when a channel request carries data that can never be sent to Safaricom (for example a fractional amount or
 * a missing MSISDN). Retrying will not fix it, so workers must fail the transfer with a business reason instead of
 * letting the job raise an incident.
 */
@Getter
public class ChannelRequestValidationException extends RuntimeException {

    public static final String INVALID_AMOUNT = "INVALID_AMOUNT";
    public static final String INVALID_MSISDN = "INVALID_MSISDN";
    public static final String INVALID_PAYER = "INVALID_PAYER";

    private final String errorCode;

    public ChannelRequestValidationException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
