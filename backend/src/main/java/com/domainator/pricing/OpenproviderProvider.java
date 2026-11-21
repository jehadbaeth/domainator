package com.domainator.pricing;

import java.math.BigDecimal;
import java.net.IDN;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;

/**
 * Openprovider REST API (reseller platform, https://docs.openprovider.com). Login with username and password to
 * get a bearer token, then GET /v1beta/tlds?with_price=true paged by limit/offset. We read the reseller price,
 * which is what your membership level pays. Prices are net.
 */
public class OpenproviderProvider implements RegistrarProvider {

    private final RestClient http;
    private final String base;
    private final String username;
    private final String password;

    public OpenproviderProvider(RestClient http, ProviderSettings s) {
        this.http = http;
        this.base = s.flag("sandbox", false) ? "https://api.cte.openprovider.eu" : "https://api.openprovider.eu";
        this.username = s.get("username");
        this.password = s.get("password");
    }

    @Override
    public String id() {
        return "openprovider";
    }

    @Override
    public String displayName() {
        return "Openprovider";
    }

    @Override
    public String taxNote() {
        return "reseller price, excl. VAT";
    }

    @Override
    public String buyUrl(String domain) {
        return "https://www.openprovider.com/domains";
    }

    @Override
    public Map<String, TldPrice> fetchPrices() {
        JsonNode login = Json.parse(http.post().uri(base + "/v1beta/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("username", username, "password", password, "ip", "0.0.0.0"))
                .retrieve().body(String.class));
        String token = login.path("data").path("token").asString(null);
        if (token == null) {
            throw new IllegalStateException("Openprovider login failed: " + login);
        }
        Map<String, TldPrice> out = new HashMap<>();
        int offset = 0;
        for (int guard = 0; guard < 100; guard++) {
            JsonNode page = Json.parse(http.get().uri(base + "/v1beta/tlds?with_price=true&limit=100&offset={o}", offset)
                    .header("Authorization", "Bearer " + token)
                    .retrieve().body(String.class));
            JsonNode results = page.path("data").path("results");
            out.putAll(parse(results));
            offset += results.size();
            if (results.isEmpty() || offset >= page.path("data").path("total").asInt(0)) {
                break;
            }
        }
        return out;
    }

    static Map<String, TldPrice> parse(JsonNode results) {
        Map<String, TldPrice> out = new HashMap<>();
        for (JsonNode t : results) {
            if (t.path("min_period").asInt(1) > 1) {
                continue;
            }
            JsonNode create = t.path("prices").path("create_price").path("reseller");
            JsonNode renew = t.path("prices").path("renew_price").path("reseller");
            BigDecimal reg = Money.parse(create.get("price"));
            BigDecimal ren = Money.parse(renew.get("price"));
            String tld = t.path("name").asString("").toLowerCase(Locale.ROOT);
            if (reg != null && ren != null && !tld.isEmpty()) {
                String key = IDN.toASCII(tld);
                out.put(key, new TldPrice(key, reg, ren, create.path("currency").asString("EUR"), null));
            }
        }
        return out;
    }
}
