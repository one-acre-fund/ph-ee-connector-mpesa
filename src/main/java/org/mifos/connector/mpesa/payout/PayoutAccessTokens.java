package org.mifos.connector.mpesa.payout;

import org.mifos.connector.mpesa.utility.MpesaProps;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Safaricom access tokens for payouts, one per account and Daraja app. A B2C shortcode usually has its own app, so the
 * single token used for STK push cannot be reused. Tokens are cached per pod: fetching one is cheap and each lives an
 * hour.
 */
@Component
public class PayoutAccessTokens {

    // Refresh a little before Safaricom expires the token, so it does not expire during a call
    private static final long EXPIRY_MARGIN_MILLIS = 60_000;

    private final Map<String, Token> tokens = new ConcurrentHashMap<>();
    private final Clock clock;

    @Autowired
    public PayoutAccessTokens() {
        this(Clock.systemUTC());
    }

    PayoutAccessTokens(Clock clock) {
        this.clock = clock;
    }

    /**
     * @return a valid token for the account, or null when it must be fetched
     */
    public String get(MpesaProps.MPESA account) {
        var token = tokens.get(key(account));
        return token == null || clock.millis() >= token.expiresAtMillis() ? null : token.value();
    }

    public void save(MpesaProps.MPESA account, String accessToken, long expiresInSeconds) {
        long lifetime = Math.max(expiresInSeconds * 1000 - EXPIRY_MARGIN_MILLIS, 0);
        tokens.put(key(account), new Token(accessToken, clock.millis() + lifetime));
    }

    public void evict(MpesaProps.MPESA account) {
        tokens.remove(key(account));
    }

    /**
     * The Basic authorization value for fetching a token with the account's B2C app credentials.
     */
    public static String basicAuthorization(MpesaProps.MPESA account) {
        var credentials = clientKey(account).trim() + ":" + clientSecret(account).trim();
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    static String clientKey(MpesaProps.MPESA account) {
        return orDefault(account.getB2c().getClientKey(), account.getClientKey());
    }

    static String clientSecret(MpesaProps.MPESA account) {
        return orDefault(account.getB2c().getClientSecret(), account.getClientSecret());
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String key(MpesaProps.MPESA account) {
        return account.getName() + ":" + clientKey(account);
    }

    private record Token(String value, long expiresAtMillis) {
    }
}
