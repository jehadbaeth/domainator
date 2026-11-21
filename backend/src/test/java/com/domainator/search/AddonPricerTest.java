package com.domainator.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.domainator.fx.ExchangeRates;
import com.domainator.registrars.RegistrarProfiles;

/** Uses the real registrars.json; currencies are chosen so no FX rate is needed. */
class AddonPricerTest {

    private final AddonPricer pricer;

    AddonPricerTest() throws Exception {
        pricer = new AddonPricer(new RegistrarProfiles(), new ExchangeRates(RestClient.create()));
    }

    @Test
    void freeAddonCostsNothing() {
        AddonPricer.Result r = pricer.price("porkbun", List.of("privacy", "ssl", "emailForwarding"), "USD");
        assertThat(r.missing()).isFalse();
        assertThat(r.perYear()).isZero();
        assertThat(r.lines()).allSatisfy(l -> assertThat(l.status()).isEqualTo("free"));
    }

    @Test
    void paidYearlyAndMonthly() {
        AddonPricer.Result r = pricer.price("inwx", List.of("privacy", "emailForwarding"), "EUR");
        assertThat(r.missing()).isFalse();
        // privacy 4.19/yr + Mail Easy 0.29/month = 3.48/yr
        assertThat(r.perYear()).isEqualByComparingTo("7.67");
    }

    @Test
    void bundleIsPaidOnce() {
        // united-domains "Domain Tresor" covers privacy and DNSSEC
        AddonPricer.Result r = pricer.price("uniteddomains", List.of("privacy", "dnssec"), "EUR");
        assertThat(r.perYear()).isEqualByComparingTo("12.61");
    }

    @Test
    void oneTimeFeeOnlyInFirstYear() {
        AddonPricer.Result r = pricer.price("namespro", List.of("privacy"), "CAD");
        assertThat(r.firstYear()).isEqualByComparingTo("8.88");
        assertThat(r.perYear()).isZero();
    }

    @Test
    void missingAddonsBlockBest() {
        assertThat(pricer.price("allinkl", List.of("privacy"), "EUR").missing()).isTrue();   // none
        assertThat(pricer.price("cloudflare", List.of("privacy"), "USD").missing()).isTrue(); // redaction only
        assertThat(pricer.price("vercel", List.of("emailForwarding"), "USD").missing()).isTrue();
        assertThat(pricer.price("domainfactory", List.of("ssl"), "EUR").missing()).isTrue(); // paid, price unknown
        assertThat(pricer.price("namebright", List.of("urlForwarding"), "USD").missing()).isFalse(); // unknown is not missing
    }
}
