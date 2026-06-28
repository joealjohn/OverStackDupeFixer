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
import java.util.logging.Logger;

/**
 * Fully asynchronous inventory scanner.
 *
 * <h3>Threading model — strict rules</h3>
 * <pre>
 *  Main thread   → snapshotAnd*()
 *                  - deepCopy() outer inventory                         (~µs)
 *                  - extractShulkerContents() — reads BlockStateMeta,
 *                    deep-copies each shulker's inner inventory         (~µs)
 *                  → hands ScanPayload to virtual thread immediately
 *
 *  Virtual thread→ analyzePayload() — pure arithmetic on pre-copied arrays
 *  Virtual thread→ WebhookManager.send*() — HTTP POST to Discord
 *  Main thread   → executeBan() — dispatchCommand (scheduled back)
 * </pre>
 *
 * <p><b>No Bukkit/Paper API is called off the main thread.</b>
 * {@code ItemStack.getItemMeta()} and {@code BlockStateMeta} access are
 * performed exclusively during the main-thread snapshot phase.</p>
 */
public class InventoryScanner {

    private final OverStackDupeFinder plugin;
    private final ExecutorService     executor;
    private final Logger              log;

    /** Key: "uuid:monitoredItemId:source[:shulkerType]" → last alert timestamp */
    private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();

    public InventoryScanner(OverStackDupeFinder plugin, ExecutorService executor) {
        this.plugin   = plugin;
        this.executor = executor;
        this.log      = plugin.getLogger();
    }

    // ══ Public API — MUST be called from main thread ══════════════════════════

