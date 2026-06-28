package com.overstackdupefinder.scanner;

import com.overstackdupefinder.OverStackDupeFinder;
import com.overstackdupefinder.config.MonitoredItem;
import com.overstackdupefinder.config.PluginConfig;
import com.overstackdupefinder.model.AlertData;
import com.overstackdupefinder.model.AlertData.AlertSource;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * Fully asynchronous inventory scanner.
 *
 * <h3>Detection scopes</h3>
 * <ol>
 *   <li><b>Loose inventory</b>: counts monitored items directly in the player's
 *       inventory (not inside shulkers). Threshold: {@code threshold-inventory}.</li>
 *   <li><b>Shulker contents</b>: opens each shulker box found in the inventory
 *       (or chest) and counts monitored items inside. Threshold: {@code threshold-shulker}.</li>
 *   <li><b>Chest contents</b>: when a player opens a chest/barrel, the same
 *       logic runs on that container's slots.</li>
 * </ol>
 *
 * <h3>Threading model</h3>
 * <pre>
 *  Main thread   → snapshotAnd*()   — takes array copy of inventory  (~µs)
 *  Virtual thread→ analyzeSnapshot() — reads ItemMeta / counts items  (CPU only)
 *  Virtual thread→ WebhookManager   — HTTP POST to Discord
 * </pre>
 */
public class InventoryScanner {

    private final OverStackDupeFinder plugin;
    private final ExecutorService executor;

    /** Key: "uuid:monitoredItemId:source" */
    private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();

    public InventoryScanner(OverStackDupeFinder plugin, ExecutorService executor) {
        this.plugin   = plugin;
        this.executor = executor;
    }

    // ── Public API (main thread) ───────────────────────────────────────────────

