package com.domainator.search;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.domainator.fx.ExchangeRates;
import com.domainator.registrars.RegistrarProfiles;
import com.domainator.registrars.RegistrarProfiles.Cost;
import com.domainator.registrars.RegistrarProfiles.Feature;
import com.domainator.search.SearchModels.AddonLine;

/**
 * Prices the add-ons a user asked for at one registrar.
 * <ul>
 *   <li>free: costs nothing</li>
 *   <li>paid with a known price: added (monthly prices x12; "once" only in the first year; a bundle once)</li>
 *   <li>paid without a known price, not offered, or privacy that is only GDPR redaction: MISSING, so the
 *       registrar cannot win "best"</li>
 *   <li>limited (offered with conditions): no cost, flagged</li>
 *   <li>unknown: no cost, flagged, still eligible because we simply do not know</li>
 * </ul>
 */
@Component
public class AddonPricer {

    public record Result(List<AddonLine> lines, BigDecimal firstYear, BigDecimal perYear, boolean missing) {
    }

    private final RegistrarProfiles profiles;
    private final ExchangeRates fx;

    public AddonPricer(RegistrarProfiles profiles, ExchangeRates fx) {
        this.profiles = profiles;
        this.fx = fx;
    }

    public Result price(String providerId, List<String> addons, String currency) {
        List<AddonLine> lines = new ArrayList<>();
        BigDecimal first = BigDecimal.ZERO;
        BigDecimal yearly = BigDecimal.ZERO;
        boolean missing = false;
        Set<String> bundlesPaid = new HashSet<>();
        for (String addon : addons) {
            Feature f = profiles.feature(providerId, addon);
            switch (f.value()) {
                case "free" -> lines.add(new AddonLine(addon, "free", null, null));
                case "limited" -> {
                    if ("privacy".equals(addon)) {
                        lines.add(new AddonLine(addon, "missing", null, "only GDPR redaction or conditional"));
                        missing = true;
                    } else {
                        lines.add(new AddonLine(addon, "limited", null, "offered with conditions"));
                    }
                }
                case "paid" -> {
                    Optional<Cost> cost = f.cost();
                    if (cost.isPresent() && cost.get().bundle() != null && !bundlesPaid.add(cost.get().bundle())) {
                        lines.add(new AddonLine(addon, "paid", BigDecimal.ZERO, "in the same bundle"));
                        continue;
                    }
                    Optional<BigDecimal> amount = cost.flatMap(c -> fx.convert(c.amount(), c.currency(), currency));
                    if (amount.isEmpty()) {
                        lines.add(new AddonLine(addon, "missing", null, "paid, price unknown"));
                        missing = true;
                        continue;
                    }
                    String per = cost.get().per();
                    BigDecimal a = ("month".equals(per) ? amount.get().multiply(BigDecimal.valueOf(12)) : amount.get())
                            .setScale(2, RoundingMode.HALF_UP);
                    if ("once".equals(per)) {
                        first = first.add(a);
                        lines.add(new AddonLine(addon, "paid", a, "once"));
                    } else {
                        yearly = yearly.add(a);
                        lines.add(new AddonLine(addon, "paid", a, "per year"));
                    }
                }
                case "none" -> {
                    lines.add(new AddonLine(addon, "missing", null, "not offered"));
                    missing = true;
                }
                default -> lines.add(new AddonLine(addon, "unknown", null, "not known"));
            }
        }
        return new Result(lines, first, yearly, missing);
    }
}
