package net.milkbowl.vault.persistence.writer;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

import org.bukkit.plugin.Plugin;

import net.milkbowl.vault.persistence.DatabaseConnectionManager;
import net.milkbowl.vault.redis.LocalFailoverManager.AuditRecord;
import net.milkbowl.vault.redis.LocalFailoverManager.PendingSyncRecord;
import net.milkbowl.vault.redis.LocalFailoverManager.PlayerTransactionRecord;

public class AsyncBatchWriter {

    private final DatabaseConnectionManager dbManager;
    private final Plugin plugin;

    private final BlockingQueue<PlayerTransactionRecord> transactionQueue;
    private final BlockingQueue<AuditRecord> auditQueue;
    private final BlockingQueue<PendingSyncRecord> syncQueue;

    private Thread batchWriterThread;
    private volatile boolean running = true;

    private final AtomicLong totalBatchWriteTimeMs = new AtomicLong(0);
    private final AtomicLong batchWriteCount = new AtomicLong(0);

    public AsyncBatchWriter(DatabaseConnectionManager dbManager, Plugin plugin) {
        this.dbManager = dbManager;
        this.plugin = plugin;
        int maxQueueCapacity = plugin.getConfig().getInt("advanced.batch-queue-limit", 50000);
        this.transactionQueue = new LinkedBlockingQueue<>(maxQueueCapacity);
        this.auditQueue = new LinkedBlockingQueue<>(maxQueueCapacity);
        this.syncQueue = new LinkedBlockingQueue<>(maxQueueCapacity);
        startBatchWriter();
    }

    public void queueBalanceSync(UUID uuid, String currency, double balance) {
        syncQueue.offer(new PendingSyncRecord(
                uuid.toString(),
                currency == null ? "default" : currency,
                balance,
                System.currentTimeMillis()
        ));
    }

    public void saveSecurityAudit(UUID uuid, String name, double amount, String action, String details) {
        auditQueue.offer(new AuditRecord(
                System.currentTimeMillis(),
                uuid != null ? uuid.toString() : null,
                name,
                amount,
                action,
                details
        ));
    }

    public void savePlayerTransaction(PlayerTransactionRecord record) {
        transactionQueue.offer(record);
    }

    public double getAverageBatchWriteLatencyMs() {
        long count = batchWriteCount.get();
        if (count == 0) return 0.0;
        return (double) totalBatchWriteTimeMs.get() / count;
    }

    private void startBatchWriter() {
        this.running = true;
        this.batchWriterThread = new Thread(this::runBatchWriteLoop, "VaultX-BatchWriter");
        this.batchWriterThread.setDaemon(true);
        this.batchWriterThread.start();
    }

    private void runBatchWriteLoop() {
        while (running || !transactionQueue.isEmpty() || !auditQueue.isEmpty() || !syncQueue.isEmpty()) {
            try {
                if (running) {
                    Thread.sleep(1000);
                }
            } catch (InterruptedException e) {
                running = false;
            }
            try {
                flushBatchQueues();
            } catch (Exception e) {
                plugin.getLogger().severe("[Vault Failover] Error in batch writer loop: " + e.getMessage());
            }
            if (!running && transactionQueue.isEmpty() && auditQueue.isEmpty() && syncQueue.isEmpty()) {
                break;
            }
        }
    }

