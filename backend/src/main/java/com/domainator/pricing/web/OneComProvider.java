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
 * one.com, https://www.one.com/en/domain/prices. Currency and VAT depend on the visitor's country (from here:
 * EUR incl. 19% VAT). The VAT rate is read from the page footer and removed. A promotion shows the current price
 * followed by the struck regular price.
 */
public class OneComProvider extends WebProvider {

    static final String URL = "https://www.one.com/en/domain/prices";
    private static final Pattern VAT = Pattern.compile("include\\s+(\\d{1,2})\\s*%\\s*VAT", Pattern.CASE_INSENSITIVE);

    public OneComProvider(RestClient http) {
        super(http, "onecom", "one.com", "excl. VAT (page shows it incl.)", 100);
    }

    @Override
    public Map<String, TldPrice> fetchPrices() {
        return parse(page(URL));
    }

    @Override
    Map<String, TldPrice> parse(Document doc) {
        Matcher m = VAT.matcher(doc.text());
        int vat = m.find() ? Integer.parseInt(m.group(1)) : 0;
        Map<String, TldPrice> out = new HashMap<>();
        for (Element row : doc.select("tr[data-pub-price-row]")) {
            Element th = row.selectFirst("th");
            String tld = th == null ? null : PricePage.tld(th.text());
            BigDecimal reg = current(row.selectFirst("td[data-label=Registration]"));
            BigDecimal ren = current(row.selectFirst("td[data-label=Renewal]"));
            if (tld != null && reg != null && ren != null) {
                boolean promo = row.selectFirst("td[data-label=Registration] span.line-through") != null;
                out.put(tld, new TldPrice(tld, PricePage.net(reg, vat), PricePage.net(ren, vat), "EUR",
                        promo ? "first year promotion" : null));
            }
        }
        return out;
    }

    private static BigDecimal current(Element cell) {
        if (cell == null) {
            return null;
        }
        Element first = cell.selectFirst("> span:not(.line-through)");
        if (first == null) {
            return null;
        }
        Element copy = first.clone();
        copy.select(".pubPriceList-badge").remove(); // "Free with hosting"
        return PricePage.number(copy.text());
    }

    @Override
    public String tldUrl(String tld) {
        return URL;
    }
}
