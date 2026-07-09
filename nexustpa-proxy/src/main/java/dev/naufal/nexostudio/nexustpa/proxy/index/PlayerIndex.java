package dev.naufal.nexostudio.nexustpa.proxy.index;

import dev.naufal.nexostudio.nexustpa.common.channel.ChannelMessageUtil;
import dev.naufal.nexostudio.nexustpa.proxy.group.GroupRegistry;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live index of all players connected through the proxy.
 * Updated on every server switch and disconnect.
 * Source of truth for "who is online, on which server, in which group" (AGENTS.md §5.2).
 */
public class PlayerIndex {

    /**
     * Immutable entry for a player in the index.
     */
    public record PlayerEntry(UUID uuid, String name, String serverName, String groupName) {
    }

    private final ConcurrentHashMap<UUID, PlayerEntry> players = new ConcurrentHashMap<>();
    private final GroupRegistry groupRegistry;

    public PlayerIndex(GroupRegistry groupRegistry) {
        this.groupRegistry = groupRegistry;
    }

    /**
     * Adds or updates a player's entry. Returns the old entry (or null if new).
     */
    public PlayerEntry addOrUpdate(UUID uuid, String name, String serverName) {
        String groupName = groupRegistry.getGroup(serverName);
        PlayerEntry newEntry = new PlayerEntry(uuid, name, serverName, groupName);
        return players.put(uuid, newEntry);
    }

    /**
     * Removes a player from the index. Returns the old entry (needed for group cleanup).
     */
    public PlayerEntry remove(UUID uuid) {
        return players.remove(uuid);
    }

    /**
     * Looks up which server a player is currently on.
     *
     * @return server name, or null if player is not in the index
     */
    public String getPlayerServer(UUID uuid) {
        PlayerEntry entry = players.get(uuid);
        return entry != null ? entry.serverName() : null;
    }

    /**
     * Builds the roster for a group: map of UUID → {name, serverName}.
     * Used by RosterPusher to serialize and send to backend servers.
     */
    public Map<UUID, ChannelMessageUtil.RosterEntry> getGroupRoster(String groupName) {
        Map<UUID, ChannelMessageUtil.RosterEntry> roster = new HashMap<>();
        for (PlayerEntry entry : players.values()) {
            if (entry.groupName().equals(groupName)) {
                roster.put(entry.uuid(),
                        new ChannelMessageUtil.RosterEntry(entry.name(), entry.serverName()));
            }
        }
        return roster;
    }

    /**
     * Gets a player entry by UUID.
     */
    public PlayerEntry getEntry(UUID uuid) {
        return players.get(uuid);
    }
}
