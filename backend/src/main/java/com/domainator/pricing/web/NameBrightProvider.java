package com.domainator.pricing.web;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.web.client.RestClient;

import com.domainator.pricing.Money;
import com.domainator.pricing.TldPrice;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * NameBright, https://www.namebright.com/pricing. Prices are in the page's embedded Angular state
 * (script#ng-state). "price" is the normal price; "fabalPrice" (prepaid account balance) is ignored.
 */
public class NameBrightProvider extends WebProvider {

    static final String URL = "https://www.namebright.com/pricing";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public NameBrightProvider(RestClient http) {
        super(http, "namebright", "NameBright", "USD, price page", 100);
    }

    @Override
    public Map<String, TldPrice> fetchPrices() {
        return parse(page(URL));
    }

    @Override
    Map<String, TldPrice> parse(Document doc) {
        Element script = doc.selectFirst("script#ng-state");
        if (script == null) {
            throw new IllegalStateException("NameBright page has no ng-state data");
        }
        JsonNode state = JSON.readTree(script.data());
        JsonNode result = null;
        for (JsonNode v : state.values()) {
            JsonNode r = v.path("b").path("result");
            if (r.has("registration") && r.has("renewal")) {
                result = r;
                break;
            }
        }
        if (result == null) {
            throw new IllegalStateException("NameBright price data not found in ng-state");
        }
        Map<String, BigDecimal> renew = new HashMap<>();
        for (JsonNode n : result.path("renewal")) {
            String tld = PricePage.tld(n.path("name").asString(""));
            BigDecimal p = Money.parse(n.get("price"));
            if (tld != null && p != null) {
                renew.put(tld, p);
            }
        }
        Map<String, TldPrice> out = new HashMap<>();
        for (JsonNode n : result.path("registration")) {
            String tld = PricePage.tld(n.path("name").asString(""));
            BigDecimal reg = Money.parse(n.get("price"));
            if (tld != null && reg != null && renew.containsKey(tld)) {
                out.put(tld, new TldPrice(tld, reg, renew.get(tld), "USD", null));
            }
        }
        return out;
    }

    @Override
    public String tldUrl(String tld) {
        return URL;
    }
}