    private void flushBatchQueues() {
        if (!transactionQueue.isEmpty()) {
            List<PlayerTransactionRecord> txList = new ArrayList<>();
            transactionQueue.drainTo(txList);
            if (!txList.isEmpty()) {
                long start = System.currentTimeMillis();
                String query = "INSERT INTO player_transactions (timestamp, uuid, type, currency, amount, other_party, category) VALUES (?, ?, ?, ?, ?, ?, ?)";
                boolean success = dbManager.executeDatabaseOperationChecked(conn -> {
                    boolean autoCommit = conn.getAutoCommit();
                    conn.setAutoCommit(false);
                    try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                        for (PlayerTransactionRecord r : txList) {
                            pstmt.setLong(1, r.timestamp);
                            pstmt.setString(2, r.uuid);
                            pstmt.setString(3, r.type);
                            pstmt.setString(4, r.currency);
                            pstmt.setDouble(5, r.amount);
                            pstmt.setString(6, r.otherParty);
                            pstmt.setString(7, r.category);
                            pstmt.addBatch();
                        }
                        pstmt.executeBatch();
                        conn.commit();
                    } catch (Exception e) {
                        conn.rollback();
                        throw e;
                    } finally {
                        conn.setAutoCommit(autoCommit);
                    }
                }, "Failed batch insert for player transactions");
                if (success) {
                    batchWriteCount.incrementAndGet();
                    totalBatchWriteTimeMs.addAndGet(System.currentTimeMillis() - start);
                } else {
                    transactionQueue.addAll(txList);
                }
            }
        }

        if (!auditQueue.isEmpty()) {
            List<AuditRecord> auditList = new ArrayList<>();
            auditQueue.drainTo(auditList);
            if (!auditList.isEmpty()) {
                long start = System.currentTimeMillis();
                String query = "INSERT INTO security_audits (timestamp, uuid, name, amount, action, details) VALUES (?, ?, ?, ?, ?, ?)";
                boolean success = dbManager.executeDatabaseOperationChecked(conn -> {
                    boolean autoCommit = conn.getAutoCommit();
                    conn.setAutoCommit(false);
                    try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                        for (AuditRecord r : auditList) {
                            pstmt.setLong(1, r.timestamp);
                            pstmt.setString(2, r.uuid);
                            pstmt.setString(3, r.name);
                            pstmt.setDouble(4, r.amount);
                            pstmt.setString(5, r.action);
                            pstmt.setString(6, r.details);
                            pstmt.addBatch();
                        }
                        pstmt.executeBatch();
                        conn.commit();
                    } catch (Exception e) {
                        conn.rollback();
                        throw e;
                    } finally {
                        conn.setAutoCommit(autoCommit);
                    }
                }, "Failed batch insert for security audits");
                if (success) {
                    batchWriteCount.incrementAndGet();
                    totalBatchWriteTimeMs.addAndGet(System.currentTimeMillis() - start);
                } else {
                    auditQueue.addAll(auditList);
                }
            }
        }

        if (!syncQueue.isEmpty()) {
            List<PendingSyncRecord> syncList = new ArrayList<>();
            syncQueue.drainTo(syncList);
            if (!syncList.isEmpty()) {
                long start = System.currentTimeMillis();
                String query = "INSERT INTO pending_syncs (uuid, currency, balance, timestamp) VALUES (?, ?, ?, ?)";
                boolean success = dbManager.executeDatabaseOperationChecked(conn -> {
                    boolean autoCommit = conn.getAutoCommit();
                    conn.setAutoCommit(false);
                    try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                        for (PendingSyncRecord r : syncList) {
                            pstmt.setString(1, r.uuid);
                            pstmt.setString(2, r.currency);
                            pstmt.setDouble(3, r.balance);
                            pstmt.setLong(4, r.timestamp);
                            pstmt.addBatch();
                        }
                        pstmt.executeBatch();
                        conn.commit();
                    } catch (Exception e) {
                        conn.rollback();
                        throw e;
                    } finally {
                        conn.setAutoCommit(autoCommit);
                    }
                }, "Failed batch insert for pending syncs");
                if (success) {
                    batchWriteCount.incrementAndGet();
                    totalBatchWriteTimeMs.addAndGet(System.currentTimeMillis() - start);
                } else {
                    syncQueue.addAll(syncList);
                }
            }
        }
    }

    public void close() {
        this.running = false;
        if (batchWriterThread != null) {
            try {
                batchWriterThread.interrupt();
                batchWriterThread.join(5000);
            } catch (InterruptedException ignored) {
            }
        }
        flushBatchQueues();
    }
}
