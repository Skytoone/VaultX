package net.milkbowl.vault.persistence.repository;

import net.milkbowl.vault.economy.VaultCreditAPI;
import net.milkbowl.vault.persistence.DatabaseConnectionManager;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class CreditRepository {

    private final DatabaseConnectionManager dbManager;

    public CreditRepository(DatabaseConnectionManager dbManager) {
        this.dbManager = dbManager;
    }

    public void saveCreditAccount(VaultCreditAPI.CreditAccount account) {
        if (account == null) return;
        String sql = dbManager.isPostgreSQL() ?
                "INSERT INTO local_credit_accounts (uuid, currency, overdraft_limit, current_used_credit, credit_score, is_frozen) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (uuid, currency) DO UPDATE SET overdraft_limit = EXCLUDED.overdraft_limit, current_used_credit = EXCLUDED.current_used_credit, credit_score = EXCLUDED.credit_score, is_frozen = EXCLUDED.is_frozen" :
                (dbManager.isMySQL() ? "INSERT INTO local_credit_accounts (uuid, currency, overdraft_limit, current_used_credit, credit_score, is_frozen) VALUES (?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE overdraft_limit = VALUES(overdraft_limit), current_used_credit = VALUES(current_used_credit), credit_score = VALUES(credit_score), is_frozen = VALUES(is_frozen)" :
                        "REPLACE INTO local_credit_accounts (uuid, currency, overdraft_limit, current_used_credit, credit_score, is_frozen) VALUES (?, ?, ?, ?, ?, ?)");
        dbManager.executeDatabaseOperation(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, account.playerUuid().toString());
                ps.setString(2, account.currency());
                ps.setDouble(3, account.overdraftLimit());
                ps.setDouble(4, account.currentUsedCredit());
                ps.setInt(5, account.creditScore());
                ps.setInt(6, account.isFrozen() ? 1 : 0);
                ps.executeUpdate();
            }
        }, "Failed to save credit account");
    }

    public Map<UUID, Map<String, VaultCreditAPI.CreditAccount>> loadAllCreditAccounts() {
        String query = "SELECT uuid, currency, overdraft_limit, current_used_credit, credit_score, is_frozen FROM local_credit_accounts";
        return dbManager.executeDatabaseQuery(conn -> {
            Map<UUID, Map<String, VaultCreditAPI.CreditAccount>> result = new HashMap<>();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(query)) {
                while (rs.next()) {
                    try {
                        UUID uuid = UUID.fromString(rs.getString("uuid"));
                        String curr = rs.getString("currency");
                        double limit = rs.getDouble("overdraft_limit");
                        double used = rs.getDouble("current_used_credit");
                        int score = rs.getInt("credit_score");
                        boolean frozen = rs.getInt("is_frozen") == 1;

                        VaultCreditAPI.CreditAccount acc = new VaultCreditAPI.CreditAccount(uuid, curr, limit, used, score, frozen);
                        result.computeIfAbsent(uuid, k -> new HashMap<>()).put(curr, acc);
                    } catch (Exception ignored) {}
                }
            }
            return result;
        }, new HashMap<>(), "Failed to load all credit accounts");
    }

    public Map<String, VaultCreditAPI.CreditAccount> loadCreditAccountsForPlayer(UUID uuid) {
        if (uuid == null) return new HashMap<>();
        String sql = "SELECT currency, overdraft_limit, current_used_credit, credit_score, is_frozen FROM local_credit_accounts WHERE uuid = ?";
        return dbManager.executeDatabaseQuery(conn -> {
            Map<String, VaultCreditAPI.CreditAccount> result = new HashMap<>();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String curr = rs.getString("currency");
                        double limit = rs.getDouble("overdraft_limit");
                        double used = rs.getDouble("current_used_credit");
                        int score = rs.getInt("credit_score");
                        boolean frozen = rs.getInt("is_frozen") == 1;
                        result.put(curr, new VaultCreditAPI.CreditAccount(uuid, curr, limit, used, score, frozen));
                    }
                }
            }
            return result;
        }, new HashMap<>(), "Failed to load credit accounts for player");
    }
}
