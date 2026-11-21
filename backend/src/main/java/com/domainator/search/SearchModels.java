package com.domainator.search;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.domainator.availability.AvailabilityService;
import com.domainator.suggest.SuggestionGenerator;

/** JSON shapes returned by the REST API. */
public final class SearchModels {

    private SearchModels() {
    }

    public record SearchResponse(
            String query,
            String currency,
            List<Integer> years,
            LocalDate exchangeRatesDate,
            List<ProviderInfo> providers,
            List<DomainResult> results) {
    }

    public record DomainResult(
            String domain,
            String tld,
            SuggestionGenerator.Kind kind,
            AvailabilityService.Status availability,
            String availabilitySource,
            int minimumYears,
            /* a live registrar says this exact name is premium: price list quotes for it do not apply */
            boolean premium,
            List<Quote> quotes,
            /* years -> provider id with the lowest total for that horizon */
            Map<Integer, String> cheapest) {
    }

    /**
     * One registrar's price for one domain. Amounts are converted to the display currency when possible;
     * {@code currency} says which currency the amounts are in.
     */
    public record Quote(
            String providerId,
            String providerName,
            String currency,
            BigDecimal registration,
            BigDecimal renewal,
            Map<Integer, BigDecimal> totals,
            boolean teaser,
            /* the registry prices this exact name above the normal TLD price */
            boolean premium,
            /* true if the price came from a live per-domain check rather than the TLD price list */
            boolean live,
            /* WHOIS privacy at this registrar: free, paid, limited (redaction only), none, unknown */
            String privacy,
            /* yearly privacy cost added to registration and renewal (display currency), or null */
            BigDecimal privacyAdded,
            String promo,
            String taxNote,
            String originalCurrency,
            BigDecimal originalRegistration,
            BigDecimal originalRenewal,
            String buyUrl) {
    }

    /**
     * @param status  LOADING, READY, ERROR, or INACTIVE (not configured / disabled; {@code error} says why)
     * @param kind    "price list", "live quotes" or "price list + live quotes"
     */
    public record ProviderInfo(
            String id,
            String name,
            String status,
            String kind,
            int tldCount,
            Instant updatedAt,
            String error,
            String taxNote,
            String note) {
    }

    public record TldRow(String tld, int minimumYears, List<Quote> quotes, Map<Integer, String> cheapest) {
    }

    public record TldResponse(String currency, List<Integer> years, List<ProviderInfo> providers, List<TldRow> tlds) {
    }
}
