package com.domainator.pricing;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.domainator.config.DomainatorProperties;

/**
 * Every provider's price list, kept in memory and on disk.
 * <ul>
 *   <li>At startup the last good list of each provider is loaded from disk, so a restart serves prices
 *       immediately without contacting anyone.</li>
 *   <li>Every {@code domainator.price-check-interval} (30 min) a provider is refreshed only if its list is older
 *       than {@code domainator.price-refresh-interval} (12 h). Failed providers are retried after
 *       {@code domainator.price-retry-interval} (2 h), never in a tight loop.</li>
 *   <li>A failed refresh keeps serving the last good list.</li>
 * </ul>
 */
@Component
public class PriceCatalog {

    private static final Logger log = LoggerFactory.getLogger(PriceCatalog.class);

    public enum Status { LOADING, READY, ERROR }

    public record ProviderState(RegistrarProvider provider, Status status, Map<String, TldPrice> prices,
                                Instant updatedAt, String error) {
    }

    private final List<RegistrarProvider> providers;
    private final RootZone rootZone;
    private final PriceCache cache;
    private final Duration maxAge;
    private final Duration retryAfter;
    private final Clock clock;
    private final Map<String, ProviderState> states = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastAttempt = new ConcurrentHashMap<>();

    @Autowired
    public PriceCatalog(List<RegistrarProvider> providers, RootZone rootZone, DomainatorProperties props, Clock clock) {
        this(providers, rootZone, new PriceCache(props.cacheDir() == null ? null : Path.of(props.cacheDir())),
                props.priceRefreshInterval(), props.priceRetryInterval(), clock);
    }

    PriceCatalog(List<RegistrarProvider> providers, RootZone rootZone, PriceCache cache, Duration maxAge,
                 Duration retryAfter, Clock clock) {
        this.providers = providers;
        this.rootZone = rootZone;
        this.cache = cache;
        this.maxAge = maxAge;
        this.retryAfter = retryAfter;
        this.clock = clock;
        for (RegistrarProvider p : providers) {
            Optional<PriceCache.Entry> cached = cache.load(p.id());
            states.put(p.id(), cached
                    .map(c -> new ProviderState(p, Status.READY, c.prices(), c.fetchedAt(), null))
                    .orElse(new ProviderState(p, Status.LOADING, Map.of(), null, null)));
            cached.ifPresent(c -> log.info("Loaded {} cached prices for {} from {}", c.prices().size(), p.id(), c.fetchedAt()));
        }
    }

    /** Refreshes the providers whose price list is missing or too old. Cheap to call often. */
    @Scheduled(initialDelay = 0, fixedDelayString = "${domainator.price-check-interval:30m}")
    public void refreshDue() {
        List<RegistrarProvider> due = providers.stream().filter(this::isDue).toList();
        if (due.isEmpty()) {
            return;
        }
        rootZone.refresh();
        // dedicated threads: several providers sleep between page requests, which would starve the common pool
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(12, due.size()));
        try {
            pool.invokeAll(due.stream().map(p -> (Callable<Void>) () -> {
                refresh(p);
                return null;
            }).toList());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdown();
        }
    }

    boolean isDue(RegistrarProvider p) {
        Instant now = clock.instant();
        Instant attempted = lastAttempt.get(p.id());
        if (attempted != null && attempted.plus(retryAfter).isAfter(now)) {
            return false; // tried recently (and failed, or it would be fresh): do not hammer the registrar
        }
        ProviderState s = states.get(p.id());
        return s == null || s.updatedAt() == null || !s.updatedAt().plus(maxAge).isAfter(now);
    }

    void refresh(RegistrarProvider provider) {
        ProviderState old = states.get(provider.id());
        lastAttempt.put(provider.id(), clock.instant());
        try {
            long start = System.currentTimeMillis();
            // Drop TLDs listed at 0.00 (not actually sold) and names that are not in the DNS root zone.
            Map<String, TldPrice> prices = provider.fetchPrices().entrySet().stream()
                    .filter(e -> e.getValue().registration().signum() > 0 && e.getValue().renewal().signum() > 0)
                    .filter(e -> rootZone.contains(e.getKey()))
                    .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
            if (prices.size() < provider.minExpectedTlds()) {
                throw new IllegalStateException("only " + prices.size() + " prices found, expected at least "
                        + provider.minExpectedTlds() + "; the page layout probably changed");
            }
            Instant now = clock.instant();
            states.put(provider.id(), new ProviderState(provider, Status.READY, prices, now, null));
            cache.save(provider.id(), prices, now);
            log.info("Loaded {} TLD prices from {} in {} ms", prices.size(), provider.id(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            String error = Secrets.describe(e);
            log.warn("Could not load prices from {}: {}", provider.id(), error);
            // keep serving the last good price list if we have one
            boolean hadPrices = old != null && !old.prices().isEmpty();
            states.put(provider.id(), new ProviderState(provider, hadPrices ? Status.READY : Status.ERROR,
                    hadPrices ? old.prices() : Map.of(), hadPrices ? old.updatedAt() : null, error));
        }
    }

    public List<ProviderState> states() {
        return providers.stream().map(p -> states.get(p.id())).toList();
    }

    public Optional<ProviderState> state(String providerId) {
        return Optional.ofNullable(states.get(providerId));
    }

    /** All TLDs that at least one of the given providers sells. */
    public Set<String> knownTlds(Set<String> providerIds) {
        Set<String> out = new TreeSet<>();
        states.values().stream()
                .filter(s -> providerIds.contains(s.provider().id()))
                .forEach(s -> out.addAll(s.prices().keySet()));
        return out;
    }
}
