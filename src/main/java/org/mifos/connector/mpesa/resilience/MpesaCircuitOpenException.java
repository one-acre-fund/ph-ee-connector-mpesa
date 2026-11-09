package org.mifos.connector.mpesa.resilience;

import java.io.IOException;

/**
 * Thrown instead of calling Safaricom while the endpoint's circuit is open. It is an {@link IOException} so the routes
 * treat it like any other request that never reached Safaricom.
 */
public class MpesaCircuitOpenException extends IOException {

    public MpesaCircuitOpenException(String circuit) {
        super("Circuit " + circuit + " is open; request not sent to Safaricom");
    }
}
