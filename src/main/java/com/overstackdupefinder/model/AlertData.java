package com.overstackdupefinder.model;

import org.bukkit.Location;

import java.util.UUID;

/**
 * Immutable value object describing a single alert event.
 *
 * <p>Holds only plain data — safe to pass across threads.</p>
 *
 * <p>For chest-based alerts, {@link #chestLocation} is non-null and holds
 * the exact block coordinates of the chest. {@link #location} always holds
 * the player's location at the time of the event.</p>
 *
 * <p>{@link #banLimit} and {@link #banReason} are copied from the item's config
 * so the async pipeline can decide whether to auto-ban without re-reading config.</p>
 */
public final class AlertData {

    private final UUID       playerUuid;
    private final String     playerName;
    private final Location   location;        // player location
    private final Location   chestLocation;   // chest block location, null for inventory alerts
    private final String     itemLabel;       // human-readable item name
    private final int        stackCount;
    private final int        totalItemCount;
    private final AlertSource source;
    private final String     container;       // e.g. "Player Inventory", "Purple Shulker Box"
    private final int        banLimit;        // from MonitoredItem — item count for auto-ban (-1=off)
    private final String     banReason;       // from MonitoredItem — per-item ban reason (blank=global)

    public AlertData(
            UUID playerUuid,
            String playerName,
            Location location,
            Location chestLocation,
            String itemLabel,
            int stackCount,
            int totalItemCount,
            AlertSource source,
            String container,
            int banLimit,
            String banReason
    ) {
        this.playerUuid    = playerUuid;
        this.playerName    = playerName;
        this.location      = location;
        this.chestLocation = chestLocation;
        this.itemLabel     = itemLabel;
        this.stackCount    = stackCount;
        this.totalItemCount = totalItemCount;
        this.source        = source;
        this.container     = container;
        this.banLimit      = banLimit;
        this.banReason     = banReason == null ? "" : banReason;
    }

    public UUID       getPlayerUuid()    { return playerUuid; }
    public String     getPlayerName()    { return playerName; }
    public Location   getLocation()      { return location; }
    public Location   getChestLocation() { return chestLocation; }
    public String     getItemLabel()     { return itemLabel; }
    public int        getStackCount()    { return stackCount; }
    public int        getTotalItemCount(){ return totalItemCount; }
    public AlertSource getSource()       { return source; }
    public String     getContainer()     { return container; }
    public int        getBanLimit()      { return banLimit; }
    public String     getBanReason()     { return banReason; }

    public boolean isChestAlert() {
        return source == AlertSource.CHEST || source == AlertSource.SHULKER_IN_CHEST;
    }

    /** True if this alert should also trigger an auto-ban (total items exceeds ban-limit). */
    public boolean shouldAutoBan() {
        return banLimit > 0 && totalItemCount >= banLimit;
    }

    public enum AlertSource {
        PLAYER_INVENTORY,
        SHULKER_IN_INVENTORY,
        CHEST,
        SHULKER_IN_CHEST
    }
}
