package org.mifos.connector.mpesa.auth;

/**
 * Store for the Safaricom OAuth access token.
 */
public interface AccessTokenStore {

    void saveToken(String accessToken, int expiresInSeconds);

    String getAccessToken();

    boolean isValid();
}
