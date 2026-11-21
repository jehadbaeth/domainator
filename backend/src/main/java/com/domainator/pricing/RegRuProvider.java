package com.domainator.pricing;

import java.math.BigDecimal;
import java.net.IDN;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;

/**
 * REG.RU (Russia) API method domain/get_prices, documented as accessible to everyone without login.
 * Prices are per year. EUR and USD are converted by REG.RU from RUB and rounded to whole units.
 * Disabled by default: paying a Russian registrar from the EU may not be possible.
 */
public class RegRuProvider implements RegistrarProvider {

    private final RestClient http;
    private final String currency;

    public RegRuProvider(RestClient http, ProviderSettings s) {
        this.http = http;
        this.currency = s.get("currency", "EUR").toUpperCase(Locale.ROOT);
    }

    @Override
    public String id() {
        return "regru";
    }

    @Override
    public String displayName() {
        return "REG.RU";
    }

    @Override
    public String taxNote() {
        return "retail price, VAT not stated";
    }

    @Override
    public String buyUrl(String domain) {
        return "https://www.reg.com/buy/domains/?query=" + domain;
    }

    @Override
    public Map<String, TldPrice> fetchPrices() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("show_renew_data", "1");
        form.add("currency", currency);
        JsonNode body = Json.parse(http.post().uri("https://api.reg.ru/api/regru2/domain/get_prices")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(String.class));
        return parse(body);
    }

    static Map<String, TldPrice> parse(JsonNode body) {
        if (body == null || !"success".equals(body.path("result").asString(""))) {
            throw new IllegalStateException("REG.RU request failed: " + (body == null ? "empty" : body.path("error_text").asString("")));
        }
        JsonNode answer = body.path("answer");
        String cur = answer.path("currency").asString("RUB");
        if ("RUR".equals(cur)) {
            cur = "RUB";
        }
        Map<String, TldPrice> out = new HashMap<>();
        for (Map.Entry<String, JsonNode> e : answer.path("prices").properties()) {
            BigDecimal reg = Money.parse(e.getValue().get("reg_price"));
            BigDecimal ren = Money.parse(e.getValue().get("renew_price"));
            if (reg != null && ren != null && !e.getKey().startsWith("__")) {
                String tld = IDN.toASCII(e.getKey().toLowerCase(Locale.ROOT));
                out.put(tld, new TldPrice(tld, reg, ren, cur, null));
            }
        }
        return out;
    }
}
