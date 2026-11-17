package org.mifos.connector.mpesa.payout;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PayoutCallbackSignerTest {

    private final PayoutCallbackSigner signer = new PayoutCallbackSigner("secret-1");

    @Test
    void sign_shouldBeStablePerTransactionAndUrlSafe() {
        var token = signer.sign("tx-1");

        assertEquals(token, new PayoutCallbackSigner("secret-1").sign("tx-1"));
        assertNotEquals(token, signer.sign("tx-2"));
        assertNotEquals(token, new PayoutCallbackSigner("secret-2").sign("tx-1"));
        assertTrue(token.matches("[A-Za-z0-9_-]{43}"), token);
    }

    @Test
    void verify_shouldOnlyAcceptTheTokenIssuedForTheTransaction() {
        assertTrue(signer.verify("tx-1", signer.sign("tx-1")));
        assertFalse(signer.verify("tx-1", signer.sign("tx-2")));
        assertFalse(signer.verify("tx-1", "forged"));
        assertFalse(signer.verify("tx-1", null));
        assertFalse(signer.verify(null, signer.sign("tx-1")));
    }

    @Test
    void withoutSecret_shouldNeitherSignNorVerify() {
        var unconfigured = new PayoutCallbackSigner("");

        assertFalse(unconfigured.isConfigured());
        assertFalse(new PayoutCallbackSigner(null).isConfigured());
        assertThrows(IllegalStateException.class, () -> unconfigured.sign("tx-1"));
        assertFalse(unconfigured.verify("tx-1", "anything"));
        assertTrue(signer.isConfigured());
    }
}
