package com.overstackdupefinder.listeners;

import com.overstackdupefinder.OverStackDupeFinder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Event-driven inventory listener with a <strong>debouncer</strong>.
 *
 * <h3>Threading model</h3>
 * <pre>
 *  Main thread   (event fires)
 *    → debounce check: is a scan already pending for this player?  [~nanoseconds]
 *    → if not, mark pending + schedule 1-tick task               [~nanoseconds]
 *  Main thread   (1 tick later, ~50 ms)
 *    → snapshotAndScanPlayer: copies inventory array              [~microseconds]
 *    → clears pending flag
 *  Virtual thread (immediately after snapshot)
 *    → analyzeSnapshot → sendAlert (HTTP)                        [off main thread]
 * </pre>
 *
 * <p>No matter how fast a player clicks, at most <em>one</em> pending main-thread
 * task exists per player at any time.</p>
 */
public class InventoryListener implements Listener {

    private final OverStackDupeFinder plugin;

    /**
     * Set of player UUIDs that already have a pending 1-tick scan task.
     * Used to debounce rapid inventory events.
     */
    private final Set<UUID> pending = ConcurrentHashMap.newKeySet();

    public InventoryListener(OverStackDupeFinder plugin) {
        this.plugin = plugin;
    }

    // ── Events ────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemHeld(PlayerItemHeldEvent event) {
        scheduleScan(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player p) scheduleScan(p);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player p) scheduleScan(p);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player p) scheduleScan(p);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        // 2-tick delay so inventory is fully loaded before we snapshot it
        Player p = event.getPlayer();
        pending.add(p.getUniqueId());
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            pending.remove(p.getUniqueId());
            if (p.isOnline()) {
                plugin.getInventoryScanner().snapshotAndScanPlayer(p);
            }
        }, 2L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        // Clean up so the UUID doesn't linger in the pending set
        pending.remove(event.getPlayer().getUniqueId());
    }

    // ── Debounced scanner ────────────────────────────────────────────────────

    /**
     * Schedules a 1-tick-delayed snapshot+scan for {@code player},
     * but only if no scan is already pending for them.
     * This collapses any number of rapid events into a single main-thread task.
     */
    private void scheduleScan(Player player) {
        UUID id = player.getUniqueId();
        // ConcurrentHashSet.add() returns false if already present → skip
        if (!pending.add(id)) return;

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            pending.remove(id);
            if (player.isOnline()) {
                // Main thread: snapshot (microseconds), then async analysis
                plugin.getInventoryScanner().snapshotAndScanPlayer(player);
            }
        }, 1L);
    }
}
