package dev.naufal.nexostudio.nexustpa.backend.command;

import dev.naufal.nexostudio.nexustpa.backend.channel.BackendMessageSender;
import dev.naufal.nexostudio.nexustpa.backend.config.BackendConfig;
import dev.naufal.nexostudio.nexustpa.backend.message.MessageManager;
import dev.naufal.nexostudio.nexustpa.backend.request.RequestManager;
import dev.naufal.nexostudio.nexustpa.backend.roster.RosterCache;
import dev.naufal.nexostudio.nexustpa.backend.storage.ToggleRepository;
import dev.naufal.nexostudio.nexustpa.common.message.MessageKeys;
import dev.naufal.nexostudio.nexustpa.common.model.RequestType;
import dev.naufal.nexostudio.nexustpa.common.model.TpaRequest;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * /tpa <player> — Request to teleport TO another player.
 * Traveler = requester, destination = target.
 */
public class TpaCommand implements CommandExecutor, TabCompleter {

    private final JavaPlugin plugin;
    private final RequestManager requestManager;
    private final RosterCache rosterCache;
    private final ToggleRepository toggleRepository;
    private final BackendMessageSender messageSender;
    private final MessageManager messageManager;
    private final BackendConfig config;

    public TpaCommand(JavaPlugin plugin, RequestManager requestManager,
                      RosterCache rosterCache, ToggleRepository toggleRepository,
                      BackendMessageSender messageSender, MessageManager messageManager,
                      BackendConfig config) {
        this.plugin = plugin;
        this.requestManager = requestManager;
        this.rosterCache = rosterCache;
        this.toggleRepository = toggleRepository;
        this.messageSender = messageSender;
        this.messageManager = messageManager;
        this.config = config;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player requester)) return true;

        if (args.length < 1) {
            requester.sendMessage("§cUsage: /tpa <player>");
            return true;
        }

        // Network sync check (AGENTS.md §5.3)
        if (!rosterCache.isSynced()) {
            messageManager.send(requester, MessageKeys.ERROR_NETWORK_NOT_READY);
            return true;
        }

        String targetName = args[0];
        UUID targetUuid = rosterCache.getUuidByName(targetName);

        if (targetUuid == null) {
            messageManager.send(requester, MessageKeys.ERROR_PLAYER_NOT_FOUND);
            return true;
        }

        if (targetUuid.equals(requester.getUniqueId())) {
            messageManager.send(requester, MessageKeys.ERROR_SELF_REQUEST);
            return true;
        }

        // Resolve actual name from roster (correct casing)
        String resolvedName = rosterCache.getPlayerName(targetUuid);
        boolean crossServer = !rosterCache.isOnSameServer(targetUuid);

        if (crossServer) {
            // Cross-server: async toggle check via DB
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                boolean accepting = toggleRepository.isAcceptingRequestsFromDb(targetUuid);

                // 🟡 Hop back to main thread, re-fetch Player
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player req = Bukkit.getPlayer(requester.getUniqueId());
                    if (req == null) return; // Disconnected during async

                    if (!accepting) {
                        messageManager.send(req, MessageKeys.ERROR_TOGGLE_OFF);
                        return;
                    }

                    createAndSendRequest(req, targetUuid, resolvedName, true);
                });
            });
        } else {
            // Same-server: toggle check from cache (no async needed)
            if (!toggleRepository.isAcceptingRequests(targetUuid)) {
                messageManager.send(requester, MessageKeys.ERROR_TOGGLE_OFF);
                return true;
            }
            createAndSendRequest(requester, targetUuid, resolvedName, false);
        }

        return true;
    }

    private void createAndSendRequest(Player requester, UUID targetUuid,
                                      String targetName, boolean crossServer) {
        TpaRequest request = requestManager.createRequest(
                requester.getUniqueId(), requester.getName(),
                targetUuid, targetName,
                RequestType.TPA, crossServer);

        // Notify requester
        messageManager.send(requester, MessageKeys.TPA_SENT,
                MessageManager.playerPlaceholder(targetName));

        if (crossServer) {
            // Send relay to target's server via proxy
            messageSender.sendRequestNotify(request);
        } else {
            // Same-server: notify target directly
            Player target = Bukkit.getPlayer(targetUuid);
            if (target != null) {
                messageManager.send(target, MessageKeys.TPA_RECEIVED,
                        MessageManager.playerPlaceholder(requester.getName()));
            }
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender,
                                                @NotNull Command command,
                                                @NotNull String label,
                                                @NotNull String[] args) {
        if (args.length == 1 && sender instanceof Player) {
            String prefix = args[0].toLowerCase();
            return rosterCache.getPlayerNames().stream()
                    .filter(name -> name.toLowerCase().startsWith(prefix))
                    .filter(name -> !name.equalsIgnoreCase(sender.getName()))
                    .toList();
        }
        return Collections.emptyList();
    }
}
