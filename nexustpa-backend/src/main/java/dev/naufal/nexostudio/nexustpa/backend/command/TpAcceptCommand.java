package dev.naufal.nexostudio.nexustpa.backend.command;

import dev.naufal.nexostudio.nexustpa.backend.channel.BackendMessageSender;
import dev.naufal.nexostudio.nexustpa.backend.config.BackendConfig;
import dev.naufal.nexostudio.nexustpa.backend.message.MessageManager;
import dev.naufal.nexostudio.nexustpa.backend.request.RequestManager;
import dev.naufal.nexostudio.nexustpa.backend.roster.RosterCache;
import dev.naufal.nexostudio.nexustpa.backend.storage.CooldownRepository;
import dev.naufal.nexostudio.nexustpa.common.message.MessageKeys;
import dev.naufal.nexostudio.nexustpa.common.model.*;
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
 * /tpaccept [player] — Accept an incoming TPA request.
 * If multiple pending, requires player name argument.
 * Handles both same-server (authoritative) and cross-server (mirror) paths.
 */
public class TpAcceptCommand implements CommandExecutor, TabCompleter {

    private final JavaPlugin plugin;
    private final RequestManager requestManager;
    private final RosterCache rosterCache;
    private final CooldownRepository cooldownRepository;
    private final BackendMessageSender messageSender;
    private final MessageManager messageManager;
    private final BackendConfig config;

    public TpAcceptCommand(JavaPlugin plugin, RequestManager requestManager,
                           RosterCache rosterCache, CooldownRepository cooldownRepository,
                           BackendMessageSender messageSender, MessageManager messageManager,
                           BackendConfig config) {
        this.plugin = plugin;
        this.requestManager = requestManager;
        this.rosterCache = rosterCache;
        this.cooldownRepository = cooldownRepository;
        this.messageSender = messageSender;
        this.messageManager = messageManager;
        this.config = config;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player target)) return true;

        List<TpaRequest> pending = requestManager.getIncoming(target.getUniqueId());

        if (pending.isEmpty()) {
            messageManager.send(target, MessageKeys.ERROR_NO_PENDING);
            return true;
        }

        TpaRequest request;
        if (pending.size() == 1) {
            request = pending.get(0);
        } else if (args.length >= 1) {
            UUID requesterUuid = rosterCache.getUuidByName(args[0]);
            if (requesterUuid == null) {
                messageManager.send(target, MessageKeys.ERROR_PLAYER_NOT_FOUND);
                return true;
            }
            request = requestManager.getIncomingFrom(target.getUniqueId(), requesterUuid);
            if (request == null) {
                messageManager.send(target, MessageKeys.ERROR_NO_PENDING);
                return true;
            }
        } else {
            messageManager.send(target, MessageKeys.ERROR_MULTIPLE_PENDING);
            return true;
        }

        if (request.isMirror()) {
            // Cross-server mirror: send resolve to authoritative
            handleMirrorAccept(target, request);
        } else {
            // Same-server authoritative: async cooldown then teleport
            handleAuthoritativeAccept(target, request);
        }

        return true;
    }

    /**
     * Mirror accept: notify target, send REQUEST_RESOLVE to authoritative via proxy.
     */
    private void handleMirrorAccept(Player target, TpaRequest request) {
        if (!requestManager.accept(request)) {
            messageManager.send(target, MessageKeys.ERROR_REQUEST_INVALID);
            return;
        }

        messageManager.send(target, MessageKeys.TPA_ACCEPTED,
                MessageManager.playerPlaceholder(request.getRequesterName()));

        // Determine cooldownBypass for the traveler
        // TPA: traveler = requester (remote) → can't check, send false
        // TPAHERE: traveler = target (local) → check hasPermission here
        boolean cooldownBypass = false;
        if (request.getType() == RequestType.TPAHERE) {
            cooldownBypass = target.hasPermission("nexustpa.cooldown.bypass");
        }

        messageSender.sendRequestResolve(request.getRequestId(),
                request.getRequesterUuid(), RequestState.ACCEPTED, cooldownBypass);
    }

    /**
     * Same-server authoritative accept: async cooldown check then teleport.
     */
    private void handleAuthoritativeAccept(Player target, TpaRequest request) {
        UUID travelerUuid = request.getTravelerUuid();

        // Determine bypass locally
        Player traveler = Bukkit.getPlayer(travelerUuid);
        boolean bypass = traveler != null && traveler.hasPermission("nexustpa.cooldown.bypass");

        if (bypass) {
            finalizeSameServerAccept(target, request);
        } else {
            int cooldownSeconds = config.getCooldownSeconds();
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                boolean onCooldown = cooldownRepository.isOnCooldown(travelerUuid, cooldownSeconds);
                int remaining = onCooldown
                        ? cooldownRepository.getRemainingCooldown(travelerUuid, cooldownSeconds)
                        : 0;

                // 🟡 Hop back to main, re-fetch Players
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player t = Bukkit.getPlayer(target.getUniqueId());
                    if (t == null) return;

                    if (onCooldown) {
                        messageManager.send(t, MessageKeys.ERROR_COOLDOWN_ACTIVE,
                                MessageManager.secondsPlaceholder(remaining));
                        return;
                    }
                    finalizeSameServerAccept(t, request);
                });
            });
        }
    }

    private void finalizeSameServerAccept(Player target, TpaRequest request) {
        if (!requestManager.accept(request)) {
            messageManager.send(target, MessageKeys.ERROR_REQUEST_INVALID);
            return;
        }

        UUID travelerUuid = request.getTravelerUuid();
        UUID destPlayerUuid = request.getDestinationPlayerUuid();

        // 🟡 Re-fetch players
        Player traveler = Bukkit.getPlayer(travelerUuid);
        Player destPlayer = Bukkit.getPlayer(destPlayerUuid);

        messageManager.send(target, MessageKeys.TPA_ACCEPTED,
                MessageManager.playerPlaceholder(request.getRequesterName()));

        if (traveler != null && destPlayer != null) {
            Player requester = Bukkit.getPlayer(request.getRequesterUuid());
            if (requester != null) {
                messageManager.send(requester, MessageKeys.TPA_ACCEPTED_SENDER,
                        MessageManager.playerPlaceholder(request.getTargetName()));
            }
            traveler.teleport(destPlayer.getLocation());
            messageManager.send(traveler, MessageKeys.TELEPORT_TELEPORTING,
                    MessageManager.playerPlaceholder(destPlayer.getName()));

            // Set cooldown async
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                cooldownRepository.setLastTeleportAt(travelerUuid, System.currentTimeMillis());
            });
        } else {
            messageManager.send(target, MessageKeys.ERROR_TELEPORT_FAILED);
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender,
                                                @NotNull Command command,
                                                @NotNull String label,
                                                @NotNull String[] args) {
        if (args.length == 1 && sender instanceof Player player) {
            List<TpaRequest> pending = requestManager.getIncoming(player.getUniqueId());
            String prefix = args[0].toLowerCase();
            return pending.stream()
                    .map(TpaRequest::getRequesterName)
                    .filter(name -> name.toLowerCase().startsWith(prefix))
                    .toList();
        }
        return Collections.emptyList();
    }
}
