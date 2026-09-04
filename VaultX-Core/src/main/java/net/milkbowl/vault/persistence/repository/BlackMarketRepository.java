package net.milkbowl.vault.persistence.repository;

import net.milkbowl.vault.persistence.DatabaseConnectionManager;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class BlackMarketRepository {

    private final DatabaseConnectionManager dbManager;

    public BlackMarketRepository(DatabaseConnectionManager dbManager) {
        this.dbManager = dbManager;
    }

    public void saveDirtyBalance(UUID uuid, double amount) {
        String sql = dbManager.isPostgreSQL() ?
                "INSERT INTO dirty_balances (uuid, amount) VALUES (?, ?) ON CONFLICT (uuid) DO UPDATE SET amount = EXCLUDED.amount" :
                (dbManager.isMySQL() ? "INSERT INTO dirty_balances (uuid, amount) VALUES (?, ?) ON DUPLICATE KEY UPDATE amount = VALUES(amount)" :
                        "REPLACE INTO dirty_balances (uuid, amount) VALUES (?, ?)");
        dbManager.executeDatabaseOperation(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setDouble(2, amount);
                ps.executeUpdate();
            }
        }, "Failed to save dirty balance");
    }

    public void deleteDirtyBalance(UUID uuid) {
        String sql = "DELETE FROM dirty_balances WHERE uuid = ?";
        dbManager.executeDatabaseOperation(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            }
        }, "Failed to delete dirty balance");
    }

    public Map<UUID, Double> loadAllDirtyBalances() {
        String query = "SELECT uuid, amount FROM dirty_balances";
        return dbManager.executeDatabaseQuery(conn -> {
            Map<UUID, Double> result = new HashMap<>();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(query)) {
                while (rs.next()) {
                    try {
                        UUID uuid = UUID.fromString(rs.getString("uuid"));
                        double amt = rs.getDouble("amount");
                        result.put(uuid, amt);
                    } catch (Exception ignored) {}
                }
            }
            return result;
        }, new HashMap<>(), "Failed to load all dirty balances");
    }

    public double loadDirtyBalanceForPlayer(UUID uuid) {
        if (uuid == null) return 0.0;
        String sql = "SELECT amount FROM dirty_balances WHERE uuid = ?";
        return dbManager.executeDatabaseQuery(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return rs.getDouble("amount");
                    }
                }
            }
            return 0.0;
        }, 0.0, "Failed to load dirty balance for player");
    }
}
