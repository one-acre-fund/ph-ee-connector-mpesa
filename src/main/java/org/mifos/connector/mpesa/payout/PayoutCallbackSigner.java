package org.mifos.connector.mpesa.payout;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Signs the callback URLs of each payout, so only Safaricom (which received the URL in our request) can post its
 * result. The token in {@code .../payout/result/{token}} is an HMAC-SHA256 of the transaction id under
 * {@code mpesa.payout.callback-secret}: it needs no storage, works on every pod and cannot be derived from a
 * transaction id without the secret.
 */
@Component
public class PayoutCallbackSigner {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    public PayoutCallbackSigner(@Value("${mpesa.payout.callback-secret:}") String secret) {
        this.key = secret == null || secret.isBlank() ? null
                : new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    /**
     * Without a secret no payout can be sent, because its callbacks could not be authenticated.
     */
    public boolean isConfigured() {
        return key != null;
    }

    /**
     * @throws IllegalStateException when no secret is configured
     */
    public String sign(String transactionId) {
        if (key == null) {
            throw new IllegalStateException("mpesa.payout.callback-secret is not set");
        }
        try {
            var mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(transactionId.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot sign payout callbacks", e);
        }
    }

    /**
     * @return whether the token is the one issued for this transaction id (constant-time comparison)
     */
    public boolean verify(String transactionId, String token) {
        if (key == null || transactionId == null || token == null) {
            return false;
        }
        return MessageDigest.isEqual(sign(transactionId).getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
    }
}
