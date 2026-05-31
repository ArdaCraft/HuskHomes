# Developer Environment — ArdaCraft HuskHomes Fork

This document covers building the project and setting up and running the local multi-server dev environment.

## Build

Run Gradle task `./gradlew shadowJar`. The output build will be in directory `fabric/build/libs/<HuskHomes-version>.jar`.

**Note** : Production ready builds needs to be run when the git tree is clean and a matching tag is found on the repo.

---

## Prerequisites

| Tool | Version |
|------|---------|
| Java | **21** (JDK 22 triggers a stricter `CompletionFailure` on `:bukkit:compileJava`) |
| Docker + Docker Compose | Docker ≥ 24, Compose plugin v2 |
| Gradle wrapper | bundled (`./gradlew`) |

---

## Directory layout

```
HuskHomesArdacraft/
├── docker-compose.yml          # 4-service dev cluster (4 MC servers — DB is external)
├── dev/
│   ├── mods/                   # Mod staging area
│   │   ├── LuckPerms-Fabric-5.4.102.jar  # Shared mods — place here, script distributes them
│   │   ├── ardacraft/          # Per-server mods dir — populated by update-mods.sh (gitignored)
│   │   ├── building/
│   │   ├── plots/
│   │   └── lobby/
│   ├── config/
│   │   ├── ardacraft/huskhomes/
│   │   │   ├── config.yml      # HuskHomes config — DB points to huskhomesdb container
│   │   │   └── server.yml      # Server identity: name: ardacraft
│   │   ├── building/huskhomes/ # … name: building
│   │   ├── plots/huskhomes/    # … name: plots
│   │   └── lobby/huskhomes/    # … name: lobby
│   └── scripts/
│       └── update-mods.sh      # Copies the built jar into dev/mods/ under a fixed name
└── target/                     # Gradle build output — remapped Fabric jar lands here
```

> `dev/mods/*.jar` and `dev/data/` are gitignored.

---

## First-time setup

### 0. Start the database container

The MariaDB instance runs as a standalone container outside of Compose and must be
started **before** `docker compose up`. The Compose services connect to it via the
shared `huskhomes-net` Docker network.

```bash
# Create the shared network (one-time)
podman network create huskhomes-net

# Start the database container (one-time, persists across reboots with --restart)
podman run -d \
  --name huskhomesdb \
  --network huskhomes-net \
  --restart unless-stopped \
  -p 3306:3306 \
  -e MARIADB_DATABASE=huskhomes \
  -e MARIADB_USER=huskhomes \
  -e MARIADB_PASSWORD=devpassword \
  -e MARIADB_ROOT_PASSWORD=rootpassword \
  mariadb:10.11
```

Wait until it is healthy before proceeding:

```bash
docker exec huskhomesdb mariadb-admin ping -uhuskhomes -pdevpassword --wait
```
> **Rootless Podman — bind-mount permissions**
> In rootless Podman, container processes run as remapped subUIDs and fall into the
> "other" permission bucket for host-owned files. Bind-mounted directories therefore
> need world-writable (`777`) permissions so containers can create subdirectories
> (e.g. LuckPerms libs cache) and write config files.
> `update-mods.sh` handles `dev/mods` and `dev/config` automatically on every run.
### 1. Build the mod

This fork targets Fabric only. Build just the `:fabric` subproject to skip the
unused Bukkit/Paper/Sponge modules and avoid a known compilation issue
on those platforms when using JDK 22:

```bash
./gradlew :fabric:build
```

> **Note — `./gradlew build` with JDK 22**
> Running the root `build` task with JDK 22 fails at `:bukkit:compileJava` with
> `CompletionFailure: class file for redis.clients.jedis.util.Pool not found`.
> This is because `common` declares Jedis as `compileOnly` (not exported to
> dependents), so Bukkit's compiler can't complete the Jedis type hierarchy.
> JDK 22 is stricter about this than JDK 21. The missing dependency has been
> added to `bukkit/build.gradle`, but **using JDK 21 is still recommended** to
> match the version specified in `gradle.properties`.

> **Note — license header check**
> The build runs `checkLicenseMain` on every `.java` file under `src/main/java`.
> Any new Java file added to the fork must start with the Apache 2.0 header block
> found in the `HEADER` file at the project root, wrapped in `/* … */` comments.
> Running `./gradlew :fabric:licenseFormat` will apply the header automatically to
> files that are missing it.

### 2. Populate server mod directories

The helper script copies the built jar and any shared mods from `dev/mods/*.jar`
into each server's own directory (`dev/mods/<server>/`):

```bash
bash dev/scripts/update-mods.sh
# → Distributed HuskHomes-Fabric-4.7.jar to: ardacraft building plots lobby
```

> `plots` and `lobby` are commented out in `docker-compose.yml` by default — their mod
> directories are still populated so they are ready to enable on demand.

Re-run this script after every rebuild or after adding/updating a shared mod.

### 3. Download LuckPerms

Download **LuckPerms-Fabric-5.4.102.jar** from Modrinth:

```
https://modrinth.com/plugin/luckperms/version/5.4.102
```

Place it in `dev/mods/` (the staging root, **not** a server subdirectory):

```
dev/mods/LuckPerms-Fabric-5.4.102.jar
```

