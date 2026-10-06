package org.mifos.connector.mpesa.flowcomponents;

/**
 * Correlation / reconciliation store for paybill workflows keyed by M-Pesa transaction id.
 */
public interface PaybillStateStore {

    void putReconciled(String mpesaTxnId, Boolean reconciled);

    Boolean getReconciled(String mpesaTxnId);

    void removeReconciled(String mpesaTxnId);

    void putWorkflowInstance(String mpesaTxnId, String workflowInstanceKey);

    String getWorkflowInstance(String mpesaTxnId);

    void removeWorkflowInstance(String mpesaTxnId);
}
