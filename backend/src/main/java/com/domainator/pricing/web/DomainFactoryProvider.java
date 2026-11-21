package com.domainator.pricing.web;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.web.client.RestClient;

import com.domainator.pricing.TldPrice;

/**
 * DomainFactory (df.eu, Germany), https://www.df.eu/de/preislisten/. Prices on the page are MONTHLY and include
 * 19% VAT; we multiply by 12 and remove the VAT so they compare with the net prices of other registrars.
 * Columns: TLD, first year promotion (often empty), regular and renewal price.
 */
public class DomainFactoryProvider extends WebProvider {

    static final String URL = "https://www.df.eu/de/preislisten/";
    private static final int VAT = 19;

    public DomainFactoryProvider(RestClient http) {
        super(http, "domainfactory", "DomainFactory", "excl. VAT (page shows 19% incl.)", 50);
    }

    @Override
    public Map<String, TldPrice> fetchPrices() {
        return parse(page(URL));
    }

    @Override
    Map<String, TldPrice> parse(Document doc) {
        Map<String, TldPrice> out = new HashMap<>();
        for (Element label : doc.select("td.df-tld__item-label")) {
            String tld = PricePage.tld(label.ownText());
            Element row = label.parent();
            if (tld == null || row == null || out.containsKey(tld)) {
                continue; // the TOP 10 block repeats some TLDs
            }
            var info = row.select("td.df-tld__item-info");
            if (info.size() < 2) {
                continue;
            }
            Element offer = info.get(0).selectFirst("span.offer");
            Element price = info.get(1).selectFirst("span.price");
            BigDecimal monthlyRegular = price == null ? null : PricePage.number(price.text());
            if (monthlyRegular == null) {
                continue;
            }
            BigDecimal monthlyOffer = offer == null ? null : PricePage.number(offer.text());
            BigDecimal renew = yearlyNet(monthlyRegular);
            BigDecimal first = monthlyOffer != null ? yearlyNet(monthlyOffer) : renew;
            out.put(tld, new TldPrice(tld, first, renew, "EUR", monthlyOffer != null ? "first year promotion" : null));
        }
        return out;
    }

    private static BigDecimal yearlyNet(BigDecimal monthlyGross) {
        return PricePage.net(monthlyGross.multiply(BigDecimal.valueOf(12)), VAT);
    }

    @Override
    public String tldUrl(String tld) {
        return URL;
    }
}
