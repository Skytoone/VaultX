package net.milkbowl.vault.persistence.repository;

import net.milkbowl.vault.persistence.DatabaseConnectionManager;
import net.milkbowl.vault.redis.LocalFailoverManager.AnalyticsReportEntry;
import net.milkbowl.vault.redis.LocalFailoverManager.AuditRecord;
import net.milkbowl.vault.redis.LocalFailoverManager.LocalEscrowRecord;
import net.milkbowl.vault.redis.LocalFailoverManager.PendingSyncRecord;
import net.milkbowl.vault.redis.LocalFailoverManager.PendingWebhookRecord;
import net.milkbowl.vault.redis.LocalFailoverManager.PlayerTransactionRecord;
import net.milkbowl.vault.redis.VaultRedisManager;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

public class AuditRepository {

    private final DatabaseConnectionManager dbManager;
    private final Plugin plugin;
    private final net.milkbowl.vault.analytics.FinancialAnalyticsService analyticsService;
    private final net.milkbowl.vault.persistence.writer.AsyncBatchWriter batchWriter;

    public AuditRepository(DatabaseConnectionManager dbManager) {
        this.dbManager = dbManager;
        this.plugin = dbManager.getPlugin();
        this.analyticsService = new net.milkbowl.vault.analytics.FinancialAnalyticsService(dbManager);
        this.batchWriter = new net.milkbowl.vault.persistence.writer.AsyncBatchWriter(dbManager, plugin);
    }

    public net.milkbowl.vault.analytics.FinancialAnalyticsService getAnalyticsService() {
        return analyticsService;
    }

    public void queueBalanceSync(UUID uuid, String currency, double balance) {
        batchWriter.queueBalanceSync(uuid, currency, balance);
    }

