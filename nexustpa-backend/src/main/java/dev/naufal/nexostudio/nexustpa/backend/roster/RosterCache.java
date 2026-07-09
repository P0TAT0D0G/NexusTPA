package dev.naufal.nexostudio.nexustpa.backend.roster;

import dev.naufal.nexostudio.nexustpa.backend.config.BackendConfig;
import dev.naufal.nexostudio.nexustpa.common.channel.ChannelMessageUtil;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory cache of group roster pushed from the proxy.
 * Updated via ROSTER_UPDATE plugin messages.
 * Used for tab-complete, group membership checks, and same-server detection.
 */
public class RosterCache {

    /**
     * Cached roster entry: player name + server name.
     */
    public record CachedEntry(String name, String serverName) {
    }

    private final BackendConfig config;
    private final ConcurrentHashMap<UUID, CachedEntry> roster = new ConcurrentHashMap<>();
    private volatile String currentGroup = "";
    private volatile boolean synced = false;
    private final java.util.logging.Logger logger;

    public RosterCache(BackendConfig config, java.util.logging.Logger logger) {
        this.config = config;
        this.logger = logger;
    }

    /**
     * Replaces the entire roster with a fresh push from the proxy.
     * Always accepts the update — backend cannot independently verify group membership
     * (proxy is the source of truth for group assignments).
     *
     * <p>Logs WARNING if group name changes (could indicate proxy config reload
     * or a RosterPusher bug). See AGENTS.md §5.2.
     */
    public void updateRoster(String groupName, Map<UUID, ChannelMessageUtil.RosterEntry> players) {
        if (!currentGroup.isEmpty() && !currentGroup.equals(groupName)) {
            logger.warning("Roster group changed: '" + currentGroup + "' → '" + groupName
                    + "'. If this was not intentional (proxy config reload), "
                    + "check RosterPusher for cross-group leakage.");
        }
        roster.clear();
        for (Map.Entry<UUID, ChannelMessageUtil.RosterEntry> entry : players.entrySet()) {
            roster.put(entry.getKey(),
                    new CachedEntry(entry.getValue().name(), entry.getValue().serverName()));
        }
        this.currentGroup = groupName;
        this.synced = true;
    }

    /**
     * Returns all player names in the group (for tab-complete).
     */
    public List<String> getPlayerNames() {
        return roster.values().stream()
                .map(CachedEntry::name)
                .toList();
    }

    /**
     * Checks if a player UUID is in the current group roster.
     */
    public boolean isInGroup(UUID uuid) {
        return roster.containsKey(uuid);
    }

    /**
     * Gets a player's display name from the roster.
     */
    public String getPlayerName(UUID uuid) {
        CachedEntry entry = roster.get(uuid);
        return entry != null ? entry.name() : null;
    }

    /**
     * Gets the server name a player is currently on (from proxy's perspective).
     */
    public String getPlayerServer(UUID uuid) {
        CachedEntry entry = roster.get(uuid);
        return entry != null ? entry.serverName() : null;
    }

    /**
     * Checks if a player is on the same server as this backend.
     * Uses config 'server-name' for comparison.
     */
    public boolean isOnSameServer(UUID playerUuid) {
        String serverName = config.getServerName();
        if (serverName.isEmpty()) return false;
        CachedEntry entry = roster.get(playerUuid);
        return entry != null && serverName.equals(entry.serverName());
    }

    /**
     * Whether the roster has been synced from the proxy at least once.
     * Commands must check this per AGENTS.md §5.3.
     */
    public boolean isSynced() {
        return synced;
    }

    public String getCurrentGroup() {
        return currentGroup;
    }

    /**
     * Gets UUID by player name (case-insensitive lookup for tab-complete matches).
     */
    public UUID getUuidByName(String name) {
        for (Map.Entry<UUID, CachedEntry> entry : roster.entrySet()) {
            if (entry.getValue().name().equalsIgnoreCase(name)) {
                return entry.getKey();
            }
        }
        return null;
    }
}