Then run `update-mods.sh` to distribute it to all four server directories:

```bash
bash dev/scripts/update-mods.sh
```

After this step each server directory should contain exactly two jars:

```
dev/mods/ardacraft/huskhomes.jar
dev/mods/ardacraft/LuckPerms-Fabric-5.4.102.jar
# … same for building/, plots/, lobby/ (plots and lobby are disabled by default)
```

---

## Starting the cluster

Ensure the `huskhomesdb` container is running and healthy before starting Compose
(see [First-time setup — step 0](#0-start-the-database-container)).

```bash
docker compose up -d
```

This starts **ardacraft**, **building**, **redis** (message broker), and **velocity** (proxy).
`plots` and `lobby` are commented out in `docker-compose.yml` and do not start by default.
All Minecraft servers connect to the already-running `huskhomesdb` container via the
shared `huskhomes-net` network.

To enable `plots` or `lobby`, uncomment the relevant service block in `docker-compose.yml`
and its corresponding entry in `dev/config/velocity/velocity.toml` under `[servers]`.
Then run `docker compose up -d` again.

Check the status:

```bash
docker compose ps
```

Follow logs for a specific server:

```bash
docker compose logs -f ardacraft
```

Confirm HuskHomes connected to MariaDB (not SQLite fallback):

```bash
docker compose logs ardacraft | grep -iE "mariadb|database|huskhomes"
```

---

## Port reference

| Service              | Port  | Notes                                      |
|----------------------|-------|--------------------------------------------|
| **velocity** (proxy) | 25577 | **Connect here** — not to individual servers |
| ardacraft            | 25565 | Direct access / remote debug: 5005         |
| building             | 25566 | Direct access / remote debug: 5006         |
| plots *(disabled)*   | 25567 | Commented out by default / debug: 5007     |
| lobby *(disabled)*   | 25568 | Commented out by default / debug: 5008     |

All servers run with `ONLINE_MODE=false` — no Mojang authentication required.
Add `localhost:25577` to your Minecraft client to connect through Velocity.

---

## Remote debugging

The JVM in each server is started with:

```
-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:<debug_port>
```

`suspend=n` means the server boots immediately without waiting for a debugger to attach.

### IntelliJ IDEA

1. **Run → Edit Configurations → + → Remote JVM Debug**
2. Set **Host** to `localhost` and **Port** to the server's debug port (e.g. `5005` for `ardacraft`).
3. Set **Module classpath** to the `fabric` module.
4. Click **Debug** — IntelliJ will attach. Breakpoints in `fabric/` and `common/` source sets will fire normally.

Create one run configuration per server (one per debug port).

### VS Code (with Extension Pack for Java)

Add an entry to `.vscode/launch.json`:

```json
{
  "version": "0.2.0",
  "configurations": [
    {
      "type": "java",
      "name": "Attach ardacraft",
      "request": "attach",
      "hostName": "localhost",
      "port": 5005
    },
    {
      "type": "java",
      "name": "Attach building",
      "request": "attach",
      "hostName": "localhost",
      "port": 5006
    },
    {
      "type": "java",
      "name": "Attach plots",
      "request": "attach",
      "hostName": "localhost",
      "port": 5007
    },
    {
      "type": "java",
      "name": "Attach lobby",
      "request": "attach",
      "hostName": "localhost",
      "port": 5008
    }
  ]
}
```

Select the target configuration from the **Run and Debug** panel and click the play button.

---

## Everyday rebuild workflow

```bash
./gradlew :fabric:build              # rebuild the mod (Fabric module only)
bash dev/scripts/update-mods.sh      # distribute updated jar to all server dirs
podman compose restart ardacraft      # restart only the server you're testing
# or restart all active servers:
podman compose restart ardacraft building
# if you have plots/lobby enabled:
# podman compose restart ardacraft building plots lobby
```

---

## Database credentials

The `huskhomesdb` container uses the following credentials:

| Field    | Value        |
|----------|-------------|
| Host     | `huskhomesdb` (Docker container name on `huskhomes-net`) |
| Port     | `3306`      |
| Database | `huskhomes` |
| User     | `huskhomes` |
| Password | `devpassword` |

These values are pre-filled in all four `dev/config/<server>/huskhomes/config.yml` files.

To open a MariaDB shell:

```bash
podman exec -it huskhomesdb mariadb -uhuskhomes -pdevpassword huskhomes
```

---

## Server linking setup

Once all four servers are running and have connected to the shared database, link them with the in-game admin commands.

Example — link `building` as a slave of `ardacraft`:

```
/huskhomes linkserver ardacraft building
```

If `plots` or `lobby` are enabled, link them the same way:

```
/huskhomes linkserver ardacraft plots
/huskhomes linkserver ardacraft lobby
```

Add a warp permission restriction:

```
/huskhomes lockwarp myWarp some.permission.node
```

Set a player's preferred server:

```
/huskhomes setpreferredserver ardacraft building PlayerName
```

See [README.md](../README.md) for the full admin command reference.

---

## Stopping and cleaning up

Stop all containers (keeps volumes):

```bash
docker compose down
```

Stop and **delete all data volumes** (worlds, logs, DB):

```bash
docker compose down -v
```
