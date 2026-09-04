package net.milkbowl.vault.redis.failover;

import net.milkbowl.vault.persistence.repository.AccountRepository;
import net.milkbowl.vault.persistence.repository.AuditRepository;
import net.milkbowl.vault.redis.LocalFailoverManager.AnalyticsReportEntry;
import net.milkbowl.vault.redis.LocalFailoverManager.LocalEscrowRecord;
import net.milkbowl.vault.redis.LocalFailoverManager.PendingWebhookRecord;
import net.milkbowl.vault.redis.VaultRedisManager;

import java.util.List;
import java.util.UUID;

/**
 * Replay engine managing offline transaction sync queues, webhook retries, escrow timeouts, and analytics scheduling.
 */
public class TransactionReplayEngine {

    private final AuditRepository auditRepository;
    private final AccountRepository accountRepository;

    public TransactionReplayEngine(AuditRepository auditRepository, AccountRepository accountRepository) {
        this.auditRepository = auditRepository;
        this.accountRepository = accountRepository;
    }

    public void queueBalanceSync(UUID uuid, String currency, double balance) {
        auditRepository.queueBalanceSync(uuid, currency, balance);
    }

    public void processQueue(VaultRedisManager redis) {
        auditRepository.processQueue(redis, accountRepository);
    }

    public void updateDelegateBalance(UUID uuid, double targetBalance) {
        auditRepository.updateDelegateBalance(uuid, targetBalance);
    }

    public void runAnalyticsAggregation() {
        auditRepository.runAnalyticsAggregation();
    }

    public List<AnalyticsReportEntry> getAnalyticsReport(int days) {
        return auditRepository.getAnalyticsReport(days);
    }

    public void savePendingWebhook(String payload, int attempts, long nextRetry) {
        auditRepository.savePendingWebhook(payload, attempts, nextRetry);
    }

    public List<PendingWebhookRecord> getPendingWebhooks(long now) {
        return auditRepository.getPendingWebhooks(now);
    }

    public void updatePendingWebhook(long id, int attempts, long nextRetry) {
        auditRepository.updatePendingWebhook(id, attempts, nextRetry);
    }

    public void deletePendingWebhook(long id) {
        auditRepository.deletePendingWebhook(id);
    }

    public void saveLocalEscrow(String id, UUID sender, UUID receiver, double amount, String currency, String status, long timeoutAt) {
        auditRepository.saveLocalEscrow(id, sender, receiver, amount, currency, status, timeoutAt);
    }

    public LocalEscrowRecord getLocalEscrow(String id) {
        return auditRepository.getLocalEscrow(id);
    }

    public void deleteLocalEscrow(String id) {
        auditRepository.deleteLocalEscrow(id);
    }

    public List<LocalEscrowRecord> getLocalEscrowsForPlayer(UUID playerUuid) {
        return auditRepository.getLocalEscrowsForPlayer(playerUuid);
    }

    public List<LocalEscrowRecord> getExpiredPendingLocalEscrows(long now) {
        return auditRepository.getExpiredPendingLocalEscrows(now);
    }

    public double getAverageBatchWriteLatencyMs() {
        return auditRepository.getAverageBatchWriteLatencyMs();
    }
}
