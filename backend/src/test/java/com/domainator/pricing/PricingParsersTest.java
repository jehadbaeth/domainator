package com.domainator.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class PricingParsersTest {

    @Test
    void porkbunHandlesThousandsSeparators() {
        var response = new PorkbunProvider.Response("SUCCESS", Map.of(
                "xyz", new PorkbunProvider.Entry("1.29", "14.21", "14.21"),
                "cars", new PorkbunProvider.Entry("2,060.25", "2,064.19", "2,064.19"),
                "broken", new PorkbunProvider.Entry("", "1.00", "1.00")));
        Map<String, TldPrice> prices = PorkbunProvider.parse(response);
        assertThat(prices).containsOnlyKeys("xyz", "cars");
        assertThat(prices.get("cars").renewal()).isEqualByComparingTo("2064.19");
        assertThat(prices.get("xyz").currency()).isEqualTo("USD");
    }

    @Test
    void ovhUsesDefaultModeAndActiveStandardPromotions() {
        OffsetDateTime now = OffsetDateTime.parse("2026-11-21T12:00:00+01:00");
        var activePromo = new OvhProvider.Promotion("Registry promo", "standard",
                now.minusDays(1), now.plusDays(5), new OvhProvider.Amount(149000000L, 28310000L));
        var discoverPromo = new OvhProvider.Promotion("Black Friday", "discover",
                now.minusDays(1), now.plusDays(5), new OvhProvider.Amount(1000000L, 0L));
        var expiredPromo = new OvhProvider.Promotion("Old", "standard",
                now.minusDays(30), now.minusDays(1), new OvhProvider.Amount(1000000L, 0L));
        var catalog = new OvhProvider.Catalog(new OvhProvider.Locale("EUR", "DE", 19), List.of(
                new OvhProvider.Plan("xyz", List.of(
                        new OvhProvider.Pricing(0, "create-premium", 12, "month", 99999900000000L, 0L, List.of()),
                        new OvhProvider.Pricing(0, "create-default", 12, "month", 189000000L, 35910000L,
                                List.of(activePromo, discoverPromo, expiredPromo)),
                        new OvhProvider.Pricing(1, "create-default", 12, "month", 1445000000L, 274550000L, List.of()))),
                new OvhProvider.Plan("com", List.of(
                        new OvhProvider.Pricing(0, "create-default", 12, "month", 849000000L, 161310000L, null),
                        new OvhProvider.Pricing(1, "create-default", 12, "month", 1429000000L, 271510000L, null)))));

        Map<String, TldPrice> net = OvhProvider.parse(catalog, false, now);
        assertThat(net.get("xyz").registration()).isEqualByComparingTo("1.49");
        assertThat(net.get("xyz").renewal()).isEqualByComparingTo("14.45");
        assertThat(net.get("xyz").promo()).startsWith("Registry promo");
        assertThat(net.get("com").registration()).isEqualByComparingTo("8.49");
        assertThat(net.get("com").promo()).isNull();

        Map<String, TldPrice> gross = OvhProvider.parse(catalog, true, now);
        assertThat(gross.get("com").registration()).isEqualByComparingTo("10.10");
    }
}
