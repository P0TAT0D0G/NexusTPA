package dev.naufal.nexostudio.nexustpa.common.model;

/**
 * Reason for a REQUEST_CANCEL message.
 * Lets the receiving side pick the correct player-facing message.
 */
public enum RequestCancelReason {

    /** Requester typed /tpcancel. */
    CANCELLED,

    /** Request timed out (default 60s). */
    EXPIRED,

    /** Target disconnected — immediate notify to authoritative side. */
    TARGET_DISCONNECT,

    /** CAS failed on authoritative side (request already expired/cancelled before resolve arrived). */
    ALREADY_RESOLVED,

    /** Cooldown check failed on authoritative side after resolve was received. */
    COOLDOWN_ACTIVE
}
