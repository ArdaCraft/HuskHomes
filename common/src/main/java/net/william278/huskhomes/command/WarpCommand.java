/*
 * This file is part of HuskHomes, licensed under the Apache License 2.0.
 *
 *  Copyright (c) William278 <will27528@gmail.com>
 *  Copyright (c) contributors
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package net.william278.huskhomes.command;

import net.william278.huskhomes.HuskHomes;
import net.william278.huskhomes.position.Warp;
import net.william278.huskhomes.teleport.Teleportable;
import net.william278.huskhomes.user.CommandUser;
import net.william278.huskhomes.user.OnlineUser;
import net.william278.huskhomes.util.TransactionResolver;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;

public class WarpCommand extends SavedPositionCommand<Warp> {

    protected WarpCommand(@NotNull HuskHomes plugin) {
        super("warp", List.of(), PositionCommandType.WARP, List.of(), plugin);
        addAdditionalPermissions(Map.of("player", true));
    }

    /**
     * Executes the /warp command, extending the default behaviour with server preference resolution.
     *
     * <p>Dispatch order:
     * <ol>
     *   <li>If no args: delegate to {@link WarpListCommand} to show the warp list.</li>
     *   <li>If a second argument is supplied it is treated as an explicit server name; the executor's
     *       server permission is checked, then the warp is resolved via
     *       {@link #resolveWarpWithServer}.</li>
     *   <li>Otherwise, for online players, {@link #resolveWarpWithUserPreference} is tried; if it
     *       returns a warp (i.e. a preferred server is configured) that warp is used directly.</li>
     *   <li>Falls through to the standard upstream {@code super.execute} path.</li>
     * </ol>
     *
     * @param executor the command sender
     * @param args     command arguments: {@code <warpName> [serverName]}
     */
    @Override
    public void execute(@NotNull CommandUser executor, @NotNull String[] args) {
        if (args.length == 0) {
            plugin.getCommand(WarpListCommand.class)
                    .ifPresent(command -> command.showWarpList(executor, 1));
            return;
        }

        final String warpName = args[0];

        // Check if we have an optional server parameter
        if (args.length >= 2) {
            final String preferredServer = args[1];

            // Validate server permission before attempting to resolve warp
            if (!hasServerPermission(executor, preferredServer)) {
                plugin.getLocales().getLocale("error_no_permission")
                        .ifPresent(executor::sendMessage);
                return;
            }

            // Try to resolve warp with specific server preference
            final Optional<Warp> warp = resolveWarpWithServer(executor, warpName, preferredServer);
            warp.ifPresent(w -> execute(executor, w, removeFirstArg(removeFirstArg(args))));
            return;
        }

        // Check if user has preferred server settings and apply them
        if (executor instanceof OnlineUser user) {
            final Optional<Warp> warpWithPreference = resolveWarpWithUserPreference(user, warpName);
            if (warpWithPreference.isPresent()) {
                execute(executor, warpWithPreference.get(), removeFirstArg(args));
                return;
            }
        }

        super.execute(executor, args);
    }

    @Override
    public void execute(@NotNull CommandUser executor, @NotNull Warp warp, @NotNull String[] args) {
        // Check individual warp permissions
        if (!hasWarpPermission(executor, warp)) {
            plugin.getLocales().getLocale("error_no_permission")
                    .ifPresent(executor::sendMessage);
            return;
        }

        // Check server permissions for the warp's server
        if (!hasServerPermission(executor, warp.getServer())) {
            plugin.getLocales().getLocale("error_no_permission")
                    .ifPresent(executor::sendMessage);
            return;
        }

        final Optional<Teleportable> optionalTeleporter = resolveTeleporter(executor, args);
        if (optionalTeleporter.isEmpty()) {
            plugin.getLocales().getLocale("error_invalid_syntax", getUsage())
                    .ifPresent(executor::sendMessage);
            return;
        }

        this.teleport(executor, optionalTeleporter.get(), warp, TransactionResolver.Action.WARP_TELEPORT);
    }

    /**
     * Returns whether {@code executor} has permission to use {@code warp}.
     *
     * <p>Two independent checks are applied in order:
     * <ol>
     *   <li><b>Built-in HuskHomes restriction</b> — if {@code permissionRestrictWarps} is enabled in
     *       settings, the executor must hold {@code warp.getPermission()} or the wildcard
     *       {@link Warp#getWildcardPermission()}.</li>
     *   <li><b>Database-stored permission</b> — if an explicit permission node was set for this warp
     *       via {@code /huskhomes lockwarp}, the executor must also hold that node.</li>
     * </ol>
     *
     * @param executor the command sender
     * @param warp     the target warp
     * @return {@code true} if all applicable permission checks pass
     */
    private boolean hasWarpPermission(@NotNull CommandUser executor, @NotNull Warp warp) {
        // Check built-in permission system
        if (plugin.getSettings().getGeneral().isPermissionRestrictWarps()) {
            if (!executor.hasPermission(warp.getPermission())
                    && !executor.hasPermission(Warp.getWildcardPermission())) {
                return false;
            }
        }

        // Check custom warp permissions from database
        final Optional<String> permission = plugin.getDatabase().getWarpPermission(warp.getName());
        if (permission.isPresent() && !executor.hasPermission(permission.get())) {
            return false;
        }

        return true;
    }

    /**
     * Returns whether {@code executor} has access to warps on {@code serverName}.
     *
     * <p>If a permission node has been registered for the server via
     * {@code /huskhomes lockserver}, the executor must hold that node. If no node is registered
     * for the server, access is unrestricted by this check.
     *
     * @param executor   the command sender
     * @param serverName the name of the server to check access for
     * @return {@code true} if the server has no permission restriction, or the executor holds it
     */
    private boolean hasServerPermission(@NotNull CommandUser executor, @NotNull String serverName) {
        final Optional<String> permission = plugin.getDatabase().getServerPermission(serverName);
        if (permission.isPresent() && !executor.hasPermission(permission.get())) {
            return false;
        }
        return true;
    }

    /**
     * Resolves a warp by name targeting a specific server, for explicit
     * {@code /warp <name> <server>} invocations.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>Look for a warp with {@code warpName} whose {@code server} field matches
     *       {@code preferredServer} directly.</li>
     *   <li>If not found, and {@code preferredServer} is a known slave server, fall back to
     *       looking for the warp on its master server (accounting for replication lag).</li>
     *   <li>If still not found, send {@code error_warp_invalid} to the executor and return
     *       empty.</li>
     * </ol>
     *
     * <p>When found, a copy of the warp is returned with its {@code server} field overridden to
     * {@code preferredServer}, which causes HuskHomes to execute a cross-server teleport.
     *
     * @param executor        the command sender (used for error messages)
     * @param warpName        the name of the warp to look up
     * @param preferredServer the target server name
     * @return the resolved warp with its server field overridden, or empty if not found
     */
    private Optional<Warp> resolveWarpWithServer(@NotNull CommandUser executor, @NotNull String warpName,
                                                 @NotNull String preferredServer) {
        // First try to find the warp on the preferred server
        Optional<Warp> warp = plugin.getDatabase().getWarp(warpName, false)
                .filter(w -> w.getServer().equals(preferredServer));

        if (warp.isEmpty()) {
            // Check if there's a master-slave relationship and try to find on master
            final Optional<String> masterServer = plugin.getDatabase().getMasterServer(preferredServer);
            if (masterServer.isPresent()) {
                warp = plugin.getDatabase().getWarp(warpName, false)
                        .filter(w -> w.getServer().equals(masterServer.get()));
            }
        }

        if (warp.isEmpty()) {
            plugin.getLocales().getLocale("error_warp_invalid", warpName)
                    .ifPresent(executor::sendMessage);
            return Optional.empty();
        }

        // Create a copy of the warp with the preferred server
        Warp originalWarp = warp.get();
        Warp modifiedWarp = originalWarp.copy();
        modifiedWarp.setServer(preferredServer);

        return Optional.of(modifiedWarp);
    }

    /**
     * Attempts to resolve a warp redirected to the user's preferred server.
     *
     * <p>Resolution chain:
     * <ol>
     *   <li>Locate the base warp by name; return empty immediately if it does not exist.</li>
     *   <li>Determine whether the warp's server is a slave (look up its master) or is already a
     *       master. Servers that are part of no linking configuration return the original warp
     *       unchanged.</li>
     *   <li>Look up the user's DB-stored preferred server for the resolved master.</li>
     *   <li>If no DB preference exists, fall back to
     *       {@link #getPermissionBasedDefaultServer}.</li>
     *   <li>Validate the preferred server with {@link #hasServerPermission}; fall back to the
     *       original warp if the user lacks access.</li>
     *   <li>Return the warp on the preferred server — either a direct DB match, or a copy of the
     *       original warp with its {@code server} field overridden.</li>
     * </ol>
     *
     * @param user     the online player whose preferences are checked
     * @param warpName the name of the warp to resolve
     * @return the warp redirected to the preferred server, the original warp if no preference
     *         applies, or empty if the warp does not exist
     */
    private Optional<Warp> resolveWarpWithUserPreference(@NotNull OnlineUser user, @NotNull String warpName) {
        // Find the warp first to determine its master server
        final Optional<Warp> originalWarp = plugin.getDatabase().getWarp(warpName, false);
        if (originalWarp.isEmpty()) {
            return Optional.empty();
        }

        final String warpServer = originalWarp.get().getServer();
        String masterServer;

        // Check if the warp's server is a slave server, and find its master
        final Optional<String> foundMaster = plugin.getDatabase().getMasterServer(warpServer);
        if (foundMaster.isPresent()) {
            masterServer = foundMaster.get();
        } else if (!plugin.getDatabase().getSlaveServers(warpServer).isEmpty()) {
            // This server is already a master
            masterServer = warpServer;
        } else {
            // Not part of any linking configuration
            return originalWarp;
        }        // Check user's preferred server for this master from database
        Optional<String> preferredServer = plugin.getDatabase().getUserPreferredServer(user.getUuid(), masterServer);
        
        // If no user preference set, check for permission-based default
        if (preferredServer.isEmpty()) {
            preferredServer = getPermissionBasedDefaultServer(user, masterServer);
        }
        
        if (preferredServer.isEmpty()) {
            return originalWarp;
        }

        final String finalPreferredServer = preferredServer.get();

        // Validate server permission
        if (!hasServerPermission(user, finalPreferredServer)) {
            return originalWarp; // Fall back to original server
        }

        // Try to find warp on preferred server
        final Optional<Warp> preferredWarp = plugin.getDatabase().getWarp(warpName, false)
                .filter(w -> w.getServer().equals(finalPreferredServer));

        if (preferredWarp.isPresent()) {
            return preferredWarp;
        } else {
            // Create a copy of the original warp with the preferred server
            Warp modifiedWarp = originalWarp.get().copy();
            modifiedWarp.setServer(finalPreferredServer);
            return Optional.of(modifiedWarp);
        }
    }

    /**
     * Checks for permission-based default preferred server for the given master server.
     * Permission format: huskhomes.linkedserver.{@code <master-server>.preferreddefault.<preferred-server>}.     *
     *
     * @param user The user to check permissions for
     * @param masterServer The master server name
     * @return Optional containing the preferred server name if permission is found, empty otherwise
     */
    private Optional<String> getPermissionBasedDefaultServer(@NotNull OnlineUser user, @NotNull String masterServer) {
        try {
            // Get all slave servers for this master
            final List<String> linkedServers = plugin.getDatabase().getSlaveServers(masterServer);
            
            // Add the master server itself as a possible preference target
            linkedServers.add(masterServer);
            
            // Check permissions for each linked server
            for (String serverName : linkedServers) {
                if (serverName == null || serverName.trim().isEmpty()) {
                    continue; // Skip invalid server names
                }
                
                // Normalize server names to lowercase for permission consistency
                String permission = "huskhomes.linkedserver."
                        + masterServer.toLowerCase().trim()
                        + ".preferreddefault."
                        + serverName.toLowerCase().trim();
                
                if (user.hasPermission(permission)) {
                    return Optional.of(serverName);
                }
            }
        } catch (Exception e) {
            plugin.log(Level.WARNING, "Error checking permission-based default server for user "
                    + user.getUsername()
                    + " and master "
                    + masterServer, e);
        }
        
        return Optional.empty();
    }
}
