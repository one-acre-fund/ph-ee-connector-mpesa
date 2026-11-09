package org.mifos.connector.mpesa.resilience;

/**
 * Safaricom endpoints called through {@link MpesaHttpCaller}; each has its own circuit breaker.
 */
public enum MpesaEndpoint {

    TOKEN("token"), STK_PUSH("stk-push"), STK_QUERY("stk-query");

    private final String id;

    MpesaEndpoint(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}
