package dev.naufal.nexostudio.nexustpa.common.model;

import java.util.UUID;

/**
 * Represents a pending cross-server teleport waiting to execute
 * when the traveler joins the destination server.
 *
 * <p>Created on the server that initiates the teleport, relayed via proxy
 * to the destination server, where it's stored in PendingTeleportManager.
 * On PlayerJoinEvent, the destination server checks for a pending teleport
 * for the joining player and executes it if not expired.
 *
 * <p>TTL is configurable (default 15s per PRD §6.3). If the traveler
 * hasn't joined the destination server within that window, the pending
 * record is discarded.
 */
public class PendingTeleport {

    private final UUID travelerUuid;
    private final UUID destinationPlayerUuid;
    private final String destinationServer;
    private final long expiresAt;

    /**
     * @param travelerUuid          UUID of the player being moved
     * @param destinationPlayerUuid UUID of the player to teleport to on arrival
     * @param destinationServer     name of the server the traveler is being sent to
     * @param expiresAt             epoch millis after which this pending teleport is invalid
     */
    public PendingTeleport(UUID travelerUuid, UUID destinationPlayerUuid,
                           String destinationServer, long expiresAt) {
        this.travelerUuid = travelerUuid;
        this.destinationPlayerUuid = destinationPlayerUuid;
        this.destinationServer = destinationServer;
        this.expiresAt = expiresAt;
    }

    /**
     * Checks if this pending teleport has expired.
     *
     * @return true if current time is past the expiry timestamp
     */
    public boolean isExpired() {
        return System.currentTimeMillis() > expiresAt;
    }

    public UUID getTravelerUuid() {
        return travelerUuid;
    }

    public UUID getDestinationPlayerUuid() {
        return destinationPlayerUuid;
    }

    public String getDestinationServer() {
        return destinationServer;
    }

    public long getExpiresAt() {
        return expiresAt;
    }
}
