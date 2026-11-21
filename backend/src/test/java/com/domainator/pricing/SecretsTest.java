package com.domainator.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SecretsTest {

    @Test
    void redactsCredentialsInUrls() {
        String msg = "I/O error on GET request for \"https://api.namecheap.com/xml.response?ApiUser=bob&ApiKey=abc123&UserName=bob"
                + "&ClientIp=1.2.3.4&Command=namecheap.users.getPricing\": timeout";
        String r = Secrets.redact(msg);
        assertThat(r).doesNotContain("abc123", "bob", "1.2.3.4").contains("ApiKey=***", "Command=namecheap.users.getPricing");
        assertThat(Secrets.redact("400 on GET \"https://www.namesilo.com/api/getPrices?version=1&type=json&key=SECRET\""))
                .doesNotContain("SECRET");
        assertThat(Secrets.redact("Password=hunter2&ApiKey=x")).isEqualTo("Password=***&ApiKey=***");
        assertThat(Secrets.redact(null)).isNull();
    }
}
