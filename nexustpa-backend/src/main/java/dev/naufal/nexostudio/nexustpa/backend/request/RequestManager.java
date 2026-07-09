package dev.naufal.nexostudio.nexustpa.backend.request;

import dev.naufal.nexostudio.nexustpa.common.model.RequestState;
import dev.naufal.nexostudio.nexustpa.common.model.RequestType;
import dev.naufal.nexostudio.nexustpa.common.model.TpaRequest;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory TPA request state manager.
 * All operations are main-thread safe via ConcurrentHashMap + CAS on TpaRequest.
 *
 * <p>Authoritative requests live on the requester's server (outgoing).
 * Mirror requests live on the target's server (incoming, cross-server).
 * Same-server requests appear in both outgoing AND incoming.
 */
public class RequestManager {

    // Keyed by requester UUID — one outgoing request at a time per player
    private final ConcurrentHashMap<UUID, TpaRequest> outgoingRequests = new ConcurrentHashMap<>();
    // Keyed by target UUID — multiple incoming requests per target
    private final ConcurrentHashMap<UUID, List<TpaRequest>> incomingRequests = new ConcurrentHashMap<>();

    /**
     * Creates and stores a new TPA request.
     *
     * @param requesterUuid requester UUID
     * @param requesterName requester display name
     * @param targetUuid    target UUID
     * @param targetName    target display name
     * @param type          TPA or TPAHERE
     * @param crossServer   true if target is on a different server
     * @return the created request, or null if requester already has an outgoing request
     */
    public TpaRequest createRequest(UUID requesterUuid, String requesterName,
                                    UUID targetUuid, String targetName,
                                    RequestType type, boolean crossServer) {
        UUID requestId = UUID.randomUUID();
        TpaRequest request = new TpaRequest(requestId, requesterUuid, requesterName,
                targetUuid, targetName, type, System.currentTimeMillis(), false);

        // Cancel any existing outgoing request from this requester
        TpaRequest existing = outgoingRequests.put(requesterUuid, request);
        if (existing != null && existing.getState() == RequestState.PENDING) {
            existing.tryTransition(RequestState.PENDING, RequestState.CANCELLED);
        }

        // For same-server: also add to incoming (authoritative serves both roles)
        if (!crossServer) {
            addToIncoming(targetUuid, request);
        }
        // For cross-server: incoming mirror will be created on target's server via relay

        return request;
    }

    /**
     * Adds a mirror request to the incoming list for a target.
     * Called by BackendMessageHandler when a REQUEST_NOTIFY arrives.
     */
    public void mirrorIncoming(TpaRequest mirrorRequest) {
        addToIncoming(mirrorRequest.getTargetUuid(), mirrorRequest);
    }

    private synchronized void addToIncoming(UUID targetUuid, TpaRequest request) {
        incomingRequests.computeIfAbsent(targetUuid, k -> new ArrayList<>()).add(request);
    }

    /**
     * Gets the outgoing request for a requester.
     */
    public TpaRequest getOutgoing(UUID requesterUuid) {
        return outgoingRequests.get(requesterUuid);
    }

    /**
     * Gets all PENDING incoming requests for a target.
     */
    public synchronized List<TpaRequest> getIncoming(UUID targetUuid) {
        List<TpaRequest> all = incomingRequests.get(targetUuid);
        if (all == null) return Collections.emptyList();
        return all.stream()
                .filter(r -> r.getState() == RequestState.PENDING)
                .toList();
    }

    /**
     * Finds a specific incoming request from a requester to a target.
     */
    public synchronized TpaRequest getIncomingFrom(UUID targetUuid, UUID requesterUuid) {
        List<TpaRequest> all = incomingRequests.get(targetUuid);
        if (all == null) return null;
        return all.stream()
                .filter(r -> r.getRequesterUuid().equals(requesterUuid)
                        && r.getState() == RequestState.PENDING)
                .findFirst()
                .orElse(null);
    }

    /**
     * Finds an incoming request by request ID.
     */
    public synchronized TpaRequest getIncomingByRequestId(UUID targetUuid, UUID requestId) {
        List<TpaRequest> all = incomingRequests.get(targetUuid);
        if (all == null) return null;
        return all.stream()
                .filter(r -> r.getRequestId().equals(requestId))
                .findFirst()
                .orElse(null);
    }

    /**
     * Finds an outgoing request by request ID.
     */
    public TpaRequest getOutgoingByRequestId(UUID requestId) {
        for (TpaRequest req : outgoingRequests.values()) {
            if (req.getRequestId().equals(requestId)) return req;
        }
        return null;
    }

