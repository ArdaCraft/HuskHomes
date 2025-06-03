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

public class WarpCommand extends SavedPositionCommand<Warp> {

    protected WarpCommand(@NotNull HuskHomes plugin) {
        super("warp", List.of(), PositionCommandType.WARP, List.of(), plugin);
        addAdditionalPermissions(Map.of("player", true));
    }

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

    private boolean hasServerPermission(@NotNull CommandUser executor, @NotNull String serverName) {
        final Optional<String> permission = plugin.getDatabase().getServerPermission(serverName);
        if (permission.isPresent() && !executor.hasPermission(permission.get())) {
            return false;
        }
        return true;
    }

    private Optional<Warp> resolveWarpWithServer(@NotNull CommandUser executor, @NotNull String warpName, @NotNull String preferredServer) {
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
        }

        // Check user's preferred server for this master from database
        final Optional<String> preferredServer = plugin.getDatabase().getUserPreferredServer(user.getUuid(), masterServer);
        if (preferredServer.isEmpty()) {
            return originalWarp;
        }

        // Validate server permission
        if (!hasServerPermission(user, preferredServer.get())) {
            return originalWarp; // Fall back to original server
        }

        // Try to find warp on preferred server
        final Optional<Warp> preferredWarp = plugin.getDatabase().getWarp(warpName, false)
                .filter(w -> w.getServer().equals(preferredServer.get()));

        if (preferredWarp.isPresent()) {
            return preferredWarp;
        } else {
            // Create a copy of the original warp with the preferred server
            Warp modifiedWarp = originalWarp.get().copy();
            modifiedWarp.setServer(preferredServer.get());
            return Optional.of(modifiedWarp);
        }
    }
}
