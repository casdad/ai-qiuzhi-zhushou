package com.getjobs.worker.manager;

import com.google.gson.JsonObject;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.CDPSession;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Page;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Clears one platform's client state without opening a remote URL. */
public final class PlatformSessionReset {
    private PlatformSessionReset() {
    }

    /** Run on the owning Playwright thread with platform tasks stopped. Caller owns the returned blank page. */
    public static Page clear(BrowserContext context, String domainSuffix, List<String> seedOrigins) {
        Objects.requireNonNull(context, "Browser context is required");
        String domain = normalizeDomain(domainSuffix);
        Set<String> origins = new LinkedHashSet<>();
        origins.add("https://" + domain);
        if (seedOrigins != null) {
            seedOrigins.forEach(origin -> collectOrigin(origins, origin, domain));
        }

        List<Page> platformPages = new ArrayList<>();
        for (Page page : new ArrayList<>(context.pages())) {
            if (page.isClosed()) {
                continue;
            }
            String pageUrl = page.url();
            if (matchesDomain(pageUrl, domain)) {
                platformPages.add(page);
            }
            collectOrigin(origins, pageUrl, domain);
            for (Frame frame : page.frames()) {
                collectOrigin(origins, frame.url(), domain);
            }
        }

        IllegalStateException failure = null;
        Page probe = null;
        CDPSession session = null;
        try {
            // Keep a tab alive throughout reset: closing the last tab can terminate CDP Chrome.
            probe = context.newPage();
            // Close old documents before clearing state so their SPA cannot restore it.
            for (Page page : platformPages) {
                try {
                    page.close();
                } catch (RuntimeException e) {
                    failure = appendFailure(failure, e);
                }
            }
            session = context.newCDPSession(probe);
            for (String origin : origins) {
                JsonObject parameters = new JsonObject();
                parameters.addProperty("origin", origin);
                parameters.addProperty("storageTypes", "all");
                try {
                    session.send("Storage.clearDataForOrigin", parameters);
                } catch (RuntimeException e) {
                    failure = appendFailure(failure, e);
                }
            }
        } catch (RuntimeException e) {
            failure = appendFailure(failure, e);
        } finally {
            try {
                // Playwright serializes this to JavaScript RegExp, which does not support Java's \Q...\E.
                Pattern cookieDomain = Pattern.compile("^\\.?(?:[^.]+\\.)*" + domain.replace(".", "\\.") + "$",
                        Pattern.CASE_INSENSITIVE);
                context.clearCookies(new BrowserContext.ClearCookiesOptions().setDomain(cookieDomain));
                if (context.cookies().stream().anyMatch(cookie -> cookieDomain.matcher(cookie.domain).matches())) {
                    throw new IllegalStateException("Platform cookies remain after session reset");
                }
            } catch (RuntimeException e) {
                failure = appendFailure(failure, e);
            }
            if (session != null) {
                try {
                    session.detach();
                } catch (RuntimeException e) {
                    failure = appendFailure(failure, e);
                }
            }
            if (failure != null && probe != null) {
                try {
                    probe.close();
                } catch (RuntimeException e) {
                    failure = appendFailure(failure, e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
        return probe;
    }

    private static String normalizeDomain(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Platform domain is required");
        }
        String domain = value.toLowerCase(Locale.ROOT);
        if (domain.startsWith(".")) {
            domain = domain.substring(1);
        }
        URI parsed = parseHttpUrl("https://" + domain);
        if (parsed == null || !domain.equals(parsed.getHost()) || parsed.getPort() != -1
                || !parsed.getRawPath().isEmpty() || parsed.getRawQuery() != null || parsed.getRawFragment() != null) {
            throw new IllegalArgumentException("Invalid platform domain");
        }
        return domain;
    }

    private static void collectOrigin(Set<String> origins, String value, String domain) {
        URI url = parseHttpUrl(value);
        if (url == null || !hostMatches(url.getHost(), domain)) {
            return;
        }
        String host = url.getHost().toLowerCase(Locale.ROOT);
        String scheme = url.getScheme().toLowerCase(Locale.ROOT);
        int port = url.getPort();
        boolean defaultPort = port == -1 || (scheme.equals("https") && port == 443)
                || (scheme.equals("http") && port == 80);
        origins.add(scheme + "://" + host + (defaultPort ? "" : ":" + port));
        origins.add("https://" + host);
    }

    private static boolean matchesDomain(String value, String domain) {
        URI url = parseHttpUrl(value);
        return url != null && hostMatches(url.getHost(), domain);
    }

    private static boolean hostMatches(String host, String domain) {
        String normalized = host.toLowerCase(Locale.ROOT);
        return normalized.equals(domain) || normalized.endsWith("." + domain);
    }

    private static URI parseHttpUrl(String value) {
        if (value == null) {
            return null;
        }
        try {
            URI url = URI.create(value);
            String scheme = url.getScheme();
            if (url.getHost() == null || url.getRawUserInfo() != null || scheme == null
                    || (!scheme.equalsIgnoreCase("https") && !scheme.equalsIgnoreCase("http"))) {
                return null;
            }
            return url;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static IllegalStateException appendFailure(IllegalStateException failure, RuntimeException cause) {
        if (failure == null) {
            failure = new IllegalStateException("Platform session reset did not complete");
        }
        failure.addSuppressed(cause);
        return failure;
    }
}
