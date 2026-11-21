package com.domainator.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;

/**
 * Vercel Registrar API: GET /v1/registrar/tlds/supported and /v1/registrar/tlds/{tld}/price.
 * There is no bulk endpoint and anonymous callers get 60 requests per minute, so only the configured TLDs are
 * fetched, slowly. POST /v1/registrar/domains/search prices up to 200 domains per call during a search.
 * <p>
 * The docs show a bearer token. It currently also answers without one; set VERCEL_TOKEN to be safe.
 * The response has no currency field; it is USD. When "years" is above 1 (TLDs with a minimum term such as
 * .ai) the prices are for the whole period, so they are divided by the number of years.
 */
public class VercelProvider implements RegistrarProvider {

    private static final Logger log = LoggerFactory.getLogger(VercelProvider.class);
    private static final String BASE = "https://api.vercel.com/v1/registrar/tlds/";

    private final RestClient http;
    private final String token;
    private static final long PACE_MS = 1500;

    private final boolean allTlds;
    private final List<String> priorityTlds;

    /**
     * @param priorityTlds TLDs whose list price is fetched (the suggestion TLDs). Live searches price every
     *                     domain anyway, so the full list (all-tlds: true, about 10 minutes) is optional.
     */
    public VercelProvider(RestClient http, ProviderSettings s, List<String> priorityTlds) {
        this.http = http;
        this.token = s.get("token");
        this.allTlds = s.flag("all-tlds", false);
        this.priorityTlds = priorityTlds;
    }

    @Override
    public String id() {
        return "vercel";
    }

    @Override
    public String displayName() {
        return "Vercel";
    }

    @Override
    public String taxNote() {
        return "USD, VAT not stated";
    }

    @Override
    public String buyUrl(String domain) {
        return "https://vercel.com/domains?q=" + domain;
    }

    @Override
    public String tldUrl(String tld) {
        return "https://vercel.com/domains";
    }

    @Override
    public Map<String, TldPrice> fetchPrices() throws Exception {
        JsonNode supported = get(BASE + "supported");
        Set<String> available = new LinkedHashSet<>();
        supported.forEach(n -> available.add(n.asString().toLowerCase()));
        List<String> tlds = allTlds ? new ArrayList<>(available)
                : priorityTlds.stream().filter(available::contains).toList();
        Map<String, TldPrice> out = new HashMap<>();
        // anonymous limit is 60 requests per minute, shared with live searches, so go slow and sequential
        for (String tld : tlds) {
            TldPrice p = fetchOne(tld);
            if (p != null) {
                out.put(tld, p);
            }
            Thread.sleep(PACE_MS);
        }
        return out;
    }

    private TldPrice fetchOne(String tld) throws InterruptedException {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return parsePrice(tld, get(BASE + tld + "/price"));
            } catch (HttpClientErrorException.TooManyRequests e) {
                log.info("Vercel rate limit reached, waiting a minute");
                Thread.sleep(61_000);
            } catch (Exception e) {
                log.debug("Vercel price for .{} failed: {}", tld, e.toString());
                return null;
            }
        }
        return null;
    }

    static TldPrice parsePrice(String tld, JsonNode n) {
        BigDecimal purchase = Money.parse(n.get("purchasePrice"));
        BigDecimal renewal = Money.parse(n.get("renewalPrice"));
        int years = Math.max(1, n.path("years").asInt(1));
        if (purchase == null || renewal == null) {
            return null;
        }
        BigDecimal y = BigDecimal.valueOf(years);
        return new TldPrice(tld, purchase.divide(y, 2, RoundingMode.HALF_UP), renewal.divide(y, 2, RoundingMode.HALF_UP), "USD", null);
    }

    @Override
    public boolean quotesDomains() {
        return true;
    }

    @Override
    public int maxDomainsPerQuote() {
        return 200;
    }

    /** POST /v1/registrar/domains/search: availability, price, renewal and premium flag per domain (USD). */
    @Override
    public Map<String, DomainQuote> quoteDomains(List<String> domains) {
        RestClient.RequestBodySpec req = http.post().uri("https://api.vercel.com/v1/registrar/domains/search")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON);
        if (token != null) {
            req = req.header("Authorization", "Bearer " + token);
        }
        return parseSearch(Json.parse(req.body(Map.of("domains", domains)).retrieve().body(String.class)));
    }

    static Map<String, DomainQuote> parseSearch(JsonNode body) {
        Map<String, DomainQuote> out = new java.util.HashMap<>();
        for (JsonNode r : body.path("results")) {
            BigDecimal price = Money.parse(r.get("price"));
            BigDecimal renewal = Money.parse(r.get("renewalPrice"));
            if (price == null || renewal == null) {
                continue; // not available: Vercel sends no price
            }
            BigDecimal y = BigDecimal.valueOf(Math.max(1, r.path("years").asInt(1)));
            out.put(r.path("domain").asString().toLowerCase(), new DomainQuote(
                    price.divide(y, 2, RoundingMode.HALF_UP), renewal.divide(y, 2, RoundingMode.HALF_UP), "USD",
                    r.path("available").asBoolean(false), r.path("premium").asBoolean(false)));
        }
        return out;
    }

    private JsonNode get(String url) {
        RestClient.RequestHeadersSpec<?> req = http.get().uri(url);
        if (token != null) {
            req = req.header("Authorization", "Bearer " + token);
        }
        return Json.parse(req.retrieve().body(String.class));
    }
}
