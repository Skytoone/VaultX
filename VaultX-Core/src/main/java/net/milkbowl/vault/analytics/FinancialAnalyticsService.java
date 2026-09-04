package net.milkbowl.vault.analytics;

import net.milkbowl.vault.persistence.DatabaseConnectionManager;
import net.milkbowl.vault.redis.LocalFailoverManager.AnalyticsReportEntry;
import org.bukkit.plugin.Plugin;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Service dedicated to financial analytics, 24h volumes, inflation/destruction reports, and scheduled daily aggregations.
 */
public class FinancialAnalyticsService {

    private final DatabaseConnectionManager dbManager;
    private final Plugin plugin;

    public FinancialAnalyticsService(DatabaseConnectionManager dbManager) {
        this.dbManager = dbManager;
        this.plugin = dbManager.getPlugin();
    }

    public double getTransactionVolume24h(String currency) {
        long timestamp24h = System.currentTimeMillis() - (24 * 60 * 60 * 1000L);
        String query = "SELECT SUM(amount) FROM player_transactions WHERE currency = ? AND timestamp >= ?";
        return dbManager.executeDatabaseQuery(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, currency != null ? currency.toLowerCase() : "default");
                pstmt.setLong(2, timestamp24h);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) return rs.getDouble(1);
                }
            }
            return 0.0;
        }, 0.0, "Failed to get 24h volume");
    }

    public List<AnalyticsReportEntry> getAnalyticsReport(int days) {
        long minTimestamp = System.currentTimeMillis() - (days * 24L * 60 * 60 * 1000);
        String query = "SELECT currency, category, " +
                " SUM(CASE WHEN type = 'DEPOSIT' THEN amount ELSE 0 END) as created, " +
                " SUM(CASE WHEN type = 'WITHDRAW' THEN amount ELSE 0 END) as destroyed " +
                " FROM player_transactions " +
                " WHERE timestamp >= ? " +
                " GROUP BY currency, category";
        return dbManager.executeDatabaseQuery(conn -> {
            List<AnalyticsReportEntry> list = new ArrayList<>();
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setLong(1, minTimestamp);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        list.add(new AnalyticsReportEntry(
                                rs.getString("currency"),
                                rs.getString("category"),
                                rs.getDouble("created"),
                                rs.getDouble("destroyed")
                        ));
                    }
                }
            }
            return list;
        }, new ArrayList<>(), "Failed to get analytics report");
    }

    public void runAnalyticsAggregation() {
        long now = System.currentTimeMillis();
        long sevenDaysAgo = now - (7L * 24 * 60 * 60 * 1000);

        String query = "SELECT " +
                " ((timestamp / 86400000) * 86400000) AS day_start, " +
                " currency, " +
                " category, " +
                " SUM(CASE WHEN type = 'DEPOSIT' THEN amount ELSE 0 END) as created, " +
                " SUM(CASE WHEN type = 'WITHDRAW' THEN amount ELSE 0 END) as destroyed " +
                " FROM player_transactions " +
                " WHERE timestamp >= ? " +
                " GROUP BY day_start, currency, category";

        String replaceQuery = "REPLACE INTO daily_analytics (day_timestamp, currency, category, amount_created, amount_destroyed) VALUES (?, ?, ?, ?, ?)";

        dbManager.executeDatabaseOperation(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setLong(1, sevenDaysAgo);
                try (ResultSet rs = pstmt.executeQuery()) {
                    try (PreparedStatement replacePstmt = conn.prepareStatement(replaceQuery)) {
                        boolean autoCommit = conn.getAutoCommit();
                        conn.setAutoCommit(false);
                        try {
                            while (rs.next()) {
                                long day = rs.getLong("day_start");
                                String currency = rs.getString("currency");
                                String category = rs.getString("category");
                                double created = rs.getDouble("created");
                                double destroyed = rs.getDouble("destroyed");

                                replacePstmt.setLong(1, day);
                                replacePstmt.setString(2, currency);
                                replacePstmt.setString(3, category);
                                replacePstmt.setDouble(4, created);
                                replacePstmt.setDouble(5, destroyed);
                                replacePstmt.addBatch();
                            }
                            replacePstmt.executeBatch();
                            conn.commit();
                        } catch (Exception e) {
                            try { conn.rollback(); } catch (SQLException ignored) {}
                            throw e;
                        } finally {
                            conn.setAutoCommit(autoCommit);
                        }
                    }
                }
            }
        }, "Failed to run analytics aggregation");

        int retentionDays = plugin.getConfig().getInt("storage.transaction-history-retention-days", 30);
        if (retentionDays > 0) {
            long cutOffTime = now - (retentionDays * 24L * 60L * 60L * 1000L);
            dbManager.executeDatabaseOperation(conn -> {
                String pruneTx = "DELETE FROM player_transactions WHERE timestamp < ?";
                try (PreparedStatement pstmt = conn.prepareStatement(pruneTx)) {
                    pstmt.setLong(1, cutOffTime);
                    int deletedTx = pstmt.executeUpdate();
                    if (deletedTx > 0) {
                        plugin.getLogger().info("[Vault Failover] Pruned " + deletedTx + " old transaction records.");
                    }
                }
                String pruneAudits = "DELETE FROM security_audits WHERE timestamp < ?";
                try (PreparedStatement pstmt = conn.prepareStatement(pruneAudits)) {
                    pstmt.setLong(1, cutOffTime);
                    int deletedAudits = pstmt.executeUpdate();
                    if (deletedAudits > 0) {
                        plugin.getLogger().info("[Vault Failover] Pruned " + deletedAudits + " old security audit records.");
                    }
                }
            }, "Failed to prune old history records");
        }

        int mailboxRetentionDays = plugin.getConfig().getInt("mailbox.retention-days", 60);
        if (mailboxRetentionDays > 0) {
            long mailCutoff = now - (mailboxRetentionDays * 24L * 60L * 60L * 1000L);
            dbManager.executeDatabaseOperation(conn -> {
                String pruneMail = "DELETE FROM player_mailbox WHERE status = 'PENDING' AND timestamp < ?";
                try (PreparedStatement pstmt = conn.prepareStatement(pruneMail)) {
                    pstmt.setLong(1, mailCutoff);
                    int deleted = pstmt.executeUpdate();
                    if (deleted > 0) {
                        plugin.getLogger().info("[Vault Failover] Pruned " + deleted + " expired mailbox records older than " + mailboxRetentionDays + " days.");
                    }
                }
            }, "Failed to prune expired mailbox records");
        }
    }
}
