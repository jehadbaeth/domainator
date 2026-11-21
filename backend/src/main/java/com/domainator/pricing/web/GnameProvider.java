package com.domainator.pricing.web;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.web.client.RestClient;

import com.domainator.pricing.TldPrice;

/**
 * Gname (Singapore). One page per TLD: https://www.gname.com/price/{tld}.html, list "ul.hasyjg" with
 * Registration / Renewal / Transfer items; a promotion adds the struck regular price. USD.
 */
public class GnameProvider extends WebProvider {

    private final List<String> tlds;

    public GnameProvider(RestClient http, List<String> tlds) {
        super(http, "gname", "Gname", "USD, VAT not stated", 3);
        this.tlds = tlds;
    }

    @Override
    public Map<String, TldPrice> fetchPrices() throws InterruptedException {
        Map<String, TldPrice> out = new HashMap<>();
        PricePage.perTld(tlds, out, tld -> parseTld(tld, page(tldUrl(tld))));
        return out;
    }

    @Override
    Map<String, TldPrice> parse(Document doc) {
        throw new UnsupportedOperationException("per TLD pages");
    }

    static TldPrice parseTld(String tld, Document doc) {
        BigDecimal reg = null;
        BigDecimal ren = null;
        boolean promo = false;
        for (Element li : doc.select("ul.hasyjg > li")) {
            Element label = li.selectFirst("> p");
            Element price = li.selectFirst("> span");
            if (label == null || price == null) {
                continue;
            }
            if ("Registration".equalsIgnoreCase(label.text().trim())) {
                reg = PricePage.number(price.text());
                promo = li.selectFirst(".linethrough") != null;
            } else if ("Renewal".equalsIgnoreCase(label.text().trim())) {
                ren = PricePage.number(price.text());
            }
        }
        return reg == null || ren == null ? null : new TldPrice(tld, reg, ren, "USD", promo ? "first year promotion" : null);
    }

    @Override
    public String buyUrl(String domain) {
        return "https://www.gname.com/search?ym=" + domain;
    }

    @Override
    public String tldUrl(String tld) {
        return "https://www.gname.com/price/" + tld + ".html";
    }
}
