package com.domainator.search;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.domainator.search.SearchModels.ProviderInfo;
import com.domainator.search.SearchModels.SearchResponse;
import com.domainator.search.SearchModels.TldResponse;
import com.domainator.registrars.RegistrarProfiles;

import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api")
public class SearchController {

    private final SearchService service;
    private final RegistrarProfiles profiles;

    public SearchController(SearchService service, RegistrarProfiles profiles) {
        this.service = service;
        this.profiles = profiles;
    }

    /**
     * Example: /api/search?q=acme&years=1,5,10&currency=EUR&providers=porkbun,ovh
     */
    @GetMapping("/search")
    public SearchResponse search(@RequestParam String q,
                                 @RequestParam(required = false) String providers,
                                 @RequestParam(required = false) String currency,
                                 @RequestParam(defaultValue = "1,5,10") String years,
                                 @RequestParam(defaultValue = "false") boolean withPrivacy) {
        return service.search(q, ids(providers), currency, years(years), withPrivacy);
    }

    /** Every TLD ranked by total cost over the longest horizon. */
    @GetMapping("/tlds")
    public TldResponse tlds(@RequestParam(required = false) String providers,
                            @RequestParam(required = false) String currency,
                            @RequestParam(defaultValue = "1,5,10") String years,
                            @RequestParam(defaultValue = "100") int limit,
                            @RequestParam(defaultValue = "false") boolean withPrivacy) {
        return service.tlds(ids(providers), currency, years(years), Math.min(Math.max(limit, 1), 2000), withPrivacy);
    }

    @GetMapping("/providers")
    public List<ProviderInfo> providers() {
        return service.allProviders();
    }

    /** Free extras per registrar (privacy, SSL, forwarding, ...) with source links. */
    @GetMapping("/registrars")
    public JsonNode registrars() {
        return profiles.all();
    }

    @GetMapping("/currencies")
    public Set<String> currencies() {
        return service.currencies();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }

    private static Set<String> ids(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }

    private static List<Integer> years(String csv) {
        List<Integer> list = Arrays.stream(csv.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .map(Integer::parseInt)
                .filter(y -> y >= 1 && y <= 30)
                .distinct().sorted().toList();
        if (list.isEmpty()) {
            throw new IllegalArgumentException("years must contain values between 1 and 30");
        }
        return list;
    }
}
