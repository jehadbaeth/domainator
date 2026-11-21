package com.domainator.search;

import java.util.List;
import java.util.Set;

/**
 * What the user wants included in the totals. The default (no add-ons, no VAT) gives the lowest prices.
 *
 * @param addons     features the user needs, e.g. privacy, emailForwarding; paid ones are added to the price
 * @param vatPercent VAT rate to add for registrars that charge EU VAT, 0 to show net prices
 */
public record PriceOptions(Set<String> addons, int vatPercent) {

    public static final PriceOptions LOWEST = new PriceOptions(Set.of(), 0);

    public PriceOptions {
        addons = addons == null ? Set.of() : Set.copyOf(addons);
        if (vatPercent < 0 || vatPercent > 50) {
            throw new IllegalArgumentException("vat must be between 0 and 50");
        }
    }

    /** Stable order for the response. */
    public List<String> orderedAddons() {
        return com.domainator.registrars.RegistrarProfiles.FEATURES.stream().filter(addons::contains).toList();
    }
}
