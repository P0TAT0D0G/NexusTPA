package dev.naufal.nexostudio.nexustpa.backend;

import dev.naufal.nexostudio.nexustpa.backend.channel.BackendMessageHandler;
import dev.naufal.nexostudio.nexustpa.backend.channel.BackendMessageSender;
import dev.naufal.nexostudio.nexustpa.backend.command.*;
import dev.naufal.nexostudio.nexustpa.backend.config.BackendConfig;
import dev.naufal.nexostudio.nexustpa.backend.listener.BackendEventListener;
import dev.naufal.nexostudio.nexustpa.backend.message.MessageManager;
import dev.naufal.nexostudio.nexustpa.backend.request.RequestManager;
import dev.naufal.nexostudio.nexustpa.backend.roster.RosterCache;
import dev.naufal.nexostudio.nexustpa.backend.storage.CooldownRepository;
import dev.naufal.nexostudio.nexustpa.backend.storage.DatabaseManager;
import dev.naufal.nexostudio.nexustpa.backend.storage.ToggleRepository;
import dev.naufal.nexostudio.nexustpa.backend.task.ExpiryTask;
import dev.naufal.nexostudio.nexustpa.backend.teleport.PendingTeleportManager;
import dev.naufal.nexostudio.nexustpa.common.channel.ChannelConstants;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * NexusTPA Paper backend plugin.
 * Handles commands, request management, MySQL persistence, and plugin messaging.
 */
public class NexusTpaBackend extends JavaPlugin {

    private BackendConfig backendConfig;
    private MessageManager messageManager;
    private DatabaseManager databaseManager;
    private CooldownRepository cooldownRepository;
    private ToggleRepository toggleRepository;
    private RosterCache rosterCache;
    private RequestManager requestManager;
    private PendingTeleportManager pendingTeleportManager;
    private BackendMessageSender messageSender;

    @Override
    public void onEnable() {
        // Config
        saveDefaultConfig();
        backendConfig = new BackendConfig(this);

        if (backendConfig.getServerName().isEmpty()) {
            getLogger().warning("'server-name' is not set in config.yml! " +
                    "Cross-server detection will not work correctly. " +
                    "Set it to this server's name as registered in velocity.toml.");
        }

        // Messages
        messageManager = new MessageManager(this);

        // Database
        databaseManager = new DatabaseManager(backendConfig, getLogger());
        databaseManager.createTables();

        // Repositories
        cooldownRepository = new CooldownRepository(databaseManager);
        toggleRepository = new ToggleRepository(databaseManager);

        // Managers
        rosterCache = new RosterCache(backendConfig);
        requestManager = new RequestManager();
        pendingTeleportManager = new PendingTeleportManager();

        // Plugin messaging
        getServer().getMessenger().registerOutgoingPluginChannel(this, ChannelConstants.CHANNEL);
        messageSender = new BackendMessageSender(this);

        BackendMessageHandler messageHandler = new BackendMessageHandler(
                this, rosterCache, requestManager, pendingTeleportManager,
                cooldownRepository, messageSender, messageManager, backendConfig);
        getServer().getMessenger().registerIncomingPluginChannel(
                this, ChannelConstants.CHANNEL, messageHandler);

        // Commands
        registerCommands();

        // Event listener
        getServer().getPluginManager().registerEvents(
                new BackendEventListener(this, requestManager, pendingTeleportManager,
                        toggleRepository, messageSender, messageManager),
                this);

        // Expiry task — every 20 ticks (1 second)
        new ExpiryTask(this, requestManager, pendingTeleportManager,
                messageSender, messageManager, backendConfig).runTaskTimer(this, 20L, 20L);

        getLogger().info("NexusTPA backend plugin enabled");
    }

    @Override
    public void onDisable() {
        if (databaseManager != null) {
            databaseManager.close();
        }
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
        getServer().getScheduler().cancelTasks(this);
        getLogger().info("NexusTPA backend plugin disabled");
    }

    private void registerCommands() {
        TpaCommand tpaCmd = new TpaCommand(this, requestManager, rosterCache,
                toggleRepository, messageSender, messageManager, backendConfig);
        getCommand("tpa").setExecutor(tpaCmd);
        getCommand("tpa").setTabCompleter(tpaCmd);

        TpaHereCommand tpaHereCmd = new TpaHereCommand(this, requestManager, rosterCache,
                toggleRepository, messageSender, messageManager, backendConfig);
        getCommand("tpahere").setExecutor(tpaHereCmd);
        getCommand("tpahere").setTabCompleter(tpaHereCmd);

        TpAcceptCommand acceptCmd = new TpAcceptCommand(this, requestManager, rosterCache,
                cooldownRepository, messageSender, messageManager, backendConfig);
        getCommand("tpaccept").setExecutor(acceptCmd);
        getCommand("tpaccept").setTabCompleter(acceptCmd);

        TpDenyCommand denyCmd = new TpDenyCommand(this, requestManager,
                messageSender, messageManager);
        getCommand("tpdeny").setExecutor(denyCmd);
        getCommand("tpdeny").setTabCompleter(denyCmd);

        TpCancelCommand cancelCmd = new TpCancelCommand(this, requestManager,
                rosterCache, messageSender, messageManager);
        getCommand("tpcancel").setExecutor(cancelCmd);

        TpToggleCommand toggleCmd = new TpToggleCommand(this, toggleRepository, messageManager);
        getCommand("tptoggle").setExecutor(toggleCmd);

        TpaReloadCommand reloadCmd = new TpaReloadCommand(this, messageManager);
        getCommand("tpareload").setExecutor(reloadCmd);
    }

    public BackendConfig getBackendConfig() { return backendConfig; }
    public MessageManager getMessageManager() { return messageManager; }
    public RosterCache getRosterCache() { return rosterCache; }
    public RequestManager getRequestManager() { return requestManager; }
}
