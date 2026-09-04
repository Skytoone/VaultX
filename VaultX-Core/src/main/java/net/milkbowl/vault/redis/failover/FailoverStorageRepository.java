package net.milkbowl.vault.redis.failover;

import net.milkbowl.vault.persistence.DatabaseConnectionManager;
import net.milkbowl.vault.persistence.repository.AccountRepository;
import net.milkbowl.vault.persistence.repository.AuditRepository;
import net.milkbowl.vault.persistence.repository.BankRepository;
import net.milkbowl.vault.persistence.repository.BlackMarketRepository;
import net.milkbowl.vault.persistence.repository.LoanRepository;
import net.milkbowl.vault.persistence.repository.MailboxRepository;
import net.milkbowl.vault.persistence.repository.StakingAuctionRepository;
import net.milkbowl.vault.persistence.repository.SubscriptionRepository;
import net.milkbowl.vault.redis.LocalFailoverManager.LocalCheckRecord;
import net.milkbowl.vault.redis.LocalFailoverManager.StockPortfolioEntry;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Manages SQLite / MySQL persistence repositories, connection state, and setting/rate tables.
 */
public class FailoverStorageRepository {

    private final Plugin plugin;
    private final DatabaseConnectionManager dbManager;

    private final AccountRepository accountRepository;
    private final BankRepository bankRepository;
    private final LoanRepository loanRepository;
    private final MailboxRepository mailboxRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final AuditRepository auditRepository;
    private final BlackMarketRepository blackMarketRepository;
    private final StakingAuctionRepository stakingAuctionRepository;

    public FailoverStorageRepository(Plugin plugin) {
        this.plugin = plugin;
        this.dbManager = new DatabaseConnectionManager(plugin);
        this.accountRepository = new AccountRepository(dbManager);
        this.bankRepository = new BankRepository(dbManager);
        this.loanRepository = new LoanRepository(dbManager);
        this.mailboxRepository = new MailboxRepository(dbManager);
        this.subscriptionRepository = new SubscriptionRepository(dbManager);
        this.auditRepository = new AuditRepository(dbManager);
        this.blackMarketRepository = new BlackMarketRepository(dbManager);
        this.stakingAuctionRepository = new StakingAuctionRepository(dbManager);
    }

    public Plugin getPlugin() {
        return plugin;
    }

    public DatabaseConnectionManager getDbManager() {
        return dbManager;
    }

    public AccountRepository getAccountRepository() {
        return accountRepository;
    }

    public BankRepository getBankRepository() {
        return bankRepository;
    }

    public LoanRepository getLoanRepository() {
        return loanRepository;
    }

    public MailboxRepository getMailboxRepository() {
        return mailboxRepository;
    }

    public SubscriptionRepository getSubscriptionRepository() {
        return subscriptionRepository;
    }

    public AuditRepository getAuditRepository() {
        return auditRepository;
    }

    public BlackMarketRepository getBlackMarketRepository() {
        return blackMarketRepository;
    }

    public StakingAuctionRepository getStakingAuctionRepository() {
        return stakingAuctionRepository;
    }

    public void close() {
        if (auditRepository != null) {
            auditRepository.close();
        }
        if (dbManager != null) {
            dbManager.close();
        }
    }

