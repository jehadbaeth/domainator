package com.domainator.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Everything that is configurable lives here and is bound from application.yml (prefix "domainator").
 */
@ConfigurationProperties(prefix = "domainator")
public record DomainatorProperties(
        @DefaultValue("EUR") String displayCurrency,
        @DefaultValue("12h") Duration priceRefreshInterval,
        /* a provider whose refresh failed is not tried again before this */
        @DefaultValue("2h") Duration priceRetryInterval,
        /* where price lists are kept between restarts; empty = memory only */
        String cacheDir,
        Suggestions suggestions,
        Availability availability,
        Map<String, Integer> tldMinimumYears,
        /* provider id -> settings (enabled, api-key, ...). Values usually come from .env via ${...} placeholders. */
        Map<String, Map<String, String>> providers,
        List<StaticProvider> staticProviders) {

    public DomainatorProperties {
        priceRefreshInterval = priceRefreshInterval == null ? Duration.ofHours(12) : priceRefreshInterval;
        priceRetryInterval = priceRetryInterval == null ? Duration.ofHours(2) : priceRetryInterval;
        cacheDir = cacheDir == null || cacheDir.isBlank() ? null : cacheDir;
        tldMinimumYears = tldMinimumYears == null ? Map.of() : tldMinimumYears;
        providers = providers == null ? Map.of() : providers;
        staticProviders = staticProviders == null ? List.of() : staticProviders;
        if (suggestions == null) {
            suggestions = new Suggestions(List.of("com"), List.of("com"), List.of(), List.of(), 60, true);
        }
        if (availability == null) {
            availability = new Availability(8, 2, Duration.ofSeconds(6), Duration.ofHours(24), Duration.ofHours(1), Map.of());
        }
    }

    public record Suggestions(
            List<String> tlds,
            List<String> modifierTlds,
            List<String> prefixes,
            List<String> suffixes,
            @DefaultValue("60") int maxCandidates,
            @DefaultValue("true") boolean domainHacks) {
    }

    public record Availability(
            @DefaultValue("8") int concurrency,
            /* parallel requests to any single RDAP server (several TLDs often share one) */
            @DefaultValue("2") int perHostConcurrency,
            @DefaultValue("6s") Duration timeout,
            /* registered domains rarely become free, so their answer is kept long */
            @DefaultValue("24h") Duration takenCacheTtl,
            /* a free domain can be registered by someone else at any time */
            @DefaultValue("1h") Duration availableCacheTtl,
            /* RDAP base URLs for TLDs that are missing from the IANA bootstrap file. */
            Map<String, String> rdapOverrides) {

        public Availability {
            rdapOverrides = rdapOverrides == null ? Map.of() : rdapOverrides;
        }
    }

    /** A registrar without a usable price API: prices are typed into the config by hand. */
    public record StaticProvider(
            String id,
            String name,
            @DefaultValue("true") boolean enabled,
            @DefaultValue("USD") String currency,
            @DefaultValue("as entered in config") String taxNote,
            Map<String, StaticPrice> prices) {
    }

    public record StaticPrice(BigDecimal registration, BigDecimal renewal) {
    }
}
