# HuskHomes - ArdaCraft Fork (Fabric 1.20.1)

This is the ArdaCraft server's fork of [HuskHomes by William278](https://github.com/WiIIiam278/HuskHomes), targeting **Fabric 1.20.1**.

- This fork has been made from commit [918539072530f5064ca082b03972259976dd7d11](https://github.com/WiIIiam278/HuskHomes/commit/918539072530f5064ca082b03972259976dd7d11), branch [fabric/1.20.1](https://github.com/WiIIiam278/HuskHomes/tree/fabric/1.20.1)
- Code specific changes related to the new features are documented in [README-Codebase.md](documentation/README-Codebase.md)
- Developer information can be found in [README-Dev.md](documentation/README-Dev.md)

For all standard features - homes, warps, teleport requests, cross-server teleportation, configuration, permissions, and more - refer to the **upstream documentation**:

> **https://william278.net/docs/huskhomes**

This README documents only the **ArdaCraft-specific additions** on top of the upstream codebase.

---

## Fork-specific features

### 1. Server Linking (master/slave warp replication)

Servers can be linked in a master/slave relationship. When a player uses `/warp`, they are automatically redirected to their preferred server's copy of that warp rather than always landing on the master server.

**Concept:**
- A **master** server holds the canonical warp definition.
- **Slave** servers are mirrors of the master; players can be sent there instead.
- Relationships are stored in the `huskhomes_server_links` database table and managed at runtime via admin commands.

**Configuring in `config.yml`** (static defaults, overridden by DB at runtime):
```yaml
cross_server:
  server_linking:
    enabled: true
    link_map:
      survival-1:
        - survival-2
```

**Runtime admin commands** (see [Admin Commands](#admin-commands)):
- `/huskhomes linkserver <master> <slave>` - add a link
- `/huskhomes unlinkserver <master> <slave>` - remove a link

---

### 2. Warp Permissions

Individual warps can be locked behind a custom permission node, independently of HuskHomes' built-in `permission_restrict_warps` setting. The two systems stack: a player must pass **both** checks.

**Admin commands:**
- `/huskhomes lockwarp <warp> <permission.node>` - require `permission.node` to use `<warp>`
- `/huskhomes unlockwarp <warp>` - remove the permission restriction

Restrictions are stored in the `huskhomes_warp_permissions` table.

---

### 3. Server Permissions

All warps on a given server can be gated behind a single permission node. This check is applied **in addition to** per-warp permissions.

**Admin commands:**
- `/huskhomes lockserver <server> <permission.node>` - require `permission.node` to warp to any warp on `<server>`
- `/huskhomes unlockserver <server>` - remove the restriction

Restrictions are stored in the `huskhomes_server_permissions` table.

---

### 4. User Preferred Server

Players (or admins on their behalf) can set a preferred server for each master server in the linking system. When the player subsequently uses `/warp`, they are redirected to their preferred server's copy of the warp automatically.

**Command:**
```
/huskhomes setpreferredserver <master-server> <preferred-server> [player]
```
- Omit `[player]` to set your own preference.
- Include `[player]` (operator only) to set it for another player.
- `<preferred-server>` must be the master server itself or one of its linked slaves.

Preferences are stored in the `huskhomes_user_preferences` table.

#### Permission-based default preferred server

If a player has no explicit preference stored, a default can be granted via permission node:

```
huskhomes.linkedserver.<master-server>.preferreddefault.<preferred-server>
```

Example: granting `huskhomes.linkedserver.survival-1.preferreddefault.survival-2` to a group will route those players to `survival-2` whenever they warp to a warp on `survival-1`.

---

### 5. Extended `/warp` syntax

An optional server argument is accepted directly in the warp command:

```
/warp <name> [server]
```

When `[server]` is supplied the player is teleported to that server's copy of the warp (subject to server permission checks). This is useful for admins who need to reach a specific server copy regardless of personal preferences.

---

### 6. Command block / `/execute` compatibility fix

Vanilla Minecraft's `/execute ... run <command>` passes the full command chain as input to Brigadier, which caused HuskHomes commands to receive the entire `/execute` string as their argument list. This fork fixes that in `FabricCommand` by scanning the input for the command name and slicing only the arguments that follow it, so commands work correctly from command blocks and `execute` chains.

---

## Admin commands

All commands below require operator level (permission level 3) or the corresponding `huskhomes.<subcommand>` permission node.

| Command | Syntax | Description |
|---|---|---|
| `linkserver` | `/huskhomes linkserver <master> <slave>` | Link a slave server to a master server |
| `unlinkserver` | `/huskhomes unlinkserver <master> <slave>` | Remove a master→slave server link |
| `lockwarp` | `/huskhomes lockwarp <warp> <permission>` | Require a permission to use a warp |
| `unlockwarp` | `/huskhomes unlockwarp <warp>` | Remove a warp's permission requirement |
| `lockserver` | `/huskhomes lockserver <server> <permission>` | Require a permission for all warps on a server |
| `unlockserver` | `/huskhomes unlockserver <server>` | Remove a server's permission requirement |
| `setpreferredserver` | `/huskhomes setpreferredserver <master> <preferred> [player]` | Set a player's preferred server for a master |

---

## Fork-specific permission nodes

| Node | Description |
|---|---|
| `huskhomes.linkedserver.<master>.preferreddefault.<server>` | Permission-based default preferred server for `<master>` |

All other permission nodes are unchanged from upstream - see [Managing Access](https://william278.net/docs/huskhomes/managing-access).

---

## Building

Requires **Java 17**. Targets **Fabric 1.20.1** (Fabric Loader 0.16.10).

```bash
./gradlew clean build
```

The built jar is output to `fabric/build/libs/`.

---

## License

Licensed under the [Apache Licence 2.0](LICENSE), the same as the upstream HuskHomes project.

- [Locales Directory](https://github.com/WiIIiam278/HuskHomes/tree/master/common/src/main/resources/locales)
- [English Locales](https://github.com/WiIIiam278/HuskHomes/tree/master/common/src/main/resources/locales/en-gb.yml)

## Links
- [Docs](https://william278.net/docs/huskhomes/) - Read the plugin documentation!
- [Modrinth](https://modrinth.com/plugin/huskhomes) - View the plugin Modrinth page (Also: [Spigot](https://www.spigotmc.org/resources/huskhomes.83767/), [Polymart](https://polymart.org/resource/huskhomes.284/), [Hangar](https://hangar.papermc.io/William278/HuskHomes), & [CurseForge](https://www.curseforge.com/minecraft/mc-mods/huskhomes/))
- [Issues](https://github.com/WiIIiam278/HuskHomes/issues) - File a bug report or feature request
- [Discord](https://discord.gg/tVYhJfyDWG) - Get help, ask questions
- [bStats](https://bstats.org/plugin/bukkit/HuskHomes/8430) - View plugin metrics (Also: [Sponge](https://bstats.org/plugin/sponge/HuskHomes/18423))

---
&copy; [William278](https://william278.net/), 2023. Licensed under the Apache-2.0 Licence.
