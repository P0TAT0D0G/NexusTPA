package dev.naufal.nexostudio.nexustpa.common.channel;

/**
 * Plugin messaging channel name and action identifiers.
 * Single source of truth — both proxy and backend reference these constants.
 *
 * <p>All messages are multiplexed on a single channel ({@link #CHANNEL}).
 * The first field in every message is the action string, which determines
 * how the rest of the payload is deserialized.
 */
public final class ChannelConstants {

    private ChannelConstants() {
    }

    /**
     * The single plugin messaging channel used for all NexusTPA communication.
     * Must be registered on both Velocity (proxy) and Paper (backend) sides.
     */
    public static final String CHANNEL = "nexustpa:main";

    // --- Proxy → Backend ---

    /** Proxy pushes updated group roster to backend servers. */
    public static final String ACTION_ROSTER_UPDATE = "ROSTER_UPDATE";

    /** Proxy reports result of a CONNECT_REQUEST back to the requesting backend. */
    public static final String ACTION_CONNECT_RESPONSE = "CONNECT_RESPONSE";

    // --- Backend → Proxy (proxy acts on it directly) ---

    /** Backend asks proxy to move a player to a different server. */
    public static final String ACTION_CONNECT_REQUEST = "CONNECT_REQUEST";

    // --- Backend → Proxy → Backend (proxy relays between backends) ---

    /** New TPA request — relay from requester's server to target's server to create mirror. */
    public static final String ACTION_REQUEST_NOTIFY = "REQUEST_NOTIFY";

    /**
     * Target responded (accept/deny) — relay from target's server back to requester's server.
     * Payload includes cooldownBypass boolean (checked by mirror for TPAHERE traveler permission).
     */
    public static final String ACTION_REQUEST_RESOLVE = "REQUEST_RESOLVE";

    /**
     * Cancel/expire/failure notification — direction-agnostic relay.
     * Carries a {@link dev.naufal.nexostudio.nexustpa.common.model.RequestCancelReason}.
     */
    public static final String ACTION_REQUEST_CANCEL = "REQUEST_CANCEL";

    /** Cross-server pending teleport data — relay from source backend to destination backend. */
    public static final String ACTION_PENDING_TELEPORT = "PENDING_TELEPORT";
}
