package com.domainator.pricing;

import java.net.IDN;
import java.util.HashMap;
import java.util.Map;

import com.domainator.config.DomainatorProperties.StaticProvider;

/** Prices typed into application.yml for registrars that have no public price API. */
public class StaticPriceProvider implements RegistrarProvider {

    private final StaticProvider config;

    public StaticPriceProvider(StaticProvider config) {
        this.config = config;
    }

    @Override
    public String id() {
        return config.id();
    }

    @Override
    public String displayName() {
        return config.name() == null ? config.id() : config.name();
    }

    @Override
    public String taxNote() {
        return config.taxNote();
    }

    @Override
    public String buyUrl(String domain) {
        return null;
    }

    @Override
    public Map<String, TldPrice> fetchPrices() {
        Map<String, TldPrice> out = new HashMap<>();
        if (config.prices() != null) {
            config.prices().forEach((tld, p) -> {
                if (p.registration() != null && p.renewal() != null) {
                    String key = IDN.toASCII(tld.toLowerCase().replaceFirst("^\\.", ""));
                    out.put(key, new TldPrice(key, p.registration(), p.renewal(), config.currency(), null));
                }
            });
        }
        return out;
    }
}
