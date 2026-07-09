package dev.naufal.nexostudio.nexustpa.common.channel;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import dev.naufal.nexostudio.nexustpa.common.model.PendingTeleport;
import dev.naufal.nexostudio.nexustpa.common.model.RequestCancelReason;
import dev.naufal.nexostudio.nexustpa.common.model.RequestState;
import dev.naufal.nexostudio.nexustpa.common.model.RequestType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Shared serialization utilities for plugin messaging.
 * Both proxy and backend use Guava (provided by their platforms),
 * so ByteArrayDataOutput/ByteArrayDataInput are safe here.
 *
 * <p>Every message starts with an action string (from {@link ChannelConstants}),
 * followed by action-specific fields. The action string is written/read by the
 * caller — these methods handle only the payload after the action.
 *
 * <p>Field order in each write method MUST exactly match the corresponding read method.
 * This is the shared contract between proxy and backend (AGENTS.md §4.4).
 */
public final class ChannelMessageUtil {

    private ChannelMessageUtil() {
    }

    // =========================================================================
    // Primitives
    // =========================================================================

    /**
     * Creates a new output stream for building a plugin message.
     * Caller should write the action string first, then call payload methods.
     */
    public static ByteArrayDataOutput newOutput() {
        return ByteStreams.newDataOutput();
    }

    /**
     * Wraps raw bytes for reading a plugin message.
     */
    public static ByteArrayDataInput newInput(byte[] data) {
        return ByteStreams.newDataInput(data);
    }

    /**
     * Reads the action string (first field) from a plugin message.
     */
    public static String readAction(ByteArrayDataInput in) {
        return in.readUTF();
    }

    /**
     * Writes a UUID as two longs (most significant bits, least significant bits).
     */
    public static void writeUUID(ByteArrayDataOutput out, UUID uuid) {
        out.writeLong(uuid.getMostSignificantBits());
        out.writeLong(uuid.getLeastSignificantBits());
    }

    /**
     * Reads a UUID written by {@link #writeUUID}.
     */
    public static UUID readUUID(ByteArrayDataInput in) {
        long msb = in.readLong();
        long lsb = in.readLong();
        return new UUID(msb, lsb);
    }

    // =========================================================================
    // ROSTER_UPDATE: Proxy → Backend
    // Fields: groupName, playerCount, [uuid, name, serverName] * playerCount
    // =========================================================================

    /**
     * Entry in a roster update: player name + server name.
     */
    public record RosterEntry(String name, String serverName) {
    }

    /**
     * Result of reading a roster update message.
     */
    public record RosterUpdateData(String groupName, Map<UUID, RosterEntry> players) {
    }

    /**
     * Writes a full roster update payload.
     * Called by proxy's RosterPusher after writing the action string.
     */
    public static void writeRosterUpdate(ByteArrayDataOutput out, String groupName,
                                         Map<UUID, RosterEntry> players) {
        out.writeUTF(groupName);
        out.writeInt(players.size());
        for (Map.Entry<UUID, RosterEntry> entry : players.entrySet()) {
            writeUUID(out, entry.getKey());
            out.writeUTF(entry.getValue().name());
            out.writeUTF(entry.getValue().serverName());
        }
    }

    /**
     * Reads a roster update payload written by {@link #writeRosterUpdate}.
     */
    public static RosterUpdateData readRosterUpdate(ByteArrayDataInput in) {
        String groupName = in.readUTF();
        int count = in.readInt();
        Map<UUID, RosterEntry> players = new HashMap<>(count);
        for (int i = 0; i < count; i++) {
            UUID uuid = readUUID(in);
            String name = in.readUTF();
            String serverName = in.readUTF();
            players.put(uuid, new RosterEntry(name, serverName));
        }
        return new RosterUpdateData(groupName, players);
    }

    // =========================================================================
    // CONNECT_REQUEST: Backend → Proxy
    // Fields: playerUuid, targetServerName
    // =========================================================================

    /**
     * Result of reading a connect request message.
     */
    public record ConnectRequestData(UUID playerUuid, String targetServer) {
    }

    public static void writeConnectRequest(ByteArrayDataOutput out, UUID playerUuid,
                                           String targetServer) {
        writeUUID(out, playerUuid);
        out.writeUTF(targetServer);
    }

    public static ConnectRequestData readConnectRequest(ByteArrayDataInput in) {
        UUID playerUuid = readUUID(in);
        String targetServer = in.readUTF();
        return new ConnectRequestData(playerUuid, targetServer);
    }

    // =========================================================================
    // CONNECT_RESPONSE: Proxy → Backend
    // Fields: playerUuid, success
    // =========================================================================

    /**
     * Result of reading a connect response message.
     */
    public record ConnectResponseData(UUID playerUuid, boolean success) {
    }

    public static void writeConnectResponse(ByteArrayDataOutput out, UUID playerUuid,
                                            boolean success) {
        writeUUID(out, playerUuid);
        out.writeBoolean(success);
    }

    public static ConnectResponseData readConnectResponse(ByteArrayDataInput in) {
        UUID playerUuid = readUUID(in);
        boolean success = in.readBoolean();
        return new ConnectResponseData(playerUuid, success);
    }

