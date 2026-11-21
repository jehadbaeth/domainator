package com.domainator.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class CostCalculatorTest {

    @Test
    void firstYearThenRenewals() {
        // Porkbun .xyz: 1.29 first year, 14.21 renewal
        BigDecimal reg = new BigDecimal("1.29");
        BigDecimal ren = new BigDecimal("14.21");
        assertThat(CostCalculator.total(reg, ren, 1, 1)).isEqualByComparingTo("1.29");
        assertThat(CostCalculator.total(reg, ren, 5, 1)).isEqualByComparingTo("58.13");
        assertThat(CostCalculator.total(reg, ren, 10, 1)).isEqualByComparingTo("129.18");
        assertThat(CostCalculator.isTeaser(reg, ren)).isTrue();
    }

    @Test
    void minimumTermIsBilledInBlocks() {
        assertThat(CostCalculator.billedYears(1, 2)).isEqualTo(2);
        assertThat(CostCalculator.billedYears(5, 2)).isEqualTo(6);
        assertThat(CostCalculator.billedYears(10, 2)).isEqualTo(10);
        assertThat(CostCalculator.total(new BigDecimal("80"), new BigDecimal("90"), 1, 2)).isEqualByComparingTo("170");
    }

    @Test
    void flatPricingIsNotATeaser() {
        assertThat(CostCalculator.isTeaser(new BigDecimal("11.81"), new BigDecimal("11.81"))).isFalse();
    }
}
