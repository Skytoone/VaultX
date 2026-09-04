package net.milkbowl.vault.persistence.repository;

import net.milkbowl.vault.persistence.DatabaseConnectionManager;
import net.milkbowl.vault.redis.LocalFailoverManager.MailRecord;
import org.bukkit.plugin.Plugin;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class MailboxRepository {

    private final DatabaseConnectionManager dbManager;
    private final Plugin plugin;

    public MailboxRepository(DatabaseConnectionManager dbManager) {
        this.dbManager = dbManager;
        this.plugin = dbManager.getPlugin();
    }

    public void addMail(UUID uuid, String senderName, String message, double amount, String currency) {
        String query = "INSERT INTO player_mailbox (uuid, sender_name, message, amount, currency, status, timestamp) VALUES (?, ?, ?, ?, ?, 'PENDING', ?)";
        dbManager.executeDatabaseOperation(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, uuid.toString());
                pstmt.setString(2, senderName);
                pstmt.setString(3, message);
                pstmt.setDouble(4, amount);
                pstmt.setString(5, currency.toLowerCase());
                pstmt.setLong(6, System.currentTimeMillis());
                pstmt.executeUpdate();
            }
        }, "Failed to add mailbox record");
    }

    public List<MailRecord> getPendingMail(UUID uuid) {
        String query = "SELECT id, uuid, sender_name, message, amount, currency, status, timestamp FROM player_mailbox WHERE uuid = ? AND status = 'PENDING' ORDER BY id ASC";
        return dbManager.executeDatabaseQuery(conn -> {
            List<MailRecord> list = new ArrayList<>();
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, uuid.toString());
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        list.add(new MailRecord(
                                rs.getInt("id"),
                                UUID.fromString(rs.getString("uuid")),
                                rs.getString("sender_name"),
                                rs.getString("message"),
                                rs.getDouble("amount"),
                                rs.getString("currency"),
                                rs.getString("status"),
                                rs.getLong("timestamp")
                        ));
                    }
                }
            }
            return list;
        }, new ArrayList<>(), "Failed to get pending mail");
    }

    public void claimMail(int mailId) {
        String query = "UPDATE player_mailbox SET status = 'CLAIMED' WHERE id = ?";
        dbManager.executeDatabaseOperation(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setInt(1, mailId);
                pstmt.executeUpdate();
            }
        }, "Failed to claim mail");
    }

    public void deleteMail(int mailId) {
        String query = "DELETE FROM player_mailbox WHERE id = ?";
        dbManager.executeDatabaseOperation(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setInt(1, mailId);
                pstmt.executeUpdate();
            }
        }, "Failed to delete mail");
    }
}