    /**
     * CAS accept: PENDING → ACCEPTED.
     */
    public boolean accept(TpaRequest request) {
        return request.tryTransition(RequestState.PENDING, RequestState.ACCEPTED);
    }

    /**
     * CAS deny: PENDING → DENIED.
     */
    public boolean deny(TpaRequest request) {
        return request.tryTransition(RequestState.PENDING, RequestState.DENIED);
    }

    /**
     * CAS cancel: PENDING → CANCELLED.
     */
    public boolean cancel(TpaRequest request) {
        return request.tryTransition(RequestState.PENDING, RequestState.CANCELLED);
    }

    /**
     * Removes a mirror request by request ID from incoming lists.
     */
    public synchronized void removeMirrorByRequestId(UUID requestId) {
        for (List<TpaRequest> list : incomingRequests.values()) {
            list.removeIf(r -> r.getRequestId().equals(requestId) && r.isMirror());
        }
    }

    /**
     * Removes a specific request from incoming lists (any type).
     */
    public synchronized void removeFromIncoming(UUID targetUuid, TpaRequest request) {
        List<TpaRequest> list = incomingRequests.get(targetUuid);
        if (list != null) {
            list.remove(request);
            if (list.isEmpty()) {
                incomingRequests.remove(targetUuid);
            }
        }
    }

    /**
     * Expires old authoritative requests. Returns list of expired requests for relay.
     * Called by ExpiryTask on main thread.
     */
    public List<TpaRequest> expireOld(int timeoutSeconds) {
        List<TpaRequest> expired = new ArrayList<>();
        for (TpaRequest req : outgoingRequests.values()) {
            if (req.isExpired(timeoutSeconds)
                    && req.tryTransition(RequestState.PENDING, RequestState.EXPIRED)) {
                expired.add(req);
            }
        }
        // Clean up non-pending outgoing
        outgoingRequests.values().removeIf(r -> r.getState() != RequestState.PENDING);
        return expired;
    }

    /**
     * Expires orphaned mirror requests (safety net).
     * Uses timeout + buffer to ensure authoritative side had time to relay.
     * 🟡 Convention: caller should notify target locally before removal.
     */
    public synchronized List<TpaRequest> expireOldMirrors(int timeoutSeconds, int bufferSeconds) {
        List<TpaRequest> expired = new ArrayList<>();
        int totalTimeout = timeoutSeconds + bufferSeconds;
        for (List<TpaRequest> list : incomingRequests.values()) {
            Iterator<TpaRequest> it = list.iterator();
            while (it.hasNext()) {
                TpaRequest req = it.next();
                if (req.isMirror() && req.isExpired(totalTimeout)) {
                    req.tryTransition(RequestState.PENDING, RequestState.EXPIRED);
                    expired.add(req);
                    it.remove();
                }
            }
        }
        return expired;
    }

    /**
     * Result of cleaning up a disconnecting player's requests.
     */
    public record CleanupResult(
            List<TpaRequest> cancelledOutgoing,  // Outgoing that need cancel relay to mirrors
            List<TpaRequest> orphanedMirrors      // Mirrors that need disconnect relay to authoritative
    ) {
    }

    /**
     * Cleans up all requests for a disconnecting player.
     * Returns lists of requests that need relay notifications.
     */
    public synchronized CleanupResult cleanupPlayer(UUID playerUuid) {
        List<TpaRequest> cancelledOutgoing = new ArrayList<>();
        List<TpaRequest> orphanedMirrors = new ArrayList<>();

        // Cancel outgoing request (this player was requester)
        TpaRequest outgoing = outgoingRequests.remove(playerUuid);
        if (outgoing != null && outgoing.tryTransition(RequestState.PENDING, RequestState.CANCELLED)) {
            cancelledOutgoing.add(outgoing);
        }

        // Remove incoming requests where this player was target
        List<TpaRequest> incoming = incomingRequests.remove(playerUuid);
        if (incoming != null) {
            for (TpaRequest req : incoming) {
                if (req.getState() == RequestState.PENDING) {
                    req.tryTransition(RequestState.PENDING, RequestState.CANCELLED);
                    if (req.isMirror()) {
                        orphanedMirrors.add(req);
                    }
                }
            }
        }

        // Also check if this player was a requester in other targets' incoming lists
        // (same-server scenario where request is in both outgoing and incoming)
        for (List<TpaRequest> list : incomingRequests.values()) {
            list.removeIf(r -> r.getRequesterUuid().equals(playerUuid)
                    && r.tryTransition(RequestState.PENDING, RequestState.CANCELLED));
        }

        return new CleanupResult(cancelledOutgoing, orphanedMirrors);
    }
}