    public void saveSetting(String key, String value) {
        String query = dbManager.isMySQL()
                ? "INSERT INTO economic_settings (key_name, val) VALUES (?, ?) ON DUPLICATE KEY UPDATE val = ?"
                : "INSERT OR REPLACE INTO economic_settings (key_name, val) VALUES (?, ?)";
        dbManager.executeDatabaseOperation(conn -> {
            try (java.sql.PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, key);
                pstmt.setString(2, value);
                if (dbManager.isMySQL()) {
                    pstmt.setString(3, value);
                }
                pstmt.executeUpdate();
            }
        }, "Failed to save setting " + key);
    }

    public String getSetting(String key) {
        String query = "SELECT val FROM economic_settings WHERE key_name = ?";
        return dbManager.executeDatabaseQuery(conn -> {
            try (java.sql.PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, key);
                try (java.sql.ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getString("val");
                    }
                }
            }
            return null;
        }, null, "Failed to get setting " + key);
    }

    public void saveExchangeRate(String currency, double rate) {
        String query = dbManager.isMySQL()
                ? "REPLACE INTO exchange_rates (currency, rate, last_updated) VALUES (?, ?, ?)"
                : "REPLACE INTO exchange_rates (currency, rate, last_updated) VALUES (?, ?, ?)";
        dbManager.executeDatabaseOperation(conn -> {
            try (java.sql.PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, currency.toLowerCase());
                pstmt.setDouble(2, rate);
                pstmt.setLong(3, System.currentTimeMillis());
                pstmt.executeUpdate();
            }
        }, "Failed to save exchange rate");
    }

    public Map<String, Double> getExchangeRates() {
        String query = "SELECT currency, rate FROM exchange_rates";
        return dbManager.executeDatabaseQuery(conn -> {
            Map<String, Double> map = new HashMap<>();
            try (java.sql.Statement stmt = conn.createStatement();
                 java.sql.ResultSet rs = stmt.executeQuery(query)) {
                while (rs.next()) {
                    map.put(rs.getString("currency").toLowerCase(), rs.getDouble("rate"));
                }
            }
            return map;
        }, new HashMap<>(), "Failed to get exchange rates");
    }

    public void saveCheck(String id, double amount, String currency, UUID creatorUuid) {
        String query = "INSERT INTO active_checks (id, amount, currency, creator_uuid, status) VALUES (?, ?, ?, ?, 'ACTIVE')";
        dbManager.executeDatabaseOperation(conn -> {
            try (java.sql.PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, id);
                pstmt.setDouble(2, amount);
                pstmt.setString(3, currency);
                pstmt.setString(4, creatorUuid.toString());
                pstmt.executeUpdate();
            }
        }, "Failed to save check " + id);
    }

    public LocalCheckRecord getCheck(String id) {
        String query = "SELECT amount, currency, creator_uuid, status FROM active_checks WHERE id = ?";
        return dbManager.executeDatabaseQuery(conn -> {
            try (java.sql.PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, id);
                try (java.sql.ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return new LocalCheckRecord(
                                id,
                                rs.getDouble("amount"),
                                rs.getString("currency"),
                                UUID.fromString(rs.getString("creator_uuid")),
                                rs.getString("status")
                        );
                    }
                }
            }
            return null;
        }, null, "Failed to get check " + id);
    }

    public void updateCheckStatus(String id, String status) {
        String query = "UPDATE active_checks SET status = ? WHERE id = ?";
        dbManager.executeDatabaseOperation(conn -> {
            try (java.sql.PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, status);
                pstmt.setString(2, id);
                pstmt.executeUpdate();
            }
        }, "Failed to update check status " + id);
    }

    public double getPlayerStockShares(UUID uuid, String commodity) {
        String query = "SELECT shares FROM player_stocks WHERE uuid = ? AND commodity = ?";
        return dbManager.executeDatabaseQuery(conn -> {
            try (java.sql.PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, uuid.toString());
                pstmt.setString(2, commodity.toLowerCase());
                try (java.sql.ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getDouble("shares");
                    }
                }
            }
            return 0.0;
        }, 0.0, "Failed to get player stock shares");
    }

    public void updatePlayerStockShares(UUID uuid, String commodity, double shares) {
        net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
            String query = dbManager.isMySQL()
                    ? "INSERT INTO player_stocks (uuid, commodity, shares) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE shares = ?"
                    : "INSERT OR REPLACE INTO player_stocks (uuid, commodity, shares) VALUES (?, ?, ?)";
            dbManager.executeDatabaseOperation(conn -> {
                try (java.sql.PreparedStatement pstmt = conn.prepareStatement(query)) {
                    pstmt.setString(1, uuid.toString());
                    pstmt.setString(2, commodity.toLowerCase());
                    pstmt.setDouble(3, shares);
                    if (dbManager.isMySQL()) {
                        pstmt.setDouble(4, shares);
                    }
                    pstmt.executeUpdate();
                }
            }, "Failed to update player stock shares");
        });
    }

    public List<StockPortfolioEntry> getPlayerPortfolio(UUID uuid) {
        String query = "SELECT commodity, shares FROM player_stocks WHERE uuid = ?";
        return dbManager.executeDatabaseQuery(conn -> {
            List<StockPortfolioEntry> portfolio = new java.util.ArrayList<>();
            try (java.sql.PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, uuid.toString());
                try (java.sql.ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        portfolio.add(new StockPortfolioEntry(rs.getString("commodity"), rs.getDouble("shares")));
                    }
                }
            }
            return portfolio;
        }, new java.util.ArrayList<>(), "Failed to get player stock portfolio");
    }

    public double getCommodityPrice(String commodity) {
        String query = "SELECT price FROM commodity_prices WHERE commodity = ?";
        return dbManager.executeDatabaseQuery(conn -> {
            try (java.sql.PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, commodity.toLowerCase());
                try (java.sql.ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getDouble("price");
                    }
                }
            }
            return 0.0;
        }, 0.0, "Failed to get commodity price");
    }

    public void updateCommodityPrice(String commodity, double price) {
        String query = dbManager.isMySQL()
                ? "INSERT INTO commodity_prices (commodity, price, last_updated) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE price = ?, last_updated = ?"
                : "INSERT OR REPLACE INTO commodity_prices (commodity, price, last_updated) VALUES (?, ?, ?)";
        long now = System.currentTimeMillis();
        dbManager.executeDatabaseOperation(conn -> {
            try (java.sql.PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, commodity.toLowerCase());
                pstmt.setDouble(2, price);
                pstmt.setLong(3, now);
                if (dbManager.isMySQL()) {
                    pstmt.setDouble(4, price);
                    pstmt.setLong(5, now);
                }
                pstmt.executeUpdate();
            }
        }, "Failed to update commodity price");
    }
}
