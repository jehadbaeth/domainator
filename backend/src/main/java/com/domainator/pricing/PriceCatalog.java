package com.domainator.pricing;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * In memory cache of every provider's price list. Refreshed on a background thread at startup and then
 * every {@code domainator.price-refresh-interval}, so a slow registrar never blocks the app from starting.
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
    private final Map<String, ProviderState> states = new ConcurrentHashMap<>();

    public PriceCatalog(List<RegistrarProvider> providers, RootZone rootZone) {
        this.providers = providers;
        this.rootZone = rootZone;
        providers.forEach(p -> states.put(p.id(), new ProviderState(p, Status.LOADING, Map.of(), null, null)));
    }

    @Scheduled(initialDelay = 0, fixedDelayString = "${domainator.price-refresh-interval:12h}")
    public void refreshAll() {
        rootZone.refresh();
        // dedicated threads: several providers sleep between page requests, which would starve the common pool
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(Math.min(12, Math.max(1, providers.size())));
        try {
            pool.invokeAll(providers.stream().map(p -> (java.util.concurrent.Callable<Void>) () -> {
                refresh(p);
                return null;
            }).toList());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdown();
        }
    }

    void refresh(RegistrarProvider provider) {
        ProviderState old = states.get(provider.id());
        try {
            long start = System.currentTimeMillis();
            // Drop TLDs listed at 0.00 (not actually sold) and names that are not in the DNS root zone.
            Map<String, TldPrice> prices = provider.fetchPrices().entrySet().stream()
                    .filter(e -> e.getValue().registration().signum() > 0 && e.getValue().renewal().signum() > 0)
                    .filter(e -> rootZone.contains(e.getKey()))
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
            if (prices.size() < provider.minExpectedTlds()) {
                throw new IllegalStateException("only " + prices.size() + " prices found, expected at least "
                        + provider.minExpectedTlds() + "; the page layout probably changed");
            }
            states.put(provider.id(), new ProviderState(provider, Status.READY, prices, Instant.now(), null));
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
