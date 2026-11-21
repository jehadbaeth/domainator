package com.domainator.pricing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Keeps each provider's last good price list on disk ({@code <cache-dir>/prices/<provider>.json}) so a restart
 * or redeploy does not refetch every registrar. Files are written atomically; a broken file is ignored.
 */
public class PriceCache {

    private static final Logger log = LoggerFactory.getLogger(PriceCache.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public record Entry(Map<String, TldPrice> prices, Instant fetchedAt) {
    }

    private final Path dir;

    public PriceCache(Path cacheDir) {
        this.dir = cacheDir == null ? null : cacheDir.resolve("prices");
    }

    public Optional<Entry> load(String providerId) {
        if (dir == null) {
            return Optional.empty();
        }
        Path file = dir.resolve(providerId + ".json");
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            JsonNode root = JSON.readTree(file.toFile());
            Map<String, TldPrice> prices = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> e : root.path("prices").properties()) {
                prices.put(e.getKey(), JSON.treeToValue(e.getValue(), TldPrice.class));
            }
            return Optional.of(new Entry(Map.copyOf(prices), Instant.parse(root.path("fetchedAt").asString())));
        } catch (Exception e) {
            log.warn("Ignoring unreadable price cache {}: {}", file, e.toString());
            return Optional.empty();
        }
    }

    public void save(String providerId, Map<String, TldPrice> prices, Instant fetchedAt) {
        if (dir == null) {
            return;
        }
        try {
            Files.createDirectories(dir);
            ObjectNode root = JSON.createObjectNode();
            root.put("provider", providerId);
            root.put("fetchedAt", fetchedAt.toString());
            root.set("prices", JSON.valueToTree(prices));
            Path tmp = Files.createTempFile(dir, providerId, ".tmp");
            JSON.writeValue(tmp.toFile(), root);
            Files.move(tmp, dir.resolve(providerId + ".json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not write price cache for {}: {}", providerId, e.toString());
        }
    }
}
