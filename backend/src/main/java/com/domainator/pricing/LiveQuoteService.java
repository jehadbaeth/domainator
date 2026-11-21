package com.domainator.pricing;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.domainator.pricing.RegistrarProvider.DomainQuote;

import jakarta.annotation.PreDestroy;

/**
 * Asks registrars that support it for live per-domain prices during a search (this is how premium names are
 * found). Results, including "no quote", are cached for 15 minutes per provider and domain, and a search waits
 * at most {@link #BUDGET} for them, so a slow registrar never blocks the page.
 */
@Service
public class LiveQuoteService {

    private static final Logger log = LoggerFactory.getLogger(LiveQuoteService.class);
    private static final Duration TTL = Duration.ofMinutes(15);
    private static final Duration BUDGET = Duration.ofSeconds(10);

    private record Cached(Optional<DomainQuote> quote, Instant at) {
    }

    private final ExecutorService pool = Executors.newFixedThreadPool(12);
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final Map<String, String> lastError = new ConcurrentHashMap<>();

    /** provider id -> domain -> quote, for the domains each provider had a price for. */
    public Map<String, Map<String, DomainQuote>> quote(List<RegistrarProvider> providers, List<String> domains) {
        Map<String, Map<String, DomainQuote>> out = new ConcurrentHashMap<>();
        List<CompletableFuture<Void>> jobs = new ArrayList<>();
        for (RegistrarProvider p : providers) {
            if (!p.quotesDomains() || domains.isEmpty()) {
                continue;
            }
            Map<String, DomainQuote> result = new ConcurrentHashMap<>();
            out.put(p.id(), result);
            List<String> todo = new ArrayList<>();
            for (String d : domains) {
                Cached c = cache.get(key(p, d));
                if (c != null && c.at().plus(TTL).isAfter(Instant.now())) {
                    c.quote().ifPresent(q -> result.put(d, q));
                } else {
                    todo.add(d);
                }
            }
            int chunk = Math.max(1, p.maxDomainsPerQuote());
            for (int i = 0; i < todo.size(); i += chunk) {
                List<String> batch = todo.subList(i, Math.min(todo.size(), i + chunk));
                jobs.add(CompletableFuture.runAsync(() -> fetch(p, batch, result), pool));
            }
        }
        try {
            CompletableFuture.allOf(jobs.toArray(CompletableFuture[]::new)).get(BUDGET.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.debug("Live quotes did not all finish in time: {}", e.toString());
        }
        // copy so late arrivals do not change the response while it is being serialized
        Map<String, Map<String, DomainQuote>> snapshot = new HashMap<>();
        out.forEach((k, v) -> snapshot.put(k, Map.copyOf(v)));
        return snapshot;
    }

    private void fetch(RegistrarProvider p, List<String> batch, Map<String, DomainQuote> result) {
        try {
            Map<String, DomainQuote> quotes = p.quoteDomains(batch);
            Instant now = Instant.now();
            for (String d : batch) {
                DomainQuote q = quotes.get(d);
                cache.put(key(p, d), new Cached(Optional.ofNullable(q), now));
                if (q != null) {
                    result.put(d, q);
                }
            }
            lastError.remove(p.id());
        } catch (Exception e) {
            String error = Secrets.describe(e);
            lastError.put(p.id(), error);
            log.warn("Live quotes from {} failed: {}", p.id(), error);
        }
    }

    /** Drops expired quotes so the cache does not grow without bound. */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "PT30M", initialDelayString = "PT30M")
    public void purgeExpired() {
        Instant cutoff = Instant.now().minus(TTL);
        cache.values().removeIf(c -> c.at().isBefore(cutoff));
    }

    public String lastError(String providerId) {
        return lastError.get(providerId);
    }

    private static String key(RegistrarProvider p, String domain) {
        return p.id() + "|" + domain;
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }
}
