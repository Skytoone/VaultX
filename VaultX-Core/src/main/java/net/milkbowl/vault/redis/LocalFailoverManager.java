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

    // Nested DTO / Record definitions for 100% backward compatibility

    public static class CustomBalanceRecord {
        public final UUID uuid;
        public final String currency;
        public final double balance;

        public CustomBalanceRecord(UUID uuid, String currency, double balance) {
            this.uuid = uuid;
            this.currency = currency;
            this.balance = balance;
        }
    }

    public static class BankShareholderRecord {
        public final UUID uuid;
        public final String bankName;
        public final double shares;
        public BankShareholderRecord(UUID uuid, String bankName, double shares) {
            this.uuid = uuid;
            this.bankName = bankName;
            this.shares = shares;
        }
    }

    public static class LoanRecord {
        public final String id;
        public final UUID borrower;
        public final String bankName;
        public final double principal;
        public final double remaining;
        public final double interestRate;
        public final long nextBilling;
        public final String status;

        public LoanRecord(String id, UUID borrower, String bankName, double principal, double remaining, double interestRate, long nextBilling, String status) {
            this.id = id;
            this.borrower = borrower;
            this.bankName = bankName;
            this.principal = principal;
            this.remaining = remaining;
            this.interestRate = interestRate;
            this.nextBilling = nextBilling;
            this.status = status;
        }
    }

    public static class MailRecord {
        public final int id;
        public final UUID uuid;
        public final String senderName;
        public final String message;
        public final double amount;
        public final String currency;
        public final String status;
        public final long timestamp;

        public MailRecord(int id, UUID uuid, String senderName, String message, double amount, String currency, String status, long timestamp) {
            this.id = id;
            this.uuid = uuid;
            this.senderName = senderName;
            this.message = message;
            this.amount = amount;
            this.currency = currency;
            this.status = status;
            this.timestamp = timestamp;
        }
    }

    public static class SubscriptionRecord {
        public final String id;
        public final UUID subscriber;
        public final String targetType;
        public final String target;
        public final double amount;
        public final String currency;
        public final int intervalHours;
        public final long lastBilling;
        public final long nextBilling;
        public final String status;
        public final long createdAt;

        public SubscriptionRecord(String id, UUID subscriber, String targetType, String target, double amount, String currency, int intervalHours, long lastBilling, long nextBilling, String status, long createdAt) {
            this.id = id;
            this.subscriber = subscriber;
            this.targetType = targetType;
            this.target = target;
            this.amount = amount;
            this.currency = currency;
            this.intervalHours = intervalHours;
            this.lastBilling = lastBilling;
            this.nextBilling = nextBilling;
            this.status = status;
            this.createdAt = createdAt;
        }
    }

    public static class AuditRecord {
        public final long timestamp;
        public final String uuid;
        public final String name;
        public final double amount;
        public final String action;
        public final String details;

        public AuditRecord(long timestamp, String uuid, String name, double amount, String action, String details) {
            this.timestamp = timestamp;
            this.uuid = uuid;
            this.name = name;
            this.amount = amount;
            this.action = action;
            this.details = details;
        }
    }

    public static class PlayerTransactionRecord {
        public final long timestamp;
        public final String uuid;
        public final String type;
        public final String currency;
        public final double amount;
        public final String otherParty;
        public final String category;

        public PlayerTransactionRecord(long timestamp, String uuid, String type, String currency, double amount, String otherParty, String category) {
            this.timestamp = timestamp;
            this.uuid = uuid;
            this.type = type;
            this.currency = currency;
            this.amount = amount;
            this.otherParty = otherParty;
            this.category = category == null ? "OTHER" : category;
        }
    }

    public static class PendingSyncRecord {
        public final String uuid;
        public final String currency;
        public final double balance;
        public final long timestamp;

        public PendingSyncRecord(String uuid, String currency, double balance, long timestamp) {
            this.uuid = uuid;
            this.currency = currency;
            this.balance = balance;
            this.timestamp = timestamp;
        }
    }

    public static class AnalyticsReportEntry {
        public final String currency;
        public final String category;
        public final double created;
        public final double destroyed;

        public AnalyticsReportEntry(String currency, String category, double created, double destroyed) {
            this.currency = currency;
            this.category = category;
            this.created = created;
            this.destroyed = destroyed;
        }
    }

    public static class PendingWebhookRecord {
        public final long id;
        public final String payload;
        public final int attempts;
        public final long nextRetry;

        public PendingWebhookRecord(long id, String payload, int attempts, long nextRetry) {
            this.id = id;
            this.payload = payload;
            this.attempts = attempts;
            this.nextRetry = nextRetry;
        }
    }

    public static class LocalEscrowRecord {
        public final String id;
        public final String sender;
        public final String receiver;
        public final double amount;
        public final String currency;
        public final String status;
        public final long timeoutAt;

        public LocalEscrowRecord(String id, String sender, String receiver, double amount, String currency, String status, long timeoutAt) {
            this.id = id;
            this.sender = sender;
            this.receiver = receiver;
            this.amount = amount;
            this.currency = currency;
            this.status = status;
            this.timeoutAt = timeoutAt;
        }
    }

    public static class LocalCheckRecord {
        public final String id;
        public final double amount;
        public final String currency;
        public final UUID creatorUuid;
        public final String status;
        public final long createdAt;
        public LocalCheckRecord(String id, double amount, String currency, UUID creatorUuid, String status, long createdAt) {
            this.id = id;
            this.amount = amount;
            this.currency = currency;
            this.creatorUuid = creatorUuid;
            this.status = status;
            this.createdAt = createdAt;
        }
        public LocalCheckRecord(String id, double amount, String currency, UUID creatorUuid, String status) {
            this(id, amount, currency, creatorUuid, status, 0L);
        }
    }

    public static class StockPortfolioEntry {
        public final String commodity;
        public final double shares;
        public StockPortfolioEntry(String commodity, double shares) {
            this.commodity = commodity;
            this.shares = shares;
        }
    }

    public static class LocalCurrencyStats {
        public final double totalMoney;
        public final int accountsCount;
        public final double averageBalance;
        public final double transactionVolume24h;

        public LocalCurrencyStats(double totalMoney, int accountsCount, double averageBalance, double transactionVolume24h) {
            this.totalMoney = totalMoney;
            this.accountsCount = accountsCount;
            this.averageBalance = averageBalance;
            this.transactionVolume24h = transactionVolume24h;
        }

        public LocalCurrencyStats(double totalMoney, double averageBalance, double transactionVolume24h) {
            this(totalMoney, 0, averageBalance, transactionVolume24h);
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
