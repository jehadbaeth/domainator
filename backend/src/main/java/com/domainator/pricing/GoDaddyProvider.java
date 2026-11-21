package com.domainator.pricing;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;

/**
 * GoDaddy Domains API v3 (https://developer.godaddy.com), Personal Access Token as bearer.
 * Live quotes only: POST /v3/domains/check-availability with max 25 domains. GoDaddy has no price list endpoint.
 * <p>
 * Amounts are integers in minor units (1199 = 11.99 USD). renewalPrice is left out when it equals the
 * registration price. Prices exclude tax and the ICANN fee. Rate limit about 600 requests per 23 minutes.
 * Some TLDs (.us, .ca, .eu) are not supported by v3.
 */
public class GoDaddyProvider implements RegistrarProvider {

    private static final Set<String> PREMIUM = Set.of("REGISTRY_PREMIUM", "PREMIUM");

    private final RestClient http;
    private final String token;

    public GoDaddyProvider(RestClient http, ProviderSettings s) {
        this.http = http;
        this.token = s.get("token");
    }

    @Override
    public String id() {
        return "godaddy";
    }

    @Override
    public String displayName() {
        return "GoDaddy";
    }

    @Override
    public String taxNote() {
        return "excl. tax and ICANN fee";
    }

    @Override
    public String buyUrl(String domain) {
        return "https://www.godaddy.com/domainsearch/find?domainToCheck=" + domain;
    }

    @Override
    public Map<String, TldPrice> fetchPrices() {
        return Map.of();
    }

    @Override
    public boolean quotesDomains() {
        return true;
    }

    @Override
    public int maxDomainsPerQuote() {
        return 25;
    }

    @Override
    public Map<String, DomainQuote> quoteDomains(List<String> domains) {
        JsonNode body = Json.parse(http.post().uri("https://api.godaddy.com/v3/domains/check-availability")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("domains", domains))
                .retrieve().body(String.class));
        return parse(body);
    }

    static Map<String, DomainQuote> parse(JsonNode body) {
        Map<String, DomainQuote> out = new HashMap<>();
        for (JsonNode item : body.path("items")) {
            for (JsonNode p : item.path("prices")) {
                if (p.path("period").asInt(0) != 1 || !"YEAR".equals(p.path("term").asString(""))) {
                    continue;
                }
                BigDecimal reg = minor(p.path("price"));
                BigDecimal ren = p.has("renewalPrice") ? minor(p.path("renewalPrice")) : reg;
                if (reg != null && ren != null) {
                    out.put(item.path("domain").asString().toLowerCase(Locale.ROOT), new DomainQuote(reg, ren,
                            p.path("price").path("currencyCode").asString("USD"), item.path("available").asBoolean(),
                            PREMIUM.contains(item.path("inventory").asString(""))));
                }
            }
        }
        return out;
    }

    private static BigDecimal minor(JsonNode price) {
        BigDecimal value = Money.parse(price.get("value"));
        if (value == null) {
            return null;
        }
        int digits;
        try {
            digits = Currency.getInstance(price.path("currencyCode").asString("USD")).getDefaultFractionDigits();
        } catch (IllegalArgumentException e) {
            digits = 2;
        }
        return value.movePointLeft(Math.max(0, digits));
    }
}
