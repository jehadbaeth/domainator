package com.domainator.pricing;

import java.util.regex.Pattern;

/**
 * Some registrars take credentials as URL query parameters (Namecheap, NameSilo, Dynadot, Internet.bs), and
 * HTTP client errors include the full URL. Everything that ends up in logs or in the API goes through here.
 */
public final class Secrets {

    private static final Pattern QUERY_SECRET = Pattern.compile(
            "(?i)((?:api[_-]?key|apiuser|api[_-]?user|key|password|passwd|pass|token|secret|auth-sid|clientip|client[_-]?ip|username)=)[^&\\s\"']+");

    private Secrets() {
    }

    public static String redact(String message) {
        return message == null ? null : QUERY_SECRET.matcher(message).replaceAll("$1***");
    }

    public static String describe(Throwable e) {
        return redact(e.getMessage() == null ? e.toString() : e.getMessage());
    }
}
