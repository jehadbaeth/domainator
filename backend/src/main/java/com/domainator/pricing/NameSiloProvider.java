package com.domainator.pricing;

import java.math.BigDecimal;
import java.net.IDN;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;

/**
 * NameSilo API operation getPrices (GET, needs a free API key from the API Manager page).
 * Response: reply.code 300 on success, then one object per TLD with registration, transfer and renew.
 * Prices are your account's prices (Discount Program applied) unless retail-prices is set. USD.
 */
public class NameSiloProvider implements RegistrarProvider {

    private final RestClient http;
    private final String apiKey;
    private final boolean retail;

    public NameSiloProvider(RestClient http, ProviderSettings s) {
        this.http = http;
        this.apiKey = s.get("api-key");
        this.retail = s.flag("retail-prices", false);
    }

    @Override
    public String id() {
        return "namesilo";
    }

    @Override
    public String displayName() {
        return "NameSilo";
    }

    @Override
    public String taxNote() {
        return "USD";
    }

    @Override
    public String buyUrl(String domain) {
        return "https://www.namesilo.com/domain/search-domains?query=" + domain;
    }

    @Override
    public Map<String, TldPrice> fetchPrices() {
        String uri = "https://www.namesilo.com/api/getPrices?version=1&type=json&key={key}" + (retail ? "&retail_prices=1" : "");
        JsonNode body = Json.parse(http.get().uri(uri, apiKey).retrieve().body(String.class));
        return parse(body);
    }

    static Map<String, TldPrice> parse(JsonNode body) {
        JsonNode reply = body == null ? null : body.path("reply");
        if (reply == null || reply.path("code").asInt(0) != 300) {
            throw new IllegalStateException("NameSilo getPrices failed: "
                    + (reply == null ? "empty" : reply.path("code").asString("") + " " + reply.path("detail").asString("")));
        }
        Map<String, TldPrice> out = new HashMap<>();
        for (Map.Entry<String, JsonNode> e : reply.properties()) {
            JsonNode v = e.getValue();
            if (!v.isObject()) {
                continue; // "code", "detail"
            }
            BigDecimal reg = Money.parse(v.get("registration"));
            BigDecimal ren = Money.parse(v.get("renew"));
            if (reg != null && ren != null) {
                String tld = IDN.toASCII(e.getKey().toLowerCase(Locale.ROOT));
                out.put(tld, new TldPrice(tld, reg, ren, "USD", null));
            }
        }
        return out;
    }
}
