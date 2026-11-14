package org.mifos.connector.mpesa.resilience;

import java.io.IOException;

/**
 * Thrown instead of calling Safaricom when the call could not finish before the Zeebe job deadline. It is an
 * {@link IOException} so the routes treat it like any other request that never reached Safaricom.
 */
public class MpesaJobDeadlineException extends IOException {

    public MpesaJobDeadlineException(String call) {
        super("No time left before the job deadline for " + call + "; request not sent to Safaricom");
    }
}
