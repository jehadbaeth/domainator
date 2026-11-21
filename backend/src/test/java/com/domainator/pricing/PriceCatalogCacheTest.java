package com.domainator.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PriceCatalogCacheTest {

    /** A provider that counts how often it is asked, and can be made to fail. */
    static class CountingProvider implements RegistrarProvider {
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean fail;

        @Override public String id() { return "counting"; }
        @Override public String displayName() { return "Counting"; }
        @Override public String taxNote() { return ""; }
        @Override public String buyUrl(String domain) { return null; }

        @Override
        public Map<String, TldPrice> fetchPrices() {
            calls.incrementAndGet();
            if (fail) {
                throw new IllegalStateException("registrar down");
            }
            return Map.of("com", new TldPrice("com", new BigDecimal("10.00"), new BigDecimal("12.00"), "USD", null));
        }
    }

    static final RootZone ALL_TLDS = new RootZone(null) {
        @Override public void refresh() { }
        @Override public boolean contains(String tld) { return true; }
    };

    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-21T12:00:00Z");
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private PriceCatalog catalog(CountingProvider p, Path dir, MutableClock clock) {
        return new PriceCatalog(List.of(p), ALL_TLDS, new PriceCache(dir), Duration.ofHours(12), Duration.ofHours(2), clock);
    }

    @Test
    void restartServesPricesFromDiskWithoutFetching(@TempDir Path dir) {
        MutableClock clock = new MutableClock();
        CountingProvider first = new CountingProvider();
        catalog(first, dir, clock).refreshDue();
        assertThat(first.calls).hasValue(1);

        // "restart" two hours later: a new catalog on the same cache directory
        clock.now = clock.now.plus(Duration.ofHours(2));
        CountingProvider second = new CountingProvider();
        PriceCatalog restarted = catalog(second, dir, clock);
        assertThat(restarted.states().get(0).status()).isEqualTo(PriceCatalog.Status.READY);
        assertThat(restarted.states().get(0).prices().get("com").renewal()).isEqualByComparingTo("12.00");
        restarted.refreshDue();
        assertThat(second.calls).hasValue(0);

        // once the list is older than 12 hours it is refreshed
        clock.now = clock.now.plus(Duration.ofHours(11));
        restarted.refreshDue();
        assertThat(second.calls).hasValue(1);
    }

    @Test
    void failedProviderIsNotHammeredAndKeepsLastGoodList(@TempDir Path dir) {
        MutableClock clock = new MutableClock();
        CountingProvider p = new CountingProvider();
        PriceCatalog catalog = catalog(p, dir, clock);
        catalog.refreshDue();

        p.fail = true;
        clock.now = clock.now.plus(Duration.ofHours(13));
        catalog.refreshDue();
        assertThat(p.calls).hasValue(2);
        assertThat(catalog.states().get(0).status()).isEqualTo(PriceCatalog.Status.READY); // old list kept
        assertThat(catalog.states().get(0).error()).contains("registrar down");

        // checks every 30 minutes do not retry before the 2 hour back-off
        clock.now = clock.now.plus(Duration.ofMinutes(30));
        catalog.refreshDue();
        assertThat(p.calls).hasValue(2);
        clock.now = clock.now.plus(Duration.ofMinutes(91));
        catalog.refreshDue();
        assertThat(p.calls).hasValue(3);
    }

    @Test
    void noCacheDirMeansMemoryOnly() {
        CountingProvider p = new CountingProvider();
        PriceCatalog c = new PriceCatalog(List.of(p), ALL_TLDS, new PriceCache(null), Duration.ofHours(12),
                Duration.ofHours(2), new MutableClock());
        c.refreshDue();
        assertThat(c.states().get(0).prices()).containsKey("com");
    }
}
