package dev.naufal.nexostudio.nexustpa.proxy.channel;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import dev.naufal.nexostudio.nexustpa.common.channel.ChannelConstants;
import dev.naufal.nexostudio.nexustpa.common.channel.ChannelMessageUtil;
import dev.naufal.nexostudio.nexustpa.common.model.RequestCancelReason;
import dev.naufal.nexostudio.nexustpa.proxy.index.PlayerIndex;
import org.slf4j.Logger;

import java.util.Optional;
import java.util.UUID;

/**
 * Handles all plugin messages received from backend servers.
 * Routes by action — proxy is a dumb relay, does NOT store request state.
 *
 * <p>Routing uses live {@link PlayerIndex} lookups so messages go to
 * the player's current server, not a cached/stale one.
 */
public class ProxyMessageHandler {

    private final ProxyServer server;
    private final PlayerIndex playerIndex;
    private final RosterPusher rosterPusher;
    private final ChannelIdentifier channel;
    private final Logger logger;

    public ProxyMessageHandler(ProxyServer server, PlayerIndex playerIndex,
                               RosterPusher rosterPusher, ChannelIdentifier channel,
                               Logger logger) {
        this.server = server;
        this.playerIndex = playerIndex;
        this.rosterPusher = rosterPusher;
        this.channel = channel;
        this.logger = logger;
    }

    /**
     * Handles a raw plugin message from a backend server.
     *
     * @param data             raw message bytes
     * @param sourceServerName the server that sent this message
     */
    public void handle(byte[] data, String sourceServerName) {
        ByteArrayDataInput in = ChannelMessageUtil.newInput(data);
        String action = ChannelMessageUtil.readAction(in);

        switch (action) {
            case ChannelConstants.ACTION_CONNECT_REQUEST -> handleConnectRequest(in, sourceServerName);
            case ChannelConstants.ACTION_REQUEST_NOTIFY -> handleRequestNotify(data, in, sourceServerName);
            case ChannelConstants.ACTION_REQUEST_RESOLVE -> handleRequestResolve(data, in);
            case ChannelConstants.ACTION_REQUEST_CANCEL -> handleRequestCancel(data, in);
            case ChannelConstants.ACTION_PENDING_TELEPORT -> handlePendingTeleport(data, in);
            default -> logger.warn("Unknown action '{}' from server {}", action, sourceServerName);
        }
    }

    /**
     * CONNECT_REQUEST: Backend asks proxy to move a player to a server.
     * Proxy finds the player and creates a connection request.
     *
     * Verified: Player.createConnectionRequest(RegisteredServer).connect()
     * returns CompletableFuture<ConnectionRequestBuilder.Result>
     */
    private void handleConnectRequest(ByteArrayDataInput in, String sourceServerName) {
        ChannelMessageUtil.ConnectRequestData req = ChannelMessageUtil.readConnectRequest(in);

        Optional<Player> playerOpt = server.getPlayer(req.playerUuid());
        if (playerOpt.isEmpty()) {
            logger.debug("CONNECT_REQUEST: player {} not found", req.playerUuid());
            sendConnectResponse(sourceServerName, req.playerUuid(), false);
            return;
        }

        Optional<RegisteredServer> targetOpt = server.getServer(req.targetServer());
        if (targetOpt.isEmpty()) {
            logger.warn("CONNECT_REQUEST: server '{}' not found", req.targetServer());
            sendConnectResponse(sourceServerName, req.playerUuid(), false);
            return;
        }

        Player player = playerOpt.get();
        RegisteredServer target = targetOpt.get();

        player.createConnectionRequest(target).connect().thenAccept(result -> {
            boolean success = result.isSuccessful();
            if (!success) {
                logger.debug("CONNECT_REQUEST: failed to connect {} to {}",
                        player.getUsername(), req.targetServer());
            }
            sendConnectResponse(sourceServerName, req.playerUuid(), success);
        }).exceptionally(ex -> {
            logger.error("CONNECT_REQUEST: exception connecting player", ex);
            sendConnectResponse(sourceServerName, req.playerUuid(), false);
            return null;
        });
    }

    /**
     * Sends CONNECT_RESPONSE back to the requesting server.
     */
    private void sendConnectResponse(String serverName, UUID playerUuid, boolean success) {
        ByteArrayDataOutput out = ChannelMessageUtil.newOutput();
        out.writeUTF(ChannelConstants.ACTION_CONNECT_RESPONSE);
        ChannelMessageUtil.writeConnectResponse(out, playerUuid, success);
        rosterPusher.sendToServer(serverName, out.toByteArray());
    }

