package net.milkbowl.vault.redis;

import net.milkbowl.vault.economy.VaultAuctionAPI;
import net.milkbowl.vault.economy.VaultCreditAPI;
import net.milkbowl.vault.economy.VaultSnapshotAPI.EconomySnapshot;
import net.milkbowl.vault.economy.VaultStakingAPI;
import net.milkbowl.vault.persistence.DatabaseConnectionManager;
import net.milkbowl.vault.persistence.repository.AccountRepository;
import net.milkbowl.vault.persistence.repository.AuditRepository;
import net.milkbowl.vault.persistence.repository.BankRepository;
import net.milkbowl.vault.persistence.repository.BlackMarketRepository;
import net.milkbowl.vault.persistence.repository.LoanRepository;
import net.milkbowl.vault.persistence.repository.MailboxRepository;
import net.milkbowl.vault.persistence.repository.StakingAuctionRepository;
import net.milkbowl.vault.persistence.repository.SubscriptionRepository;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public class LocalFailoverManager {

    private static LocalFailoverManager instance;

    // Nested DTO definitions delegating to net.milkbowl.vault.model for 100% backward compatibility

    public static class CustomBalanceRecord extends net.milkbowl.vault.model.CustomBalanceRecord {
        public CustomBalanceRecord(UUID uuid, String currency, double balance) {
            super(uuid, currency, balance);
        }
    }

    public static class BankShareholderRecord extends net.milkbowl.vault.model.BankShareholderRecord {
        public BankShareholderRecord(UUID uuid, String bankName, double shares) {
            super(uuid, bankName, shares);
        }
    }

    public static class LoanRecord extends net.milkbowl.vault.model.LoanRecord {
        public LoanRecord(String id, UUID borrower, String bankName, double principal, double remaining, double interestRate, long nextBilling, String status) {
            super(id, borrower, bankName, principal, remaining, interestRate, nextBilling, status);
        }
    }

    public static class MailRecord extends net.milkbowl.vault.model.MailRecord {
        public MailRecord(int id, UUID uuid, String senderName, String message, double amount, String currency, String status, long timestamp) {
            super(id, uuid, senderName, message, amount, currency, status, timestamp);
        }
    }

    public static class SubscriptionRecord extends net.milkbowl.vault.model.SubscriptionRecord {
        public SubscriptionRecord(String id, UUID subscriber, String targetType, String target, double amount, String currency, int intervalHours, long lastBilling, long nextBilling, String status, long createdAt) {
            super(id, subscriber, targetType, target, amount, currency, intervalHours, lastBilling, nextBilling, status, createdAt);
        }
    }

    public static class AuditRecord extends net.milkbowl.vault.model.AuditRecord {
        public AuditRecord(long timestamp, String uuid, String name, double amount, String action, String details) {
            super(timestamp, uuid, name, amount, action, details);
        }
    }

    public static class PlayerTransactionRecord extends net.milkbowl.vault.model.PlayerTransactionRecord {
        public PlayerTransactionRecord(long timestamp, String uuid, String type, String currency, double amount, String otherParty, String category) {
            super(timestamp, uuid, type, currency, amount, otherParty, category);
        }
    }

    public static class PendingSyncRecord extends net.milkbowl.vault.model.PendingSyncRecord {
        public PendingSyncRecord(String uuid, String currency, double balance, long timestamp) {
            super(uuid, currency, balance, timestamp);
        }
    }

    public static class AnalyticsReportEntry extends net.milkbowl.vault.model.AnalyticsReportEntry {
        public AnalyticsReportEntry(String currency, String category, double created, double destroyed) {
            super(currency, category, created, destroyed);
        }
    }

    public static class PendingWebhookRecord extends net.milkbowl.vault.model.PendingWebhookRecord {
        public PendingWebhookRecord(long id, String payload, int attempts, long nextRetry) {
            super(id, payload, attempts, nextRetry);
        }
    }

    public static class LocalEscrowRecord extends net.milkbowl.vault.model.LocalEscrowRecord {
        public LocalEscrowRecord(String id, String sender, String receiver, double amount, String currency, String status, long timeoutAt) {
            super(id, sender, receiver, amount, currency, status, timeoutAt);
        }
    }

    public static class LocalCheckRecord extends net.milkbowl.vault.model.LocalCheckRecord {
        public LocalCheckRecord(String id, double amount, String currency, UUID creatorUuid, String status, long createdAt) {
            super(id, amount, currency, creatorUuid, status, createdAt);
        }
        public LocalCheckRecord(String id, double amount, String currency, UUID creatorUuid, String status) {
            super(id, amount, currency, creatorUuid, status);
        }
    }

    public static class StockPortfolioEntry extends net.milkbowl.vault.model.StockPortfolioEntry {
        public StockPortfolioEntry(String commodity, double shares) {
            super(commodity, shares);
        }
    }

    public static class LocalCurrencyStats extends net.milkbowl.vault.model.LocalCurrencyStats {
        public LocalCurrencyStats(double totalMoney, int accountsCount, double averageBalance, double transactionVolume24h) {
            super(totalMoney, accountsCount, averageBalance, transactionVolume24h);
        }
        public LocalCurrencyStats(double totalMoney, double averageBalance, double transactionVolume24h) {
            super(totalMoney, averageBalance, transactionVolume24h);
        }
    }

    private final Plugin plugin;
    private final net.milkbowl.vault.redis.failover.FailoverStorageRepository storageRepository;
    private final net.milkbowl.vault.redis.failover.TransactionReplayEngine replayEngine;

    public static LocalFailoverManager getInstance() {
        return instance;
    }

    public LocalFailoverManager(Plugin plugin) {
        instance = this;
        this.plugin = plugin;
        this.storageRepository = new net.milkbowl.vault.redis.failover.FailoverStorageRepository(plugin);
        this.replayEngine = new net.milkbowl.vault.redis.failover.TransactionReplayEngine(
                storageRepository.getAuditRepository(),
                storageRepository.getAccountRepository()
        );
    }

    public Plugin getPlugin() {
        return plugin;
    }

    public net.milkbowl.vault.redis.failover.FailoverStorageRepository getStorageRepository() {
        return storageRepository;
    }

    public net.milkbowl.vault.redis.failover.TransactionReplayEngine getReplayEngine() {
        return replayEngine;
    }

    public DatabaseConnectionManager getDbManager() {
        return storageRepository.getDbManager();
    }

    public AccountRepository getAccountRepository() {
        return storageRepository.getAccountRepository();
    }

    public BankRepository getBankRepository() {
        return storageRepository.getBankRepository();
    }

    public LoanRepository getLoanRepository() {
        return storageRepository.getLoanRepository();
    }

    public MailboxRepository getMailboxRepository() {
        return storageRepository.getMailboxRepository();
    }

    public SubscriptionRepository getSubscriptionRepository() {
        return storageRepository.getSubscriptionRepository();
    }

    public AuditRepository getAuditRepository() {
        return storageRepository.getAuditRepository();
    }

    public BlackMarketRepository getBlackMarketRepository() {
        return storageRepository.getBlackMarketRepository();
    }

    public StakingAuctionRepository getStakingAuctionRepository() {
        return storageRepository.getStakingAuctionRepository();
    }

    public void close() {
        if (instance == this) {
            instance = null;
        }
        if (storageRepository != null) {
            storageRepository.close();
        }
    }

    // Delegation to Repositories

    // Delegation to Repositories & Replay Engine

    public void queueBalanceSync(UUID uuid, String currency, double balance) {
        replayEngine.queueBalanceSync(uuid, currency, balance);
    }

    public void processQueue(VaultRedisManager redis) {
        replayEngine.processQueue(redis);
    }

    public void updateDelegateBalance(UUID uuid, double targetBalance) {
        replayEngine.updateDelegateBalance(uuid, targetBalance);
    }

    public void saveBankBalance(String bankName, double balance) {
        getBankRepository().saveBankBalance(bankName, balance);
    }

    public double getBankBalance(String bankName) {
        return getBankRepository().getBankBalance(bankName);
    }

    public Map<String, Double> loadAllBanks() {
        return getBankRepository().loadAllBanks();
    }

    public void saveSecurityAudit(UUID uuid, String name, double amount, String action, String details) {
        getAuditRepository().saveSecurityAudit(uuid, name, amount, action, details);
    }

    public List<AuditRecord> getSecurityAudits(UUID uuid, int page, int pageSize) {
        return getAuditRepository().getSecurityAudits(uuid, page, pageSize);
    }

    public List<AuditRecord> getSecurityAuditsAll(int page, int pageSize) {
        return getAuditRepository().getSecurityAuditsAll(page, pageSize);
    }

    public void saveCustomCurrencyBalance(UUID uuid, String currency, double balance) {
        getAccountRepository().saveCustomCurrencyBalance(uuid, currency, balance);
    }

    public void saveCustomCurrencyBalance(UUID uuid, String currency, double balance, long timestamp) {
        getAccountRepository().saveCustomCurrencyBalance(uuid, currency, balance, timestamp);
    }

    public long getCustomCurrencyTimestamp(UUID uuid, String currency) {
        return getAccountRepository().getCustomCurrencyTimestamp(uuid, currency);
    }

    public double getCustomCurrencyBalance(UUID uuid, String currency) {
        return getAccountRepository().getCustomCurrencyBalance(uuid, currency);
    }

    public void saveUuidCache(String name, UUID uuid) {
        getAccountRepository().saveUuidCache(name, uuid);
    }

    public UUID getUuidFromCache(String name) {
        return getAccountRepository().getUuidFromCache(name);
    }

    public String getNameFromCache(UUID uuid) {
        return getAccountRepository().getNameFromCache(uuid);
    }

    public void savePlayerTransaction(UUID uuid, String type, String currency, double amount, String otherParty) {
        getAuditRepository().savePlayerTransaction(uuid, type, currency, amount, otherParty);
    }

    public List<PlayerTransactionRecord> getPlayerTransactions(UUID uuid, int page, int pageSize) {
        return getAuditRepository().getPlayerTransactions(uuid, page, pageSize);
    }

    public double getTotalMoneySupply(String currency) {
        return getAccountRepository().getTotalMoneySupply(currency);
    }

    public double getAverageAccountBalance(String currency) {
        return getAccountRepository().getAverageAccountBalance(currency);
    }

    public double getTransactionVolume24h(String currency) {
        return getAuditRepository().getTransactionVolume24h(currency);
    }

    public LocalCurrencyStats getLocalCurrencyStats(String currency) {
        return new LocalCurrencyStats(
                getTotalMoneySupply(currency),
                0,
                getAverageAccountBalance(currency),
                getTransactionVolume24h(currency)
        );
    }

    public List<AnalyticsReportEntry> getAnalyticsReport(int days) {
        return replayEngine.getAnalyticsReport(days);
    }

    public double getTotalCurrencyBalanceSum(String currency) {
        return getAccountRepository().getTotalCurrencyBalanceSum(currency);
    }

    public void runAnalyticsAggregation() {
        replayEngine.runAnalyticsAggregation();
    }

    public void savePendingWebhook(String payload, int attempts, long nextRetry) {
        replayEngine.savePendingWebhook(payload, attempts, nextRetry);
    }

    public List<PendingWebhookRecord> getPendingWebhooks(long now) {
        return replayEngine.getPendingWebhooks(now);
    }

    public void updatePendingWebhook(long id, int attempts, long nextRetry) {
        replayEngine.updatePendingWebhook(id, attempts, nextRetry);
    }

    public void deletePendingWebhook(long id) {
        replayEngine.deletePendingWebhook(id);
    }

    public void saveLocalEscrow(String id, UUID sender, UUID receiver, double amount, String currency, String status, long timeoutAt) {
        replayEngine.saveLocalEscrow(id, sender, receiver, amount, currency, status, timeoutAt);
    }

    public LocalEscrowRecord getLocalEscrow(String id) {
        return replayEngine.getLocalEscrow(id);
    }

    public void deleteLocalEscrow(String id) {
        replayEngine.deleteLocalEscrow(id);
    }

    public List<LocalEscrowRecord> getLocalEscrowsForPlayer(UUID playerUuid) {
        return replayEngine.getLocalEscrowsForPlayer(playerUuid);
    }

    public List<LocalEscrowRecord> getExpiredPendingLocalEscrows(long now) {
        return replayEngine.getExpiredPendingLocalEscrows(now);
    }

    public double getAverageBatchWriteLatencyMs() {
        return replayEngine.getAverageBatchWriteLatencyMs();
    }

    public void saveExchangeRate(String currency, double rate) {
        storageRepository.saveExchangeRate(currency, rate);
    }

    public Map<String, Double> getExchangeRates() {
        return storageRepository.getExchangeRates();
    }

    public void saveSetting(String key, String value) {
        storageRepository.saveSetting(key, value);
    }

    public String getSetting(String key) {
        return storageRepository.getSetting(key);
    }

    public void saveCheck(String id, double amount, String currency, UUID creatorUuid) {
        storageRepository.saveCheck(id, amount, currency, creatorUuid);
    }

    public LocalCheckRecord getCheck(String id) {
        return storageRepository.getCheck(id);
    }

    public void updateCheckStatus(String id, String status) {
        storageRepository.updateCheckStatus(id, status);
    }

    public double getPlayerStockShares(UUID uuid, String commodity) {
        return storageRepository.getPlayerStockShares(uuid, commodity);
    }

    public void updatePlayerStockShares(UUID uuid, String commodity, double shares) {
        storageRepository.updatePlayerStockShares(uuid, commodity, shares);
    }

    public List<StockPortfolioEntry> getPlayerPortfolio(UUID uuid) {
        return storageRepository.getPlayerPortfolio(uuid);
    }

    public void createBankAccount(String name, UUID ownerUuid) {
        getBankRepository().createBankAccount(name, ownerUuid);
    }

    public void deleteBankAccount(String name) {
        getBankRepository().deleteBankAccount(name);
    }

    public UUID getBankAccountOwner(String name) {
        return getBankRepository().getBankAccountOwner(name);
    }

    public void addBankMember(String bankName, UUID uuid, String role) {
        getBankRepository().addBankMember(bankName, uuid, role);
    }

    public void removeBankMember(String bankName, UUID uuid) {
        getBankRepository().removeBankMember(bankName, uuid);
    }

    public Map<UUID, String> getBankMembers(String bankName) {
        return getBankRepository().getBankMembers(bankName);
    }

    public List<String> getBanksForPlayer(UUID uuid) {
        return getBankRepository().getBanksForPlayer(uuid);
    }

    public Map<String, String> getPendingInvitesForPlayer(UUID uuid) {
        return getBankRepository().getPendingInvitesForPlayer(uuid);
    }

    public String getBankRole(String bankName, UUID uuid) {
        return getBankRepository().getBankRole(bankName, uuid);
    }

    public void addMail(UUID uuid, String senderName, String message, double amount, String currency) {
        getMailboxRepository().addMail(uuid, senderName, message, amount, currency);
    }

    public List<MailRecord> getPendingMail(UUID uuid) {
        return getMailboxRepository().getPendingMail(uuid);
    }

    public void claimMail(int mailId) {
        getMailboxRepository().claimMail(mailId);
    }

    public void deleteMail(int mailId) {
        getMailboxRepository().deleteMail(mailId);
    }

    public void applyBulkInterest(String currency, double factor, long timestamp) {
        getAccountRepository().applyBulkInterest(currency, factor, timestamp);
    }

    public void applyBulkBankInterest(double factor) {
        getBankRepository().applyBulkBankInterest(factor);
    }

    public void applyBulkWealthTax(double threshold, double taxRate, long timestamp) {
        getAccountRepository().applyBulkWealthTax(threshold, taxRate, timestamp);
    }

    public List<CustomBalanceRecord> getAllCustomBalances() {
        return getAccountRepository().getAllCustomBalances();
    }

    public List<CustomBalanceRecord> getCustomBalancesUpdatedSince(long timestamp) {
        return getAccountRepository().getCustomBalancesUpdatedSince(timestamp);
    }

    public double getCustomWealthTaxSum(String currency, double threshold, double taxRate) {
        return getAccountRepository().getCustomWealthTaxSum(currency, threshold, taxRate);
    }

    public Map<String, Double> getAllBankBalances() {
        return getBankRepository().getAllBankBalances();
    }

    public double getCommodityPrice(String commodity) {
        return storageRepository.getCommodityPrice(commodity);
    }

    public void updateCommodityPrice(String commodity, double price) {
        storageRepository.updateCommodityPrice(commodity, price);
    }

    public void saveLoan(LoanRecord loan) {
        getLoanRepository().saveLoan(loan);
    }

    public void updateLoanRemaining(String id, double remaining, String status) {
        getLoanRepository().updateLoanRemaining(id, remaining, status);
    }

    public void updateLoanBilling(String id, long nextBilling) {
        getLoanRepository().updateLoanBilling(id, nextBilling);
    }

    public List<LoanRecord> getActiveLoans() {
        return getLoanRepository().getActiveLoans();
    }

    public List<LoanRecord> getLoansForPlayer(UUID uuid) {
        return getLoanRepository().getLoansForPlayer(uuid);
    }

    public double getPlayerDebt(UUID uuid) {
        return getLoanRepository().getPlayerDebt(uuid);
    }

    public void updatePlayerDebt(UUID uuid, double amount) {
        getLoanRepository().updatePlayerDebt(uuid, amount);
    }

    public double getOutstandingBankShares(String bankName) {
        return getBankRepository().getOutstandingBankShares(bankName);
    }

    public List<BankShareholderRecord> getBankShareholders() {
        return getBankRepository().getBankShareholders();
    }

    public void saveSubscription(SubscriptionRecord sub) {
        getSubscriptionRepository().saveSubscription(sub);
    }

    public void deleteSubscription(String id) {
        getSubscriptionRepository().deleteSubscription(id);
    }

    public void updateSubscriptionBilling(String id, long lastBilling, long nextBilling, String status) {
        getSubscriptionRepository().updateSubscriptionBilling(id, lastBilling, nextBilling, status);
    }

    public List<SubscriptionRecord> getSubscriptionsForSubscriber(UUID subscriberUuid) {
        return getSubscriptionRepository().getSubscriptionsForSubscriber(subscriberUuid);
    }

    public List<SubscriptionRecord> getDueSubscriptions(long now) {
        return getSubscriptionRepository().getDueSubscriptions(now);
    }

    public SubscriptionRecord getSubscription(String id) {
        return getSubscriptionRepository().getSubscription(id);
    }

    public double getTotalBankLoansDebt() {
        return getLoanRepository().getTotalBankLoansDebt();
    }

    public double getTotalPlayerDebts() {
        return getLoanRepository().getTotalPlayerDebts();
    }

    public List<VaultRedisManager.LeaderboardEntry> getLocalLeaderboard(String currency, int limit) {
        return getAccountRepository().getLocalLeaderboard(currency, limit);
    }

    public void saveDiscordLink(UUID uuid, String discordTag) {
        getAccountRepository().saveDiscordLink(uuid, discordTag);
    }

    public void removeDiscordLink(UUID uuid) {
        getAccountRepository().removeDiscordLink(uuid);
    }

    public Map<UUID, String> loadAllDiscordLinks() {
        return getAccountRepository().loadAllDiscordLinks();
    }

    public Map<UUID, Double> getTopBalances(String currency, int limit) {
        return getAccountRepository().getTopBalances(currency, limit);
    }

    public int getPlayerRank(UUID uuid, String currency) {
        return getAccountRepository().getPlayerRank(uuid, currency);
    }

    public boolean createSnapshot(String snapshotId, String label, long timestamp, int totalAccounts, double totalNetWorth, Map<UUID, Map<String, Double>> allBalances) {
        return getAccountRepository().createSnapshot(snapshotId, label, timestamp, totalAccounts, totalNetWorth, allBalances);
    }

    public List<EconomySnapshot> getSnapshotsFromDb(int limit) {
        return getAccountRepository().getSnapshotsFromDb(limit);
    }

    public Map<UUID, Map<String, Double>> getSnapshotBalances(String snapshotId) {
        return getAccountRepository().getSnapshotBalances(snapshotId);
    }

    public Map<String, Double> getPlayerSnapshotBalances(UUID uuid, String snapshotId) {
        return getAccountRepository().getPlayerSnapshotBalances(uuid, snapshotId);
    }

    public boolean deleteSnapshotFromDb(String snapshotId) {
        return getAccountRepository().deleteSnapshotFromDb(snapshotId);
    }

    public void saveCryptoWallet(UUID uuid, String cryptoName, double amount) {
        getStakingAuctionRepository().saveCryptoWallet(uuid, cryptoName, amount);
    }

    public void deleteCryptoWallet(UUID uuid, String cryptoName) {
        getStakingAuctionRepository().deleteCryptoWallet(uuid, cryptoName);
    }

    public Map<UUID, Map<String, Double>> loadAllCryptoWallets() {
        return getStakingAuctionRepository().loadAllCryptoWallets();
    }

    public void saveAuction(VaultAuctionAPI.AuctionListing listing) {
        getStakingAuctionRepository().saveAuction(listing);
    }

    public void deleteAuction(String auctionId) {
        getStakingAuctionRepository().deleteAuction(auctionId);
    }

    public Map<String, VaultAuctionAPI.AuctionListing> loadAllAuctions() {
        return getStakingAuctionRepository().loadAllAuctions();
    }

    public void savePendingAuctionItem(UUID uuid, ItemStack item) {
        getStakingAuctionRepository().savePendingAuctionItem(uuid, item);
    }

    public Map<UUID, List<ItemStack>> loadAllPendingAuctionItems() {
        return getStakingAuctionRepository().loadAllPendingAuctionItems();
    }

    public void deletePendingAuctionItems(UUID uuid) {
        getStakingAuctionRepository().deletePendingAuctionItems(uuid);
    }

    public void saveStake(VaultStakingAPI.StakeDeposit deposit) {
        getStakingAuctionRepository().saveStake(deposit);
    }

    public Map<String, VaultStakingAPI.StakeDeposit> loadAllStakes() {
        return getStakingAuctionRepository().loadAllStakes();
    }

    public void saveCreditAccount(VaultCreditAPI.CreditAccount account) {
        getStakingAuctionRepository().saveCreditAccount(account);
    }

    public Map<UUID, Map<String, VaultCreditAPI.CreditAccount>> loadAllCreditAccounts() {
        return getStakingAuctionRepository().loadAllCreditAccounts();
    }

    public void saveDirtyBalance(UUID uuid, double amount) {
        getBlackMarketRepository().saveDirtyBalance(uuid, amount);
    }

    public void deleteDirtyBalance(UUID uuid) {
        getBlackMarketRepository().deleteDirtyBalance(uuid);
    }

    public Map<UUID, Double> loadAllDirtyBalances() {
        return getBlackMarketRepository().loadAllDirtyBalances();
    }

    public Map<String, Double> loadCryptoWalletForPlayer(UUID uuid) {
        return getStakingAuctionRepository().loadCryptoWalletForPlayer(uuid);
    }

    public Map<String, VaultCreditAPI.CreditAccount> loadCreditAccountsForPlayer(UUID uuid) {
        return getStakingAuctionRepository().loadCreditAccountsForPlayer(uuid);
    }

    public double loadDirtyBalanceForPlayer(UUID uuid) {
        return getBlackMarketRepository().loadDirtyBalanceForPlayer(uuid);
    }
}
