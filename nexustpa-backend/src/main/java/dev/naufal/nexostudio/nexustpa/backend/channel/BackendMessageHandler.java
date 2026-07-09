package dev.naufal.nexostudio.nexustpa.backend.channel;

import com.google.common.io.ByteArrayDataInput;
import dev.naufal.nexostudio.nexustpa.backend.config.BackendConfig;
import dev.naufal.nexostudio.nexustpa.backend.message.MessageManager;
import dev.naufal.nexostudio.nexustpa.backend.request.RequestManager;
import dev.naufal.nexostudio.nexustpa.backend.roster.RosterCache;
import dev.naufal.nexostudio.nexustpa.backend.storage.CooldownRepository;
import dev.naufal.nexostudio.nexustpa.backend.teleport.PendingTeleportManager;
import dev.naufal.nexostudio.nexustpa.common.channel.ChannelConstants;
import dev.naufal.nexostudio.nexustpa.common.channel.ChannelMessageUtil;
import dev.naufal.nexostudio.nexustpa.common.message.MessageKeys;
import dev.naufal.nexostudio.nexustpa.common.model.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Handles incoming plugin messages from the proxy.
 * Runs on the MAIN THREAD (Paper dispatches plugin messages on main thread).
 *
 * <p>REQUEST_RESOLVE with ACCEPTED triggers the async cooldown pipeline:
 * MAIN → ASYNC (cooldown check) → MAIN (re-fetch Player, CAS, teleport/relay).
 */
public class BackendMessageHandler implements PluginMessageListener {

    private final JavaPlugin plugin;
    private final RosterCache rosterCache;
    private final RequestManager requestManager;
    private final PendingTeleportManager pendingTeleportManager;
    private final CooldownRepository cooldownRepository;
    private final BackendMessageSender messageSender;
    private final MessageManager messageManager;
    private final BackendConfig config;

    public BackendMessageHandler(JavaPlugin plugin, RosterCache rosterCache,
                                 RequestManager requestManager,
                                 PendingTeleportManager pendingTeleportManager,
                                 CooldownRepository cooldownRepository,
                                 BackendMessageSender messageSender,
                                 MessageManager messageManager, BackendConfig config) {
        this.plugin = plugin;
        this.rosterCache = rosterCache;
        this.requestManager = requestManager;
        this.pendingTeleportManager = pendingTeleportManager;
        this.cooldownRepository = cooldownRepository;
        this.messageSender = messageSender;
        this.messageManager = messageManager;
        this.config = config;
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player,
                                        byte @NotNull [] data) {
        if (!channel.equals(ChannelConstants.CHANNEL)) return;

        ByteArrayDataInput in = ChannelMessageUtil.newInput(data);
        String action = ChannelMessageUtil.readAction(in);

        switch (action) {
            case ChannelConstants.ACTION_ROSTER_UPDATE -> handleRosterUpdate(in);
            case ChannelConstants.ACTION_CONNECT_RESPONSE -> handleConnectResponse(in);
            case ChannelConstants.ACTION_REQUEST_NOTIFY -> handleRequestNotify(in);
            case ChannelConstants.ACTION_REQUEST_RESOLVE -> handleRequestResolve(in);
            case ChannelConstants.ACTION_REQUEST_CANCEL -> handleRequestCancel(in);
            case ChannelConstants.ACTION_PENDING_TELEPORT -> handlePendingTeleport(in);
            default -> plugin.getLogger().warning("Unknown action: " + action);
        }
    }

    // --- ROSTER_UPDATE: in-memory only ---
    private void handleRosterUpdate(ByteArrayDataInput in) {
        ChannelMessageUtil.RosterUpdateData update = ChannelMessageUtil.readRosterUpdate(in);
        rosterCache.updateRoster(update.groupName(), update.players());
    }

