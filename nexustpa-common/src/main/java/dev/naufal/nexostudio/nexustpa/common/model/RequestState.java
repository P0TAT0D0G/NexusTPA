package dev.naufal.nexostudio.nexustpa.common.model;

/**
 * Lifecycle state of a TPA request.
 * All transitions must be atomic via {@link TpaRequest#tryTransition(RequestState, RequestState)}.
 */
public enum RequestState {
    PENDING,
    ACCEPTED,
    DENIED,
    CANCELLED,
    EXPIRED
}
