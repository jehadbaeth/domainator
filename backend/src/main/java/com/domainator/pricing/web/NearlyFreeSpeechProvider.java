package com.domainator.pricing.web;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.web.client.RestClient;

import com.domainator.pricing.TldPrice;

/** NearlyFreeSpeech.NET, https://www.nearlyfreespeech.net/services/domains?full (USD, about 65 TLDs). */
public class NearlyFreeSpeechProvider extends WebProvider {

    static final String URL = "https://www.nearlyfreespeech.net/services/domains?full";

    public NearlyFreeSpeechProvider(RestClient http) {
        super(http, "nfs", "NearlyFreeSpeech", "USD, price page", 30);
    }

    @Override
    public Map<String, TldPrice> fetchPrices() {
        return parse(page(URL));
    }

    @Override
    Map<String, TldPrice> parse(Document doc) {
        Map<String, TldPrice> out = new HashMap<>();
        for (Element row : doc.select("table.domains tr")) {
            var cells = row.select("td");
            if (cells.size() < 3) {
                continue;
            }
            String tld = PricePage.tld(cells.get(0).text());
            // a promotion looks like "$3.00 <strike>$18.89</strike>": the first price is what you pay now
            BigDecimal reg = PricePage.number(cells.get(1).ownText().isBlank() ? cells.get(1).text() : cells.get(1).ownText());
            BigDecimal ren = PricePage.number(cells.get(2).text());
            if (tld != null && reg != null && ren != null) {
                String promo = cells.get(1).selectFirst("strike") != null ? "first year promotion (regular " + cells.get(1).selectFirst("strike").text() + ")" : null;
                out.put(tld, new TldPrice(tld, reg, ren, "USD", promo));
            }
        }
        return out;
    }

    @Override
    public String tldUrl(String tld) {
        return "https://www.nearlyfreespeech.net/services/domains";
    }
}
