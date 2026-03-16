# LsEnderChest

✨ A **modern and lightweight Ender Chest plugin** for **Paper 1.21.x** servers.

**LsEnderChest** replaces the vanilla Ender Chest with a **permission-based expandable storage system**, allowing players to have **1–6 rows** depending on their rank.

Built to be **simple, fast, and configurable**, with support for **SQLite and MySQL** storage.

---

# Features

* 📦 **Expandable Ender Chests** (1–6 rows via permissions)
* 👤 `/lsec` to open your Ender Chest
* 🛠️ `/lsec <player>` to view other players' chests
* 💾 **SQLite & MySQL support**
* 🌐 Works with **online-mode and offline-mode servers**
* ⚡ Lightweight and efficient
* 👀 **Offline player support**
* 🔒 Admin bypass option
  
---

### 🔄 LsEnderChestConverter

If you are switching from **vanilla Ender Chests** to **LsEnderChest**, you can use the companion plugin **[LsEnderChestConverter](https://github.com/lopikss/LsEnderChestConverter/releases)**.

This plugin converts existing **vanilla Ender Chest inventories** into the storage system used by **LsEnderChest**.

Features:

* 🔁 Converts existing player Ender Chests
* 👤 Optional **convert-on-first-join** system
* ⚡ Safe migration for existing servers
* 🛠️ Manual conversion command

This allows servers to switch to **LsEnderChest without players losing their items**.

---
  
![LsEnderChest Preview](https://media.discordapp.net/attachments/890970451579371590/1482690647008874671/echest.png?ex=69b7dec5&is=69b68d45&hm=6fef7c6d4cca6dcdc8ceeec665730aa5a7e5ab0b03cf48ad2de9ed903ec2e705&=&format=webp&quality=lossless&width=943&height=653)

</div>

---

# Commands

`/lsec`
Opens your Ender Chest.

`/lsec <player>`
Opens another player's Ender Chest (requires permission).

---

# Permissions

| Permission            | Description                                | Default |
| --------------------- | ------------------------------------------ | ------- |
| `enderchest.use`      | Allows players to use `/lsec`              | true    |
| `enderchest.admin`    | Allows opening other players' Ender Chests | op      |
| `enderchest.bypass`   | Allows bypassing normal restrictions       | op      |
| `enderchest.rows.1-6` | Allows a 1 row Ender Chest                 | false   |
| `enderchest.rows.2`   | Allows a 2 row Ender Chest                 | false   |


---

# Compatibility

* **Paper**
* **Minecraft 1.21.x**
* **Java 21**

---

# Installation

1. Download the plugin
2. Place it in your server's `plugins` folder
3. Restart the server
4. Configure `config.yml` if needed
