package com.domainator.pricing;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;

/**
 * Cloudflare Registrar API (beta): POST /client/v4/accounts/{account_id}/registrar/domain-check, max 20 domains,
 * bearer token. Live quotes only, at-cost prices in USD. Only a subset of TLDs can be registered through the API,
 * and premium names come back as not registrable without a price, so they simply do not appear.
 */
public class CloudflareProvider implements RegistrarProvider {

    private final RestClient http;
    private final String token;
    private final String accountId;

    public CloudflareProvider(RestClient http, ProviderSettings s) {
        this.http = http;
        this.token = s.get("api-token");
        this.accountId = s.get("account-id");
    }

    @Override
    public String id() {
        return "cloudflare";
    }

    @Override
    public String displayName() {
        return "Cloudflare";
    }

    @Override
    public String taxNote() {
        return "USD, at cost";
    }

    @Override
    public String buyUrl(String domain) {
        return "https://dash.cloudflare.com/?to=/:account/domains/register/" + domain;
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
        return 20;
    }

    @Override
    public Map<String, DomainQuote> quoteDomains(List<String> domains) {
        JsonNode body = Json.parse(http.post()
                .uri("https://api.cloudflare.com/client/v4/accounts/{a}/registrar/domain-check", accountId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("domains", domains))
                .retrieve().body(String.class));
        return parse(body);
    }

    static Map<String, DomainQuote> parse(JsonNode body) {
        if (!body.path("success").asBoolean(false)) {
            throw new IllegalStateException("Cloudflare domain-check failed: " + body.path("errors"));
        }
        Map<String, DomainQuote> out = new HashMap<>();
        for (JsonNode d : body.path("result").path("domains")) {
            JsonNode pricing = d.path("pricing");
            BigDecimal reg = Money.parse(pricing.get("registration_cost"));
            BigDecimal ren = Money.parse(pricing.get("renewal_cost"));
            if (reg != null && ren != null) {
                out.put(d.path("name").asString().toLowerCase(Locale.ROOT), new DomainQuote(reg, ren,
                        pricing.path("currency").asString("USD"), d.path("registrable").asBoolean(false),
                        !"standard".equalsIgnoreCase(d.path("tier").asString("standard"))));
            }
        }
        return out;
    }
}
