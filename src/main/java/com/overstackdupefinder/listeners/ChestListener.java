package com.overstackdupefinder.listeners;

import com.overstackdupefinder.OverStackDupeFinder;
import org.bukkit.Location;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * Scans chest/barrel inventories when a player opens them.
 *
 * <p>When a chest alert fires, <em>both</em> the player's coordinates and the
 * chest's block coordinates are recorded so the Discord embed can show both.</p>
 *
 * <h3>Threading model</h3>
 * <pre>
 *  Main thread (InventoryOpenEvent)
 *    → getContents() — returns a copy of the slot array             [~microseconds]
 *    → snapshotAndScanChest() — hands the copy to virtual thread    [returns immediately]
 *  Virtual thread
 *    → analyzeSnapshot → sendAlert (HTTP)
 * </pre>
 */
public class ChestListener implements Listener {

    private final OverStackDupeFinder plugin;

    public ChestListener(OverStackDupeFinder plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!plugin.getPluginConfig().isScanChests()) return;
        if (!(event.getPlayer() instanceof Player player)) return;

        Inventory inv = event.getInventory();
        InventoryType type = inv.getType();
        if (type != InventoryType.CHEST && type != InventoryType.BARREL) return;

        // getContents() returns a defensive copy — snapshot is instant
        ItemStack[] snapshot  = inv.getContents();
        Location    playerLoc = player.getLocation().clone();
        Location    chestLoc  = resolveChestLocation(inv);  // block coords of the chest

        // Hand to virtual thread immediately; main thread is free
        plugin.getInventoryScanner().snapshotAndScanChest(snapshot, player, playerLoc, chestLoc);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns the chest block's location, or null if we can't determine it
     * (e.g., a plugin-created inventory without a block holder).
     */
    private Location resolveChestLocation(Inventory inv) {
        InventoryHolder holder = inv.getHolder();
        if (holder instanceof DoubleChest dc) return dc.getLocation();
        if (holder instanceof Chest chest)   return chest.getBlock().getLocation();
        return null;
    }
}
