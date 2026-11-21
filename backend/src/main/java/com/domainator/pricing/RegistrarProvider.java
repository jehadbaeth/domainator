package com.domainator.pricing;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * One registrar. There are two ways a registrar can deliver prices, and a provider may do both:
 * <ul>
 *   <li>a full price list per TLD ({@link #fetchPrices()}), refreshed in the background, and/or</li>
 *   <li>a live quote per domain ({@link #quoteDomains(List)}), which is the only way to see premium prices.</li>
 * </ul>
 * To add a registrar, implement this interface and register it in {@link ProviderRegistry}.
 */
public interface RegistrarProvider {

    /** Stable id used in the API and in config, e.g. "porkbun". */
    String id();

    String displayName();

    /** Shown next to prices so it is clear whether VAT is included. */
    String taxNote();

    /** Link to the registrar's search for this exact domain, or null if the site has no such URL. */
    String buyUrl(String domain);

    /** Link to the registrar's offer / price page for a TLD; used when there is no per-domain link. */
    default String tldUrl(String tld) {
        return null;
    }

    /**
     * A refresh that yields fewer TLDs than this is treated as broken (ERROR) rather than silently serving a
     * half empty list. Used for providers that read web pages, which break when a site is redesigned.
     */
    default int minExpectedTlds() {
        return 0;
    }

    /**
     * Fetches the full price list, keyed by lower case ASCII (punycode) TLD without the leading dot.
     * Called on a background thread, may be slow. Per-domain-only providers return an empty map.
     */
    Map<String, TldPrice> fetchPrices() throws Exception;

    /** True if {@link #quoteDomains(List)} does something. */
    default boolean quotesDomains() {
        return false;
    }

    /** Max domains per {@link #quoteDomains(List)} call; the caller splits larger lists. */
    default int maxDomainsPerQuote() {
        return 50;
    }

    /**
     * Live prices for specific domains (lower case ASCII). Domains the registrar does not sell can be left out.
     * Called during a search, so keep it fast.
     */
    default Map<String, DomainQuote> quoteDomains(List<String> domains) throws Exception {
        return Map.of();
    }

    /**
     * @param available null if the registrar did not say
     * @param premium   true if the registry prices this name above the normal TLD price
     */
    record DomainQuote(BigDecimal registration, BigDecimal renewal, String currency, Boolean available, boolean premium) {
    }
}
