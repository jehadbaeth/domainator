package com.domainator.pricing;

import java.math.BigDecimal;
import java.net.IDN;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;

/**
 * Dynadot API3 command tld_price (key from Tools, API in the control panel, available to all accounts).
 * Prices are per year ("(Price/1 year)") at your account's price level. Dynadot marks API3 as legacy, scheduled
 * for retirement in favour of their RESTful API, so this may need porting later.
 */
public class DynadotProvider implements RegistrarProvider {

    private final RestClient http;
    private final String apiKey;
    private final String currency;
    private final String host;

    public DynadotProvider(RestClient http, ProviderSettings s) {
        this.http = http;
        this.apiKey = s.get("api-key");
        this.currency = s.get("currency", "USD").toLowerCase(Locale.ROOT);
        this.host = s.flag("sandbox", false) ? "https://api-sandbox.dynadot.com" : "https://api.dynadot.com";
    }

    @Override
    public String id() {
        return "dynadot";
    }

    @Override
    public String displayName() {
        return "Dynadot";
    }

    @Override
    public String taxNote() {
        return "VAT not stated";
    }

    @Override
    public String buyUrl(String domain) {
        return "https://www.dynadot.com/domain/search?domain=" + domain;
    }

    @Override
    public Map<String, TldPrice> fetchPrices() {
        JsonNode body = Json.parse(http.get()
                .uri(host + "/api3.json?key={key}&command=tld_price&currency={cur}", apiKey, currency)
                .retrieve().body(String.class));
        return parse(body);
    }

    static Map<String, TldPrice> parse(JsonNode body) {
        JsonNode r = body == null ? null : body.path("TldPriceResponse");
        if (r == null || r.isMissingNode() || !"success".equalsIgnoreCase(r.path("Status").asString(""))) {
            throw new IllegalStateException("Dynadot tld_price failed: " + (body == null ? "empty" : body.toString()));
        }
        String cur = r.path("Currency").asString("USD").toUpperCase(Locale.ROOT);
        Map<String, TldPrice> out = new HashMap<>();
        for (JsonNode t : r.path("TldPrice")) {
            String unit = t.path("Price").path("Unit").asString("");
            if (!unit.isEmpty() && !unit.contains("1 year")) {
                continue;
            }
            BigDecimal reg = Money.parse(t.path("Price").get("Register"));
            BigDecimal ren = Money.parse(t.path("Price").get("Renew"));
            String tld = t.path("Tld").asString("").toLowerCase(Locale.ROOT).replaceFirst("^\\.", "");
            if (reg != null && ren != null && !tld.isEmpty()) {
                String key = IDN.toASCII(tld);
                out.put(key, new TldPrice(key, reg, ren, cur, null));
            }
        }
        return out;
    }
}
