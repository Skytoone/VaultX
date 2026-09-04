package net.milkbowl.vault.persistence.repository;

import net.milkbowl.vault.persistence.DatabaseConnectionManager;
import net.milkbowl.vault.redis.LocalFailoverManager.SubscriptionRecord;
import org.bukkit.plugin.Plugin;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class SubscriptionRepository {

    private final DatabaseConnectionManager dbManager;
    private final Plugin plugin;

    public SubscriptionRepository(DatabaseConnectionManager dbManager) {
        this.dbManager = dbManager;
        this.plugin = dbManager.getPlugin();
    }

    public void saveSubscription(SubscriptionRecord sub) {
        String query = dbManager.isMySQL()
                ? "INSERT INTO player_subscriptions (id, subscriber, target_type, target, amount, currency, interval_hours, last_billing, next_billing, status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                  "ON DUPLICATE KEY UPDATE amount = ?, currency = ?, interval_hours = ?, last_billing = ?, next_billing = ?, status = ?"
                : "INSERT OR REPLACE INTO player_subscriptions (id, subscriber, target_type, target, amount, currency, interval_hours, last_billing, next_billing, status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        dbManager.executeDatabaseOperation(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, sub.id);
                pstmt.setString(2, sub.subscriber.toString());
                pstmt.setString(3, sub.targetType.toUpperCase());
                pstmt.setString(4, sub.target);
                pstmt.setDouble(5, sub.amount);
                pstmt.setString(6, sub.currency.toLowerCase());
                pstmt.setInt(7, sub.intervalHours);
                pstmt.setLong(8, sub.lastBilling);
                pstmt.setLong(9, sub.nextBilling);
                pstmt.setString(10, sub.status.toUpperCase());
                pstmt.setLong(11, sub.createdAt);
                if (dbManager.isMySQL()) {
                    pstmt.setDouble(12, sub.amount);
                    pstmt.setString(13, sub.currency.toLowerCase());
                    pstmt.setInt(14, sub.intervalHours);
                    pstmt.setLong(15, sub.lastBilling);
                    pstmt.setLong(16, sub.nextBilling);
                    pstmt.setString(17, sub.status.toUpperCase());
                }
                pstmt.executeUpdate();
            }
        }, "Failed to save subscription " + sub.id);
    }

    public void deleteSubscription(String id) {
        String query = "DELETE FROM player_subscriptions WHERE id = ?";
        dbManager.executeDatabaseOperation(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, id);
                pstmt.executeUpdate();
            }
        }, "Failed to delete subscription " + id);
    }

    public void updateSubscriptionBilling(String id, long lastBilling, long nextBilling, String status) {
        String query = "UPDATE player_subscriptions SET last_billing = ?, next_billing = ?, status = ? WHERE id = ?";
        dbManager.executeDatabaseOperation(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setLong(1, lastBilling);
                pstmt.setLong(2, nextBilling);
                pstmt.setString(3, status.toUpperCase());
                pstmt.setString(4, id);
                pstmt.executeUpdate();
            }
        }, "Failed to update subscription billing " + id);
    }

    public List<SubscriptionRecord> getSubscriptionsForSubscriber(UUID subscriberUuid) {
        String query = "SELECT id, subscriber, target_type, target, amount, currency, interval_hours, last_billing, next_billing, status, created_at FROM player_subscriptions WHERE subscriber = ?";
        return dbManager.executeDatabaseQuery(conn -> {
            List<SubscriptionRecord> list = new ArrayList<>();
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, subscriberUuid.toString());
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        list.add(new SubscriptionRecord(
                                rs.getString("id"),
                                UUID.fromString(rs.getString("subscriber")),
                                rs.getString("target_type"),
                                rs.getString("target"),
                                rs.getDouble("amount"),
                                rs.getString("currency"),
                                rs.getInt("interval_hours"),
                                rs.getLong("last_billing"),
                                rs.getLong("next_billing"),
                                rs.getString("status"),
                                rs.getLong("created_at")
                        ));
                    }
                }
            }
            return list;
        }, new ArrayList<>(), "Failed to get subscriptions for subscriber");
    }

    public List<SubscriptionRecord> getDueSubscriptions(long now) {
        String query = "SELECT id, subscriber, target_type, target, amount, currency, interval_hours, last_billing, next_billing, status, created_at FROM player_subscriptions WHERE status = 'ACTIVE' AND next_billing <= ?";
        return dbManager.executeDatabaseQuery(conn -> {
            List<SubscriptionRecord> list = new ArrayList<>();
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setLong(1, now);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        list.add(new SubscriptionRecord(
                                rs.getString("id"),
                                UUID.fromString(rs.getString("subscriber")),
                                rs.getString("target_type"),
                                rs.getString("target"),
                                rs.getDouble("amount"),
                                rs.getString("currency"),
                                rs.getInt("interval_hours"),
                                rs.getLong("last_billing"),
                                rs.getLong("next_billing"),
                                rs.getString("status"),
                                rs.getLong("created_at")
                        ));
                    }
                }
            }
            return list;
        }, new ArrayList<>(), "Failed to get due subscriptions");
    }

    public SubscriptionRecord getSubscription(String id) {
        String query = "SELECT id, subscriber, target_type, target, amount, currency, interval_hours, last_billing, next_billing, status, created_at FROM player_subscriptions WHERE id = ?";
        return dbManager.executeDatabaseQuery(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, id);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return new SubscriptionRecord(
                                rs.getString("id"),
                                UUID.fromString(rs.getString("subscriber")),
                                rs.getString("target_type"),
                                rs.getString("target"),
                                rs.getDouble("amount"),
                                rs.getString("currency"),
                                rs.getInt("interval_hours"),
                                rs.getLong("last_billing"),
                                rs.getLong("next_billing"),
                                rs.getString("status"),
                                rs.getLong("created_at")
                        );
                    }
                }
            }
            return null;
        }, null, "Failed to get subscription " + id);
    }
}
