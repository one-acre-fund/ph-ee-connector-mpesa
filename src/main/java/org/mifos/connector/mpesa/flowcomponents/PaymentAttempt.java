package org.mifos.connector.mpesa.flowcomponents;

/**
 * Outcome of a provider call for one transaction, kept so a repeated activation of the same job
 * can complete with the original result instead of paying again.
 */
public class PaymentAttempt {

    private boolean failed;
    private String serverTransactionId;
    private String errorCode;
    private String errorDescription;
    private String errorInformation;

    public PaymentAttempt() {
    }

    public PaymentAttempt(boolean failed, String serverTransactionId, String errorCode, String errorDescription,
                          String errorInformation) {
        this.failed = failed;
        this.serverTransactionId = serverTransactionId;
        this.errorCode = errorCode;
        this.errorDescription = errorDescription;
        this.errorInformation = errorInformation;
    }

    public boolean isFailed() {
        return failed;
    }

    public void setFailed(boolean failed) {
        this.failed = failed;
    }

    public String getServerTransactionId() {
        return serverTransactionId;
    }

    public void setServerTransactionId(String serverTransactionId) {
        this.serverTransactionId = serverTransactionId;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getErrorDescription() {
        return errorDescription;
    }

    public void setErrorDescription(String errorDescription) {
        this.errorDescription = errorDescription;
    }

    public String getErrorInformation() {
        return errorInformation;
    }

    public void setErrorInformation(String errorInformation) {
        this.errorInformation = errorInformation;
    }
}
