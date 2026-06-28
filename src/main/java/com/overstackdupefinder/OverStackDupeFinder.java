package com.overstackdupefinder;

import com.overstackdupefinder.commands.OsdCommand;
import com.overstackdupefinder.config.PluginConfig;
import com.overstackdupefinder.listeners.ChestListener;
import com.overstackdupefinder.listeners.InventoryListener;
import com.overstackdupefinder.scanner.InventoryScanner;
import com.overstackdupefinder.webhook.WebhookManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * OverStackDupeFinder — Main plugin entry point.
 *
 * <h3>Architecture overview</h3>
 * <pre>
 *  ┌─────────────────────────────────────────────────────┐
 *  │  Main server thread                                 │
 *  │   • Event handlers (debounced)                      │
 *  │   • Inventory snapshots  → array copy, ~microseconds│
 *  │   • Periodic timer tick  → snapshot all players     │
 *  └────────────────────┬────────────────────────────────┘
 *                       │  CompletableFuture.supplyAsync()
 *                       ▼
 *  ┌─────────────────────────────────────────────────────┐
 *  │  Virtual-thread pool  (Java 21)                     │
 *  │   • analyzeSnapshot  — reads ItemMeta, counts items │
 *  │   • WebhookManager   — HTTP POST to Discord         │
 *  └─────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p>The main server thread is never blocked by item analysis or network I/O.</p>
 */
public final class OverStackDupeFinder extends JavaPlugin {

    private static OverStackDupeFinder instance;

    private PluginConfig     pluginConfig;
    private WebhookManager   webhookManager;
    private InventoryScanner inventoryScanner;

    /** Virtual-thread executor — one OS thread per in-flight task, zero blocking. */
    private ExecutorService executor;
    private BukkitTask      scanTask;

    @Override
    public void onEnable() {
        instance = this;

        // ── Configuration ──────────────────────────────────────────────────────
        saveDefaultConfig();
        pluginConfig = new PluginConfig(this);

        // ── Virtual-thread pool ────────────────────────────────────────────────
        // newVirtualThreadPerTaskExecutor: each submitted task gets its own
        // lightweight virtual thread — ideal for I/O-bound work (HTTP webhooks)
        // and short CPU bursts (item counting).
        executor = Executors.newVirtualThreadPerTaskExecutor();

        // ── Managers ───────────────────────────────────────────────────────────
        webhookManager   = new WebhookManager(this);
        inventoryScanner = new InventoryScanner(this, executor);

        // ── Listeners ─────────────────────────────────────────────────────────
        getServer().getPluginManager().registerEvents(new InventoryListener(this), this);
        getServer().getPluginManager().registerEvents(new ChestListener(this), this);

        // ── Commands ──────────────────────────────────────────────────────────
        OsdCommand cmd = new OsdCommand(this);
        getCommand("overstackdupe").setExecutor(cmd);
        getCommand("overstackdupe").setTabCompleter(cmd);

        // ── Periodic scan ──────────────────────────────────────────────────────
        scheduleScan();

        getLogger().info("OverStackDupeFinder enabled — using virtual-thread async pipeline.");
    }

    @Override
    public void onDisable() {
        if (scanTask != null) scanTask.cancel();

        // Gracefully shut down the executor; let in-flight HTTP calls finish
        if (executor != null) {
            executor.shutdown();
        }

        getLogger().info("OverStackDupeFinder disabled.");
    }

    // ── Public helpers ─────────────────────────────────────────────────────────

    public static OverStackDupeFinder getInstance() { return instance; }

    public PluginConfig     getPluginConfig()     { return pluginConfig; }
    public WebhookManager   getWebhookManager()   { return webhookManager; }
    public InventoryScanner getInventoryScanner() { return inventoryScanner; }
    public ExecutorService  getExecutor()         { return executor; }
    public Logger           log()                 { return getLogger(); }

    /**
     * Reloads configuration and reschedules the periodic scan.
     * Called by {@code /osd reload}.
     */
    public void reload() {
        reloadConfig();
        pluginConfig = new PluginConfig(this);

        // Re-create webhook manager with new config
        webhookManager = new WebhookManager(this);

        // Re-create scanner (preserves existing executor)
        inventoryScanner = new InventoryScanner(this, executor);

        if (scanTask != null) scanTask.cancel();
        scheduleScan();

        getLogger().info("Configuration reloaded.");
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private void scheduleScan() {
        long ticks = pluginConfig.getScanIntervalSeconds() * 20L;
        scanTask = getServer().getScheduler().runTaskTimer(
                this,
                // Main thread: snapshot only (~microseconds per player)
                () -> inventoryScanner.snapshotAndScanAll(),
                ticks,
                ticks
        );
    }
}
