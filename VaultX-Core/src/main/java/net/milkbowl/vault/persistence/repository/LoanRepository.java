package net.milkbowl.vault.persistence.repository;

import net.milkbowl.vault.persistence.DatabaseConnectionManager;
import net.milkbowl.vault.redis.LocalFailoverManager.LoanRecord;
import org.bukkit.plugin.Plugin;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class LoanRepository {

    private final DatabaseConnectionManager dbManager;
    private final Plugin plugin;

    public LoanRepository(DatabaseConnectionManager dbManager) {
        this.dbManager = dbManager;
        this.plugin = dbManager.getPlugin();
    }

    public void saveLoan(LoanRecord loan) {
        String query = "INSERT INTO bank_loans (id, borrower, bank_name, principal, remaining, interest_rate, next_billing, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        dbManager.executeDatabaseOperation(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, loan.id);
                pstmt.setString(2, loan.borrower.toString());
                pstmt.setString(3, loan.bankName);
                pstmt.setDouble(4, loan.principal);
                pstmt.setDouble(5, loan.remaining);
                pstmt.setDouble(6, loan.interestRate);
                pstmt.setLong(7, loan.nextBilling);
                pstmt.setString(8, loan.status);
                pstmt.executeUpdate();
            }
        }, "Failed to save loan " + loan.id);
    }

    public void updateLoanRemaining(String id, double remaining, String status) {
        String query = "UPDATE bank_loans SET remaining = ?, status = ? WHERE id = ?";
        dbManager.executeDatabaseOperation(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setDouble(1, remaining);
                pstmt.setString(2, status);
                pstmt.setString(3, id);
                pstmt.executeUpdate();
            }
        }, "Failed to update loan remaining " + id);
    }

    public void updateLoanBilling(String id, long nextBilling) {
        String query = "UPDATE bank_loans SET next_billing = ? WHERE id = ?";
        dbManager.executeDatabaseOperation(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setLong(1, nextBilling);
                pstmt.setString(2, id);
                pstmt.executeUpdate();
            }
        }, "Failed to update loan billing timestamp " + id);
    }

    public List<LoanRecord> getActiveLoans() {
        String query = "SELECT id, borrower, bank_name, principal, remaining, interest_rate, next_billing, status FROM bank_loans WHERE status = 'ACTIVE'";
        return dbManager.executeDatabaseQuery(conn -> {
            List<LoanRecord> list = new ArrayList<>();
            try (PreparedStatement pstmt = conn.prepareStatement(query);
                 ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    list.add(new LoanRecord(
                            rs.getString("id"),
                            UUID.fromString(rs.getString("borrower")),
                            rs.getString("bank_name"),
                            rs.getDouble("principal"),
                            rs.getDouble("remaining"),
                            rs.getDouble("interest_rate"),
                            rs.getLong("next_billing"),
                            rs.getString("status")
                    ));
                }
            }
            return list;
        }, new ArrayList<>(), "Failed to get active loans");
    }

    public List<LoanRecord> getLoansForPlayer(UUID uuid) {
        String query = "SELECT id, borrower, bank_name, principal, remaining, interest_rate, next_billing, status FROM bank_loans WHERE borrower = ?";
        return dbManager.executeDatabaseQuery(conn -> {
            List<LoanRecord> list = new ArrayList<>();
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, uuid.toString());
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        list.add(new LoanRecord(
                                rs.getString("id"),
                                UUID.fromString(rs.getString("borrower")),
                                rs.getString("bank_name"),
                                rs.getDouble("principal"),
                                rs.getDouble("remaining"),
                                rs.getDouble("interest_rate"),
                                rs.getLong("next_billing"),
                                rs.getString("status")
                        ));
                    }
                }
            }
            return list;
        }, new ArrayList<>(), "Failed to get loans for player");
    }

    public double getPlayerDebt(UUID uuid) {
        String query = "SELECT amount FROM player_debts WHERE uuid = ?";
        return dbManager.executeDatabaseQuery(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, uuid.toString());
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getDouble("amount");
                    }
                }
            }
            return 0.0;
        }, 0.0, "Failed to get player debt");
    }

    public void updatePlayerDebt(UUID uuid, double amount) {
        net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
            String query = dbManager.isMySQL()
                    ? "INSERT INTO player_debts (uuid, amount) VALUES (?, ?) ON DUPLICATE KEY UPDATE amount = ?"
                    : "INSERT OR REPLACE INTO player_debts (uuid, amount) VALUES (?, ?)";
            dbManager.executeDatabaseOperation(conn -> {
                try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                    pstmt.setString(1, uuid.toString());
                    pstmt.setDouble(2, amount);
                    if (dbManager.isMySQL()) {
                        pstmt.setDouble(3, amount);
                    }
                    pstmt.executeUpdate();
                }
            }, "Failed to update player debt");
        });
    }

    public double getTotalBankLoansDebt() {
        String query = "SELECT SUM(remaining) AS total FROM bank_loans WHERE status = 'ACTIVE'";
        return dbManager.executeDatabaseQuery(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query);
                 ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getDouble("total");
                }
            }
            return 0.0;
        }, 0.0, "Failed to get total bank loans debt");
    }

    public double getTotalPlayerDebts() {
        String query = "SELECT SUM(amount) AS total FROM player_debts";
        return dbManager.executeDatabaseQuery(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query);
                 ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getDouble("total");
                }
            }
            return 0.0;
        }, 0.0, "Failed to get total player debts");
    }
}
