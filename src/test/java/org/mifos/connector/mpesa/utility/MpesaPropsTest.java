package org.mifos.connector.mpesa.utility;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MpesaPropsTest {

    @Test
    void mpesaGroup_shouldTrimAndParseNumericFields() {
        MpesaProps.MPESA mpesa = mpesa("roster");

        assertEquals(174379L, mpesa.getBusinessShortCode());
        assertEquals(600000L, mpesa.getTill());
        assertEquals("http://auth", mpesa.getAuthHost());
        assertEquals("http://api", mpesa.getApiHost());
        assertEquals("key", mpesa.getClientKey());
        assertEquals("secret", mpesa.getClientSecret());
        assertEquals("passkey", mpesa.getPassKey());
        assertTrue(mpesa.toString().contains("name='roster'"));
        assertTrue(mpesa.toString().contains("till='600000'"));
    }

    @Test
    void defaultCredentials_shouldOnlyBeReturnedForDefaultGroup() {
        assertEquals("key", mpesa("default").getDefaultClientKey());
        assertEquals("secret", mpesa("default").getDefaultClientSecret());
        assertNull(mpesa("roster").getDefaultClientKey());
        assertNull(mpesa("roster").getDefaultClientSecret());
    }

    @Test
    void mpesaProps_shouldExposeGroups() {
        MpesaProps props = new MpesaProps();
        List<MpesaProps.MPESA> groups = List.of(mpesa("default"));
        props.setGroup(groups);

        assertSame(groups, props.getGroup());

        MpesaAMSProp amsProp = new MpesaAMSProp();
        ReflectionTestUtils.setField(amsProp, "mpesaProps", props);
        assertSame(groups, amsProp.getGroup());
    }

    @Test
    void paybillProp_shouldResolveAmsAndCurrencyByShortCode() {
        MpesaPaybillProp prop = new MpesaPaybillProp();
        prop.setAccountHoldingInstitutionId("kenya");
        prop.setGroup(List.of(new ShortCodeAms("174379", "roster", "KES"), new ShortCodeAms("600000", "paygops", "UGX")));

        assertEquals("kenya", prop.getAccountHoldingInstitutionId());
        assertEquals("paygops", prop.getAMSFromShortCode("600000"));
        assertEquals("KES", prop.getCurrencyFromShortCode("174379"));
        assertEquals(2, prop.getGroups().size());
        assertThrows(java.util.NoSuchElementException.class, () -> prop.getAMSFromShortCode("999"));
    }

    @Test
    void shortCodeAms_shouldExposeFields() {
        ShortCodeAms shortCodeAms = new ShortCodeAms();
        shortCodeAms.setBusinessShortCode("174379");
        shortCodeAms.setAms("roster");
        shortCodeAms.setCurrency("KES");

        assertEquals("174379", shortCodeAms.getBusinessShortCode());
        assertEquals("roster", shortCodeAms.getAms());
        assertEquals("KES", shortCodeAms.getCurrency());
        assertTrue(shortCodeAms.toString().contains("roster"));
    }

    @Test
    void connectionUtils_shouldBuildTimeoutOptions() {
        assertEquals("httpClient.connectTimeout=500&httpClient.connectionRequestTimeout=500&httpClient.socketTimeout=500",
                ConnectionUtils.getConnectionTimeoutDsl(500));
    }

    private static MpesaProps.MPESA mpesa(String name) {
        MpesaProps.MPESA mpesa = new MpesaProps.MPESA();
        mpesa.setName(name);
        mpesa.setBusinessShortCode(" 174379 ");
        mpesa.setTill(" 600000 ");
        mpesa.setAuthHost("http://auth");
        mpesa.setApiHost("http://api");
        mpesa.setClientKey("key");
        mpesa.setClientSecret("secret");
        mpesa.setPassKey("passkey");
        return mpesa;
    }
}
