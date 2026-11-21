package com.domainator.pricing.web;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.web.client.RestClient;

import com.domainator.pricing.TldPrice;

/**
 * Loopia (Sweden), https://www.loopia.com/domainnames/detailed_prices/. SEK, each cell has the price without
 * and with VAT; we read the one without. Columns: first year, renewal, reactivation, transfer, holder change.
 */
public class LoopiaProvider extends WebProvider {

    static final String URL = "https://www.loopia.com/domainnames/detailed_prices/";

    public LoopiaProvider(RestClient http) {
        super(http, "loopia", "Loopia", "SEK, excl. VAT", 15);
    }

    @Override
    public Map<String, TldPrice> fetchPrices() {
        return parse(page(URL));
    }

    @Override
    Map<String, TldPrice> parse(Document doc) {
        Map<String, TldPrice> out = new HashMap<>();
        for (Element row : doc.select("tr")) {
            var cells = row.select("> td");
            if (cells.size() < 3 || cells.get(1).selectFirst("span.without_tax") == null) {
                continue;
            }
            String tld = PricePage.tld(cells.get(0).ownText().isBlank() ? cells.get(0).text() : cells.get(0).ownText());
            BigDecimal first = PricePage.number(cells.get(1).selectFirst("span.without_tax").text());
            Element renCell = cells.get(2).selectFirst("span.without_tax");
            BigDecimal ren = renCell == null ? null : PricePage.number(renCell.text());
            if (tld != null && first != null && ren != null) {
                out.put(tld, new TldPrice(tld, first, ren, "SEK", null));
            }
        }
        return out;
    }

    @Override
    public String tldUrl(String tld) {
        return URL;
    }
}
