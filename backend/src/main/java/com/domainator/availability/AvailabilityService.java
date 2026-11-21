package com.domainator.availability;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.naming.NameNotFoundException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.domainator.config.DomainatorProperties;

import jakarta.annotation.PreDestroy;

/**
 * Checks whether a domain is registered.
 * <ol>
 *   <li>DNS: if the name has NS records it is registered (cheap, no rate limits).</li>
 *   <li>RDAP at the registry: 404 means not registered, 200 means registered. The RDAP server per TLD
 *       comes from the IANA bootstrap file plus configured overrides for TLDs missing from it.</li>
 *   <li>If the TLD has no RDAP server, an NXDOMAIN answer from DNS is reported as LIKELY_AVAILABLE,
 *       since a domain can be registered without nameservers.</li>
 * </ol>
 * "Available" means "not registered". It does not prove the name is sold at the standard price:
 * registries keep premium and reserved names that only a registrar API can price.
 */
@Service
public class AvailabilityService {

    private static final Logger log = LoggerFactory.getLogger(AvailabilityService.class);
    private static final String BOOTSTRAP_URL = "https://data.iana.org/rdap/dns.json";

    public enum Status { AVAILABLE, LIKELY_AVAILABLE, TAKEN, UNKNOWN }

    public record Result(Status status, String source) {
    }

    record Bootstrap(List<List<List<String>>> services) {
    }

    private record Cached(Result result, Instant at) {
    }

    private final DomainatorProperties.Availability config;
    private final RestClient rest;
    private final HttpClient http;
    private final ExecutorService pool;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private volatile Map<String, String> rdapServers = Map.of();

    public AvailabilityService(DomainatorProperties props, RestClient rest) {
        this.config = props.availability();
        this.rest = rest;
        this.http = HttpClient.newBuilder()
                .connectTimeout(config.timeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.pool = Executors.newFixedThreadPool(Math.max(1, config.concurrency()));
    }

    @Scheduled(initialDelay = 0, fixedDelayString = "PT24H")
    public void loadBootstrap() {
        Map<String, String> servers = new HashMap<>();
        try {
            Bootstrap b = rest.get().uri(BOOTSTRAP_URL).retrieve().body(Bootstrap.class);
            if (b != null && b.services() != null) {
                for (List<List<String>> service : b.services()) {
                    if (service.size() < 2 || service.get(1).isEmpty()) {
                        continue;
                    }
                    String url = service.get(1).stream().filter(u -> u.startsWith("https")).findFirst().orElse(service.get(1).get(0));
                    service.get(0).forEach(tld -> servers.put(tld.toLowerCase(), url));
                }
            }
            log.info("Loaded RDAP servers for {} TLDs", servers.size());
        } catch (Exception e) {
            log.warn("Could not load the IANA RDAP bootstrap file: {}", e.toString());
        }
        config.rdapOverrides().forEach((tld, url) -> servers.put(tld.toLowerCase(), url));
        rdapServers = servers;
    }

    /** Checks many domains in parallel, bounded by the configured concurrency. Order is preserved. */
    public Map<String, Result> checkAll(List<String> domains) {
        Map<String, CompletableFuture<Result>> futures = new LinkedHashMap<>();
        for (String d : domains) {
            futures.put(d, CompletableFuture.supplyAsync(() -> check(d), pool));
        }
        Map<String, Result> out = new LinkedHashMap<>();
        long deadline = System.nanoTime() + config.timeout().multipliedBy(3).toNanos();
        futures.forEach((d, f) -> {
            try {
                long left = Math.max(1, deadline - System.nanoTime());
                out.put(d, f.get(left, TimeUnit.NANOSECONDS));
            } catch (Exception e) {
                out.put(d, new Result(Status.UNKNOWN, "timeout"));
            }
        });
        return out;
    }

    public Result check(String domain) {
        Cached c = cache.get(domain);
        if (c != null && c.at().plus(config.cacheTtl()).isAfter(Instant.now())) {
            return c.result();
        }
        Result r = doCheck(domain);
        if (r.status() != Status.UNKNOWN) {
            cache.put(domain, new Cached(r, Instant.now()));
        }
        return r;
    }

    private Result doCheck(String domain) {
        DnsAnswer dns = nsLookup(domain);
        if (dns == DnsAnswer.HAS_NS) {
            return new Result(Status.TAKEN, "dns");
        }
        String tld = domain.substring(domain.lastIndexOf('.') + 1);
        String server = rdapServers.get(tld);
        if (server != null) {
            Result rdap = rdap(server, domain);
            if (rdap.status() != Status.UNKNOWN) {
                return rdap;
            }
        }
        if (dns == DnsAnswer.NXDOMAIN) {
            return new Result(Status.LIKELY_AVAILABLE, "dns (no RDAP for ." + tld + ")");
        }
        return new Result(Status.UNKNOWN, server == null ? "no RDAP for ." + tld : "rdap error");
    }

    private Result rdap(String base, String domain) {
        String url = (base.endsWith("/") ? base : base + "/") + "domain/" + domain;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(config.timeout())
                    .header("Accept", "application/rdap+json, application/json")
                    .GET().build();
            int code = http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
            return switch (code) {
                case 200 -> new Result(Status.TAKEN, "rdap");
                case 404 -> new Result(Status.AVAILABLE, "rdap");
                default -> new Result(Status.UNKNOWN, "rdap http " + code);
            };
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(Status.UNKNOWN, "interrupted");
        } catch (Exception e) {
            return new Result(Status.UNKNOWN, "rdap " + e.getClass().getSimpleName());
        }
    }

    private enum DnsAnswer { HAS_NS, NXDOMAIN, OTHER }

    private DnsAnswer nsLookup(String domain) {
        Hashtable<String, String> env = new Hashtable<>();
        env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
        env.put("com.sun.jndi.dns.timeout.initial", "1500");
        env.put("com.sun.jndi.dns.timeout.retries", "2");
        try {
            InitialDirContext ctx = new InitialDirContext(env);
            try {
                Attributes attrs = ctx.getAttributes(domain + ".", new String[]{"NS"});
                Attribute ns = attrs.get("NS");
                return ns != null && ns.size() > 0 ? DnsAnswer.HAS_NS : DnsAnswer.OTHER;
            } finally {
                ctx.close();
            }
        } catch (NameNotFoundException e) {
            return DnsAnswer.NXDOMAIN;
        } catch (Exception e) {
            return DnsAnswer.OTHER;
        }
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }
}
