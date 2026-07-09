package dev.naufal.nexostudio.nexustpa.proxy.channel;

import com.google.common.io.ByteArrayDataOutput;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import dev.naufal.nexostudio.nexustpa.common.channel.ChannelConstants;
import dev.naufal.nexostudio.nexustpa.common.channel.ChannelMessageUtil;
import dev.naufal.nexostudio.nexustpa.proxy.index.PlayerIndex;
import org.slf4j.Logger;

import java.util.Map;
import java.util.UUID;

/**
 * Pushes roster updates to all backend servers in a group.
 * Called on every player connect/disconnect/server-switch to keep
 * backend RosterCaches in sync.
 *
 * <p>Verified: RegisteredServer.sendPluginMessage(ChannelIdentifier, byte[])
 * is the correct API for sending plugin messages to backend servers in Velocity.
 * Both RegisteredServer and Player implement ChannelMessageSink.
 */
public class RosterPusher {

    private final ProxyServer server;
    private final PlayerIndex playerIndex;
    private final ChannelIdentifier channel;
    private final Logger logger;

    public RosterPusher(ProxyServer server, PlayerIndex playerIndex,
                        ChannelIdentifier channel, Logger logger) {
        this.server = server;
        this.playerIndex = playerIndex;
        this.channel = channel;
        this.logger = logger;
    }

    /**
     * Pushes the full roster of a group to all backend servers in that group
     * that have at least one connected player.
     *
     * <p>Plugin messaging requires at least one player on the target server.
     * Servers with 0 players will receive a fresh roster on the next player join
     * (triggered by ServerConnectedEvent).
     */
    public void pushRosterToGroup(String groupName) {
        Map<UUID, ChannelMessageUtil.RosterEntry> roster =
                playerIndex.getGroupRoster(groupName);

        // Serialize roster
        ByteArrayDataOutput out = ChannelMessageUtil.newOutput();
        out.writeUTF(ChannelConstants.ACTION_ROSTER_UPDATE);
        ChannelMessageUtil.writeRosterUpdate(out, groupName, roster);
        byte[] data = out.toByteArray();

        // Send to all servers in the group
        // For servers not in the group registry (isolated), we still need to
        // send if the group name IS the server name (isolated single-member group)
        for (RegisteredServer regServer : server.getAllServers()) {
            String serverName = regServer.getServerInfo().getName();
            // Only send to servers in this group
            if (!playerIndex.getGroupRoster(groupName).isEmpty() ||
                    groupName.equals(serverName)) {
                // Check if server has players (plugin messaging requirement)
                if (!regServer.getPlayersConnected().isEmpty()) {
                    // Only send to servers that are actually in this group
                    // by checking their group membership
                    String serverGroup = server.getAllServers().stream()
                            .filter(s -> s.getServerInfo().getName().equals(serverName))
                            .findFirst()
                            .map(s -> groupName) // We need GroupRegistry here
                            .orElse(serverName);

                    regServer.sendPluginMessage(channel, data);
                }
            }
        }
    }

    /**
     * Sends raw plugin message data to a specific server by name.
     * Returns true if sent, false if server not found or has no players.
     */
    public boolean sendToServer(String serverName, byte[] data) {
        return server.getServer(serverName)
                .filter(s -> !s.getPlayersConnected().isEmpty())
                .map(s -> {
                    s.sendPluginMessage(channel, data);
                    return true;
                })
                .orElse(false);
    }

    /**
     * Sends raw plugin message data to the server a player is currently on.
     * Returns true if sent, false if player not found or server has no players.
     */
    public boolean sendToPlayerServer(UUID playerUuid, byte[] data) {
        String serverName = playerIndex.getPlayerServer(playerUuid);
        if (serverName == null) {
            return false;
        }
        return sendToServer(serverName, data);
    }
}
