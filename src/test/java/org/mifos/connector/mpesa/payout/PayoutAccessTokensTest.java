package org.mifos.connector.mpesa.payout;

import org.junit.jupiter.api.Test;
import org.mifos.connector.mpesa.utility.MpesaProps;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PayoutAccessTokensTest {

    private static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");

    @Test
    void get_shouldReturnTheTokenUntilShortlyBeforeItExpires() {
        MpesaProps.MPESA account = PayoutTestData.account();
        PayoutAccessTokens fresh = new PayoutAccessTokens(Clock.fixed(NOW, ZoneOffset.UTC));
        fresh.save(account, "abc", 3600);

        assertEquals("abc", fresh.get(account));

        PayoutAccessTokens later = new PayoutAccessTokens(Clock.fixed(NOW, ZoneOffset.UTC));
        later.save(account, "abc", 30);
        assertNull(later.get(account), "a token about to expire is not used");
    }

    @Test
    void get_shouldKeepTokensPerAccount() {
        PayoutAccessTokens tokens = new PayoutAccessTokens(Clock.fixed(NOW, ZoneOffset.UTC));
        MpesaProps.MPESA fineract = PayoutTestData.account();
        MpesaProps.MPESA roster = PayoutTestData.account();
        roster.setName("roster");
        tokens.save(fineract, "fineract-token", 3600);

        assertNull(tokens.get(roster));
        tokens.evict(fineract);
        assertNull(tokens.get(fineract));
    }

    @Test
    void get_afterExpiry_shouldReturnNull() {
        MpesaProps.MPESA account = PayoutTestData.account();
        MutableClock clock = new MutableClock();
        PayoutAccessTokens tokens = new PayoutAccessTokens(clock);
        tokens.save(account, "abc", 3600);

        clock.advance(Duration.ofMinutes(59));

        assertNull(tokens.get(account));
    }

    @Test
    void basicAuthorization_shouldUseB2cCredentialsAndFallBackToTheAccounts() {
        MpesaProps.MPESA account = PayoutTestData.account();
        assertEquals("stk-key:stk-secret", decode(PayoutAccessTokens.basicAuthorization(account)));

        account.getB2c().setClientKey("b2c-key");
        account.getB2c().setClientSecret("b2c-secret");
        assertEquals("b2c-key:b2c-secret", decode(PayoutAccessTokens.basicAuthorization(account)));

        // blank values, as left by unset environment variables, also fall back
        account.getB2c().setClientKey(" ");
        account.getB2c().setClientSecret("");
        assertEquals("stk-key:stk-secret", decode(PayoutAccessTokens.basicAuthorization(account)));
    }

    private static String decode(String basic) {
        return new String(Base64.getDecoder().decode(basic.substring("Basic ".length())), StandardCharsets.UTF_8);
    }

    private static final class MutableClock extends Clock {
        private Instant now = NOW;

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