    /** Snapshots all online players and submits async analysis. Called from timer. */
    public void snapshotAndScanAll() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            snapshotAndScanPlayer(player);
        }
    }

    /** Snapshots a single player's inventory and submits async analysis. */
    public void snapshotAndScanPlayer(Player player) {
        // MUST snapshot on main thread — getContents() returns a copy of the array,
        // but each ItemStack in it is a reference. We clone what we need.
        ItemStack[] raw = player.getInventory().getContents();
        ItemStack[] snapshot = deepCopy(raw);

        UUID     uuid = player.getUniqueId();
        String   name = player.getName();
        Location loc  = player.getLocation().clone();

        submitAnalysis(snapshot, uuid, name, loc, false);
    }

    /**
     * Snapshots a chest inventory and submits async analysis.
     *
     * @param chestContents the chest's inventory contents
     * @param player        the player who opened the chest
     * @param playerLoc     the player's location at the time of opening
     * @param chestLoc      the chest block's location (shown separately in Discord alert)
     */
    public void snapshotAndScanChest(ItemStack[] chestContents, Player player,
                                     Location playerLoc, Location chestLoc) {
        ItemStack[] snapshot = deepCopy(chestContents);
        submitAnalysis(snapshot, player.getUniqueId(), player.getName(),
                playerLoc.clone(), chestLoc != null ? chestLoc.clone() : null, true);
    }

    public void clearCooldowns() { cooldowns.clear(); }

    // ── Async pipeline ────────────────────────────────────────────────────────

    private void submitAnalysis(ItemStack[] snapshot, UUID uuid, String playerName,
                                 Location loc, boolean isChest) {
        submitAnalysis(snapshot, uuid, playerName, loc, null, isChest);
    }

    private void submitAnalysis(ItemStack[] snapshot, UUID uuid, String playerName,
                                 Location playerLoc, Location chestLoc, boolean isChest) {
        CompletableFuture
            .supplyAsync(() -> analyzeSnapshot(snapshot, uuid, playerName, playerLoc, chestLoc, isChest), executor)
            .thenAccept(alerts -> {
                if (alerts.isEmpty()) return;

                // Track players already banned in this batch to avoid duplicate bans
                java.util.Set<String> bannedThisCycle = new java.util.HashSet<>();

                for (AlertData alert : alerts) {
                    plugin.getWebhookManager().sendAlert(alert);

                                    // Auto-ban — dispatched on main thread (Bukkit requirement)
                    PluginConfig cfg = plugin.getPluginConfig();
                    if (cfg.isAutoBanEnabled() && alert.shouldAutoBan()
                            && bannedThisCycle.add(alert.getPlayerName())) {
                        // Use item-specific reason if set, otherwise global reason
                        String banReason = alert.getBanReason().isBlank()
                                ? cfg.getAutoBanReason()
                                : alert.getBanReason();
                        executeBan(alert.getPlayerName(), banReason, cfg);
                    }
                }
            })
            .exceptionally(ex -> {
                plugin.getLogger().warning("[OverStackDupeFinder] Analysis error: " + ex.getMessage());
                ex.printStackTrace();
                return null;
            });
    }

    /**
     * Schedules a console command on the main thread to ban a player.
     * Also sends a Discord ban embed if configured.
     * Safe to call from any thread.
     */
    private void executeBan(String playerName, String banReason, PluginConfig cfg) {
        String cmd = cfg.getAutoBanCommand()
                .replace("{player}", playerName)
                .replace("{reason}",  banReason);

        plugin.getLogger().warning("[AutoBan] Executing: " + cmd);

        // dispatchCommand MUST run on the main thread
        plugin.getServer().getScheduler().runTask(plugin, () ->
            plugin.getServer().dispatchCommand(
                plugin.getServer().getConsoleSender(), cmd)
        );

        // Send green Discord ban embed (runs on this virtual thread — HTTP is fine)
        if (cfg.isAutoBanNotifyDiscord()) {
            plugin.getWebhookManager().sendBanAlert(playerName, banReason);
        }
    }

    /**
     * Pure analysis — safe to run on any thread.
     * Scans both loose items in the container AND items inside shulker boxes.
     */
    private List<AlertData> analyzeSnapshot(ItemStack[] contents, UUID uuid, String playerName,
                                             Location playerLoc, Location chestLoc, boolean isChest) {
        List<AlertData>      results  = new ArrayList<>();
        PluginConfig         cfg      = plugin.getPluginConfig();
        List<MonitoredItem>  monitored = cfg.getMonitoredItems();
        boolean              dbg      = cfg.isDebug();

        // ── 1. Count LOOSE items (not inside shulkers) ────────────────────────
        boolean scanLoose = isChest ? cfg.isScanChestsLooseItems() : cfg.isScanPlayerInventory();
        if (scanLoose) {
            Map<String, int[]> looseCounts = new HashMap<>(); // monitoredItem.id → [totalItems, stackSize]

            for (ItemStack item : contents) {
                if (item == null) continue;
                if (isShulkerBox(item.getType())) continue; // shulkers handled below

                MonitoredItem matched = matchItem(item, monitored);
                if (matched == null) continue;

                looseCounts.computeIfAbsent(matched.getId(), k -> new int[]{0, resolveStackSize(item, matched)});
                looseCounts.get(matched.getId())[0] += item.getAmount();
            }

            for (Map.Entry<String, int[]> entry : looseCounts.entrySet()) {
                String miId      = entry.getKey();
                int    total     = entry.getValue()[0];
                int    stackSize = entry.getValue()[1];
                int    stacks    = (int) Math.ceil((double) total / stackSize);

                MonitoredItem mi = findById(monitored, miId);
                if (mi == null) continue;
                int threshold = mi.getThresholdInventory();
                if (threshold < 0) continue; // disabled

                if (dbg) {
                    plugin.getLogger().info("[DEBUG] " + playerName + " loose | item='" + miId +
                            "' total=" + total + " threshold=" + threshold +
                            " source=" + (isChest ? "CHEST" : "INVENTORY"));
                }

                if (total < threshold) continue; // threshold is raw item count

                AlertSource src = isChest ? AlertSource.CHEST : AlertSource.PLAYER_INVENTORY;
                String coolKey  = uuid + ":" + miId + ":" + src.name();
                if (isOnCooldown(coolKey)) {
                    if (dbg) plugin.getLogger().info("[DEBUG] On cooldown for " + coolKey);
                    continue;
                }
                setCooldown(coolKey);

                String container = isChest ? "Chest" : "Player Inventory";
                String label     = mi.getMaterial() != null ? mi.getMaterial().name() : miId;

                results.add(new AlertData(uuid, playerName, playerLoc, chestLoc, label,
                        stacks, total, src, container, mi.getBanLimit(), mi.getBanReason()));
            }
        }

        // ── 2. Scan INSIDE shulker boxes ──────────────────────────────────────
        boolean scanShulkers = isChest ? cfg.isScanChestsShulkers() : cfg.isScanShulkerContents();
        if (scanShulkers) {
            for (ItemStack item : contents) {
                if (item == null) continue;
                if (!isShulkerBox(item.getType())) continue;

                // Peek inside the shulker — getItemMeta() returns a copy, thread-safe
                if (!(item.getItemMeta() instanceof BlockStateMeta bsm)) continue;
                if (!(bsm.getBlockState() instanceof ShulkerBox shulker)) continue;

                ItemStack[] inner = shulker.getInventory().getContents();
                String shulkerName = item.getType().name();

                if (dbg) {
                    plugin.getLogger().info("[DEBUG] " + playerName + " shulker=" +
                            shulkerName + " slots=" + inner.length +
                            " source=" + (isChest ? "CHEST" : "INVENTORY"));
                }

                // Count monitored items inside this shulker
                Map<String, int[]> shulkerCounts = new HashMap<>();

                for (ItemStack slot : inner) {
                    if (slot == null) continue;

                    MonitoredItem matched = matchItem(slot, monitored);
                    if (matched == null) continue;

                    shulkerCounts.computeIfAbsent(matched.getId(), k -> new int[]{0, resolveStackSize(slot, matched)});
                    shulkerCounts.get(matched.getId())[0] += slot.getAmount();
                }

                for (Map.Entry<String, int[]> entry : shulkerCounts.entrySet()) {
                    String miId      = entry.getKey();
                    int    total     = entry.getValue()[0];
                    int    stackSize = entry.getValue()[1];
                    int    stacks    = (int) Math.ceil((double) total / stackSize);

                    MonitoredItem mi = findById(monitored, miId);
                    if (mi == null) continue;
                    int threshold = mi.getThresholdShulker();
                    if (threshold < 0) continue; // disabled

                    if (dbg) {
                        plugin.getLogger().info("[DEBUG] " + playerName + " shulker=" + shulkerName +
                                " | item='" + miId + "' total=" + total + " threshold=" + threshold);
                    }

                    if (total < threshold) continue; // threshold is raw item count

                    AlertSource src = isChest ? AlertSource.SHULKER_IN_CHEST : AlertSource.SHULKER_IN_INVENTORY;
                    String coolKey  = uuid + ":" + miId + ":" + src.name() + ":" + shulkerName;
                    if (isOnCooldown(coolKey)) {
                        if (dbg) plugin.getLogger().info("[DEBUG] On cooldown for " + coolKey);
                        continue;
                    }
                    setCooldown(coolKey);

                    String label = mi.getMaterial() != null ? mi.getMaterial().name() : miId;

                    results.add(new AlertData(uuid, playerName, playerLoc, chestLoc, label,
                            stacks, total, src, prettyName(shulkerName), mi.getBanLimit(), mi.getBanReason()));
                }
            }
        }

        return results;
    }

    // ── Item matching ─────────────────────────────────────────────────────────

    /**
     * Checks if an item matches any monitored item — by material first, then by name keywords.
     * Returns the first matching MonitoredItem, or null.
     */
    private MonitoredItem matchItem(ItemStack item, List<MonitoredItem> monitored) {
        // 1. Material match (fast)
        for (MonitoredItem mi : monitored) {
            if (mi.matchesMaterial(item.getType())) return mi;
        }

        // 2. Display name keyword match
        String displayName = getDisplayName(item);
        if (displayName != null) {
            String lower = displayName.toLowerCase();
            for (MonitoredItem mi : monitored) {
                if (mi.matchesName(lower) != null) return mi;
            }
        }

        return null;
    }

    /**
     * Gets the plain display name of an item using the Adventure API, or null if it has none.
     * Uses Adventure's {@code PlainTextComponentSerializer} so color/formatting is stripped.
     */
    private static String getDisplayName(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) return null;
        // Adventure API — returns plain text with all formatting stripped
        net.kyori.adventure.text.Component comp = meta.displayName();
        if (comp == null) return null;
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(comp).trim();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private int resolveStackSize(ItemStack item, MonitoredItem mi) {
        if (mi.getMaterial() != null) return mi.getMaterial().getMaxStackSize();
        return item.getType().getMaxStackSize();
    }

    private MonitoredItem findById(List<MonitoredItem> items, String id) {
        for (MonitoredItem mi : items) {
            if (mi.getId().equals(id)) return mi;
        }
        return null;
    }

    private static boolean isShulkerBox(Material type) {
        return type.name().endsWith("SHULKER_BOX");
    }

    private boolean isOnCooldown(String key) {
        Long last = cooldowns.get(key);
        if (last == null) return false;
        return (System.currentTimeMillis() - last) < plugin.getPluginConfig().getAlertCooldownSeconds() * 1000L;
    }

    private void setCooldown(String key) {
        cooldowns.put(key, System.currentTimeMillis());
    }

    /**
     * Deep-copies an ItemStack array. Each non-null element is cloned.
     * This ensures complete thread-safety for async analysis.
     */
    private static ItemStack[] deepCopy(ItemStack[] source) {
        ItemStack[] copy = new ItemStack[source.length];
        for (int i = 0; i < source.length; i++) {
            if (source[i] != null) copy[i] = source[i].clone();
        }
        return copy;
    }

    private static String prettyName(String materialName) {
        String[] parts = materialName.split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!part.isEmpty()) {
                if (!sb.isEmpty()) sb.append(' ');
                sb.append(Character.toUpperCase(part.charAt(0)))
                  .append(part.substring(1).toLowerCase());
            }
        }
        return sb.toString();
    }
}
