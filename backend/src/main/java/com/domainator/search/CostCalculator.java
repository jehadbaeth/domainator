package com.domainator.search;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Total cost of owning a domain for N years: the first year at the registration price and every
 * following year at the renewal price. TLDs with a minimum term (e.g. .ai registers and renews in
 * 2 year blocks) are billed in whole blocks, so 5 years of .ai costs 6 billed years.
 */
public final class CostCalculator {

    private CostCalculator() {
    }

    public static int billedYears(int years, int minimumYears) {
        int block = Math.max(1, minimumYears);
        return ((years + block - 1) / block) * block;
    }

    public static BigDecimal total(BigDecimal registration, BigDecimal renewal, int years, int minimumYears) {
        if (years < 1) {
            throw new IllegalArgumentException("years must be >= 1");
        }
        int billed = billedYears(years, minimumYears);
        return registration.add(renewal.multiply(BigDecimal.valueOf(billed - 1L))).setScale(2, RoundingMode.HALF_UP);
    }

    /** True when the first year is a teaser: renewal costs at least 1.5x the registration. */
    public static boolean isTeaser(BigDecimal registration, BigDecimal renewal) {
        return registration.signum() > 0
                ? renewal.compareTo(registration.multiply(new BigDecimal("1.5"))) >= 0
                : renewal.signum() > 0;
    }
}