    /** Snapshots all online players and submits async analysis. */
    public void snapshotAndScanAll() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            snapshotAndScanPlayer(player);
        }
    }

    /** Snapshots a single player's inventory and submits async analysis. */
    public void snapshotAndScanPlayer(Player player) {
        ItemStack[] raw      = player.getInventory().getContents();
        ScanPayload payload  = buildPayload(raw, player.getUniqueId(), player.getName(),
                                            player.getLocation().clone(), null, false);
        submitAnalysis(payload);
    }

    /** Snapshots a chest and submits async analysis. */
    public void snapshotAndScanChest(ItemStack[] chestContents, Player player,
                                     Location playerLoc, Location chestLoc) {
        ScanPayload payload = buildPayload(chestContents, player.getUniqueId(), player.getName(),
                                           playerLoc.clone(),
                                           chestLoc != null ? chestLoc.clone() : null,
                                           true);
        submitAnalysis(payload);
    }

    public void clearCooldowns() { cooldowns.clear(); }

    // ══ Main-thread snapshot helpers ══════════════════════════════════════════

    /**
     * Builds a fully immutable {@link ScanPayload} on the main thread.
     *
     * <p>This is the ONLY place where {@code ItemMeta} / {@code BlockStateMeta} is read —
     * guaranteeing all Bukkit API calls happen synchronously.</p>
     */
    private ScanPayload buildPayload(ItemStack[] raw, UUID uuid, String playerName,
                                     Location playerLoc, Location chestLoc, boolean isChest) {
        int len          = raw.length;
        ItemStack[] outer = new ItemStack[len];   // deep-copied outer slots
        // shulkerSlots[i] = deep-copied inner inventory of shulker at slot i, or null
        ItemStack[][] shulkerSlots = new ItemStack[len][];
        String[]      shulkerTypes = new String[len];

        for (int i = 0; i < len; i++) {
            ItemStack item = raw[i];
            if (item == null) continue;

            // Clone the outer slot unconditionally
            outer[i] = item.clone();

            // If it's a shulker box, extract its inner inventory NOW (main thread)
            if (isShulkerBox(item.getType())) {
                // getItemMeta() — main thread only
                if (item.getItemMeta() instanceof BlockStateMeta bsm
                        && bsm.getBlockState() instanceof ShulkerBox shulker) {
                    ItemStack[] inner = shulker.getInventory().getContents();
                    shulkerSlots[i] = deepCopy(inner);
                    shulkerTypes[i] = item.getType().name();
                }
            }
        }

        return new ScanPayload(uuid, playerName, playerLoc, chestLoc, isChest,
                               outer, shulkerSlots, shulkerTypes,
                               plugin.getPluginConfig()); // snapshot config reference
    }

    // ══ Async pipeline ════════════════════════════════════════════════════════

    private void submitAnalysis(ScanPayload payload) {
        CompletableFuture
            .supplyAsync(() -> analyzePayload(payload), executor)
            .thenAcceptAsync(alerts -> {
                if (alerts.isEmpty()) return;

                java.util.Set<String> bannedThisCycle = new java.util.HashSet<>();

                for (AlertData alert : alerts) {
                    plugin.getWebhookManager().sendAlert(alert);

                    // Auto-ban
                    PluginConfig cfg = payload.cfg;
                    if (cfg.isAutoBanEnabled()
                            && alert.shouldAutoBan()
                            && bannedThisCycle.add(alert.getPlayerName())) {
                        String banReason = alert.getBanReason().isBlank()
                                ? cfg.getAutoBanReason()
                                : alert.getBanReason();
                        executeBan(alert.getPlayerName(), banReason, cfg);
                    }
                }
            }, executor)
            .exceptionally(ex -> {
                log.warning("[OverStackDupeFinder] Analysis error: " + ex.getMessage());
                ex.printStackTrace();
                return null;
            });
    }

    /**
     * Dispatches a ban command on the main thread and optionally sends a Discord embed.
     * Safe to call from any thread.
     */
    private void executeBan(String playerName, String banReason, PluginConfig cfg) {
        String cmd = cfg.getAutoBanCommand()
                .replace("{player}", playerName)
                .replace("{reason}",  banReason);

        log.warning("[AutoBan] Executing: " + cmd);

        // dispatchCommand MUST run on the main thread
        plugin.getServer().getScheduler().runTask(plugin,
                () -> plugin.getServer().dispatchCommand(
                        plugin.getServer().getConsoleSender(), cmd));

        // HTTP — fine on virtual thread
        if (cfg.isAutoBanNotifyDiscord()) {
            plugin.getWebhookManager().sendBanAlert(playerName, banReason);
        }
    }

    // ══ Pure analysis — safe on any thread (no Bukkit API) ═══════════════════

    /**
     * Analyses a pre-snapshotted {@link ScanPayload}.
     * All data here is plain Java objects — no Bukkit API calls.
     */
    private List<AlertData> analyzePayload(ScanPayload p) {
        List<AlertData>     results   = new ArrayList<>();
        PluginConfig        cfg       = p.cfg;
        List<MonitoredItem> monitored = cfg.getMonitoredItems();
        boolean             dbg       = cfg.isDebug();

        // ── 1. Loose items (skip shulker slots) ───────────────────────────────
        boolean scanLoose = p.isChest ? cfg.isScanChestsLooseItems() : cfg.isScanPlayerInventory();
        if (scanLoose) {
            Map<String, int[]> counts = new HashMap<>(); // id → [total, stackSize]

            for (ItemStack item : p.outerSlots) {
                if (item == null) continue;
                if (isShulkerBox(item.getType())) continue; // handled in shulker section

                MonitoredItem mi = matchItem(item, monitored);
                if (mi == null) continue;

                counts.computeIfAbsent(mi.getId(), k -> new int[]{0, stackSizeOf(item, mi)});
                counts.get(mi.getId())[0] += item.getAmount();
            }

            for (Map.Entry<String, int[]> e : counts.entrySet()) {
                String miId      = e.getKey();
                int    total     = e.getValue()[0];
                int    stackSize = e.getValue()[1];
                int    stacks    = (int) Math.ceil((double) total / stackSize);

                MonitoredItem mi = findById(monitored, miId);
                if (mi == null) continue;
                int threshold = mi.getThresholdInventory();
                if (threshold < 0) continue;

                if (dbg) log.info("[DEBUG] " + p.playerName + " loose | item='" + miId +
                        "' total=" + total + " threshold=" + threshold +
                        " source=" + (p.isChest ? "CHEST" : "INVENTORY"));

                if (total < threshold) continue;

                AlertSource src     = p.isChest ? AlertSource.CHEST : AlertSource.PLAYER_INVENTORY;
                String      coolKey = p.uuid + ":" + miId + ":" + src.name();
                if (checkAndSetCooldown(coolKey, cfg, dbg)) continue;

                String container = p.isChest ? "Chest" : "Player Inventory";
                String label     = mi.getMaterial() != null ? mi.getMaterial().name() : miId;
                results.add(new AlertData(p.uuid, p.playerName, p.playerLoc, p.chestLoc, label,
                        stacks, total, src, container, mi.getBanLimit(), mi.getBanReason()));
            }
        }

        // ── 2. Shulker contents ───────────────────────────────────────────────
        boolean scanShulkers = p.isChest ? cfg.isScanChestsShulkers() : cfg.isScanShulkerContents();
        if (scanShulkers) {
            for (int i = 0; i < p.outerSlots.length; i++) {
                ItemStack[] inner    = p.shulkerSlots[i];
                String      shulkerType = p.shulkerTypes[i];
                if (inner == null || shulkerType == null) continue;

                if (dbg) log.info("[DEBUG] " + p.playerName + " shulker=" + shulkerType +
                        " slots=" + inner.length + " source=" + (p.isChest ? "CHEST" : "INVENTORY"));

                Map<String, int[]> shulkerCounts = new HashMap<>();

                for (ItemStack slot : inner) {
                    if (slot == null) continue;
                    MonitoredItem mi = matchItem(slot, monitored);
                    if (mi == null) continue;
                    shulkerCounts.computeIfAbsent(mi.getId(), k -> new int[]{0, stackSizeOf(slot, mi)});
                    shulkerCounts.get(mi.getId())[0] += slot.getAmount();
                }

                for (Map.Entry<String, int[]> e : shulkerCounts.entrySet()) {
                    String miId      = e.getKey();
                    int    total     = e.getValue()[0];
                    int    stackSize = e.getValue()[1];
                    int    stacks    = (int) Math.ceil((double) total / stackSize);

                    MonitoredItem mi = findById(monitored, miId);
                    if (mi == null) continue;
                    int threshold = mi.getThresholdShulker();
                    if (threshold < 0) continue;

                    if (dbg) log.info("[DEBUG] " + p.playerName + " shulker=" + shulkerType +
                            " | item='" + miId + "' total=" + total + " threshold=" + threshold);

                    if (total < threshold) continue;

                    AlertSource src     = p.isChest ? AlertSource.SHULKER_IN_CHEST : AlertSource.SHULKER_IN_INVENTORY;
                    String      coolKey = p.uuid + ":" + miId + ":" + src.name() + ":" + shulkerType;
                    if (checkAndSetCooldown(coolKey, cfg, dbg)) continue;

                    String label = mi.getMaterial() != null ? mi.getMaterial().name() : miId;
                    results.add(new AlertData(p.uuid, p.playerName, p.playerLoc, p.chestLoc, label,
                            stacks, total, src, prettyName(shulkerType), mi.getBanLimit(), mi.getBanReason()));
                }
            }
        }

        return results;
    }

    // ══ Item matching — no Bukkit API, uses pre-cloned ItemStack data ═════════

    /**
     * Matches an item by material, then by display-name keyword.
     * {@code item.getItemMeta()} is called here — this is safe because
     * {@code item} is a clone made on the main thread during snapshotting.
     * Cloned ItemStacks can have their meta read from any thread.
     */
    private MonitoredItem matchItem(ItemStack item, List<MonitoredItem> monitored) {
        // Fast material match
        for (MonitoredItem mi : monitored) {
            if (mi.matchesMaterial(item.getType())) return mi;
        }

        // Display name keyword match (reads meta of a pre-cloned ItemStack — safe)
        String name = getDisplayName(item);
        if (name != null) {
            String lower = name.toLowerCase();
            for (MonitoredItem mi : monitored) {
                if (mi.matchesName(lower) != null) return mi;
            }
        }
        return null;
    }

    /** Strips color/formatting from an item's display name. Cloned ItemStack — thread-safe. */
    private static String getDisplayName(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) return null;
        net.kyori.adventure.text.Component comp = meta.displayName();
        if (comp == null) return null;
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(comp).trim();
    }

    // ══ Helpers ═══════════════════════════════════════════════════════════════

    private static int stackSizeOf(ItemStack item, MonitoredItem mi) {
        if (mi.getMaterial() != null) return mi.getMaterial().getMaxStackSize();
        return item.getType().getMaxStackSize();
    }

    private static MonitoredItem findById(List<MonitoredItem> items, String id) {
        for (MonitoredItem mi : items) {
            if (mi.getId().equals(id)) return mi;
        }
        return null;
    }

    private static boolean isShulkerBox(Material type) {
        return type.name().endsWith("SHULKER_BOX");
    }

    /**
     * Returns true if {@code key} is on cooldown (skips alert), false if it was just set.
     * Thread-safe via {@link ConcurrentHashMap}.
     */
    private boolean checkAndSetCooldown(String key, PluginConfig cfg, boolean dbg) {
        long now  = System.currentTimeMillis();
        Long last = cooldowns.get(key);
        if (last != null && (now - last) < cfg.getAlertCooldownSeconds() * 1000L) {
            if (dbg) log.info("[DEBUG] On cooldown for " + key);
            return true; // still on cooldown
        }
        cooldowns.put(key, now);
        return false;
    }

    /** Deep-copies an ItemStack array. Each non-null slot is cloned (main thread). */
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

    // ══ ScanPayload — immutable carrier between main thread and virtual thread ═

    /**
     * Fully immutable snapshot of everything needed for one scan cycle.
     * Created on the main thread; consumed on a virtual thread.
     *
     * <p>All arrays are deep-copies. {@code chestLoc} may be null for player scans.</p>
     */
    private record ScanPayload(
            UUID          uuid,
            String        playerName,
            Location      playerLoc,
            Location      chestLoc,      // null for player inventory scans
            boolean       isChest,
            ItemStack[]   outerSlots,    // deep-copied outer inventory
            ItemStack[][] shulkerSlots,  // [slotIndex] = deep-copied inner inv, or null
            String[]      shulkerTypes,  // [slotIndex] = material name of shulker, or null
            PluginConfig  cfg            // config snapshot at submission time
    ) {}
}