    public void processQueue(VaultRedisManager redis, AccountRepository accountRepo) {
        String selectQuery = "SELECT id, uuid, currency, balance, timestamp FROM pending_syncs ORDER BY id ASC";
        String deleteQuery = "DELETE FROM pending_syncs WHERE id = ?";

        dbManager.executeDatabaseOperation(conn -> {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(selectQuery);
                 PreparedStatement delStmt = conn.prepareStatement(deleteQuery)) {

                int count = 0;
                while (rs.next()) {
                    int id = rs.getInt("id");
                    UUID uuid = UUID.fromString(rs.getString("uuid"));
                    String currency = rs.getString("currency");
                    double balance = rs.getDouble("balance");
                    long localTimestamp = rs.getLong("timestamp");

                    long redisTimestamp = redis.getCustomCurrencyTimestamp(uuid, currency);
                    if (localTimestamp >= redisTimestamp) {
                        redis.publishBalanceUpdateDirect(uuid, currency, balance, localTimestamp);
                    } else {
                        double redisBalance = redis.getCustomCurrencyBalanceDirect(uuid, currency);
                        if (currency.equalsIgnoreCase("default")) {
                            accountRepo.saveCustomCurrencyBalance(uuid, "default", redisBalance, redisTimestamp);
                            updateDelegateBalance(uuid, redisBalance);
                        } else {
                            accountRepo.saveCustomCurrencyBalance(uuid, currency, redisBalance, redisTimestamp);
                        }
                    }

                    delStmt.setInt(1, id);
                    delStmt.executeUpdate();
                    count++;
                }
                if (count > 0) {
                    plugin.getLogger().info("[Vault Failover] Successfully processed " + count + " queued transactions with Latest-Write-Wins reconciliation.");
                }
            }
        }, "Error processing pending sync queue");
    }

    public void updateDelegateBalance(UUID uuid, double targetBalance) {
        org.bukkit.plugin.RegisteredServiceProvider<net.milkbowl.vault.economy.Economy> rsp = Bukkit.getServicesManager().getRegistration(net.milkbowl.vault.economy.Economy.class);
        if (rsp != null) {
            net.milkbowl.vault.economy.Economy econ = rsp.getProvider();
            OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
            if (op != null) {
                net.milkbowl.vault.util.FoliaScheduler.runSync(plugin, () -> {
                    double current = econ.getBalance(op);
                    double diff = targetBalance - current;
                    if (diff > 0) {
                        econ.depositPlayer(op, diff);
                    } else if (diff < 0) {
                        econ.withdrawPlayer(op, -diff);
                    }
                });
            }
        }
    }

    public void saveSecurityAudit(UUID uuid, String name, double amount, String action, String details) {
        batchWriter.saveSecurityAudit(uuid, name, amount, action, details);
    }

    public List<AuditRecord> getSecurityAudits(UUID uuid, int page, int pageSize) {
        String query = "SELECT timestamp, uuid, name, amount, action, details FROM security_audits WHERE uuid = ? ORDER BY id DESC LIMIT ? OFFSET ?";
        return dbManager.executeDatabaseQuery(conn -> {
            List<AuditRecord> list = new ArrayList<>();
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, uuid.toString());
                pstmt.setInt(2, pageSize);
                pstmt.setInt(3, (page - 1) * pageSize);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        list.add(new AuditRecord(
                                rs.getLong("timestamp"),
                                rs.getString("uuid"),
                                rs.getString("name"),
                                rs.getDouble("amount"),
                                rs.getString("action"),
                                rs.getString("details")
                        ));
                    }
                }
            }
            return list;
        }, new ArrayList<>(), "Failed to load security audits");
    }

    public List<AuditRecord> getSecurityAuditsAll(int page, int pageSize) {
        String query = "SELECT timestamp, uuid, name, amount, action, details FROM security_audits ORDER BY id DESC LIMIT ? OFFSET ?";
        return dbManager.executeDatabaseQuery(conn -> {
            List<AuditRecord> list = new ArrayList<>();
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setInt(1, pageSize);
                pstmt.setInt(2, (page - 1) * pageSize);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        list.add(new AuditRecord(
                                rs.getLong("timestamp"),
                                rs.getString("uuid"),
                                rs.getString("name"),
                                rs.getDouble("amount"),
                                rs.getString("action"),
                                rs.getString("details")
                        ));
                    }
                }
            }
            return list;
        }, new ArrayList<>(), "Failed to load security audits");
    }

    public void savePlayerTransaction(UUID uuid, String type, String currency, double amount, String otherParty) {
        String category = determineCategory(otherParty);
        batchWriter.savePlayerTransaction(new PlayerTransactionRecord(
                System.currentTimeMillis(),
                uuid != null ? uuid.toString() : null,
                type,
                currency.toLowerCase(),
                amount,
                otherParty,
                category
        ));
    }

    public static String determineCategory(String caller) {
        if (caller == null) return "OTHER";
        String lower = caller.toLowerCase();
        if (lower.contains("shop") || lower.contains("trade") || lower.contains("auction") || lower.contains("market") || lower.contains("chestshop")) {
            return "SHOP";
        }
        if (lower.contains("quest") || lower.contains("challenge") || lower.contains("mission") || lower.contains("battlepass")) {
            return "QUEST";
        }
        if (lower.contains("job") || lower.contains("mcmmo") || lower.contains("mine")) {
            return "JOB";
        }
        if (lower.contains("admin") || lower.contains("command") || lower.contains("vaultx") || lower.contains("console")) {
            return "ADMIN";
        }
        return "OTHER";
    }

    public List<PlayerTransactionRecord> getPlayerTransactions(UUID uuid, int page, int pageSize) {
        String query = "SELECT timestamp, uuid, type, currency, amount, other_party, category FROM player_transactions WHERE uuid = ? ORDER BY id DESC LIMIT ? OFFSET ?";
        return dbManager.executeDatabaseQuery(conn -> {
            List<PlayerTransactionRecord> list = new ArrayList<>();
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, uuid.toString());
                pstmt.setInt(2, pageSize);
                pstmt.setInt(3, (page - 1) * pageSize);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        list.add(new PlayerTransactionRecord(
                                rs.getLong("timestamp"),
                                rs.getString("uuid"),
                                rs.getString("type"),
                                rs.getString("currency"),
                                rs.getDouble("amount"),
                                rs.getString("other_party"),
                                rs.getString("category")
                        ));
                    }
                }
            }
            return list;
        }, new ArrayList<>(), "Failed to load player transactions");
    }

    public double getTransactionVolume24h(String currency) {
        return analyticsService.getTransactionVolume24h(currency);
    }

    public List<AnalyticsReportEntry> getAnalyticsReport(int days) {
        return analyticsService.getAnalyticsReport(days);
    }

    public void runAnalyticsAggregation() {
        analyticsService.runAnalyticsAggregation();
    }

    public void savePendingWebhook(String payload, int attempts, long nextRetry) {
        String query = "INSERT INTO pending_webhooks (payload, attempts, next_retry) VALUES (?, ?, ?)";
        dbManager.executeDatabaseOperation(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, payload);
                pstmt.setInt(2, attempts);
                pstmt.setLong(3, nextRetry);
                pstmt.executeUpdate();
            }
        }, "Failed to save pending webhook");
    }

    public List<PendingWebhookRecord> getPendingWebhooks(long now) {
        String query = "SELECT id, payload, attempts, next_retry FROM pending_webhooks WHERE next_retry <= ? ORDER BY id ASC";
        return dbManager.executeDatabaseQuery(conn -> {
            List<PendingWebhookRecord> list = new ArrayList<>();
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setLong(1, now);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        list.add(new PendingWebhookRecord(
                                rs.getLong("id"),
                                rs.getString("payload"),
                                rs.getInt("attempts"),
                                rs.getLong("next_retry")
                        ));
                    }
                }
            }
            return list;
        }, new ArrayList<>(), "Failed to get pending webhooks");
    }

    public void updatePendingWebhook(long id, int attempts, long nextRetry) {
        String query = "UPDATE pending_webhooks SET attempts = ?, next_retry = ? WHERE id = ?";
        dbManager.executeDatabaseOperation(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setInt(1, attempts);
                pstmt.setLong(2, nextRetry);
                pstmt.setLong(3, id);
                pstmt.executeUpdate();
            }
        }, "Failed to update pending webhook");
    }

    public void deletePendingWebhook(long id) {
        String query = "DELETE FROM pending_webhooks WHERE id = ?";
        dbManager.executeDatabaseOperation(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setLong(1, id);
                pstmt.executeUpdate();
            }
        }, "Failed to delete pending webhook");
    }

    public void saveLocalEscrow(String id, UUID sender, UUID receiver, double amount, String currency, String status, long timeoutAt) {
        String query = "REPLACE INTO local_escrows (id, sender, receiver, amount, currency, status, timeout_at) VALUES (?, ?, ?, ?, ?, ?, ?)";
        dbManager.executeDatabaseOperation(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, id);
                pstmt.setString(2, sender.toString());
                pstmt.setString(3, receiver.toString());
                pstmt.setDouble(4, amount);
                pstmt.setString(5, currency.toLowerCase());
                pstmt.setString(6, status);
                pstmt.setLong(7, timeoutAt);
                pstmt.executeUpdate();
            }
        }, "Failed to save local escrow");
    }

    public LocalEscrowRecord getLocalEscrow(String id) {
        String query = "SELECT sender, receiver, amount, currency, status, timeout_at FROM local_escrows WHERE id = ?";
        return dbManager.executeDatabaseQuery(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, id);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return new LocalEscrowRecord(
                                id,
                                rs.getString("sender"),
                                rs.getString("receiver"),
                                rs.getDouble("amount"),
                                rs.getString("currency"),
                                rs.getString("status"),
                                rs.getLong("timeout_at")
                        );
                    }
                }
            }
            return null;
        }, null, "Failed to get local escrow");
    }

    public void deleteLocalEscrow(String id) {
        String query = "DELETE FROM local_escrows WHERE id = ?";
        dbManager.executeDatabaseOperation(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, id);
                pstmt.executeUpdate();
            }
        }, "Failed to delete local escrow");
    }

    public List<LocalEscrowRecord> getLocalEscrowsForPlayer(UUID playerUuid) {
        String query = "SELECT id, sender, receiver, amount, currency, status, timeout_at FROM local_escrows WHERE sender = ? OR receiver = ?";
        return dbManager.executeDatabaseQuery(conn -> {
            List<LocalEscrowRecord> list = new ArrayList<>();
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, playerUuid.toString());
                pstmt.setString(2, playerUuid.toString());
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        list.add(new LocalEscrowRecord(
                                rs.getString("id"),
                                rs.getString("sender"),
                                rs.getString("receiver"),
                                rs.getDouble("amount"),
                                rs.getString("currency"),
                                rs.getString("status"),
                                rs.getLong("timeout_at")
                        ));
                    }
                }
            }
            return list;
        }, new ArrayList<>(), "Failed to get local escrows for player");
    }

    public List<LocalEscrowRecord> getExpiredPendingLocalEscrows(long now) {
        String query = "SELECT id, sender, receiver, amount, currency, status, timeout_at FROM local_escrows WHERE status = 'PENDING' AND timeout_at <= ?";
        return dbManager.executeDatabaseQuery(conn -> {
            List<LocalEscrowRecord> list = new ArrayList<>();
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setLong(1, now);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        list.add(new LocalEscrowRecord(
                                rs.getString("id"),
                                rs.getString("sender"),
                                rs.getString("receiver"),
                                rs.getDouble("amount"),
                                rs.getString("currency"),
                                rs.getString("status"),
                                rs.getString("timeout_at") != null ? rs.getLong("timeout_at") : 0L
                        ));
                    }
                }
            }
            return list;
        }, new ArrayList<>(), "Failed to get expired pending local escrows");
    }

    public double getAverageBatchWriteLatencyMs() {
        return batchWriter.getAverageBatchWriteLatencyMs();
    }

    public void close() {
        if (batchWriter != null) {
            batchWriter.close();
        }
    }
}
