# 🛡️ OverStackDupeFinder

> A high-performance, asynchronous **Paper 1.21+** plugin that detects suspicious amounts of critical items (like Spawners, Netherite, Notch Apples, etc.) in player inventories and chests. Includes Discord Webhook alerts and automated bans (compatible with LiteBans).

---

## 📋 Table of Contents

- [Features](#-features)
- [Requirements](#-requirements)
- [Installation](#-installation)
- [Configuration](#-configuration)
- [Discord Webhook Setup](#-discord-webhook-setup)
- [Auto-Ban Integration](#-auto-ban-integration)
- [Commands & Permissions](#-commands--permissions)
- [How Detection Works](#-how-detection-works)
- [Building from Source](#-building-from-source)
- [License](#-license)

---

## ✨ Features

- 🔍 **Granular Scans** — Monitors items sitting loose in player inventories, inside shulker boxes, loose in chests/barrels, or in shulker boxes inside chests/barrels.
- ⚡ **Asynchronous & Non-Blocking** — Uses Java Virtual Threads to scan metadata and perform Discord Webhook I/O. Zero impact on server TPS.
- 🧱 **Granular Config** — Configure alert thresholds, ban limits, and ban reasons per item (e.g. check for exactly 32 Netherite Blocks or 128 Netherite Ingots, rather than stack sizes).
- 🏷️ **Custom Display Names** — Matches items using their custom/colored display names in addition to material types.
- 🔨 **Auto-Ban (LiteBans Support)** — Execute console ban commands automatically if a player exceeds configured raw item limits.
- 🔔 **Enhanced Coordinates** — Chest alerts send both the **Player Coordinates** and the **Chest Block Coordinates** separately to Discord.
- ⏱️ **Cooldown System** — Prevents webhook spam with smart per-player, per-item cooldowns.
- ♻️ **Hot Reload** — `/osd reload` applies config changes instantly.

---

## 📦 Requirements

| Requirement | Version |
|---|---|
| **Minecraft Server** | Paper 1.21 or newer |
| **Java** | Java 21 or newer |
| **Server Mode** | Offline or Online |

> ⚠️ This plugin is built against the **Paper API**. It will **not** work on vanilla Spigot or CraftBukkit.

---

## 🚀 Installation

1. Place `OverStackDupeFinder-1.0.0.jar` into your server's `plugins/` folder.
2. Start the server to generate the default `config.yml`.
3. Configure your Discord Webhook URL, monitored items, and auto-ban commands in `plugins/OverStackDupeFinder/config.yml`.
4. Run `/osd reload` to apply the configuration.

---

## ⚙️ Configuration

File location: `plugins/OverStackDupeFinder/config.yml`

```yaml
# Your Discord webhook URL.
webhook-url: "https://discord.com/api/webhooks/..."

# ──────────────────────────────────────────────────────────
# WHAT ITEMS TO WATCH
# ──────────────────────────────────────────────────────────
# Each entry supports these 6 fields:
#
#   material           → Minecraft item ID (ALL CAPS). Leave blank if using custom-names only.
#   custom-names       → Words to match in display name. Case-insensitive. Leave as [] if not needed.
#   alert-in-inventory → Alert when carrying this many items LOOSE in inventory (raw item count).
#   alert-in-shulker   → Alert when a single shulker box contains this many items inside (raw item count).
#   ban-limit          → Auto-ban when player has this many items (requires auto-ban.enabled: true).
#   ban-reason         → Custom ban reason. Leave blank ("") to use global reason.

monitored-items:
  spawners:
    material: "SPAWNER"
    custom-names:
      - "spawner"
    alert-in-inventory: 640   # 10 stacks
    alert-in-shulker: 320     # 5 stacks
    ban-limit: 1280           # 20 stacks
    ban-reason: "velo-panikum-poda"

  netherite-blocks:
    material: "NETHERITE_BLOCK"
    custom-names: []
    alert-in-inventory: 32
    alert-in-shulker: 32
    ban-limit: 50
    ban-reason: "Dupe-detected"

  enchanted-golden-apples:
    material: "ENCHANTED_GOLDEN_APPLE"
    custom-names: []
    alert-in-inventory: 32
    alert-in-shulker: 32
    ban-limit: 50
    ban-reason: "Dupe-detected"

  netherite-ingots:
    material: "NETHERITE_INGOT"
    custom-names: []
    alert-in-inventory: 128
    alert-in-shulker: 128
    ban-limit: 200
    ban-reason: "Dupe-detected"

# ──────────────────────────────────────────────────────────
# WHAT TO SCAN
# ──────────────────────────────────────────────────────────
scan-player-inventory: true
scan-shulker-contents: true

# Chest scans (triggers when opening chests/barrels)
scan-chests-loose-items: true
scan-chests-shulkers: true

# ──────────────────────────────────────────────────────────
# TIMING & COOLDOWNS
# ──────────────────────────────────────────────────────────
scan-interval-seconds: 5
alert-cooldown-seconds: 60

# ──────────────────────────────────────────────────────────
# AUTO-BAN ON DETECTION
# ──────────────────────────────────────────────────────────
auto-ban:
  enabled: false
  command: "ban {player} {reason}"
  reason: "Suspicious item stash detected by OverStackDupeFinder"
  notify-discord: true
```

---

## 🔨 Auto-Ban Integration

If a player is detected holding/storing more than the `ban-limit` of a monitored item:
1. The plugin dispatches the configured `auto-ban.command` from the console on the main thread.
2. If `auto-ban.notify-discord` is set to `true`, a separate **green** ban embed is sent to Discord highlighting the player and ban reason.
3. Compatible out-of-the-box with **LiteBans**, **AdvancedBan**, **EssentialsX**, or vanilla `/ban`.

---

## 🛠️ Commands & Permissions

### Commands
* `/osd reload` — Reloads the configuration.
* `/osd status` — Shows loaded items, thresholds, and current scanning state.
* `/osd test` — Sends a test webhook embed to verify connectivity.

### Permissions
* `overstackdupefinder.admin` — Access to all `/osd` commands (Default: OP).

---

## 🏗️ Building from Source

Build the plugin jar using Maven:
```bash
mvn clean package
```
The output file will be generated at `target/OverStackDupeFinder-1.0.0.jar`.

---

## 📄 License

This project is licensed under the **MIT License**.
