package dev.naufal.nexostudio.nexustpa.backend.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * MySQL repository for cooldown timestamps.
 * ALL methods must be called from ASYNC thread only (runTaskAsynchronously).
 */
public class CooldownRepository {

    private final DatabaseManager db;

    public CooldownRepository(DatabaseManager db) {
        this.db = db;
    }

    /**
     * Checks if a player is on cooldown.
     * ASYNC ONLY.
     */
    public boolean isOnCooldown(UUID uuid, int cooldownSeconds) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT last_teleport_at FROM nexustpa_cooldowns WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long lastTeleportAt = rs.getLong("last_teleport_at");
                    long elapsed = System.currentTimeMillis() - lastTeleportAt;
                    return elapsed < (long) cooldownSeconds * 1000;
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    /**
     * Returns remaining cooldown in seconds.
     * ASYNC ONLY. Convention: only call when isOnCooldown() returned true.
     */
    public int getRemainingCooldown(UUID uuid, int cooldownSeconds) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT last_teleport_at FROM nexustpa_cooldowns WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long lastTeleportAt = rs.getLong("last_teleport_at");
                    long elapsed = System.currentTimeMillis() - lastTeleportAt;
                    long remaining = ((long) cooldownSeconds * 1000) - elapsed;
                    return (int) Math.ceil(remaining / 1000.0);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return 0;
    }

    /**
     * Records a teleport timestamp.
     * ASYNC ONLY.
     */
    public void setLastTeleportAt(UUID uuid, long epochMillis) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO nexustpa_cooldowns (uuid, last_teleport_at) VALUES (?, ?) " +
                             "ON DUPLICATE KEY UPDATE last_teleport_at = VALUES(last_teleport_at)")) {
            ps.setString(1, uuid.toString());
            ps.setLong(2, epochMillis);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}
