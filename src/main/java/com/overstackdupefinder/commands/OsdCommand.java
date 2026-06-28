package com.overstackdupefinder.commands;

import com.overstackdupefinder.OverStackDupeFinder;
import com.overstackdupefinder.config.MonitoredItem;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.List;

/**
 * Handles the {@code /overstackdupe} (alias {@code /osd}) command.
 *
 * <p>Sub-commands:
 * <ul>
 *   <li>{@code reload} — reloads config without restart</li>
 *   <li>{@code status} — prints current configuration</li>
 *   <li>{@code test}   — sends a test embed to the configured webhook</li>
 * </ul>
 */
public class OsdCommand implements CommandExecutor, TabCompleter {

    private static final String PERMISSION = "overstackdupefinder.admin";

    private final OverStackDupeFinder plugin;

    public OsdCommand(OverStackDupeFinder plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            sender.sendMessage("§cYou don't have permission to use this command.");
            return true;
        }

        if (args.length == 0) { sendHelp(sender, label); return true; }

        return switch (args[0].toLowerCase()) {
            case "reload" -> handleReload(sender);
            case "status" -> handleStatus(sender);
            case "test"   -> handleTest(sender);
            default       -> { sendHelp(sender, label); yield true; }
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(PERMISSION)) return List.of();
        if (args.length == 1) {
            return List.of("reload", "status", "test").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .toList();
        }
        return List.of();
    }

    // ── Sub-commands ──────────────────────────────────────────────────────────

    private boolean handleReload(CommandSender sender) {
        plugin.reload();
        plugin.getInventoryScanner().clearCooldowns();
        sender.sendMessage("§a[OverStackDupeFinder] §fConfiguration reloaded.");
        return true;
    }

    private boolean handleStatus(CommandSender sender) {
        var cfg = plugin.getPluginConfig();
        boolean webhookOk = !cfg.getWebhookUrl().isBlank() && !cfg.getWebhookUrl().contains("YOUR_WEBHOOK");
        sender.sendMessage("§6[OverStackDupeFinder] §eStatus:");
        sender.sendMessage("§7  Webhook configured:         §f" + (webhookOk ? "§aYES" : "§cNO"));
        sender.sendMessage("§7  Scan player inventory:      §f" + cfg.isScanPlayerInventory());
        sender.sendMessage("§7  Scan shulkers (inventory):  §f" + cfg.isScanShulkerContents());
        sender.sendMessage("§7  Scan chest loose items:     §f" + cfg.isScanChestsLooseItems());
        sender.sendMessage("§7  Scan chest shulkers:        §f" + cfg.isScanChestsShulkers());
        sender.sendMessage("§7  Scan interval:              §f" + cfg.getScanIntervalSeconds() + "s");
        sender.sendMessage("§7  Alert cooldown:             §f" + cfg.getAlertCooldownSeconds() + "s");
        sender.sendMessage("§7  Debug mode:                 §f" + cfg.isDebug());
        sender.sendMessage("§7  Whitelisted players:        §f" + cfg.getWhitelistedPlayers());
        sender.sendMessage("§7  Monitored items:");
        for (MonitoredItem mi : cfg.getMonitoredItems()) {
            sender.sendMessage("§7    §e" + mi.getId() + " §7→ mat=" + mi.getMaterial()
                    + " keywords=" + mi.getNameKeywords()
                    + " inv≥" + mi.getThresholdInventory()
                    + " shulker≥" + mi.getThresholdShulker());
        }
        return true;
    }

    private boolean handleTest(CommandSender sender) {
        sender.sendMessage("§6[OverStackDupeFinder] §eSending test webhook to Discord...");
        // Run async so HTTP doesn't block the main thread
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            plugin.getWebhookManager().sendTestWebhook();
            sender.sendMessage("§a[OverStackDupeFinder] §fTest sent! Check your Discord channel and server console.");
        });
        return true;
    }

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage("§6[OverStackDupeFinder] §eCommands:");
        sender.sendMessage("§7  /" + label + " reload §f- Reload config");
        sender.sendMessage("§7  /" + label + " status §f- Show current settings");
        sender.sendMessage("§7  /" + label + " test   §f- Send a test webhook to Discord");
    }
}
