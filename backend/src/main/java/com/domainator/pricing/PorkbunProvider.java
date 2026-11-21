package com.domainator.pricing;

import java.math.BigDecimal;
import java.net.IDN;
import java.util.HashMap;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * Porkbun publishes its complete price list without authentication:
 * POST https://api.porkbun.com/api/json/v3/pricing/get
 */
public class PorkbunProvider implements RegistrarProvider {

    static final String PRICING_URL = "https://api.porkbun.com/api/json/v3/pricing/get";

    private final RestClient http;

    public PorkbunProvider(RestClient http) {
        this.http = http;
    }

    record Response(String status, Map<String, Entry> pricing) {
    }

    record Entry(String registration, String renewal, String transfer) {
    }

    @Override
    public String id() {
        return "porkbun";
    }

    @Override
    public String displayName() {
        return "Porkbun";
    }

    @Override
    public String taxNote() {
        return "list price (USD)";
    }

    @Override
    public String buyUrl(String domain) {
        return "https://porkbun.com/checkout/search?q=" + domain;
    }

    @Override
    public Map<String, TldPrice> fetchPrices() {
        Response response = http.post()
                .uri(PRICING_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{}")
                .retrieve()
                .body(Response.class);
        return parse(response);
    }

    static Map<String, TldPrice> parse(Response response) {
        if (response == null || !"SUCCESS".equals(response.status()) || response.pricing() == null) {
            throw new IllegalStateException("Porkbun pricing request failed: " + (response == null ? "empty" : response.status()));
        }
        Map<String, TldPrice> out = new HashMap<>();
        response.pricing().forEach((tld, e) -> {
            BigDecimal reg = money(e.registration());
            BigDecimal ren = money(e.renewal());
            if (reg != null && ren != null) {
                String key = IDN.toASCII(tld.toLowerCase());
                out.put(key, new TldPrice(key, reg, ren, "USD", null));
            }
        });
        return out;
    }

    /** Porkbun formats large prices with a thousands separator, e.g. "2,060.25". */
    static BigDecimal money(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.replace(",", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
