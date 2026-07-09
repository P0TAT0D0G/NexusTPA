package dev.naufal.nexostudio.nexustpa.backend.teleport;

import dev.naufal.nexostudio.nexustpa.common.model.PendingTeleport;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages pending cross-server teleports waiting for player join.
 * Keyed by traveler UUID — consumed on PlayerJoinEvent.
 */
public class PendingTeleportManager {

    private final ConcurrentHashMap<UUID, PendingTeleport> pending = new ConcurrentHashMap<>();

    /**
     * Stores a pending teleport for a traveler.
     * Overwrites any existing pending for the same traveler.
     */
    public void addPending(PendingTeleport teleport) {
        pending.put(teleport.getTravelerUuid(), teleport);
    }

    /**
     * Atomically retrieves and removes the pending teleport for a traveler.
     * Returns null if none exists or if expired.
     */
    public PendingTeleport consumePending(UUID travelerUuid) {
        PendingTeleport tp = pending.remove(travelerUuid);
        if (tp != null && tp.isExpired()) {
            return null; // Expired, discard
        }
        return tp;
    }

    /**
     * Removes a pending teleport without returning it.
     * Used on player disconnect cleanup.
     */
    public void removePending(UUID travelerUuid) {
        pending.remove(travelerUuid);
    }

    /**
     * Cleans up all expired pending teleports.
     */
    public void cleanupExpired() {
        pending.values().removeIf(PendingTeleport::isExpired);
    }
}
