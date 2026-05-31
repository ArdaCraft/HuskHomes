This document lists the codebase modifications to implement ArdaCraft-specific features since the initial fork. 

---

## Commit Analysis — ArdaCraft/HuskHomes (`fabric/1.20.1` branch)

All 4 commits were in June 2025 on the `fabric/1.20.1` branch, building on each other sequentially.

---

### `61a5abaa` — `fix: build.gradle for 1.20.1` *(Jun 3)*

**2 files changed** (build.gradle, gradle.properties)

A build configuration fix to make the project compile properly for Fabric 1.20.1:
- Rewrote the `processResources` task in build.gradle to correctly pass token replacements into JSON/YAML resource files (e.g., version strings)
- Bumped `fabric_impactor_api_version` from `5.2.3-SNAPSHOT` → `5.2.4` (stable release)

---

### `062fc803` — `feature: server linking` *(Jun 3)*

**16 files changed, ~1930 lines added**.

This adds a complete **master/slave server linking system** for warp replication across multiple servers. Key changes:

**New database tables** (added to all 5 DB backends — H2, MySQL, MariaDB, PostgreSQL, SQLite):
- `huskhomes_server_links` — links master ↔ slave servers
- `huskhomes_warp_permissions` — per-warp required permissions
- `huskhomes_server_permissions` — per-server required permissions
- `huskhomes_user_preferences` — per-user preferred server per master

**`Database.java`** — new abstract methods: `getSlaveServers`, `getMasterServer`, `addServerLink`, `removeServerLink`, `getWarpPermission`, `setWarpPermission`, `getServerPermission`, `setServerPermission`, `getUserPreferredServer`, `setUserPreferredServer`, etc. — all implemented in all 4 DB classes.

**https://github.com/WiIIiam278/HuskHomes/tree/master/common/src/main/java/net/william278/huskhomes/command/WarpCommand.java#L34-L53** — overhauled to support:
- Per-warp and per-server permission restrictions (`hasWarpPermission`, `hasServerPermission`)
- Optional `[server]` argument: `/warp <name> [server]`
- User preferred server resolution (`resolveWarpWithUserPreference`)
- Warp redirect to the preferred/linked server copy

**https://github.com/WiIIiam278/HuskHomes/tree/master/common/src/main/java/net/william278/huskhomes/config/Settings.java#L147-L158** — 4 new config sections: `ServerLinkingSettings`, `WarpPermissionSettings`, `ServerPermissionSettings`, `PreferredServerSettings`

**https://github.com/WiIIiam278/HuskHomes/tree/master/common/src/main/java/net/william278/huskhomes/command/HuskHomesCommand.java#L109-L125** — 7 new admin subcommands: `/huskhomes lockwarp`, `unlockwarp`, `lockserver`, `unlockserver`, `linkserver`, `unlinkserver`, `setpreferredserver`

**Gradle bumps**: Fabric Loom 1.7 → 1.10, Fabric Loader 0.15.11 → 0.16.10, Gradle wrapper 8.8 → 8.12.1

---

### `19361b10` — `feature: default preferred server` *(Jun 4)*

**1 file changed** (WarpCommand.java, +52/-7)

Enhances the server linking feature by adding **permission-based default server selection**. When a user has no explicit preferred server set in the DB, the system now also checks for permissions in the format:

```
huskhomes.linkedserver.<master-server>.preferreddefault.<preferred-server>
```

New private method `getPermissionBasedDefaultServer()` added to `WarpCommand`, integrated into `resolveWarpWithUserPreference()` as a fallback.

---

### `985044bd` — `fix: commands not executing from command blocks` *(Jun 4)*

**1 file changed** (FabricCommand.java, +39/-7)

Fixes a bug where commands executed via `/execute ... run <command>` or command blocks would pass the **entire input string** (e.g., `execute as @a run home myHome`) into the argument parser. The original `removeFirstArg()` only stripped the first word (`execute`), leaving `as @a run home myHome` as the args — breaking all command logic.

The fix introduces `parseCommandArgs(input, commandName)` which scans the input string to find the actual command name and extracts only what follows it. Applied to both `getBrigadierExecutor()` and `getBrigadierSuggester()`.

---

### Summary

| Commit | What | Scope |
|---|---|---|
| `61a5aba` | Build fix for 1.20.1 | Infrastructure |
| `062fc803` | Server linking + warp/server permissions | Major feature |
| `19361b10` | Permission-based default preferred server | Feature extension |
| `985044b` | Fix command block / `/execute` argument parsing | Bug fix |

The bulk of the work is the server linking feature in commit `062fc803`, which introduced a custom cross-server warp replication system on top of the base HuskHomes plugin.