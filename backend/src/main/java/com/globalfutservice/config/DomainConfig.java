package com.globalfutservice.config;

import com.globalfutservice.domain.coaching.CoachingPolicy;
import com.globalfutservice.domain.crypto.EnvelopeCipher;
import com.globalfutservice.domain.crypto.SecureIds;
import com.globalfutservice.domain.pricing.PricingEngine;
import com.globalfutservice.domain.pricing.PricingPolicy;
import com.globalfutservice.domain.pricing.QuoteSigner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.Base64;

/**
 * Wires the framework-free domain core into the Spring context.
 *
 * <p>This is the only place that knows the domain exists as beans. The domain classes
 * themselves carry no annotations, which is what lets them be unit-tested without a
 * container and verified offline without Maven.
 */
@Configuration
public class DomainConfig {

    /**
     * Injected rather than called statically, so tests can freeze time and assert on
     * quote expiry and guarantee windows without sleeping.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public PricingPolicy pricingPolicy(AppProperties props) {
        AppProperties.Pricing p = props.pricing();
        return new PricingPolicy(
                p.marketTaxBps(),
                p.marketTaxMode(),
                p.gatewayFeeBps(),
                p.gatewayFeeMode(),
                p.maxWalletRedemptionBps(),
                p.loyaltyCurrency(),
                p.pointValueMinor(),
                p.earnSpendUnitMinor(),
                p.earnPointsPerUnit(),
                p.quoteTtl(),
                p.tierDiscountEnabled());
    }

    @Bean
    public PricingEngine pricingEngine(PricingPolicy policy, Clock clock) {
        return new PricingEngine(policy, clock, SecureIds::quoteId);
    }

    @Bean
    public CoachingPolicy coachingPolicy(AppProperties props) {
        AppProperties.Coaching c = props.coaching();
        return new CoachingPolicy(
                c.sessionLength(),
                c.blockSessionLength(),
                c.slotStep(),
                c.minLeadTime(),
                c.maxAdvance(),
                c.changeCutoff(),
                c.maxReschedules(),
                c.noShowGrace(),
                c.creditValidity());
    }

    @Bean
    public QuoteSigner quoteSigner(AppProperties props) {
        return new QuoteSigner(props.security().quoteSigningSecret());
    }

    /**
     * Master key for the credential vault.
     *
     * <p>Supplied as base64 of exactly 32 bytes via {@code GFS_CREDENTIAL_MASTER_KEY}.
     * Deliberately fails startup when absent or malformed: an application that boots
     * without a usable key would go on to store EA sign-ins it could not protect.
     */
    @Bean
    public EnvelopeCipher envelopeCipher(AppProperties props) {
        String encoded = props.security().credentialMasterKey();
        byte[] key;
        try {
            key = Base64.getDecoder().decode(encoded.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "gfs.security.credential-master-key must be base64. "
                            + "Generate one with: openssl rand -base64 32", e);
        }
        return new EnvelopeCipher(key);
    }
}
