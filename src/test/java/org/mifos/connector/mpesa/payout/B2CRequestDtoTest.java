package org.mifos.connector.mpesa.payout;

import org.junit.jupiter.api.Test;
import org.mifos.connector.mpesa.utility.MpesaProps;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class B2CRequestDtoTest {

    private static final String CREDENTIAL = "s3cr3t-encrypted-initiator-password";

    @Test
    void paymentRequest_toString_shouldMaskTheSecurityCredential() {
        var dto = new B2CPaymentRequestDTO();
        dto.setOriginatorConversationId("tx-1");
        dto.setSecurityCredential(CREDENTIAL);

        var text = dto.toString();

        assertTrue(text.contains("originatorConversationId=tx-1"), text);
        assertTrue(text.contains("securityCredential="), text);
        assertFalse(text.contains(CREDENTIAL), text);
    }

    @Test
    void statusQuery_toString_shouldMaskTheSecurityCredential() {
        var dto = new B2CTransactionStatusRequestDTO();
        dto.setOriginalConversationId("tx-1");
        dto.setSecurityCredential(CREDENTIAL);

        var text = dto.toString();

        assertTrue(text.contains("originalConversationId=tx-1"), text);
        assertFalse(text.contains(CREDENTIAL), text);
    }

    @Test
    void b2cSettings_toString_shouldLeaveSecretsOut() {
        var b2c = new MpesaProps.B2C();
        b2c.setShortCode("600999");
        b2c.setSecurityCredential(CREDENTIAL);
        b2c.setClientSecret("client-secret");

        var text = b2c.toString();

        assertTrue(text.contains("600999"), text);
        assertFalse(text.contains(CREDENTIAL), text);
        assertFalse(text.contains("client-secret"), text);
    }
}
