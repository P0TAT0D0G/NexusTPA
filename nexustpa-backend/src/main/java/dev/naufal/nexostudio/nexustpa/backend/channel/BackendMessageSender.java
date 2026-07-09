package dev.naufal.nexostudio.nexustpa.backend.channel;

import com.google.common.io.ByteArrayDataOutput;
import dev.naufal.nexostudio.nexustpa.common.channel.ChannelConstants;
import dev.naufal.nexostudio.nexustpa.common.channel.ChannelMessageUtil;
import dev.naufal.nexostudio.nexustpa.common.model.PendingTeleport;
import dev.naufal.nexostudio.nexustpa.common.model.RequestCancelReason;
import dev.naufal.nexostudio.nexustpa.common.model.RequestState;
import dev.naufal.nexostudio.nexustpa.common.model.TpaRequest;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;

/**
 * Utility for sending outgoing plugin messages from backend to proxy.
 * All methods MUST be called from the main thread (sendPluginMessage requirement).
 */
public class BackendMessageSender {

    private final JavaPlugin plugin;

    public BackendMessageSender(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Gets any online player on this server to use as a message carrier.
     * Plugin messaging requires a Player to send through.
     * Returns null if no players are online.
     */
    public Player getCarrierPlayer() {
        var players = Bukkit.getOnlinePlayers();
        if (players.isEmpty()) {
            plugin.getLogger().warning("Cannot send plugin message: no players online");
            return null;
        }
        return players.iterator().next();
    }

    /**
     * Sends a CONNECT_REQUEST to the proxy.
     */
    public void sendConnectRequest(UUID travelerUuid, String targetServer) {
        Player carrier = getCarrierPlayer();
        if (carrier == null) return;

        ByteArrayDataOutput out = ChannelMessageUtil.newOutput();
        out.writeUTF(ChannelConstants.ACTION_CONNECT_REQUEST);
        ChannelMessageUtil.writeConnectRequest(out, travelerUuid, targetServer);
        carrier.sendPluginMessage(plugin, ChannelConstants.CHANNEL, out.toByteArray());
    }

    /**
     * Sends a REQUEST_NOTIFY to relay a new TPA request to the target's server.
     */
    public void sendRequestNotify(TpaRequest request) {
        Player carrier = getCarrierPlayer();
        if (carrier == null) return;

        ByteArrayDataOutput out = ChannelMessageUtil.newOutput();
        out.writeUTF(ChannelConstants.ACTION_REQUEST_NOTIFY);
        ChannelMessageUtil.writeRequestNotify(out, request.getRequestId(),
                request.getRequesterUuid(), request.getRequesterName(),
                request.getTargetUuid(), request.getTargetName(),
                request.getType(), request.getCreatedAt());
        carrier.sendPluginMessage(plugin, ChannelConstants.CHANNEL, out.toByteArray());
    }

    /**
     * Sends a REQUEST_RESOLVE back to the requester's server.
     * Includes requesterUuid for proxy routing and cooldownBypass for TPAHERE.
     */
    public void sendRequestResolve(UUID requestId, UUID requesterUuid,
                                   RequestState resolution, boolean cooldownBypass) {
        Player carrier = getCarrierPlayer();
        if (carrier == null) return;

        ByteArrayDataOutput out = ChannelMessageUtil.newOutput();
        out.writeUTF(ChannelConstants.ACTION_REQUEST_RESOLVE);
        ChannelMessageUtil.writeRequestResolve(out, requestId, requesterUuid,
                resolution, cooldownBypass);
        carrier.sendPluginMessage(plugin, ChannelConstants.CHANNEL, out.toByteArray());
    }

    /**
     * Sends a REQUEST_CANCEL message (direction-agnostic).
     */
    public void sendRequestCancel(UUID requestId, RequestCancelReason reason,
                                  UUID routingPlayerUuid) {
        Player carrier = getCarrierPlayer();
        if (carrier == null) return;

        ByteArrayDataOutput out = ChannelMessageUtil.newOutput();
        out.writeUTF(ChannelConstants.ACTION_REQUEST_CANCEL);
        ChannelMessageUtil.writeRequestCancel(out, requestId, reason, routingPlayerUuid);
        carrier.sendPluginMessage(plugin, ChannelConstants.CHANNEL, out.toByteArray());
    }

    /**
     * Sends a PENDING_TELEPORT to the destination server via proxy.
     */
    public void sendPendingTeleport(PendingTeleport teleport) {
        Player carrier = getCarrierPlayer();
        if (carrier == null) return;

        ByteArrayDataOutput out = ChannelMessageUtil.newOutput();
        out.writeUTF(ChannelConstants.ACTION_PENDING_TELEPORT);
        ChannelMessageUtil.writePendingTeleport(out, teleport);
        carrier.sendPluginMessage(plugin, ChannelConstants.CHANNEL, out.toByteArray());
    }
}
