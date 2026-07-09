package dev.naufal.nexostudio.nexustpa.backend.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.naufal.nexostudio.nexustpa.backend.config.BackendConfig;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;

/**
 * HikariCP connection pool manager matching NexusGuild's pattern.
 */
public class DatabaseManager {

    private final BackendConfig config;
    private final Logger logger;
    private HikariDataSource dataSource;

    public DatabaseManager(BackendConfig config, Logger logger) {
        this.config = config;
        this.logger = logger;
        setupPool();
    }

    private void setupPool() {
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl("jdbc:mysql://" + config.getMysqlHost() + ":" + config.getMysqlPort()
                + "/" + config.getMysqlDatabase() + "?useSSL=false&characterEncoding=utf8");
        hc.setUsername(config.getMysqlUsername());
        hc.setPassword(config.getMysqlPassword());
        hc.setMaximumPoolSize(config.getMysqlPoolSize());
        hc.addDataSourceProperty("cachePrepStmts", "true");
        hc.addDataSourceProperty("prepStmtCacheSize", "250");
        hc.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        hc.setPoolName("NexusTPA-Pool");

        dataSource = new HikariDataSource(hc);
    }

    /**
     * Creates required database tables if they don't exist.
     */
    public void createTables() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS nexustpa_cooldowns (" +
                            "uuid VARCHAR(36) PRIMARY KEY, " +
                            "last_teleport_at BIGINT NOT NULL" +
                            ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
            );
            stmt.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS nexustpa_toggle_state (" +
                            "uuid VARCHAR(36) PRIMARY KEY, " +
                            "accepting BOOLEAN NOT NULL DEFAULT TRUE" +
                            ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
            );
            logger.info("Database tables verified");
        } catch (SQLException e) {
            logger.severe("Failed to create database tables: " + e.getMessage());
        }
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
