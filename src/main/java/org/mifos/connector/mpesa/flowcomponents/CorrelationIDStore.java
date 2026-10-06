package org.mifos.connector.mpesa.flowcomponents;

/**
 * Maps Safaricom server correlation ids to client correlation ids.
 */
public interface CorrelationIDStore {

    void addMapping(String serverCorrelation, String clientCorrelation);

    String getClientCorrelation(String serverCorrelation);
}
