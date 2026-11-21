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
 * EuroDNS (Luxembourg). One page per TLD: https://www.eurodns.com/domain-extensions/{tld}-domain-registration
 * with "Annual fee" (a promotion shows the current and the struck regular price) and "Renewal fee". EUR, excl.
 * VAT. Only the configured TLDs are fetched, slowly.
 */
public class EuroDnsProvider extends WebProvider {

    private final List<String> tlds;

    public EuroDnsProvider(RestClient http, List<String> tlds) {
        super(http, "eurodns", "EuroDNS", "EUR, excl. VAT", 3);
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
        for (Element row : doc.select("#price tr")) {
            var cells = row.select("> td");
            if (cells.size() < 2) {
                continue;
            }
            String label = cells.get(0).text().toLowerCase();
            Element strong = cells.get(1).selectFirst("strong");
            BigDecimal value = PricePage.number(strong != null ? strong.text() : cells.get(1).text());
            if (label.startsWith("annual fee")) {
                reg = value;
                promo = cells.get(1).selectFirst("s") != null;
            } else if (label.startsWith("renewal fee")) {
                ren = value;
            }
        }
        return reg == null || ren == null ? null : new TldPrice(tld, reg, ren, "EUR", promo ? "first year promotion" : null);
    }

    @Override
    public String tldUrl(String tld) {
        return "https://www.eurodns.com/domain-extensions/" + tld.replace('.', '-') + "-domain-registration";
    }
}
