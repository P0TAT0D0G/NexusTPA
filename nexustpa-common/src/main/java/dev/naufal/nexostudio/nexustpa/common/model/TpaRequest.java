package dev.naufal.nexostudio.nexustpa.common.model;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Represents a TPA request.
 *
 * <p>State transitions are guarded by {@link #tryTransition(RequestState, RequestState)}
 * which uses CAS on an {@link AtomicReference} to prevent double-accept, double-deny,
 * accept-after-expire, and other race conditions (AGENTS.md §5.5).
 *
 * <p>A request can be <b>authoritative</b> (on the requester's server, where timers run
 * and final state transitions resolve) or a <b>mirror</b> (on the target's server,
 * created via relay to enable /tpaccept and /tpdeny locally).
 */
public class TpaRequest {

    private final UUID requestId;
    private final UUID requesterUuid;
    private final String requesterName;
    private final UUID targetUuid;
    private final String targetName;
    private final RequestType type;
    private final AtomicReference<RequestState> state;
    private final long createdAt;
    private final boolean mirror;

    /**
     * Creates a new TPA request.
     *
     * @param requestId     unique identifier for this request
     * @param requesterUuid UUID of the player who initiated the request
     * @param requesterName display name of the requester
     * @param targetUuid    UUID of the target player
     * @param targetName    display name of the target
     * @param type          TPA or TPAHERE
     * @param createdAt     epoch millis when the request was created
     * @param mirror        true if this is a mirror copy on the target's server
     */
    public TpaRequest(UUID requestId, UUID requesterUuid, String requesterName,
                      UUID targetUuid, String targetName, RequestType type,
                      long createdAt, boolean mirror) {
        this.requestId = requestId;
        this.requesterUuid = requesterUuid;
        this.requesterName = requesterName;
        this.targetUuid = targetUuid;
        this.targetName = targetName;
        this.type = type;
        this.state = new AtomicReference<>(RequestState.PENDING);
        this.createdAt = createdAt;
        this.mirror = mirror;
    }

    /**
     * Attempts an atomic state transition.
     * Returns true if the transition succeeded (current state was {@code expected}),
     * false if the state had already changed (e.g. expired, cancelled, or double-accepted).
     *
     * @param expected the state we expect to transition from
     * @param next     the state to transition to
     * @return true if transition succeeded
     */
    public boolean tryTransition(RequestState expected, RequestState next) {
        return state.compareAndSet(expected, next);
    }

    /**
     * Returns the UUID of the player who will be teleported (the "traveler").
     * For TPA: requester moves to target.
     * For TPAHERE: target moves to requester.
     */
    public UUID getTravelerUuid() {
        return type == RequestType.TPA ? requesterUuid : targetUuid;
    }

    /**
     * Returns the UUID of the player who stays in place (the "destination player").
     * For TPA: target stays.
     * For TPAHERE: requester stays.
     */
    public UUID getDestinationPlayerUuid() {
        return type == RequestType.TPA ? targetUuid : requesterUuid;
    }

    /**
     * Checks if this request has expired based on the given timeout.
     *
     * @param timeoutSeconds the configured request timeout in seconds
     * @return true if the request is older than the timeout
     */
    public boolean isExpired(int timeoutSeconds) {
        return System.currentTimeMillis() - createdAt > (long) timeoutSeconds * 1000;
    }

    public UUID getRequestId() {
        return requestId;
    }

    public UUID getRequesterUuid() {
        return requesterUuid;
    }

    public String getRequesterName() {
        return requesterName;
    }

    public UUID getTargetUuid() {
        return targetUuid;
    }

    public String getTargetName() {
        return targetName;
    }

    public RequestType getType() {
        return type;
    }

    public RequestState getState() {
        return state.get();
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public boolean isMirror() {
        return mirror;
    }
}
