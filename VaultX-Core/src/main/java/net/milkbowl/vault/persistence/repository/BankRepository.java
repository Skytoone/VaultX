package net.milkbowl.vault.persistence.repository;

import net.milkbowl.vault.persistence.DatabaseConnectionManager;
import net.milkbowl.vault.redis.LocalFailoverManager.BankShareholderRecord;
import org.bukkit.plugin.Plugin;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class BankRepository {

    private final DatabaseConnectionManager dbManager;
    private final Plugin plugin;

    public BankRepository(DatabaseConnectionManager dbManager) {
        this.dbManager = dbManager;
        this.plugin = dbManager.getPlugin();
    }

    public void saveBankBalance(String bankName, double balance) {
        net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
            String query = dbManager.isMySQL()
                    ? "INSERT INTO local_banks (name, balance) VALUES (?, ?) ON DUPLICATE KEY UPDATE balance = VALUES(balance)"
                    : "INSERT INTO local_banks (name, balance) VALUES (?, ?) ON CONFLICT(name) DO UPDATE SET balance = excluded.balance";
            dbManager.executeDatabaseOperation(conn -> {
                try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                    pstmt.setString(1, bankName.toLowerCase());
                    pstmt.setDouble(2, balance);
                    pstmt.executeUpdate();
                }
            }, "Failed to save bank balance locally");
        });
    }

    public double getBankBalance(String bankName) {
        String query = "SELECT balance FROM local_banks WHERE name = ?";
        return dbManager.executeDatabaseQuery(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, bankName.toLowerCase());
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getDouble("balance");
                    }
                }
            }
            return 0.0;
        }, 0.0, "Failed to fetch bank balance");
    }

    public Map<String, Double> loadAllBanks() {
        String query = "SELECT name, balance FROM local_banks";
        return dbManager.executeDatabaseQuery(conn -> {
            Map<String, Double> banks = new HashMap<>();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(query)) {
                while (rs.next()) {
                    banks.put(rs.getString("name").toLowerCase(), rs.getDouble("balance"));
                }
            }
            return banks;
        }, new HashMap<>(), "Failed to load local banks");
    }

    public void createBankAccount(String name, UUID ownerUuid) {
        net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
            String query1 = "REPLACE INTO bank_accounts (name, owner_uuid, created_at) VALUES (?, ?, ?)";
            String query2 = "REPLACE INTO bank_members (bank_name, uuid, role) VALUES (?, ?, 'OWNER')";
            dbManager.executeDatabaseOperation(conn -> {
                boolean autoCommit = conn.getAutoCommit();
                conn.setAutoCommit(false);
                try {
                    try (PreparedStatement pstmt = conn.prepareStatement(query1)) {
                        pstmt.setString(1, name.toLowerCase());
                        pstmt.setString(2, ownerUuid.toString());
                        pstmt.setLong(3, System.currentTimeMillis());
                        pstmt.executeUpdate();
                    }
                    try (PreparedStatement pstmt = conn.prepareStatement(query2)) {
                        pstmt.setString(1, name.toLowerCase());
                        pstmt.setString(2, ownerUuid.toString());
                        pstmt.executeUpdate();
                    }
                    conn.commit();
                } catch (SQLException e) {
                    conn.rollback();
                    throw e;
                } finally {
                    conn.setAutoCommit(autoCommit);
                }
            }, "Failed to create bank account");
        });
    }

    public void deleteBankAccount(String name) {
        net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
            String query1 = "DELETE FROM bank_accounts WHERE name = ?";
            String query2 = "DELETE FROM bank_members WHERE bank_name = ?";
            String query3 = "DELETE FROM local_banks WHERE name = ?";
            dbManager.executeDatabaseOperation(conn -> {
                boolean autoCommit = conn.getAutoCommit();
                conn.setAutoCommit(false);
                try {
                    try (PreparedStatement pstmt = conn.prepareStatement(query1)) {
                        pstmt.setString(1, name.toLowerCase());
                        pstmt.executeUpdate();
                    }
                    try (PreparedStatement pstmt = conn.prepareStatement(query2)) {
                        pstmt.setString(1, name.toLowerCase());
                        pstmt.executeUpdate();
                    }
                    try (PreparedStatement pstmt = conn.prepareStatement(query3)) {
                        pstmt.setString(1, name.toLowerCase());
                        pstmt.executeUpdate();
                    }
                    conn.commit();
                } catch (SQLException e) {
                    conn.rollback();
                    throw e;
                } finally {
                    conn.setAutoCommit(autoCommit);
                }
            }, "Failed to delete bank account");
        });
    }

    public UUID getBankAccountOwner(String name) {
        String query = "SELECT owner_uuid FROM bank_accounts WHERE name = ?";
        return dbManager.executeDatabaseQuery(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, name.toLowerCase());
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return UUID.fromString(rs.getString("owner_uuid"));
                    }
                }
            }
            return null;
        }, null, "Failed to get bank account owner");
    }

    public void addBankMember(String bankName, UUID uuid, String role) {
        String query = "REPLACE INTO bank_members (bank_name, uuid, role) VALUES (?, ?, ?)";
        dbManager.executeDatabaseOperation(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, bankName.toLowerCase());
                pstmt.setString(2, uuid.toString());
                pstmt.setString(3, role.toUpperCase());
                pstmt.executeUpdate();
            }
        }, "Failed to add bank member");
    }

    public void removeBankMember(String bankName, UUID uuid) {
        String query = "DELETE FROM bank_members WHERE bank_name = ? AND uuid = ?";
        dbManager.executeDatabaseOperation(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, bankName.toLowerCase());
                pstmt.setString(2, uuid.toString());
                pstmt.executeUpdate();
            }
        }, "Failed to remove bank member");
    }

    public Map<UUID, String> getBankMembers(String bankName) {
        String query = "SELECT uuid, role FROM bank_members WHERE bank_name = ?";
        return dbManager.executeDatabaseQuery(conn -> {
            Map<UUID, String> map = new HashMap<>();
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, bankName.toLowerCase());
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        map.put(UUID.fromString(rs.getString("uuid")), rs.getString("role").toUpperCase());
                    }
                }
            }
            return map;
        }, new HashMap<>(), "Failed to get bank members");
    }

    public List<String> getBanksForPlayer(UUID uuid) {
        String query = "SELECT bank_name FROM bank_members WHERE uuid = ? AND role NOT LIKE 'INVITED_%'";
        return dbManager.executeDatabaseQuery(conn -> {
            List<String> list = new ArrayList<>();
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, uuid.toString());
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        list.add(rs.getString("bank_name").toLowerCase());
                    }
                }
            }
            return list;
        }, new ArrayList<>(), "Failed to get banks for player");
    }

    public Map<String, String> getPendingInvitesForPlayer(UUID uuid) {
        String query = "SELECT bank_name, role FROM bank_members WHERE uuid = ? AND role LIKE 'INVITED_%'";
        return dbManager.executeDatabaseQuery(conn -> {
            Map<String, String> invites = new HashMap<>();
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, uuid.toString());
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        String role = rs.getString("role").replace("INVITED_", "");
                        invites.put(rs.getString("bank_name").toLowerCase(), role);
                    }
                }
            }
            return invites;
        }, new HashMap<>(), "Failed to get pending invites");
    }

    public String getBankRole(String bankName, UUID uuid) {
        String query = "SELECT role FROM bank_members WHERE bank_name = ? AND uuid = ?";
        return dbManager.executeDatabaseQuery(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, bankName.toLowerCase());
                pstmt.setString(2, uuid.toString());
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getString("role").toUpperCase();
                    }
                }
            }
            return null;
        }, null, "Failed to get bank role");
    }

    public void applyBulkBankInterest(double factor) {
        String query = "UPDATE local_banks SET balance = balance * ?";
        dbManager.executeDatabaseOperation(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setDouble(1, factor);
                pstmt.executeUpdate();
            }
        }, "Failed to apply bulk bank interest");
    }

    public Map<String, Double> getAllBankBalances() {
        String query = "SELECT name, balance FROM local_banks";
        return dbManager.executeDatabaseQuery(conn -> {
            Map<String, Double> map = new HashMap<>();
            try (PreparedStatement pstmt = conn.prepareStatement(query);
                 ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    map.put(rs.getString("name").toLowerCase(), rs.getDouble("balance"));
                }
            }
            return map;
        }, new HashMap<>(), "Failed to get all bank balances");
    }

    public double getOutstandingBankShares(String bankName) {
        String query = "SELECT SUM(shares) AS total FROM player_stocks WHERE commodity = ?";
        return dbManager.executeDatabaseQuery(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, "bank:" + bankName.toLowerCase());
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getDouble("total");
                    }
                }
            }
            return 0.0;
        }, 0.0, "Failed to get outstanding bank shares");
    }

    public List<BankShareholderRecord> getBankShareholders() {
        String query = "SELECT uuid, commodity, shares FROM player_stocks WHERE commodity LIKE 'bank:%'";
        return dbManager.executeDatabaseQuery(conn -> {
            List<BankShareholderRecord> list = new ArrayList<>();
            try (PreparedStatement pstmt = conn.prepareStatement(query);
                 ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    String comm = rs.getString("commodity");
                    if (comm.startsWith("bank:")) {
                        String bank = comm.substring(5);
                        list.add(new BankShareholderRecord(
                                UUID.fromString(rs.getString("uuid")),
                                bank,
                                rs.getDouble("shares")
                        ));
                    }
                }
            }
            return list;
        }, new ArrayList<>(), "Failed to get bank shareholders");
    }
}
