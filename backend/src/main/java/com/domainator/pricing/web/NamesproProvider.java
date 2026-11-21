package com.domainator.pricing.web;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.web.client.RestClient;

import com.domainator.pricing.TldPrice;

/**
 * Namespro (Canada), https://www.namespro.ca/Pricing.asp?sec=1. One price per TLD that applies to registration
 * and renewal, in CAD, excluding GST.
 */
public class NamesproProvider extends WebProvider {

    static final String URL = "https://www.namespro.ca/Pricing.asp?sec=1";
    private static final Pattern CAD = Pattern.compile("C\\$\\s*([0-9.,]+)\\s*/\\s*year");

    public NamesproProvider(RestClient http) {
        super(http, "namespro", "Namespro", "CAD, excl. GST", 10);
    }

    @Override
    public Map<String, TldPrice> fetchPrices() {
        return parse(page(URL));
    }

    @Override
    Map<String, TldPrice> parse(Document doc) {
        Map<String, TldPrice> out = new HashMap<>();
        for (Element row : doc.select("tr")) {
            String tldText = null;
            for (Element td : row.select("> td")) {
                if (td.text().matches("\\.[a-z0-9.-]+")) {
                    tldText = td.text();
                    break;
                }
            }
            Matcher m = CAD.matcher(row.text());
            if (tldText == null || !m.find()) {
                continue;
            }
            String tld = PricePage.tld(tldText);
            BigDecimal price = PricePage.number(m.group(1));
            if (tld != null && price != null) {
                out.put(tld, new TldPrice(tld, price, price, "CAD", null));
            }
        }
        return out;
    }

    @Override
    public String tldUrl(String tld) {
        return URL;
    }
}
