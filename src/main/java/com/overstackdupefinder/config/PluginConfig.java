package com.overstackdupefinder.config;

import com.overstackdupefinder.OverStackDupeFinder;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Typed wrapper around Bukkit's FileConfiguration.
 */
public class PluginConfig {

    private final OverStackDupeFinder plugin;

    private String              webhookUrl;
    private List<MonitoredItem> monitoredItems;
    private boolean             scanPlayerInventory;
    private boolean             scanShulkerContents;  // shulkers in player inventory
    private boolean             scanChestsLooseItems; // loose items directly in a chest
    private boolean             scanChestsShulkers;   // shulkers sitting inside a chest
    private int                 scanIntervalSeconds;
    private long                alertCooldownSeconds;
    private boolean             logToConsole;
    private boolean             debug;
    private String              headUrl;
    // Auto-ban
    private boolean             autoBanEnabled;
    private String              autoBanCommand;
    private String              autoBanReason;
    private boolean             autoBanNotifyDiscord;
    // Whitelist
    private List<String>        whitelistedPlayers;

    public PluginConfig(OverStackDupeFinder plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        FileConfiguration cfg = plugin.getConfig();

        webhookUrl           = cfg.getString("webhook-url", "");
        scanPlayerInventory  = cfg.getBoolean("scan-player-inventory", true);
        scanShulkerContents  = cfg.getBoolean("scan-shulker-contents", true);
        scanChestsLooseItems = cfg.getBoolean("scan-chests-loose-items", true);
        scanChestsShulkers   = cfg.getBoolean("scan-chests-shulkers", true);
        scanIntervalSeconds  = Math.max(1, cfg.getInt("scan-interval-seconds", 5));
        alertCooldownSeconds = cfg.getLong("alert-cooldown-seconds", 60);
        logToConsole         = cfg.getBoolean("log-to-console", true);
        debug                = cfg.getBoolean("debug", false);
        headUrl              = cfg.getString("head-url", "https://mc-heads.net/avatar/{name}/64");

        // Auto-ban
        autoBanEnabled       = cfg.getBoolean("auto-ban.enabled", false);
        autoBanCommand       = cfg.getString("auto-ban.command", "ban {player} {reason}");
        autoBanReason        = cfg.getString("auto-ban.reason", "Suspicious item stash detected by OverStackDupeFinder");
        autoBanNotifyDiscord = cfg.getBoolean("auto-ban.notify-discord", true);

        // Whitelist
        whitelistedPlayers   = new ArrayList<>();
        List<String> rawWhitelist = cfg.getStringList("whitelisted-players");
        for (String p : rawWhitelist) {
            if (p != null && !p.isBlank()) {
                whitelistedPlayers.add(p.trim().toLowerCase());
            }
        }

        if (autoBanEnabled) {
            plugin.getLogger().info("[AutoBan] Enabled — command: " + autoBanCommand);
        }

        // ── Parse monitored-items ──────────────────────────────────────────────
        monitoredItems = new ArrayList<>();
        ConfigurationSection sec = cfg.getConfigurationSection("monitored-items");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                ConfigurationSection entry = sec.getConfigurationSection(key);
                if (entry == null) continue;

                // Material (optional)
                Material mat = null;
                String matName = entry.getString("material", "");
                if (!matName.isBlank()) {
                    try {
                        mat = Material.valueOf(matName.toUpperCase());
                    } catch (IllegalArgumentException e) {
                        plugin.getLogger().warning(
                            "Unknown material '" + matName + "' in monitored-items." + key + " — skipping material.");
                    }
                }

                // custom-names (renamed from name-keywords for clarity)
                List<String> keywords = entry.getStringList("custom-names")
                        .stream()
                        .map(String::toLowerCase)
                        .toList();

                // Alert thresholds (raw item count)
                int threshInv   = entry.getInt("alert-in-inventory", 10);
                int threshShulk = entry.getInt("alert-in-shulker", 5);

                // Per-item auto-ban config
                int    banLimit  = entry.getInt("ban-limit", -1);    // -1 = disabled
                String banReason = entry.getString("ban-reason", ""); // blank = use global reason

                MonitoredItem mi = new MonitoredItem(key, mat, keywords, threshInv, threshShulk, banLimit, banReason);
                monitoredItems.add(mi);

                if (debug) {
                    plugin.getLogger().info("[DEBUG] Loaded monitored item: " + mi);
                }
            }
        }

        // Fallback if nothing configured
        if (monitoredItems.isEmpty()) {
            plugin.getLogger().warning(
                "No monitored-items configured! Defaulting to SPAWNER (inv≥640, shulker≥320).");
            monitoredItems.add(new MonitoredItem(
                "spawner-default", Material.SPAWNER, List.of("spawner"), 640, 320, -1, ""
            ));
        }

        if (webhookUrl.isBlank() || webhookUrl.contains("YOUR_WEBHOOK")) {
            plugin.getLogger().warning("webhook-url is not set! Alerts will only be logged to console.");
        } else {
            plugin.getLogger().info("Webhook URL configured OK.");
        }

        if (debug) {
            plugin.getLogger().info("[DEBUG] scan-player-inventory: " + scanPlayerInventory);
            plugin.getLogger().info("[DEBUG] scan-shulker-contents: " + scanShulkerContents);
            plugin.getLogger().info("[DEBUG] scan-chests-loose-items: " + scanChestsLooseItems);
            plugin.getLogger().info("[DEBUG] scan-chests-shulkers: " + scanChestsShulkers);
            plugin.getLogger().info("[DEBUG] monitored-items loaded: " + monitoredItems.size());
        }
    }

    public String              getWebhookUrl()          { return webhookUrl; }
    public List<MonitoredItem> getMonitoredItems()      { return Collections.unmodifiableList(monitoredItems); }
    public boolean             isScanPlayerInventory()  { return scanPlayerInventory; }
    public boolean             isScanShulkerContents()  { return scanShulkerContents; }  // player inventory shulkers
    public boolean             isScanChestsLooseItems() { return scanChestsLooseItems; }
    public boolean             isScanChestsShulkers()   { return scanChestsShulkers; }
    /** True if either chest-scan mode is enabled (used by ChestListener). */
    public boolean             isScanChests()           { return scanChestsLooseItems || scanChestsShulkers; }
    public int                 getScanIntervalSeconds() { return scanIntervalSeconds; }
    public long                getAlertCooldownSeconds(){ return alertCooldownSeconds; }
    public boolean             isLogToConsole()         { return logToConsole; }
    public boolean             isDebug()                { return debug; }
    public String              getHeadUrl()             { return headUrl; }
    // Auto-ban
    public boolean             isAutoBanEnabled()       { return autoBanEnabled; }
    public String              getAutoBanCommand()      { return autoBanCommand; }
    public String              getAutoBanReason()       { return autoBanReason; }
    public boolean             isAutoBanNotifyDiscord() { return autoBanNotifyDiscord; }
    // Whitelist
    public List<String>        getWhitelistedPlayers()  { return Collections.unmodifiableList(whitelistedPlayers); }
    public boolean             isWhitelisted(String name) {
        if (name == null) return false;
        return whitelistedPlayers.contains(name.trim().toLowerCase());
    }
}
