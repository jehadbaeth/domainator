package com.domainator.pricing;

import java.math.BigDecimal;

/**
 * Standard (non premium) yearly price of a TLD at one registrar, in the registrar's own currency.
 *
 * @param registration price of the first year
 * @param renewal      price of every following year
 * @param promo        short description when the registration price is a time limited promotion, else null
 */
public record TldPrice(String tld, BigDecimal registration, BigDecimal renewal, String currency, String promo) {
}
