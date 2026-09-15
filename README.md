# LsEnderChest

✨ A **modern, lightweight and security-focused Ender Chest plugin** for **Paper 26.3**.

**LsEnderChest** replaces the vanilla Ender Chest with permission-based virtual storage that can give each player **1–6 rows**, backed by **SQLite or MySQL**.

Version **2.0.0** is a major rewrite with live shared chest sessions, automatic overflow protection, GUI restore history, per-player audit journals and a built-in vanilla Ender Chest converter.

---

# Features

- 📦 **Expandable Ender Chests** — 1–6 rows through permissions
- 👤 `/lsec`, `/ec` and `/enderchest` all provide the same functionality
- 🛠️ Administrators can open/edit another player's chest **even while that player is using it**
- 🔄 One shared live inventory per player, preventing stale writable copies
- 🧰 **Automatic Overflow Chests** when a player loses storage rows
- 🕘 Safe downgrade behavior — the final available slot becomes the Overflow Chest
- 🕰️ **GUI restore history** with timestamps, previews and confirmation
- 🧾 **Per-player UUID audit journals** with individual item changes and restore points
- 👮 Admin opens, edits, restores and manual conversions are identified in the target player's journal
- 🔁 Built-in replacement for **LsEnderChestConverter**
- ⚠️ `/ec convert all` requires a second `/ec confirm` command before anything happens
- 💾 **SQLite & MySQL support**
- ⚡ Ordered asynchronous database I/O
- 🌐 Online-mode and offline-mode UUID support
- 🔒 Advanced item blocking and save-time validation
- 🔔 Admin update notifications
- 🛡️ Multiple anti-dupe and failure-recovery protections

