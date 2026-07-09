package dev.naufal.nexostudio.nexustpa.backend.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Toggle state repository with in-memory cache for local players.
 * Cache is populated on join, evicted on quit.
 * DB methods are ASYNC ONLY.
 */
public class ToggleRepository {

    private final DatabaseManager db;
    // Cache for locally online players only — accessed from main thread
    private final ConcurrentHashMap<UUID, Boolean> cache = new ConcurrentHashMap<>();

    public ToggleRepository(DatabaseManager db) {
        this.db = db;
    }

    /**
     * Checks toggle from cache (main thread safe).
     * Returns true (accepting) if not in cache (default).
     */
    public boolean isAcceptingRequests(UUID uuid) {
        return cache.getOrDefault(uuid, true);
    }

    /**
     * Checks toggle from DB directly. ASYNC ONLY.
     * Used for cross-server toggle checks.
     */
    public boolean isAcceptingRequestsFromDb(UUID uuid) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT accepting FROM nexustpa_toggle_state WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getBoolean("accepting");
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return true; // Default: accepting
    }

    /**
     * Sets toggle state in DB and updates cache. ASYNC ONLY.
     *
     * @param uuid      player UUID
     * @param accepting true = accepting requests
     */
    public void setAcceptingRequests(UUID uuid, boolean accepting) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO nexustpa_toggle_state (uuid, accepting) VALUES (?, ?) " +
                             "ON DUPLICATE KEY UPDATE accepting = VALUES(accepting)")) {
            ps.setString(1, uuid.toString());
            ps.setBoolean(2, accepting);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        cache.put(uuid, accepting);
    }

    /**
     * Loads a player's toggle state into cache. ASYNC ONLY.
     * Called on PlayerJoinEvent.
     */
    public void loadIntoCache(UUID uuid) {
        boolean accepting = isAcceptingRequestsFromDb(uuid);
        cache.put(uuid, accepting);
    }

    /**
     * Evicts a player from cache. Called on PlayerQuitEvent.
     */
    public void evictCache(UUID uuid) {
        cache.remove(uuid);
    }
}
