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
        Suggestions suggestions,
        Availability availability,
        Map<String, Integer> tldMinimumYears,
        /* provider id -> settings (enabled, api-key, ...). Values usually come from .env via ${...} placeholders. */
        Map<String, Map<String, String>> providers,
        List<StaticProvider> staticProviders) {

    public DomainatorProperties {
        tldMinimumYears = tldMinimumYears == null ? Map.of() : tldMinimumYears;
        providers = providers == null ? Map.of() : providers;
        staticProviders = staticProviders == null ? List.of() : staticProviders;
        if (suggestions == null) {
            suggestions = new Suggestions(List.of("com"), List.of("com"), List.of(), List.of(), 60, true);
        }
        if (availability == null) {
            availability = new Availability(8, Duration.ofSeconds(6), Duration.ofMinutes(15), Map.of());
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
            @DefaultValue("6s") Duration timeout,
            @DefaultValue("15m") Duration cacheTtl,
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
