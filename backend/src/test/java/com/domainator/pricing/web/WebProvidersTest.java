package com.domainator.pricing.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.domainator.pricing.TldPrice;

/** Fixtures are trimmed copies of the real public price pages, fetched 2026-11-21. */
class WebProvidersTest {

    private static final RestClient NONE = RestClient.create();

    private static Document page(String name) throws Exception {
        try (InputStream in = WebProvidersTest.class.getResourceAsStream("/fixtures/web/" + name)) {
            return Jsoup.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void numbers() {
        assertThat(PricePage.number("4,17")).isEqualByComparingTo("4.17");
        assertThat(PricePage.number("1.234,56 €")).isEqualByComparingTo("1234.56");
        assertThat(PricePage.number("2,060.25")).isEqualByComparingTo("2060.25");
        assertThat(PricePage.number("1905.00")).isEqualByComparingTo("1905.00");
        assertThat(PricePage.number("$14.89/year")).isEqualByComparingTo("14.89");
        assertThat(PricePage.number("0,08 €*")).isEqualByComparingTo("0.08");
        assertThat(PricePage.number("C$22.70 / year")).isEqualByComparingTo("22.70");
        assertThat(PricePage.number("12 €")).isEqualByComparingTo("12");
        assertThat(PricePage.number("Free")).isNull();
        assertThat(PricePage.net(new java.math.BigDecimal("11.90"), 19)).isEqualByComparingTo("10.00");
    }

    @Test
    void nearlyFreeSpeechPromoAndRenewal() throws Exception {
        Map<String, TldPrice> p = new NearlyFreeSpeechProvider(NONE).parse(page("nfs.html"));
        assertThat(p.get("com").registration()).isEqualByComparingTo("14.59");
        assertThat(p.get("com").renewal()).isEqualByComparingTo("14.89");
        assertThat(p.get("xyz").registration()).isEqualByComparingTo("3.00");
        assertThat(p.get("xyz").renewal()).isEqualByComparingTo("22.99");
        assertThat(p.get("xyz").promo()).contains("18.89");
    }

    @Test
    void nameBrightUsesPriceNotPrepaid() throws Exception {
        Map<String, TldPrice> p = new NameBrightProvider(NONE).parse(page("namebright.html"));
        assertThat(p.get("com").registration()).isEqualByComparingTo("11.76");
        assertThat(p.get("io").renewal()).isEqualByComparingTo("50");
    }

    @Test
    void domainFactoryMonthlyGrossToYearlyNet() throws Exception {
        Map<String, TldPrice> p = new DomainFactoryProvider(NONE).parse(page("domainfactory.html"));
        // .de: 0,08 €/month promo, 0,99 €/month regular, incl. 19% VAT
        assertThat(p.get("de").registration()).isEqualByComparingTo("0.81"); // 0.96 / 1.19
        assertThat(p.get("de").renewal()).isEqualByComparingTo("9.98");      // 11.88 / 1.19
        assertThat(p.get("com").renewal()).isEqualByComparingTo("20.07");    // 23.88 / 1.19
    }

    @Test
    void oneComStripsBadgeAndVat() throws Exception {
        Map<String, TldPrice> p = new OneComProvider(NONE).parse(page("onecom.html"));
        assertThat(p.get("com").registration()).isEqualByComparingTo("3.50");  // 4,17 / 1.19
        assertThat(p.get("com").renewal()).isEqualByComparingTo("24.20");      // 28,80 / 1.19
        assertThat(p.get("academy").registration()).isEqualByComparingTo("29.15"); // badge text ignored, 34,69 / 1.19
        assertThat(p.get("com").promo()).isNotNull();
    }

    @Test
    void allInklSetupPlusYearly() throws Exception {
        Map<String, TldPrice> p = new AllInklProvider(NONE).parse(page("allinkl.html"));
        assertThat(p.get("de").renewal()).isEqualByComparingTo("8.32"); // 9,90 / 1.19
        assertThat(p.get("com").renewal()).isEqualByComparingTo("12.52"); // 14,90 / 1.19
        assertThat(p.get("ac").registration()).isGreaterThan(p.get("ac").renewal()); // setup fee in year one
    }

    @Test
    void loopiaWithoutTax() throws Exception {
        Map<String, TldPrice> p = new LoopiaProvider(NONE).parse(page("loopia.html"));
        assertThat(p.get("com").registration()).isEqualByComparingTo("129");
        assertThat(p.get("com").renewal()).isEqualByComparingTo("299");
        assertThat(p.get("com").currency()).isEqualTo("SEK");
    }

    @Test
    void namespro() throws Exception {
        Map<String, TldPrice> p = new NamesproProvider(NONE).parse(page("namespro.html"));
        assertThat(p.get("com").renewal()).isEqualByComparingTo("22.70");
        assertThat(p.get("com").currency()).isEqualTo("CAD");
    }

    @Test
    void perTldPages() throws Exception {
        TldPrice eu = EuroDnsProvider.parseTld("com", page("eurodns-com.html"));
        assertThat(eu.registration()).isEqualByComparingTo("10.99");
        assertThat(eu.renewal()).isEqualByComparingTo("22.00");

        TldPrice one = OneOhOneDomainProvider.parseTld("com", page("101domain-com.html"));
        assertThat(one.registration()).isEqualByComparingTo("15.99");
        assertThat(one.renewal()).isEqualByComparingTo("21.49");

        TldPrice gn = GnameProvider.parseTld("xyz", page("gname-xyz.html"));
        assertThat(gn.registration()).isEqualByComparingTo("5.99");
        assertThat(gn.renewal()).isEqualByComparingTo("24.99");
        assertThat(gn.promo()).isNotNull();
    }

    @Test
    void unitedDomainsRowsAndRenewalFootnote() throws Exception {
        Map<String, UnitedDomainsProvider.Row> rows = UnitedDomainsProvider.rows(page("uniteddomains-list.html"));
        assertThat(rows.get("com").firstYearOnly()).isTrue();
        assertThat(rows.get("com").grossPrice()).isEqualByComparingTo("12");
        assertThat(rows.get("xyz").firstYearOnly()).isFalse();
        assertThat(UnitedDomainsProvider.renewal(page("uniteddomains-com.html"))).isEqualByComparingTo("27");
    }
}
