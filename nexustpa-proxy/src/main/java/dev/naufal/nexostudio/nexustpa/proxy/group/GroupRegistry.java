package dev.naufal.nexostudio.nexustpa.proxy.group;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps server names to groups and provides group lookup utilities.
 * No hardcoded server names — all from config (AGENTS.md §5.6).
 */
public class GroupRegistry {

    // server name (exact casing) → group name
    private volatile Map<String, String> serverToGroup = Collections.emptyMap();
    // group name → list of server names
    private volatile Map<String, List<String>> groupToServers = Collections.emptyMap();

    /**
     * Rebuilds the registry from config data.
     *
     * @param groups map of group name to list of server names
     */
    public void reload(Map<String, List<String>> groups) {
        Map<String, String> s2g = new HashMap<>();
        Map<String, List<String>> g2s = new HashMap<>();

        for (Map.Entry<String, List<String>> entry : groups.entrySet()) {
            String groupName = entry.getKey();
            List<String> servers = List.copyOf(entry.getValue());
            g2s.put(groupName, servers);
            for (String server : servers) {
                s2g.put(server, groupName);
            }
        }

        this.serverToGroup = Map.copyOf(s2g);
        this.groupToServers = Map.copyOf(g2s);
    }

    /**
     * Returns the group a server belongs to.
     * If the server is not in any configured group, returns the server name itself
     * (isolated single-member group — PRD §4).
     */
    public String getGroup(String serverName) {
        return serverToGroup.getOrDefault(serverName, serverName);
    }

    /**
     * Returns all servers in a group, or empty list if group is unknown.
     */
    public List<String> getServersInGroup(String groupName) {
        return groupToServers.getOrDefault(groupName, Collections.emptyList());
    }

    /**
     * Checks if two servers are in the same group.
     */
    public boolean isSameGroup(String serverA, String serverB) {
        return getGroup(serverA).equals(getGroup(serverB));
    }
}