    // =========================================================================
    // REQUEST_NOTIFY: Backend A → Proxy → Backend B
    // Fields: requestId, requesterUuid, requesterName, targetUuid, targetName,
    //         type (ordinal), createdAt
    // =========================================================================

    /**
     * Result of reading a request notify message.
     */
    public record RequestNotifyData(UUID requestId, UUID requesterUuid, String requesterName,
                                    UUID targetUuid, String targetName, RequestType type,
                                    long createdAt) {
    }

    public static void writeRequestNotify(ByteArrayDataOutput out, UUID requestId,
                                          UUID requesterUuid, String requesterName,
                                          UUID targetUuid, String targetName,
                                          RequestType type, long createdAt) {
        writeUUID(out, requestId);
        writeUUID(out, requesterUuid);
        out.writeUTF(requesterName);
        writeUUID(out, targetUuid);
        out.writeUTF(targetName);
        out.writeInt(type.ordinal());
        out.writeLong(createdAt);
    }

    public static RequestNotifyData readRequestNotify(ByteArrayDataInput in) {
        UUID requestId = readUUID(in);
        UUID requesterUuid = readUUID(in);
        String requesterName = in.readUTF();
        UUID targetUuid = readUUID(in);
        String targetName = in.readUTF();
        RequestType type = RequestType.values()[in.readInt()];
        long createdAt = in.readLong();
        return new RequestNotifyData(requestId, requesterUuid, requesterName,
                targetUuid, targetName, type, createdAt);
    }

    // =========================================================================
    // REQUEST_RESOLVE: Backend B → Proxy → Backend A
    // Fields: requestId, requesterUuid, resolution (ordinal), cooldownBypass
    //
    // requesterUuid is included so the proxy can route this message to the
    // requester's current server (proxy looks up PlayerIndex by UUID).
    //
    // v4: cooldownBypass boolean included — mirror checks traveler's
    //     hasPermission("nexustpa.cooldown.bypass") for TPAHERE case.
    //     For TPA, authoritative checks locally (ignores this field).
    // =========================================================================

    /**
     * Result of reading a request resolve message.
     */
    public record RequestResolveData(UUID requestId, UUID requesterUuid,
                                     RequestState resolution, boolean cooldownBypass) {
    }

    public static void writeRequestResolve(ByteArrayDataOutput out, UUID requestId,
                                           UUID requesterUuid, RequestState resolution,
                                           boolean cooldownBypass) {
        writeUUID(out, requestId);
        writeUUID(out, requesterUuid);
        out.writeInt(resolution.ordinal());
        out.writeBoolean(cooldownBypass);
    }

    public static RequestResolveData readRequestResolve(ByteArrayDataInput in) {
        UUID requestId = readUUID(in);
        UUID requesterUuid = readUUID(in);
        RequestState resolution = RequestState.values()[in.readInt()];
        boolean cooldownBypass = in.readBoolean();
        return new RequestResolveData(requestId, requesterUuid, resolution, cooldownBypass);
    }

    // =========================================================================
    // REQUEST_CANCEL: either direction via Proxy
    // Fields: requestId, reason (ordinal), targetUuid
    //
    // targetUuid is included so the proxy knows which player's server to route to.
    // For authoritative→mirror: targetUuid = request's target (mirror side).
    // For mirror→authoritative: targetUuid = request's requester (authoritative side).
    // =========================================================================

    /**
     * Result of reading a request cancel message.
     */
    public record RequestCancelData(UUID requestId, RequestCancelReason reason,
                                    UUID routingPlayerUuid) {
    }

    public static void writeRequestCancel(ByteArrayDataOutput out, UUID requestId,
                                          RequestCancelReason reason, UUID routingPlayerUuid) {
        writeUUID(out, requestId);
        out.writeInt(reason.ordinal());
        writeUUID(out, routingPlayerUuid);
    }

    public static RequestCancelData readRequestCancel(ByteArrayDataInput in) {
        UUID requestId = readUUID(in);
        RequestCancelReason reason = RequestCancelReason.values()[in.readInt()];
        UUID routingPlayerUuid = readUUID(in);
        return new RequestCancelData(requestId, reason, routingPlayerUuid);
    }

    // =========================================================================
    // PENDING_TELEPORT: Backend → Proxy → Backend
    // Fields: travelerUuid, destinationPlayerUuid, destinationServer, expiresAt
    // =========================================================================

    public static void writePendingTeleport(ByteArrayDataOutput out, PendingTeleport teleport) {
        writeUUID(out, teleport.getTravelerUuid());
        writeUUID(out, teleport.getDestinationPlayerUuid());
        out.writeUTF(teleport.getDestinationServer());
        out.writeLong(teleport.getExpiresAt());
    }

    public static PendingTeleport readPendingTeleport(ByteArrayDataInput in) {
        UUID travelerUuid = readUUID(in);
        UUID destinationPlayerUuid = readUUID(in);
        String destinationServer = in.readUTF();
        long expiresAt = in.readLong();
        return new PendingTeleport(travelerUuid, destinationPlayerUuid,
                destinationServer, expiresAt);
    }
}
