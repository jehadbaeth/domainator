package com.domainator.pricing;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;

/**
 * Gandi API v5 (https://api.gandi.net/docs), Personal Access Token as bearer.
 * Live quotes only: GET /v5/domain/check?name=...&amp;processes=create&amp;processes=renew, one domain per call
 * (limit 1000 requests per minute). Prices are taken before taxes; the currency is the account's.
 * The renew process in this endpoint is not shown in Gandi's examples, so a domain without a renew price is skipped.
 */
public class GandiProvider implements RegistrarProvider {

    private final RestClient http;
    private final String token;
    private final String base;

    public GandiProvider(RestClient http, ProviderSettings s) {
        this.http = http;
        this.token = s.get("token");
        this.base = s.flag("sandbox", false) ? "https://api.sandbox.gandi.net" : "https://api.gandi.net";
    }

    @Override
    public String id() {
        return "gandi";
    }

    @Override
    public String displayName() {
        return "Gandi";
    }

    @Override
    public String taxNote() {
        return "excl. VAT";
    }

    @Override
    public String buyUrl(String domain) {
        return "https://shop.gandi.net/domain/suggest?search=" + domain;
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
        return 100;
    }

    @Override
    public Map<String, DomainQuote> quoteDomains(List<String> domains) throws Exception {
        Map<String, DomainQuote> out = new ConcurrentHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<?>> futures = domains.stream().<Future<?>>map(d -> pool.submit(() -> {
                JsonNode body = Json.parse(http.get()
                        .uri(base + "/v5/domain/check?name={d}&processes=create&processes=renew", d)
                        .header("Authorization", "Bearer " + token)
                        .retrieve().body(String.class));
                DomainQuote q = parse(body);
                if (q != null) {
                    out.put(d, q);
                }
            })).toList();
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        return out;
    }

    static DomainQuote parse(JsonNode body) {
        String currency = body.path("currency").asString("EUR");
        BigDecimal reg = null;
        BigDecimal ren = null;
        boolean premium = false;
        String status = "";
        for (JsonNode product : body.path("products")) {
            String process = product.path("process").asString("");
            if ("create".equals(process)) {
                status = product.path("status").asString("");
            }
            for (JsonNode price : product.path("prices")) {
                if (!"y".equals(price.path("duration_unit").asString("y")) || price.path("min_duration").asInt(1) > 1) {
                    continue;
                }
                premium |= "premium".equals(price.path("type").asString(""));
                BigDecimal value = Money.parse(price.get("price_before_taxes"));
                if ("create".equals(process) && reg == null) {
                    reg = value;
                } else if ("renew".equals(process) && ren == null) {
                    ren = value;
                }
            }
        }
        if (reg == null || ren == null) {
            return null;
        }
        premium |= status.toLowerCase(Locale.ROOT).contains("premium");
        return new DomainQuote(reg, ren, currency, status.isEmpty() ? null : status.startsWith("available"), premium);
    }
}
