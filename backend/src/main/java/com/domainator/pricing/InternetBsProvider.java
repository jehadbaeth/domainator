package com.domainator.pricing;

import java.math.BigDecimal;
import java.net.IDN;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;

/**
 * Internet.bs (CentralNic group) API: Account/PriceList/Get with version=5 returns one row per TLD with
 * registration, renewal and currency. Needs ApiKey and Password; prices depend on the account's price level.
 * Set INTERNETBS_TEST=true to use the public test environment (testapi.internet.bs, testapi/testpass) whose
 * prices are NOT retail. Rows with a minimum period above 1 year are skipped.
 */
public class InternetBsProvider implements RegistrarProvider {

    private final RestClient http;
    private final String base;
    private final String apiKey;
    private final String password;
    private final String currency;
    private final boolean test;

    public InternetBsProvider(RestClient http, ProviderSettings s) {
        this.http = http;
        this.test = s.flag("test", false);
        this.base = test ? "https://testapi.internet.bs" : "https://api.internet.bs";
        this.apiKey = test ? "testapi" : s.get("api-key");
        this.password = test ? "testpass" : s.get("password");
        this.currency = s.get("currency", "USD").toUpperCase(Locale.ROOT);
    }

    @Override
    public String id() {
        return "internetbs";
    }

    @Override
    public String displayName() {
        return test ? "Internet.bs (TEST prices)" : "Internet.bs";
    }

    @Override
    public String taxNote() {
        return "account price level";
    }

    @Override
    public String buyUrl(String domain) {
        return "https://internetbs.net/en/domain-name-registrations/search?domain=" + domain;
    }

    @Override
    public Map<String, TldPrice> fetchPrices() {
        JsonNode body = Json.parse(http.get()
                .uri(base + "/Account/PriceList/Get?ApiKey={k}&Password={p}&ResponseFormat=JSON&version=5&Currency={c}",
                        apiKey, password, currency)
                .retrieve().body(String.class));
        return parse(body);
    }

    static Map<String, TldPrice> parse(JsonNode body) {
        if (body == null || !"SUCCESS".equalsIgnoreCase(body.path("status").asString(""))) {
            throw new IllegalStateException("Internet.bs price list failed: " + (body == null ? "empty" : body.path("message").asString(body.toString())));
        }
        Map<String, TldPrice> out = new HashMap<>();
        for (JsonNode row : body.path("product")) {
            if (!"1".equals(row.path("minperiod").asString("1"))) {
                continue;
            }
            BigDecimal reg = Money.parse(row.get("registration"));
            BigDecimal ren = Money.parse(row.get("renewal"));
            String tld = row.path("tld").asString("").toLowerCase(Locale.ROOT).replaceFirst("^\\.", "");
            if (reg != null && ren != null && !tld.isEmpty()) {
                String key = IDN.toASCII(tld);
                out.put(key, new TldPrice(key, reg, ren, row.path("currency").asString("USD"), null));
            }
        }
        return out;
    }
}
