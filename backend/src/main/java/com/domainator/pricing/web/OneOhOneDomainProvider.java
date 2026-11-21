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
 * 101domain. One page per TLD: https://www.101domain.com/{tld}.htm with a "Registration ... USD / year" and
 * "Renewal ... USD / year" block. Only the configured TLDs are fetched, slowly.
 */
public class OneOhOneDomainProvider extends WebProvider {

    private final List<String> tlds;

    public OneOhOneDomainProvider(RestClient http, List<String> tlds) {
        super(http, "101domain", "101domain", "USD, VAT not stated", 3);
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
        for (Element row : doc.select("li.col-data-simple__row")) {
            Element heading = row.selectFirst(".col-data-simple__heading");
            Element text = row.selectFirst(".col-data-simple__text");
            if (heading == null || text == null || !text.text().contains("USD")) {
                continue;
            }
            if ("Registration".equals(heading.text().trim())) {
                reg = PricePage.number(text.text());
            } else if ("Renewal".equals(heading.text().trim())) {
                ren = PricePage.number(text.text());
            }
        }
        return reg == null || ren == null ? null : new TldPrice(tld, reg, ren, "USD", null);
    }

    @Override
    public String tldUrl(String tld) {
        return "https://www.101domain.com/" + tld.replace('.', '_') + ".htm";
    }
}
