package com.overstackdupefinder.webhook;

import com.overstackdupefinder.OverStackDupeFinder;
import com.overstackdupefinder.config.PluginConfig;
import com.overstackdupefinder.model.AlertData;
import org.bukkit.Location;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * Sends rich Discord embed payloads to the configured webhook URL.
 *
 * <p>Always called from a virtual thread — blocking HTTP I/O is fine here.</p>
 */
public class WebhookManager {

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS    = 10_000;
    private static final int EMBED_COLOR        = 0xFF4500;

    private final OverStackDupeFinder plugin;

    public WebhookManager(OverStackDupeFinder plugin) {
        this.plugin = plugin;
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /** Sends a real alert embed. Called from virtual thread. */
    public void sendAlert(AlertData alert) {
        PluginConfig cfg = plugin.getPluginConfig();

        if (cfg.isLogToConsole()) logToConsole(alert);

        String url = cfg.getWebhookUrl();
        if (url.isBlank() || url.contains("YOUR_WEBHOOK")) {
            plugin.getLogger().warning("[Webhook] URL not configured — skipping HTTP send.");
            return;
        }

        String payload = buildAlertPayload(alert, cfg);

        if (cfg.isDebug()) {
            plugin.getLogger().info("[DEBUG Webhook] Payload: " + payload);
        }

        postWithLog(url, payload, "alert for " + alert.getPlayerName());
    }

    /**
     * Sends a test embed to verify the webhook works.
     * Called from the /osd test command (via async Bukkit task).
     */
    public void sendTestWebhook() {
        String url = plugin.getPluginConfig().getWebhookUrl();
        if (url.isBlank() || url.contains("YOUR_WEBHOOK")) {
            plugin.getLogger().warning("[Webhook] Cannot send test — webhook-url is not configured.");
            return;
        }

        String payload = """
                {
                  "username": "OverStackDupeFinder",
                  "embeds": [{
                    "title": "✅  Webhook Test Successful",
                    "description": "If you see this, your Discord webhook is configured correctly!",
                    "color": 3066993,
                    "footer": { "text": "OverStackDupeFinder test" },
                    "timestamp": "%s"
                  }]
                }
                """.formatted(Instant.now());

        postWithLog(url, payload, "test message");
    }

    /**
     * Sends a green "Player Banned" embed to Discord.
     * Called from a virtual thread — HTTP blocking is fine here.
     */
    public void sendBanAlert(String playerName, String reason) {
        String url = plugin.getPluginConfig().getWebhookUrl();
        if (url.isBlank() || url.contains("YOUR_WEBHOOK")) return;

        String headUrl = plugin.getPluginConfig().getHeadUrl()
                .replace("{name}", playerName)
                .replace("{uuid}", "");

        String payload = """
                {
                  "username": "OverStackDupeFinder",
                  "avatar_url": "https://mc-heads.net/avatar/%s/64",
                  "embeds": [{
                    "title": "\uD83D\uDD28  Player Banned",
                    "description": "**%s** has been automatically banned for a suspicious item stash.",
                    "color": 3066993,
                    "thumbnail": { "url": "%s" },
                    "fields": [
                      { "name": "\uD83D\uDC64 Player",  "value": "`%s`", "inline": true  },
                      { "name": "\u2696\uFE0F Reason",  "value": "`%s`", "inline": false },
                      { "name": "\uD83E\uDD16 Banned By", "value": "`OverStackDupeFinder (Auto)`", "inline": true }
                    ],
                    "footer": { "text": "OverStackDupeFinder \u2022 Auto-Ban" },
                    "timestamp": "%s"
                  }]
                }
                """.formatted(
                esc(playerName),
                esc(playerName),
                esc(headUrl),
                esc(playerName),
                esc(reason),
                Instant.now()
        );

        postWithLog(url, payload, "ban alert for " + playerName);
    }

    // ── Payload builder ────────────────────────────────────────────────────────

    private String buildAlertPayload(AlertData alert, PluginConfig cfg) {
        Location playerLoc  = alert.getLocation();
        Location chestLoc   = alert.getChestLocation();
        String   world      = playerLoc.getWorld() != null ? playerLoc.getWorld().getName() : "unknown";
        String   playerCoords = String.format("%d, %d, %d",
                (int) playerLoc.getX(), (int) playerLoc.getY(), (int) playerLoc.getZ());
        String   headUrl    = cfg.getHeadUrl()
                .replace("{name}", alert.getPlayerName())
                .replace("{uuid}", alert.getPlayerUuid().toString());

        String itemName   = prettyLabel(alert.getItemLabel());
        String container  = alert.getContainer();
        String sourceDesc = sourceDescription(alert.getSource());

        // Build the optional chest-coordinates field
        String chestField = "";
        if (chestLoc != null) {
            String chestCoords = String.format("%d, %d, %d",
                    (int) chestLoc.getX(), (int) chestLoc.getY(), (int) chestLoc.getZ());
            chestField = """
                      { "name": "📦 Chest Coords",    "value": "`%s`",  "inline": true  },
                    """.formatted(esc(chestCoords));
        }

        return """
                {
                  "username": "OverStackDupeFinder",
                  "avatar_url": "https://mc-heads.net/avatar/%s/64",
                  "embeds": [{
                    "title": "⚠️  Suspicious Item Amount Detected",
                    "description": "Player **%s** has an unusually large amount of **%s** in **%s**.",
                    "color": %d,
                    "thumbnail": { "url": "%s" },
                    "fields": [
                      { "name": "👤 Player",          "value": "`%s`",                   "inline": true  },
                      { "name": "📦 Item",            "value": "`%s`",                   "inline": true  },
                      { "name": "🧱 Container",       "value": "`%s`",                   "inline": true  },
                      { "name": "📊 Amount",          "value": "`%d stacks (%d items)`", "inline": true  },
                      { "name": "📍 Player Coords",   "value": "`%s`",                   "inline": true  },
                      %s
                      { "name": "🌍 World",           "value": "`%s`",                   "inline": true  },
                      { "name": "🔍 Detected In",     "value": "`%s`",                   "inline": false }
                    ],
                    "footer": { "text": "OverStackDupeFinder • %s" },
                    "timestamp": "%s"
                  }]
                }
                """.formatted(
                esc(alert.getPlayerName()),
                esc(alert.getPlayerName()), esc(itemName), esc(container),
                EMBED_COLOR,
                esc(headUrl),
                esc(alert.getPlayerName()),
                esc(itemName),
                esc(container),
                alert.getStackCount(), alert.getTotalItemCount(),
                esc(playerCoords),
                chestField,
                esc(world),
                esc(sourceDesc),
                esc(alert.getPlayerName()),
                Instant.now()
        );
    }

    // ── HTTP ──────────────────────────────────────────────────────────────────

    private void postWithLog(String urlString, String jsonPayload, String context) {
        boolean debug = plugin.getPluginConfig().isDebug();
        try {
            if (debug) plugin.getLogger().info("[DEBUG Webhook] Posting " + context + " to Discord...");

            HttpURLConnection conn = (HttpURLConnection) URI.create(urlString).toURL().openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("User-Agent", "OverStackDupeFinder/1.0");
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonPayload.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();

            // Read response body (error stream on failure, input stream on success)
            String body;
            try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    code >= 400 ? conn.getErrorStream() : conn.getInputStream(),
                    StandardCharsets.UTF_8))) {
                body = br.lines().collect(Collectors.joining("\n"));
            }

