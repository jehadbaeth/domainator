package com.domainator.pricing;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class ProviderConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    RestClient restClient(RestClient.Builder builder) {
        HttpClient jdk = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdk);
        factory.setReadTimeout(Duration.ofSeconds(90)); // the OVH catalog is ~30 MB
        return builder.requestFactory(factory)
                .defaultHeader("User-Agent", "domainator/0.2 (personal domain price comparison)")
                .build();
    }

    @Bean
    List<RegistrarProvider> registrarProviders(ProviderRegistry registry) {
        return registry.active();
    }
}
