LsEnderChest - Internal Data
===========================

DO NOT EDIT, DELETE, MOVE, OR REPLACE FILES IN THIS FOLDER
unless you know exactly what you are doing.

This folder contains internal state used to keep player storage,
conversion, row resizing, and Overflow Chests safe.

Important files may include:

- database.db
  SQLite player/overflow storage when SQLite is enabled.

- converted-players.yml
  Tracks players whose vanilla Ender Chest has already been converted.

- row-state.yml
  Tracks each player's last known Ender Chest row count so permission
  downgrades can be detected and handled safely.

- overflow-secret.key
  Security key used to verify genuine Overflow Chest recovery items.
  Deleting or changing this key can make existing Overflow Chests invalid.

Back up this entire folder before making any manual changes.