            conn.disconnect();

            if (code == 204 || (code >= 200 && code < 300)) {
                if (debug) plugin.getLogger().info("[DEBUG Webhook] POST OK (HTTP " + code + ")");
            } else {
                plugin.getLogger().warning(
                        "[Webhook] Discord returned HTTP " + code + " for " + context + ". Response: " + body);
            }

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING,
                    "[Webhook] Failed to POST " + context + ": " + e.getMessage(), e);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void logToConsole(AlertData alert) {
        Location playerLoc = alert.getLocation();
        Location chestLoc  = alert.getChestLocation();
        String chestInfo   = chestLoc != null
                ? String.format(" | Chest: %d,%d,%d",
                        (int) chestLoc.getX(), (int) chestLoc.getY(), (int) chestLoc.getZ())
                : "";
        plugin.getLogger().warning(String.format(
                "[ALERT] %s | %d stacks (%d items) of '%s' in %s | Source: %s | Player: %d,%d,%d [%s]%s",
                alert.getPlayerName(),
                alert.getStackCount(), alert.getTotalItemCount(),
                alert.getItemLabel(),
                alert.getContainer(),
                alert.getSource().name(),
                (int) playerLoc.getX(), (int) playerLoc.getY(), (int) playerLoc.getZ(),
                playerLoc.getWorld() != null ? playerLoc.getWorld().getName() : "?",
                chestInfo
        ));
    }

    private static String sourceDescription(AlertData.AlertSource source) {
        return switch (source) {
            case PLAYER_INVENTORY    -> "Loose in Player Inventory";
            case SHULKER_IN_INVENTORY -> "Shulker Box in Player Inventory";
            case CHEST               -> "Loose in Chest";
            case SHULKER_IN_CHEST    -> "Shulker Box inside Chest";
        };
    }

    /** Converts material name (SPAWNER) or keyword (spawner) to "Spawner". */
    private static String prettyLabel(String label) {
        return prettyName(label.replace("_", " "));
    }

    private static String prettyName(String name) {
        String[] parts = name.split("[_ ]+");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!part.isEmpty()) {
                if (!sb.isEmpty()) sb.append(' ');
                sb.append(Character.toUpperCase(part.charAt(0)))
                  .append(part.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return sb.toString();
    }

    private static String esc(String raw) {
        if (raw == null) return "";
        return raw.replace("\\", "\\\\").replace("\"", "\\\"")
                  .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
