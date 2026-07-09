package dev.naufal.nexostudio.nexustpa.proxy;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import dev.naufal.nexostudio.nexustpa.common.channel.ChannelConstants;
import dev.naufal.nexostudio.nexustpa.proxy.channel.ProxyMessageHandler;
import dev.naufal.nexostudio.nexustpa.proxy.channel.RosterPusher;
import dev.naufal.nexostudio.nexustpa.proxy.config.ProxyConfig;
import dev.naufal.nexostudio.nexustpa.proxy.group.GroupRegistry;
import dev.naufal.nexostudio.nexustpa.proxy.index.PlayerIndex;
import dev.naufal.nexostudio.nexustpa.proxy.listener.ProxyEventListener;
import org.slf4j.Logger;

import java.nio.file.Path;

/**
 * NexusTPA Velocity proxy plugin.
 * Source of truth for live player-server-group state.
 * Dumb relay for request messages between backend servers.
 */
@Plugin(id = "nexustpa", name = "NexusTPA", version = "1.0.0",
        description = "Cross-server TPA plugin for Terra Network",
        authors = {"naufal"})
public class NexusTpaProxy {

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    // Verified: MinecraftChannelIdentifier.from() splits on ':'
    // to create namespace:value pair (Velocity docs, PaperMC wiki)
    public static final MinecraftChannelIdentifier CHANNEL =
            MinecraftChannelIdentifier.from(ChannelConstants.CHANNEL);

    private GroupRegistry groupRegistry;
    private PlayerIndex playerIndex;
    private RosterPusher rosterPusher;
    private ProxyMessageHandler messageHandler;

    @Inject
    public NexusTpaProxy(ProxyServer server, Logger logger,
                         @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        // Load config
        ProxyConfig config = new ProxyConfig(dataDirectory, logger);
        config.load();

        // Build group registry
        groupRegistry = new GroupRegistry();
        groupRegistry.reload(config.getGroups());
        logger.info("Loaded {} server groups", config.getGroups().size());

        // Create player index
        playerIndex = new PlayerIndex(groupRegistry);

        // Register plugin messaging channel
        // Verified: server.getChannelRegistrar().register() (Velocity docs)
        server.getChannelRegistrar().register(CHANNEL);

        // Create handlers
        rosterPusher = new RosterPusher(server, playerIndex, CHANNEL, logger);
        messageHandler = new ProxyMessageHandler(server, playerIndex, rosterPusher,
                CHANNEL, logger);

        // Register event listener
        server.getEventManager().register(this,
                new ProxyEventListener(server, playerIndex, groupRegistry,
                        rosterPusher, messageHandler, CHANNEL, logger));

        logger.info("NexusTPA proxy plugin enabled");
    }

    public ProxyServer getServer() {
        return server;
    }

    public GroupRegistry getGroupRegistry() {
        return groupRegistry;
    }

    public PlayerIndex getPlayerIndex() {
        return playerIndex;
    }
}
