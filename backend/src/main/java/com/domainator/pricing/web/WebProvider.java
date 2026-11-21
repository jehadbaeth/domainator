package com.domainator.pricing.web;

import java.util.Map;

import org.jsoup.nodes.Document;
import org.springframework.web.client.RestClient;

import com.domainator.pricing.RegistrarProvider;
import com.domainator.pricing.TldPrice;

/** Base class for registrars read from a public price page. */
abstract class WebProvider implements RegistrarProvider {

    protected final RestClient http;
    private final String id;
    private final String name;
    private final String taxNote;
    private final int minExpected;

    WebProvider(RestClient http, String id, String name, String taxNote, int minExpected) {
        this.http = http;
        this.id = id;
        this.name = name;
        this.taxNote = taxNote;
        this.minExpected = minExpected;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String displayName() {
        return name;
    }

    @Override
    public String taxNote() {
        return taxNote;
    }

    @Override
    public int minExpectedTlds() {
        return minExpected;
    }

    @Override
    public String buyUrl(String domain) {
        return null; // most of these sites search via POST or JavaScript; tldUrl is used instead
    }

    /** Parses an already fetched page; separated from fetching so it can be unit tested on saved pages. */
    abstract Map<String, TldPrice> parse(Document doc);

    Document page(String url) {
        return PricePage.fetch(http, url);
    }
}
