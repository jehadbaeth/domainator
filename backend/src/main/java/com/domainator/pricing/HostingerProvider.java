package com.domainator.pricing;

import java.math.BigDecimal;
import java.net.IDN;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;

/**
 * Hostinger API billing catalog: GET https://developers.hostinger.com/api/billing/v1/catalog?category=DOMAIN,
 * bearer token from hPanel (Profile, API). Amounts are integer cents.
 * <p>
 * EXPERIMENTAL: Hostinger documents "first_period_price" and "price" but not explicitly that "price" is the
 * renewal price. We read first_period_price as the first year and price as the renewal, for 1 year items only.
 */
public class HostingerProvider implements RegistrarProvider {

    private static final Pattern PRICE_ID = Pattern.compile("^hostingercom-domain-([a-z0-9-]+)-[a-z]{3}-1y$");

    private final RestClient http;
    private final String token;

    public HostingerProvider(RestClient http, ProviderSettings s) {
        this.http = http;
        this.token = s.get("token");
    }

    @Override
    public String id() {
        return "hostinger";
    }

    @Override
    public String displayName() {
        return "Hostinger";
    }

    @Override
    public String taxNote() {
        return "experimental mapping";
    }

    @Override
    public String buyUrl(String domain) {
        return "https://www.hostinger.com/domain-name-search?domain=" + domain;
    }

    @Override
    public Map<String, TldPrice> fetchPrices() {
        JsonNode body = Json.parse(http.get().uri("https://developers.hostinger.com/api/billing/v1/catalog?category=DOMAIN")
                .header("Authorization", "Bearer " + token)
                .retrieve().body(String.class));
        return parse(body);
    }

    static Map<String, TldPrice> parse(JsonNode body) {
        Map<String, TldPrice> out = new HashMap<>();
        for (JsonNode item : body) {
            String name = item.path("name").asString("").trim().toLowerCase(Locale.ROOT);
            for (JsonNode p : item.path("prices")) {
                if (p.path("period").asInt(0) != 1 || !"year".equals(p.path("period_unit").asString(""))) {
                    continue;
                }
                String tld = tldOf(name, p.path("id").asString(""));
                BigDecimal first = Money.parse(p.get("first_period_price"));
                BigDecimal renew = Money.parse(p.get("price"));
                if (tld != null && renew != null) {
                    BigDecimal reg = first == null ? renew : first;
                    out.put(tld, new TldPrice(tld, reg.movePointLeft(2), renew.movePointLeft(2),
                            p.path("currency").asString("USD").toUpperCase(Locale.ROOT), null));
                }
            }
        }
        return out;
    }

    private static String tldOf(String itemName, String priceId) {
        if (itemName.matches("^\\.?[a-z0-9.-]+$")) {
            return IDN.toASCII(itemName.replaceFirst("^\\.", ""));
        }
        Matcher m = PRICE_ID.matcher(priceId);
        return m.matches() ? m.group(1) : null;
    }
}
