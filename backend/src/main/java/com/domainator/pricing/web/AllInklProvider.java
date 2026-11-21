package com.domainator.pricing.web;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.web.client.RestClient;

import com.domainator.pricing.TldPrice;

/**
 * ALL-INKL.COM (Germany), https://all-inkl.com/domains/preise. Columns: TLD, country, setup fee, yearly fee,
 * incl. 19% VAT (removed here). First year = setup + yearly fee, renewal = yearly fee. Domains are sold with
 * their hosting packages.
 */
public class AllInklProvider extends WebProvider {

    static final String URL = "https://all-inkl.com/domains/preise";
    private static final int VAT = 19;

    public AllInklProvider(RestClient http) {
        super(http, "allinkl", "ALL-INKL.COM", "excl. VAT (page shows 19% incl.)", 50);
    }

    @Override
    public Map<String, TldPrice> fetchPrices() {
        return parse(page(URL));
    }

    @Override
    Map<String, TldPrice> parse(Document doc) {
        Map<String, TldPrice> out = new HashMap<>();
        for (Element row : doc.select("table.table-domains tbody tr")) {
            var cells = row.select("> td");
            if (cells.size() < 4) {
                continue;
            }
            String tld = PricePage.tld(cells.get(0).text());
            BigDecimal setup = PricePage.number(cells.get(2).text());
            BigDecimal yearly = PricePage.number(cells.get(3).ownText().isBlank() ? cells.get(3).text() : cells.get(3).ownText());
            if (tld != null && setup != null && yearly != null) {
                out.put(tld, new TldPrice(tld, PricePage.net(setup.add(yearly), VAT), PricePage.net(yearly, VAT), "EUR",
                        setup.signum() > 0 ? "includes setup fee " + setup + " EUR" : null));
            }
        }
        return out;
    }

    @Override
    public String tldUrl(String tld) {
        return "https://all-inkl.com/domains/" + tld.replace('.', '-') + "-tld";
    }
}
