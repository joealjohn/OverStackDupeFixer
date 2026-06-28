package com.overstackdupefinder.config;

import org.bukkit.Material;

import java.util.List;

/**
 * Represents a single monitored item entry from config.
 *
 * <p>Each entry has a material, optional display-name keywords,
 * alert thresholds (item count for alert), and optional per-item ban config.</p>
 */
public final class MonitoredItem {

    private final String       id;
    private final Material     material;         // may be null if only name-based
    private final List<String> nameKeywords;     // lowercased custom-names
    private final int          thresholdInventory; // item count for alert (loose in inventory)
    private final int          thresholdShulker;   // item count for alert (inside shulker)
    private final int          banLimit;           // item count that triggers auto-ban (-1 = disabled)
    private final String       banReason;          // per-item ban reason (blank = use global reason)

    public MonitoredItem(String id, Material material, List<String> nameKeywords,
                         int thresholdInventory, int thresholdShulker,
                         int banLimit, String banReason) {
        this.id                 = id;
        this.material           = material;
        this.nameKeywords       = nameKeywords;
        this.thresholdInventory = thresholdInventory;
        this.thresholdShulker   = thresholdShulker;
        this.banLimit           = banLimit;
        this.banReason          = banReason == null ? "" : banReason;
    }

    public String       getId()                 { return id; }
    public Material     getMaterial()            { return material; }
    public List<String> getNameKeywords()        { return nameKeywords; }
    public int          getThresholdInventory()  { return thresholdInventory; }
    public int          getThresholdShulker()    { return thresholdShulker; }
    /** Item count that triggers auto-ban. -1 = never auto-ban this item. */
    public int          getBanLimit()            { return banLimit; }
    /** Custom ban reason for this item. Empty = use global auto-ban.reason. */
    public String       getBanReason()           { return banReason; }

    /** Whether this monitored item matches the given material. */
    public boolean matchesMaterial(Material mat) {
        return material != null && material == mat;
    }

    /** Whether this monitored item matches the given display name (lowercased). Returns matched keyword or null. */
    public String matchesName(String lowerDisplayName) {
        for (String kw : nameKeywords) {
            if (lowerDisplayName.contains(kw)) return kw;
        }
        return null;
    }

    @Override
    public String toString() {
        return "MonitoredItem{id='" + id + "', material=" + material +
                ", keywords=" + nameKeywords +
                ", alertInv=" + thresholdInventory +
                ", alertShulker=" + thresholdShulker +
                ", banLimit=" + banLimit + "}";
    }
}