    /**
     * REQUEST_NOTIFY: Backend A created a new TPA request, relay to target's server.
     * Reads targetUuid from payload to determine destination.
     * If target is offline, sends REQUEST_CANCEL(TARGET_DISCONNECT) back.
     */
    private void handleRequestNotify(byte[] rawData, ByteArrayDataInput in,
                                     String sourceServerName) {
        ChannelMessageUtil.RequestNotifyData notify = ChannelMessageUtil.readRequestNotify(in);

        String targetServer = playerIndex.getPlayerServer(notify.targetUuid());
        if (targetServer == null) {
            // Target not online — send cancel back to source
            logger.debug("REQUEST_NOTIFY: target {} offline, sending cancel back",
                    notify.targetUuid());
            sendRequestCancel(sourceServerName, notify.requestId(),
                    RequestCancelReason.TARGET_DISCONNECT, notify.requesterUuid());
            return;
        }

        // Forward the entire raw message to target's server
        if (!rosterPusher.sendToServer(targetServer, rawData)) {
            logger.warn("REQUEST_NOTIFY: failed to forward to server {}", targetServer);
            sendRequestCancel(sourceServerName, notify.requestId(),
                    RequestCancelReason.TARGET_DISCONNECT, notify.requesterUuid());
        }
    }

    /**
     * REQUEST_RESOLVE: Target accepted/denied, relay to requester's server.
     * Reads requesterUuid from payload to determine destination.
     */
    private void handleRequestResolve(byte[] rawData, ByteArrayDataInput in) {
        ChannelMessageUtil.RequestResolveData resolve = ChannelMessageUtil.readRequestResolve(in);

        String requesterServer = playerIndex.getPlayerServer(resolve.requesterUuid());
        if (requesterServer == null) {
            // Requester disconnected — drop (stale)
            logger.debug("REQUEST_RESOLVE: requester {} offline, dropping", resolve.requesterUuid());
            return;
        }

        if (!rosterPusher.sendToServer(requesterServer, rawData)) {
            logger.warn("REQUEST_RESOLVE: failed to forward to server {}", requesterServer);
        }
    }

    /**
     * REQUEST_CANCEL: Direction-agnostic cancel relay.
     * Reads routingPlayerUuid from payload to determine destination.
     */
    private void handleRequestCancel(byte[] rawData, ByteArrayDataInput in) {
        ChannelMessageUtil.RequestCancelData cancel = ChannelMessageUtil.readRequestCancel(in);

        String targetServer = playerIndex.getPlayerServer(cancel.routingPlayerUuid());
        if (targetServer == null) {
            // Routing target offline — drop
            logger.debug("REQUEST_CANCEL: routing player {} offline, dropping",
                    cancel.routingPlayerUuid());
            return;
        }

        if (!rosterPusher.sendToServer(targetServer, rawData)) {
            logger.debug("REQUEST_CANCEL: failed to forward to server {}", targetServer);
        }
    }

    /**
     * Constructs and sends a REQUEST_CANCEL message.
     * Used when proxy detects a player is offline and needs to notify the source.
     */
    private void sendRequestCancel(String serverName, UUID requestId,
                                   RequestCancelReason reason, UUID routingPlayerUuid) {
        ByteArrayDataOutput out = ChannelMessageUtil.newOutput();
        out.writeUTF(ChannelConstants.ACTION_REQUEST_CANCEL);
        ChannelMessageUtil.writeRequestCancel(out, requestId, reason, routingPlayerUuid);
        rosterPusher.sendToServer(serverName, out.toByteArray());
    }

    /**
     * PENDING_TELEPORT: Relay pending teleport data to the destination server.
     * Reads destination server name from payload.
     */
    private void handlePendingTeleport(byte[] rawData, ByteArrayDataInput in) {
        // Need to read the destination server from the pending teleport data
        // PendingTeleport format: travelerUuid, destinationPlayerUuid, destinationServer, expiresAt
        // We need to peek at destinationServer without consuming the full payload
        // Since we already consumed the action string, re-read from raw data
        ByteArrayDataInput fullIn = ChannelMessageUtil.newInput(rawData);
        ChannelMessageUtil.readAction(fullIn); // skip action
        ChannelMessageUtil.readUUID(fullIn);   // skip travelerUuid
        ChannelMessageUtil.readUUID(fullIn);   // skip destinationPlayerUuid
        String destinationServer = fullIn.readUTF();

        if (!rosterPusher.sendToServer(destinationServer, rawData)) {
            logger.warn("PENDING_TELEPORT: failed to forward to server {}", destinationServer);
        }
    }
}