    // --- CONNECT_RESPONSE: notify player on failure ---
    private void handleConnectResponse(ByteArrayDataInput in) {
        ChannelMessageUtil.ConnectResponseData resp = ChannelMessageUtil.readConnectResponse(in);
        UUID travelerUuid = resp.playerUuid();
        if (!resp.success()) {
            UUID requestId = requestManager.getTransferRequestId(travelerUuid);
            TpaRequest request = null;
            if (requestId != null) {
                request = requestManager.getOutgoingByRequestId(requestId);
                if (request == null) request = requestManager.getIncomingByRequestId(travelerUuid, requestId);
            }

            if (request != null) {
                failTransfer(request, RequestCancelReason.TARGET_DISCONNECT);
            } else {
                Player player = Bukkit.getPlayer(travelerUuid);
                if (player != null) {
                    messageManager.send(player, MessageKeys.ERROR_TELEPORT_FAILED);
                }
                requestManager.clearTransferring(travelerUuid);
            }
        }
    }

    // --- REQUEST_NOTIFY: create mirror, notify target ---
    private void handleRequestNotify(ByteArrayDataInput in) {
        ChannelMessageUtil.RequestNotifyData notify = ChannelMessageUtil.readRequestNotify(in);

        // Create mirror request on this server
        TpaRequest mirror = new TpaRequest(
                notify.requestId(), notify.requesterUuid(), notify.requesterName(),
                notify.targetUuid(), notify.targetName(), notify.type(),
                notify.createdAt(), true // mirror = true
        );
        requestManager.mirrorIncoming(mirror);

        // Notify the target player
        Player target = Bukkit.getPlayer(notify.targetUuid());
        if (target != null) {
            String key = (notify.type() == RequestType.TPA)
                    ? MessageKeys.TPA_RECEIVED
                    : MessageKeys.TPA_HERE_RECEIVED;
            messageManager.send(target, key,
                    MessageManager.playerPlaceholder(notify.requesterName()));
        }
    }

    // --- REQUEST_RESOLVE: the complex async pipeline ---
    private void handleRequestResolve(ByteArrayDataInput in) {
        ChannelMessageUtil.RequestResolveData resolve = ChannelMessageUtil.readRequestResolve(in);

        // This is the authoritative side — find the outgoing request
        TpaRequest request = requestManager.getOutgoingByRequestId(resolve.requestId());
        if (request == null) {
            plugin.getLogger().fine("REQUEST_RESOLVE: request not found: " + resolve.requestId());
            return;
        }

        if (resolve.resolution() == RequestState.DENIED) {
            // Deny is simple — CAS and notify
            if (requestManager.deny(request)) {
                Player requester = Bukkit.getPlayer(request.getRequesterUuid());
                if (requester != null) {
                    messageManager.send(requester, MessageKeys.TPA_DENIED_SENDER,
                            MessageManager.playerPlaceholder(request.getTargetName()));
                }
            }
            return;
        }

        // ACCEPTED — async cooldown pipeline
        if (resolve.resolution() == RequestState.ACCEPTED) {
            handleAcceptedResolve(request, resolve.cooldownBypass());
        }
    }

