package com.domainator.pricing.web;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.web.client.RestClient;

import com.domainator.pricing.TldPrice;

/**
 * united-domains (Germany). The list page https://www.united-domains.de/domain-registrieren/preisliste/ has one
 * price per TLD in EUR incl. 19% VAT. Rows with unit "/1. Jahr" are first year promotions whose renewal price is
 * only on the TLD's own page ("Ab dem 2. Jahr 27 €"), so those are fetched for the configured TLDs only and
 * skipped otherwise. Rows with unit "/Jahr" are read as the same price every year. VAT is removed.
 */
public class UnitedDomainsProvider extends WebProvider {

    static final String LIST = "https://www.united-domains.de/domain-registrieren/preisliste/";
    private static final int VAT = 19;
    private static final Pattern RENEWAL = Pattern.compile("Ab dem 2\\. Jahr\\s+([0-9.,]+)\\s*€");

    private final List<String> priorityTlds;

    public UnitedDomainsProvider(RestClient http, List<String> priorityTlds) {
        super(http, "uniteddomains", "united-domains", "excl. VAT (page shows 19% incl.)", 100);
        this.priorityTlds = priorityTlds;
    }

    record Row(String tld, BigDecimal grossPrice, boolean firstYearOnly) {
    }

    @Override
    public Map<String, TldPrice> fetchPrices() throws InterruptedException {
        Map<String, Row> rows = rows(page(LIST));
        Map<String, TldPrice> out = new HashMap<>();
        rows.values().stream().filter(r -> !r.firstYearOnly()).forEach(r -> {
            BigDecimal p = PricePage.net(r.grossPrice(), VAT);
            out.put(r.tld(), new TldPrice(r.tld(), p, p, "EUR", null));
        });
        List<String> promoTlds = priorityTlds.stream().filter(t -> rows.containsKey(t) && rows.get(t).firstYearOnly()).toList();
        PricePage.perTld(promoTlds, out, tld -> {
            BigDecimal renewalGross = renewal(page(tldUrl(tld)));
            return renewalGross == null ? null : new TldPrice(tld, PricePage.net(rows.get(tld).grossPrice(), VAT),
                    PricePage.net(renewalGross, VAT), "EUR", "first year promotion");
        });
        return out;
    }

    @Override
    Map<String, TldPrice> parse(Document doc) {
        throw new UnsupportedOperationException("use rows() and renewal()");
    }

    static Map<String, Row> rows(Document doc) {
        Map<String, Row> out = new HashMap<>();
        for (Element row : doc.select("div.mol-price-list__row[data-searchable-text]")) {
            String tld = PricePage.tld(row.attr("data-searchable-text"));
            BigDecimal price = PricePage.number(row.attr("data-price"));
            Element unit = row.selectFirst(".mol-price-list__row-price-unit");
            if (tld == null || price == null || unit == null || !tld.matches("[a-z0-9.-]+") || out.containsKey(tld)) {
                continue;
            }
            String u = unit.ownText().trim();
            if (u.startsWith("/1. Jahr")) {
                out.put(tld, new Row(tld, price, true));
            } else if (u.startsWith("/Jahr")) {
                out.put(tld, new Row(tld, price, false));
            }
        }
        return out;
    }

    static BigDecimal renewal(Document tldPage) {
        Matcher m = RENEWAL.matcher(tldPage.text());
        return m.find() ? PricePage.number(m.group(1)) : null;
    }

    @Override
    public String tldUrl(String tld) {
        return "https://www.united-domains.de/" + tld.replace('.', '-') + "-domain/";
    }
}
