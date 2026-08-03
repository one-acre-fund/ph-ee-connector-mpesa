package org.mifos.connector.mpesa.utility;


public class ConnectionUtils {

    /**
     * Returns Camel URI options for request timeouts.
     * Connection-pool lifetime / idle eviction (NAT idle protection) is configured on the
     * Camel {@code http}/{@code https} components via {@code HttpClientConfiguration}.
     *
     * @param timeout timeout value in ms
     */
    public static String getConnectionTimeoutDsl(int timeout) {
        String base = "httpClient.connectTimeout={}&httpClient.connectionRequestTimeout={}&httpClient.socketTimeout={}";
        return base.replace("{}", "" + timeout);
    }
}