    /**
     * Handles accepted resolve with async cooldown check.
     * MAIN → ASYNC (cooldown) → MAIN (CAS + teleport).
     */
    private void handleAcceptedResolve(TpaRequest request, boolean cooldownBypass) {
        UUID travelerUuid = request.getTravelerUuid();

        // Determine bypass: TPA → check locally, TPAHERE → use payload
        boolean bypass;
        if (request.getType() == RequestType.TPA) {
            // TPA: traveler = requester = local player
            Player traveler = Bukkit.getPlayer(travelerUuid);
            bypass = traveler != null && traveler.hasPermission("nexustpa.cooldown.bypass");
        } else {
            // TPAHERE: traveler = target = remote, use mirror's check
            bypass = cooldownBypass;
        }

        if (bypass) {
            // Skip cooldown check, go straight to teleport
            Bukkit.getScheduler().runTask(plugin, () -> finalizeAccept(request));
        } else {
            // Async cooldown check
            int cooldownSeconds = config.getCooldownSeconds();
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                boolean onCooldown = cooldownRepository.isOnCooldown(travelerUuid, cooldownSeconds);
                int remaining = onCooldown
                        ? cooldownRepository.getRemainingCooldown(travelerUuid, cooldownSeconds)
                        : 0;

                // 🟡 Hop back to main thread, re-fetch Player
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (onCooldown) {
                        // Cooldown active — notify requester, send cancel to mirror
                        Player requester = Bukkit.getPlayer(request.getRequesterUuid());
                        if (requester != null) {
                            messageManager.send(requester, MessageKeys.ERROR_COOLDOWN_ACTIVE,
                                    MessageManager.secondsPlaceholder(remaining));
                        }
                        requestManager.cancel(request);
                        messageSender.sendRequestCancel(request.getRequestId(),
                                RequestCancelReason.COOLDOWN_ACTIVE,
                                request.getTargetUuid());
                        return;
                    }
                    finalizeAccept(request);
                });
            });
        }
    }

    private void failTransfer(TpaRequest request, RequestCancelReason reason) {
        if (request == null) return;
        requestManager.clearTransferring(request.getTravelerUuid());
        requestManager.removeByRequestId(request.getRequestId());

        // Notify traveler
        Player traveler = Bukkit.getPlayer(request.getTravelerUuid());
        if (traveler != null) {
            messageManager.send(traveler, MessageKeys.ERROR_TELEPORT_FAILED);
            if (request.isMirror()) {
                messageSender.sendRequestCancel(request.getRequestId(), reason, request.getRequesterUuid());
            }
        } else {
            UUID relayTarget = request.isMirror() ? request.getRequesterUuid() : request.getTargetUuid();
            messageSender.sendRequestCancel(request.getRequestId(), reason, relayTarget);
        }

        // Notify requester if they are on this server and NOT the traveler (TPAHERE authoritative)
        if (!request.isMirror() && request.getType() == RequestType.TPAHERE) {
            Player requester = Bukkit.getPlayer(request.getRequesterUuid());
            if (requester != null) {
                messageManager.send(requester, MessageKeys.ERROR_TARGET_DISCONNECTED,
                        MessageManager.playerPlaceholder(request.getTargetName()));
            }
        }
    }

    /**
     * Final accept step — MAIN THREAD ONLY.
     * CAS PENDING→ACCEPTED, then teleport or cross-server handoff.
     */
    private void finalizeAccept(TpaRequest request) {
        // CAS check
        if (!requestManager.accept(request)) {
            // Already resolved (expired, cancelled, etc.)
            messageSender.sendRequestCancel(request.getRequestId(),
                    RequestCancelReason.ALREADY_RESOLVED,
                    request.getTargetUuid());
            return;
        }

        UUID travelerUuid = request.getTravelerUuid();
        UUID destPlayerUuid = request.getDestinationPlayerUuid();

        // Notify requester
        Player requester = Bukkit.getPlayer(request.getRequesterUuid());
        if (requester != null) {
            messageManager.send(requester, MessageKeys.TPA_ACCEPTED_SENDER,
                    MessageManager.playerPlaceholder(request.getTargetName()));
        }

        String destServer = rosterCache.getPlayerServer(destPlayerUuid);
        String travelerServer = rosterCache.getPlayerServer(travelerUuid);

        if (destServer == null || travelerServer == null) {
            plugin.getLogger().warning("finalizeAccept: roster cache miss. destServer=" + destServer + ", travelerServer=" + travelerServer);
            failTransfer(request, RequestCancelReason.TARGET_DISCONNECT);
            return;
        }

        // Are they on the same server right now?
        boolean sameServer = travelerServer.equals(destServer);

        if (sameServer) {
            if (destServer.equals(config.getServerName())) {
                // Both are on THIS authoritative server — direct teleport
                Player traveler = Bukkit.getPlayer(travelerUuid);
                Player destPlayer = Bukkit.getPlayer(destPlayerUuid);
                if (traveler != null && destPlayer != null) {
                    traveler.teleport(destPlayer.getLocation());
                    messageManager.send(traveler, MessageKeys.TELEPORT_TELEPORTING,
                            MessageManager.playerPlaceholder(destPlayer.getName()));
                    // Clear mirror if it was originally cross-server
                    messageSender.sendRequestCancel(request.getRequestId(), RequestCancelReason.ALREADY_RESOLVED, request.getTargetUuid());
                } else {
                    failTransfer(request, RequestCancelReason.TARGET_DISCONNECT);
                }
            } else {
                // Both are on a DIFFERENT server (e.g. requester moved to target's server before accepting)
                failTransfer(request, RequestCancelReason.TARGET_DISCONNECT);
            }
            return;
        } else {
            // Cross-server teleport: ask proxy to move traveler to destServer
            PendingTeleport pending = new PendingTeleport(
                    travelerUuid, destPlayerUuid, destServer,
                    System.currentTimeMillis() + (long) config.getPendingTeleportTtl() * 1000
            );
            messageSender.sendPendingTeleport(pending);

            // Always mark as transferring to track CONNECT_RESPONSE and TTL on authoritative
            requestManager.markTransferring(travelerUuid, request.getRequestId());

            Player traveler = Bukkit.getPlayer(travelerUuid);
            if (traveler != null) {
                messageManager.send(traveler, MessageKeys.TELEPORT_TELEPORTING,
                        MessageManager.playerPlaceholder(request.getTargetName()));
            }

            messageSender.sendConnectRequest(travelerUuid, destServer);
        }

        // Set cooldown async
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            cooldownRepository.setLastTeleportAt(travelerUuid, System.currentTimeMillis());
        });
    }

    // --- REQUEST_CANCEL: route by reason ---
    private void handleRequestCancel(ByteArrayDataInput in) {
        ChannelMessageUtil.RequestCancelData cancel = ChannelMessageUtil.readRequestCancel(in);

        switch (cancel.reason()) {
            case CANCELLED, EXPIRED -> {
                // From authoritative → remove mirror on this server
                requestManager.removeMirrorByRequestId(cancel.requestId());
                Player target = Bukkit.getPlayer(cancel.routingPlayerUuid());
                if (target != null) {
                    String key = cancel.reason() == RequestCancelReason.CANCELLED
                            ? MessageKeys.TPA_CANCELLED_TARGET
                            : MessageKeys.TPA_EXPIRED_TARGET;
                    // Need requester name — find from mirror before it was removed
                    messageManager.send(target, key);
                }
            }
            case TARGET_DISCONNECT -> {
                // From mirror side → notify requester, cancel outgoing
                TpaRequest outgoing = requestManager.getOutgoingByRequestId(cancel.requestId());
                if (outgoing != null) {
                    requestManager.cancel(outgoing);
                    Player requester = Bukkit.getPlayer(cancel.routingPlayerUuid());
                    if (requester != null) {
                        messageManager.send(requester, MessageKeys.ERROR_TARGET_DISCONNECTED,
                                MessageManager.playerPlaceholder(outgoing.getTargetName()));
                    }
                }
            }
            case ALREADY_RESOLVED -> {
                // Mirror tried to resolve but authoritative already moved on — silent cleanup
                requestManager.removeMirrorByRequestId(cancel.requestId());
            }
            case COOLDOWN_ACTIVE -> {
                // Authoritative rejected due to cooldown — notify target
                requestManager.removeMirrorByRequestId(cancel.requestId());
                Player target = Bukkit.getPlayer(cancel.routingPlayerUuid());
                if (target != null) {
                    messageManager.send(target, MessageKeys.ERROR_COOLDOWN_CANCEL);
                }
            }
        }
    }

    // --- PENDING_TELEPORT: store for later ---
    private void handlePendingTeleport(ByteArrayDataInput in) {
        PendingTeleport teleport = ChannelMessageUtil.readPendingTeleport(in);
        pendingTeleportManager.addPending(teleport);
    }
}
