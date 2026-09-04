package net.milkbowl.vault.persistence.repository;

import net.milkbowl.vault.economy.VaultSnapshotAPI.EconomySnapshot;
import net.milkbowl.vault.persistence.DatabaseConnectionManager;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Repository handling local SQLite/MySQL persistence of economy snapshots and balance states.
 */
public class SnapshotRepository {

    private final DatabaseConnectionManager dbManager;

    public SnapshotRepository(DatabaseConnectionManager dbManager) {
        this.dbManager = dbManager;
    }

    public boolean createSnapshot(String snapshotId, String label, long timestamp, int totalAccounts, double totalNetWorth, Map<UUID, Map<String, Double>> allBalances) {
        String insertSnapshot = "INSERT INTO economy_snapshots (snapshot_id, label, timestamp, total_accounts, total_net_worth) VALUES (?, ?, ?, ?, ?)";
        String insertBalance = "INSERT INTO snapshot_balances (snapshot_id, uuid, currency, balance) VALUES (?, ?, ?, ?)";

        return dbManager.executeDatabaseQuery(conn -> {
            boolean origAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement pstmt = conn.prepareStatement(insertSnapshot)) {
                    pstmt.setString(1, snapshotId);
                    pstmt.setString(2, label != null ? label : "Snapshot " + snapshotId);
                    pstmt.setLong(3, timestamp);
                    pstmt.setInt(4, totalAccounts);
                    pstmt.setDouble(5, totalNetWorth);
                    pstmt.executeUpdate();
                }

                try (PreparedStatement pstmt = conn.prepareStatement(insertBalance)) {
                    for (Map.Entry<UUID, Map<String, Double>> entry : allBalances.entrySet()) {
                        String uStr = entry.getKey().toString();
                        for (Map.Entry<String, Double> bEntry : entry.getValue().entrySet()) {
                            pstmt.setString(1, snapshotId);
                            pstmt.setString(2, uStr);
                            pstmt.setString(3, bEntry.getKey().toLowerCase());
                            pstmt.setDouble(4, bEntry.getValue());
                            pstmt.addBatch();
                        }
                    }
                    pstmt.executeBatch();
                }
                conn.commit();
                return true;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(origAutoCommit);
            }
        }, false, "Failed to save economy snapshot");
    }

    public List<EconomySnapshot> getSnapshotsFromDb(int limit) {
        String query = "SELECT snapshot_id, label, timestamp, total_accounts, total_net_worth FROM economy_snapshots ORDER BY timestamp DESC LIMIT ?";
        return dbManager.executeDatabaseQuery(conn -> {
            List<EconomySnapshot> list = new ArrayList<>();
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setInt(1, limit > 0 ? limit : 50);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        list.add(new EconomySnapshot(
                                rs.getString("snapshot_id"),
                                rs.getLong("timestamp"),
                                rs.getString("label"),
                                rs.getInt("total_accounts"),
                                rs.getDouble("total_net_worth")
                        ));
                    }
                }
            }
            return list;
        }, new ArrayList<>(), "Failed to get snapshots from DB");
    }

    public Map<UUID, Map<String, Double>> getSnapshotBalances(String snapshotId) {
        String query = "SELECT uuid, currency, balance FROM snapshot_balances WHERE snapshot_id = ?";
        return dbManager.executeDatabaseQuery(conn -> {
            Map<UUID, Map<String, Double>> map = new HashMap<>();
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, snapshotId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        try {
                            UUID u = UUID.fromString(rs.getString("uuid"));
                            String cur = rs.getString("currency").toLowerCase();
                            double bal = rs.getDouble("balance");
                            map.computeIfAbsent(u, k -> new HashMap<>()).put(cur, bal);
                        } catch (Exception ignored) {}
                    }
                }
            }
            return map;
        }, new HashMap<>(), "Failed to load snapshot balances");
    }

    public Map<String, Double> getPlayerSnapshotBalances(UUID uuid, String snapshotId) {
        String query = "SELECT currency, balance FROM snapshot_balances WHERE snapshot_id = ? AND uuid = ?";
        return dbManager.executeDatabaseQuery(conn -> {
            Map<String, Double> map = new HashMap<>();
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, snapshotId);
                pstmt.setString(2, uuid.toString());
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        map.put(rs.getString("currency").toLowerCase(), rs.getDouble("balance"));
                    }
                }
            }
            return map;
        }, new HashMap<>(), "Failed to load player snapshot balances");
    }

    public boolean deleteSnapshotFromDb(String snapshotId) {
        String del1 = "DELETE FROM economy_snapshots WHERE snapshot_id = ?";
        String del2 = "DELETE FROM snapshot_balances WHERE snapshot_id = ?";
        return dbManager.executeDatabaseQuery(conn -> {
            try (PreparedStatement p1 = conn.prepareStatement(del1);
                 PreparedStatement p2 = conn.prepareStatement(del2)) {
                p1.setString(1, snapshotId);
                p1.executeUpdate();
                p2.setString(1, snapshotId);
                p2.executeUpdate();
                return true;
            }
        }, false, "Failed to delete snapshot");
    }
}
