package dev.naufal.nexostudio.nexustpa.proxy.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import dev.naufal.nexostudio.nexustpa.proxy.channel.ProxyMessageHandler;
import dev.naufal.nexostudio.nexustpa.proxy.channel.RosterPusher;
import dev.naufal.nexostudio.nexustpa.proxy.group.GroupRegistry;
import dev.naufal.nexostudio.nexustpa.proxy.index.PlayerIndex;
import org.slf4j.Logger;

/**
 * Handles Velocity proxy events for player state tracking and plugin messaging.
 */
public class ProxyEventListener {

    private final ProxyServer server;
    private final PlayerIndex playerIndex;
    private final GroupRegistry groupRegistry;
    private final RosterPusher rosterPusher;
    private final ProxyMessageHandler messageHandler;
    private final ChannelIdentifier channel;
    private final Logger logger;

    public ProxyEventListener(ProxyServer server, PlayerIndex playerIndex,
                              GroupRegistry groupRegistry, RosterPusher rosterPusher,
                              ProxyMessageHandler messageHandler,
                              ChannelIdentifier channel, Logger logger) {
        this.server = server;
        this.playerIndex = playerIndex;
        this.groupRegistry = groupRegistry;
        this.rosterPusher = rosterPusher;
        this.messageHandler = messageHandler;
        this.channel = channel;
        this.logger = logger;
    }

    /**
     * Fired after a player successfully connects to a backend server.
     * Updates the player index and pushes rosters to affected groups.
     *
     * Verified: ServerConnectedEvent.getServer() returns the new server,
     * getPreviousServer() returns Optional<RegisteredServer> of old server.
     */
    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        Player player = event.getPlayer();
        String newServerName = event.getServer().getServerInfo().getName();

        // Update index, get old entry
        PlayerIndex.PlayerEntry oldEntry = playerIndex.addOrUpdate(
                player.getUniqueId(), player.getUsername(), newServerName);

        String newGroup = groupRegistry.getGroup(newServerName);

        // If player switched groups, push roster to old group (removes this player)
        if (oldEntry != null && !oldEntry.groupName().equals(newGroup)) {
            rosterPusher.pushRosterToGroup(oldEntry.groupName());
        }

        // Push roster to current group (adds/updates this player)
        rosterPusher.pushRosterToGroup(newGroup);
    }

    /**
     * Fired when a player disconnects from the proxy entirely.
     */
    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        PlayerIndex.PlayerEntry oldEntry = playerIndex.remove(player.getUniqueId());

        if (oldEntry != null) {
            // Push updated roster to old group (this player removed)
            rosterPusher.pushRosterToGroup(oldEntry.groupName());
        }
    }

    /**
     * Handles incoming plugin messages from backend servers.
     * Routes to ProxyMessageHandler for processing.
     *
     * Verified: PluginMessageEvent fires for both directions.
     * We only process messages from backend→proxy (source is ServerConnection).
     * setResult(ForwardResult.handled()) prevents forwarding to other backends.
     */
    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().equals(channel)) {
            return;
        }

        // Only handle messages from backend servers, not from players
        if (!(event.getSource() instanceof com.velocitypowered.api.proxy.ServerConnection)) {
            return;
        }

        // Mark as handled so Velocity doesn't forward to other servers
        event.setResult(PluginMessageEvent.ForwardResult.handled());

        com.velocitypowered.api.proxy.ServerConnection sourceConnection =
                (com.velocitypowered.api.proxy.ServerConnection) event.getSource();
        String sourceServerName = sourceConnection.getServerInfo().getName();

        try {
            messageHandler.handle(event.getData(), sourceServerName);
        } catch (Exception e) {
            logger.error("Error handling plugin message from {}", sourceServerName, e);
        }
    }
}
