# Developer Environment — ArdaCraft HuskHomes Fork

This document covers building the project and setting up and running the local **multi-server** dev environment.

## Table of Contents

1. [Build](#build)
2. [Prerequisites](#prerequisites)
3. [Database Setup](#database-setup)
4. [Directory layout](#directory-layout)
5. [First-time setup](#first-time-setup)
6. [Starting the cluster](#starting-the-cluster)
7. [Port reference](#port-reference)
8. [Rebuild workflow](#rebuild-workflow)
9. [Stopping and cleaning up](#stopping-and-cleaning-up) 
10. [Connecting to the cluster](#connecting-to-the-cluster)


## Build

Run Gradle task `./gradlew shadowJar`. The output build will be in directory `fabric/build/libs/<HuskHomes-version>.jar`.

**Note** : Production ready builds needs to be run when the git tree is **clean** and a **matching tag** is found on the repo.

---

## Prerequisites

| Tool | Version |
|------|---------|
| Java | **21** (JDK 22 triggers a stricter `CompletionFailure` on `:bukkit:compileJava`) |
 | MariaDB | Not included in the compose, separate instance for convenience. |
| Docker + Docker Compose | Docker ≥ 24, Compose plugin v2 |
| Gradle wrapper | bundled (`./gradlew`) |

### Required mods 

These mods are required for the dev environment to run. They should be downloaded manually and placed in the `mods/`
directory and will be mounted when `dev/scripts/update-mods.sh` is run.

- [LuckPerms v5.4](https://modrinth.com/plugin/luckperms/version/5.4.102)
- [Fabric API v0.92.7+1.20.1](https://modrinth.com/mod/fabric-api/version/0.92.7+1.20.1)
- [Fabric Carpet v1.20-1.4.112](https://modrinth.com/mod/carpet/version/1.4.112) - Simulated Players on each instance

---

## Database setup

Huskhomes needs a database in order to run. It is advised to run a local instance on a separate container for ease of use.

### Setting up a local MariaDB instance

```bash
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

To copy a file from the host to the container `/tmp` (eg: database dumps):

```bash
podman cp ./dump.sql huskhomesdb:/tmp/dump.sql
```

Opening a bash shell on the container:

```bash
podman exec -it huskhomesdb /bin/bash
```

Importing a dump file from the container's bash shell:

```bash
mysql -u huskhomes -p huskhomes < /tmp/backup.sql 
```
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

```bash
./gradlew :fabric:build
```

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
Re-run this script after every rebuild or after adding/updating a shared mod.

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

This starts all four backend servers (**ardacraft**, **building**, **plots**, **lobby**),
**redis** (message broker), and **velocity** (proxy).
All Minecraft servers connect to the already-running `huskhomesdb` container via the
shared `huskhomes-net` network.

Each server boots as a single-biome flat world capped to one chunk
(`SIMULATION_DISTANCE=1`, `VIEW_DISTANCE=2`, `worldborder set 16`):

| Server     | Biome            | Fake player      |
|------------|------------------|------------------|
| ardacraft  | Plains (prairie) | andy-ardacraft   |
| building   | Desert           | bob-building     |
| plots      | Mangrove swamp   | patrick-plots    |
| lobby      | Snowy plains     | larry-lobby      |

Fake players are spawned automatically via RCON on every server start using the
Carpet mod (`/player <name> spawn`). They will vanish if the server stops and
re-appear on the next startup — this is expected behaviour.

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
| plots                | 25567 | Direct access / remote debug: 5007         |
| lobby                | 25568 | Direct access / remote debug: 5008         |

All servers run with `ONLINE_MODE=false` — no Mojang authentication required.
Add `localhost:25577` to your Minecraft client to connect through Velocity.

---

## Rebuild workflow

```bash
./gradlew :fabric:build              # rebuild the mod (Fabric module only)
bash dev/scripts/update-mods.sh      # distribute updated jar to all server dirs
podman compose restart ardacraft      # restart only the server you're testing
# or restart all active servers:
podman compose restart ardacraft building plots lobby
```

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

## Connecting to the cluster

The velocity proxy is your entrypoint to connect to the cluster. Launch a **Minecraft client** go to multiplayer and add
 the following server: 
- Server name: `HuskHomes Ardacraft Dev`
- Server address: `localhost:25577`

Upon connecting, you will be in the **lobby server** (as per the velocity.toml conf).
From there you can use the `/server` command to switch between servers.