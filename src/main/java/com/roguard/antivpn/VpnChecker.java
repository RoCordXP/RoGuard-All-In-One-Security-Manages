package com.roguard.antivpn;

import com.roguard.RoGuard;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Looks an address up against free reputation services. */
public class VpnChecker {

    private final RoGuard plugin;

    /** Results are cached so repeat joins never hit the network. */
    private final Map<String, Result> cache = new ConcurrentHashMap<>();
    private static final long CACHE_MILLIS = 6 * 60 * 60 * 1000L;

    public VpnChecker(RoGuard plugin) {
        this.plugin = plugin;
    }

    public CompletableFuture<Result> check(String ip) {
        Result cached = cache.get(ip);
        if (cached != null && System.currentTimeMillis() - cached.created < CACHE_MILLIS) {
            return CompletableFuture.completedFuture(cached);
        }

        return CompletableFuture.supplyAsync(() -> {
            Result result = lookup(ip);
            cache.put(ip, result);
            return result;
        });
    }

    private Result lookup(String ip) {
        if (isLocal(ip)) return new Result(false, "Local address");

        Result ipApi = queryIpApi(ip);
        if (ipApi.flagged) return ipApi;

        Result proxyCheck = queryProxyCheck(ip);
        if (proxyCheck.flagged) return proxyCheck;

        return new Result(false, "Clean");
    }

    private boolean isLocal(String ip) {
        return ip == null || ip.isEmpty()
                || ip.equals("127.0.0.1") || ip.equals("0:0:0:0:0:0:0:1")
                || ip.startsWith("192.168.") || ip.startsWith("10.")
                || ip.startsWith("172.16.") || ip.startsWith("172.17.")
                || ip.startsWith("172.18.") || ip.startsWith("172.19.");
    }

    private Result queryIpApi(String ip) {
        String body = fetch("http://ip-api.com/json/" + ip + "?fields=proxy,hosting,status");
        if (body == null) return new Result(false, "Unavailable");

        if (body.contains("\"proxy\":true")) return new Result(true, "Proxy");
        if (body.contains("\"hosting\":true")) return new Result(true, "Hosting network");
        return new Result(false, "Clean");
    }

    private Result queryProxyCheck(String ip) {
        String body = fetch("https://proxycheck.io/v2/" + ip + "?vpn=1");
        if (body == null) return new Result(false, "Unavailable");

        if (body.contains("\"proxy\":\"yes\"")) return new Result(true, "VPN or proxy");
        return new Result(false, "Clean");
    }

    private String fetch(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(2500);
            connection.setReadTimeout(2500);
            connection.setRequestProperty("User-Agent", "RoGuard");

            if (connection.getResponseCode() != 200) return null;

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream()))) {
                StringBuilder out = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) out.append(line);
                return out.toString();
            }
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    public static class Result {
        public final boolean flagged;
        public final String reason;
        private final long created = System.currentTimeMillis();

        public Result(boolean flagged, String reason) {
            this.flagged = flagged;
            this.reason = reason;
        }
    }
}
