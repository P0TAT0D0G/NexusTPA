package dev.naufal.nexostudio.nexustpa.backend.config;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Typed accessor for config.yml values.
 */
public class BackendConfig {

    private final JavaPlugin plugin;

    public BackendConfig(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public String getServerName() {
        return plugin.getConfig().getString("server-name", "");
    }

    public int getRequestTimeout() {
        return plugin.getConfig().getInt("request-timeout", 60);
    }

    public int getCooldownSeconds() {
        return plugin.getConfig().getInt("cooldown-seconds", 5);
    }

    public int getPendingTeleportTtl() {
        return plugin.getConfig().getInt("pending-teleport-ttl", 15);
    }

    public String getMysqlHost() {
        return plugin.getConfig().getString("storage.mysql.host", "localhost");
    }

    public int getMysqlPort() {
        return plugin.getConfig().getInt("storage.mysql.port", 3306);
    }

    public String getMysqlDatabase() {
        return plugin.getConfig().getString("storage.mysql.database", "nexustpa");
    }

    public String getMysqlUsername() {
        return plugin.getConfig().getString("storage.mysql.username", "root");
    }

    public String getMysqlPassword() {
        return plugin.getConfig().getString("storage.mysql.password", "");
    }

    public int getMysqlPoolSize() {
        return plugin.getConfig().getInt("storage.mysql.pool-size", 10);
    }

    public void reload() {
        plugin.reloadConfig();
    }
}