![LsEnderChest Preview](https://raw.githubusercontent.com/lopikss/LsEnderChest/main/images/echest.png)

---

# What's new in 2.0.0?

## Live shared Ender Chest sessions

LsEnderChest now keeps **one live inventory session per chest owner**.

If a player has their Ender Chest open and an administrator runs:

`/ec <player>`

both users see and edit the **same live inventory**, rather than two independent copies. This means administrators are no longer blocked simply because the owner already has the chest open, while also avoiding stale-copy save conflicts.

Read-only staff can still view another player's chest without being able to modify it.

---

## Automatic Overflow Chests

Losing a rank or row permission no longer makes items outside the new chest size disappear.

Example: a player goes from **4 rows to 1 row**.

- Slots **1–8** stay where they are
- Slot **9** becomes an **Overflow Chest**
- Everything that was in slot 9 and beyond is safely moved into that Overflow Chest

The same rule applies to every downgrade: the **last slot of the new chest size** becomes the Overflow Chest.

Overflow Chests are normal `CHEST` items with protected LsEnderChest metadata, but they **cannot be placed and are not reusable storage**. Their actual contents are stored server-side rather than trusting item lore or visible metadata. Tokens are signed, tied to their owner and backed by a unique storage ID.

**Right-clicking an Overflow Chest consumes it and drops every stored item on the ground at the player's location.** Players cannot open it as storage or add extra items to it, so an Overflow Chest can never become a portable extra inventory. Its lore clearly warns that claiming it is a one-time action and that the contents will be dropped.

If another Overflow Chest is caught in a later downgrade before it has been claimed, its contents are **flattened into the new Overflow Chest instead of nesting Overflow Chests inside each other**.

Permission changes are monitored automatically, and an offline player's storage is checked the next time it is opened.

---

## GUI restore system

Administrators can open a player's restore history with:

`/ec restore <player>`

Restore history is presented as a GUI containing timestamped restore points. An administrator can:

1. Browse previous sessions
2. Preview the exact historical chest contents
3. Select a restore point
4. Confirm the restore in a second GUI

A normal player session is treated as **one restore session**:

`open chest → make any number of changes → close the last viewer → one restore point`

Individual item movements are still logged separately for auditing.

Before a restore changes anything, LsEnderChest writes a **pre-restore backup** first. This makes accidental restores reversible from the same restore GUI.

Historical Overflow Chests are included in restore snapshots. When an old snapshot is restored, its historical overflow data is recreated under new protected IDs instead of reusing stale tokens.

> Restore is intentionally administrator-only. Restoring old inventory states can recreate items that were previously removed from the chest, so this permission should only be given to trusted staff.

---

## Per-player audit journals

The old global `simple.log` and `detailed.log` system has been replaced.

Each Ender Chest owner now has one journal:

`plugins/LsEnderChest/logs/players/<uuid>.log`

The journal records information such as:

- Chest opens and closes
- Exact item additions/removals
- The actor responsible for each change
- Admin opens and edits
- Blocked-item attempts
- Read-only edit attempts
- Overflow creation and one-time claims
- Row downgrades
- Vanilla conversion activity
- Restore actions
- Machine-readable restore snapshots

Old `simple.log` / `detailed.log` files are **archived under `logs/legacy/` instead of being deleted**, so existing audit history is not destroyed during the upgrade.

---

## Built-in vanilla Ender Chest converter

The standalone **LsEnderChestConverter is no longer required**.

2.0.0 includes safe vanilla migration directly in the main plugin:

- Optional automatic one-time conversion on join
- `/ec convert <player>` for one online player
- `/ec convert all` for all online players
- Vanilla contents are cleared only after custom storage has saved successfully
- The vanilla chest is verified again before it is cleared
- Conversion rolls back if the vanilla contents change during migration
- Existing different LsEnderChest contents are never blindly overwritten
- Exact duplicate data created by the old converter can be detected and cleaned safely

For safety, `/ec convert all` **does not start immediately**. It first displays a warning and requires:

`/ec confirm`

within the configured confirmation timeout. Until `/ec confirm` is entered, **nothing is converted**.

If the old converter plugin is still installed, LsEnderChest disables it and warns you to remove the old JAR.

---

# Commands

| Command | Description |
| --- | --- |
| `/ec` | Open your Ender Chest |
| `/ec <player>` | Open another player's Ender Chest |
| `/ec restore <player>` | Open the admin restore-history GUI |
| `/ec convert <player>` | Safely migrate one online player's vanilla Ender Chest |
| `/ec convert all` | Arm bulk conversion for all online players |
| `/ec confirm` | Confirm the pending dangerous bulk action |
| `/ec reload` | Reload reloadable configuration |

`/lsec` and `/enderchest` support **all of the exact same subcommands**.

### EssentialsX

`/ec` and `/enderchest` are registered as full Paper root commands rather than aliases. LsEnderChest loads after EssentialsX and intentionally takes ownership of the bare `/ec` and `/enderchest` labels. EssentialsX's namespaced commands remain available if needed.

---

# Permissions

| Permission | Description | Default |
| --- | --- | --- |
| `enderchest.use` | Open your own LsEnderChest | true |
| `enderchest.admin` | Edit other players' chests, use restore and reload | op |
| `enderchest.view.other` | View other players' chests read-only | false |
| `enderchest.convert` | Use the built-in vanilla converter | op |
| `enderchest.bypass` | Use the vanilla Ender Chest instead | false |
| `enderchest.blocked.bypass` | Bypass item restrictions | op |
| `enderchest.rows.1` | Gives 1 row | false |
| `enderchest.rows.2` | Gives 2 rows | false |
| `enderchest.rows.3` | Gives 3 rows | false |
| `enderchest.rows.4` | Gives 4 rows | false |
| `enderchest.rows.5` | Gives 5 rows | false |
| `enderchest.rows.6` | Gives 6 rows | false |

The highest row permission a player has is used. If none are present, `settings.default-rows` is used.

---

# Storage & safety

LsEnderChest supports **SQLite** and **MySQL**.

2.0.0's storage/session rewrite includes:

- One shared live chest state per owner
- Ordered database work on a dedicated worker
- Save retries for transient failures
- SQLite WAL with stronger synchronous durability
- Restore backup-before-write behavior
- Signed one-time Overflow Chest tokens with server-side backing data
- Safe overflow flattening instead of nesting
- Save → verify → clear vanilla conversion flow
- Rollback when conversion verification fails
- Final blocked-item sweep before a chest is persisted

No inventory plugin can guarantee protection against every possible server crash, filesystem failure, database failure or malicious third-party plugin, but 2.0.0 is designed to avoid the common stale-copy, resize and migration duplication/data-loss paths.

---

# Item Blocking

`blocked-items.yml` can restrict items by material and supported item metadata/components. Restrictions are checked during inventory interactions and again before the last viewer closes a chest.

---

# Compatibility

- **Paper 26.3**
- **Java 25**
- Paper-compatible forks may work, but Paper is the primary supported platform

---

# Installation / Upgrade

1. Stop the server
2. Back up your server and `plugins/LsEnderChest` folder before a major-version upgrade
3. Remove the old standalone `LsEnderChestConverter.jar` if present
4. Place `LsEnderChest-2.0.0.jar` in the `plugins` folder
5. Start the server
6. Review the generated `config.yml` and `blocked-items.yml`

Existing LsEnderChest inventory data remains supported. Legacy audit logs are archived rather than deleted.

---

If you enjoy the plugin, donations are appreciated :D
