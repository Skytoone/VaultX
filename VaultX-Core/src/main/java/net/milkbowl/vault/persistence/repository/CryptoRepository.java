package net.milkbowl.vault.persistence.repository;

import net.milkbowl.vault.persistence.DatabaseConnectionManager;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class CryptoRepository {

    private final DatabaseConnectionManager dbManager;

    public CryptoRepository(DatabaseConnectionManager dbManager) {
        this.dbManager = dbManager;
    }

    public void saveCryptoWallet(UUID uuid, String cryptoName, double amount) {
        String sql = dbManager.isPostgreSQL() ?
                "INSERT INTO crypto_wallets (uuid, crypto_name, balance) VALUES (?, ?, ?) ON CONFLICT (uuid, crypto_name) DO UPDATE SET balance = EXCLUDED.balance" :
                (dbManager.isMySQL() ? "INSERT INTO crypto_wallets (uuid, crypto_name, balance) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE balance = VALUES(balance)" :
                        "REPLACE INTO crypto_wallets (uuid, crypto_name, balance) VALUES (?, ?, ?)");
        dbManager.executeDatabaseOperation(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, cryptoName);
                ps.setDouble(3, amount);
                ps.executeUpdate();
            }
        }, "Failed to save crypto wallet");
    }

    public void deleteCryptoWallet(UUID uuid, String cryptoName) {
        String sql = "DELETE FROM crypto_wallets WHERE uuid = ? AND crypto_name = ?";
        dbManager.executeDatabaseOperation(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, cryptoName);
                ps.executeUpdate();
            }
        }, "Failed to delete crypto wallet");
    }

    public Map<UUID, Map<String, Double>> loadAllCryptoWallets() {
        String query = "SELECT uuid, crypto_name, balance FROM crypto_wallets";
        return dbManager.executeDatabaseQuery(conn -> {
            Map<UUID, Map<String, Double>> result = new HashMap<>();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(query)) {
                while (rs.next()) {
                    try {
                        UUID uuid = UUID.fromString(rs.getString("uuid"));
                        String name = rs.getString("crypto_name");
                        double bal = rs.getDouble("balance");
                        result.computeIfAbsent(uuid, k -> new HashMap<>()).put(name, bal);
                    } catch (Exception ignored) {}
                }
            }
            return result;
        }, new HashMap<>(), "Failed to load all crypto wallets");
    }

    public Map<String, Double> loadCryptoWalletForPlayer(UUID uuid) {
        if (uuid == null) return new HashMap<>();
        String sql = "SELECT crypto_name, balance FROM crypto_wallets WHERE uuid = ?";
        return dbManager.executeDatabaseQuery(conn -> {
            Map<String, Double> result = new HashMap<>();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.put(rs.getString("crypto_name"), rs.getDouble("balance"));
                    }
                }
            }
            return result;
        }, new HashMap<>(), "Failed to load crypto wallet for player");
    }
}
