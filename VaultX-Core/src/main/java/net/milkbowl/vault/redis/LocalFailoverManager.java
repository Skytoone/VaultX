package net.milkbowl.vault.redis;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import net.milkbowl.vault.economy.VaultSnapshotAPI.EconomySnapshot;
import net.milkbowl.vault.economy.VaultAuctionAPI;
import net.milkbowl.vault.economy.VaultStakingAPI;
import net.milkbowl.vault.economy.VaultCreditAPI;
import net.milkbowl.vault.util.ItemSerializer;
import org.bukkit.inventory.ItemStack;

public class LocalFailoverManager {

    private final Plugin plugin;
    private final File dbFile;
    private final boolean isMySQL;
    private final boolean isPostgreSQL;
    private final String jdbcUrl;
    private final String dbUser;
    private final String dbPass;
    private final Object writeLock = new Object();
    private com.zaxxer.hikari.HikariDataSource dataSource;
    private static final int MAX_POOL_SIZE = 10;

    private final BlockingQueue<PlayerTransactionRecord> transactionQueue;
    private final BlockingQueue<AuditRecord> auditQueue;
    private final BlockingQueue<PendingSyncRecord> syncQueue;
    private Thread batchWriterThread;
    private volatile boolean running = true;

    private final java.util.concurrent.atomic.AtomicLong totalBatchWriteTimeMs = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong batchWriteCount = new java.util.concurrent.atomic.AtomicLong(0);

    @FunctionalInterface
    private interface DbConsumer {
        void accept(Connection conn) throws SQLException;
    }

    @FunctionalInterface
    private interface DbFunction<R> {
        R apply(Connection conn) throws SQLException;
    }

    private static LocalFailoverManager instance;

    public static LocalFailoverManager getInstance() {
        return instance;
    }

    public LocalFailoverManager(Plugin plugin) {
        instance = this;
        this.plugin = plugin;
        int maxQueueCapacity = plugin.getConfig().getInt("advanced.batch-queue-limit", 50000);
        this.transactionQueue = new LinkedBlockingQueue<>(maxQueueCapacity);
        this.auditQueue = new LinkedBlockingQueue<>(maxQueueCapacity);
        this.syncQueue = new LinkedBlockingQueue<>(maxQueueCapacity);
        String storageType = plugin.getConfig().getString("storage.type", "sqlite").toLowerCase();
        this.isMySQL = "mysql".equals(storageType) || "mariadb".equals(storageType);
        this.isPostgreSQL = "postgresql".equals(storageType) || "postgres".equals(storageType);
        
        if (isMySQL) {
            this.dbFile = null;
            String host = plugin.getConfig().getString("storage.mysql.host", "127.0.0.1");
            int port = plugin.getConfig().getInt("storage.mysql.port", 3306);
            String db = plugin.getConfig().getString("storage.mysql.database", "vaultx");
            this.dbUser = plugin.getConfig().getString("storage.mysql.username", "root");
            this.dbPass = plugin.getConfig().getString("storage.mysql.password", "");
            String props = plugin.getConfig().getString("storage.mysql.properties", "?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true");
            
            boolean hasMariaDBDriver = hasClass("org.mariadb.jdbc.Driver");
            String protocol = ("mariadb".equals(storageType) && hasMariaDBDriver) ? "mariadb" : "mysql";
            this.jdbcUrl = "jdbc:" + protocol + "://" + host + ":" + port + "/" + db + props;
            
            registerDriver(storageType);
            
            // Initialize HikariCP connection pool for MySQL
            com.zaxxer.hikari.HikariConfig config = new com.zaxxer.hikari.HikariConfig();
            config.setJdbcUrl(jdbcUrl);
            config.setUsername(dbUser);
            config.setPassword(dbPass);
            config.setMaximumPoolSize(MAX_POOL_SIZE);
            config.setMinimumIdle(Math.max(2, MAX_POOL_SIZE / 4));
            config.setConnectionTimeout(2000);
            
            // Speed optimizations & prepared statement caching
            config.addDataSourceProperty("cachePrepStmts", "true");
            config.addDataSourceProperty("prepStmtCacheSize", "250");
            config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
            config.addDataSourceProperty("useServerPrepStmts", "true");
            
            this.dataSource = new com.zaxxer.hikari.HikariDataSource(config);
        } else if (isPostgreSQL) {
            this.dbFile = null;
            String host = plugin.getConfig().getString("storage.postgresql.host", "127.0.0.1");
            int port = plugin.getConfig().getInt("storage.postgresql.port", 5432);
            String db = plugin.getConfig().getString("storage.postgresql.database", "vaultx");
            this.dbUser = plugin.getConfig().getString("storage.postgresql.username", "postgres");
            this.dbPass = plugin.getConfig().getString("storage.postgresql.password", "");
            String props = plugin.getConfig().getString("storage.postgresql.properties", "");
            
            this.jdbcUrl = "jdbc:postgresql://" + host + ":" + port + "/" + db + (props.isEmpty() ? "" : "?" + props);
            registerDriver("postgresql");
            
            com.zaxxer.hikari.HikariConfig config = new com.zaxxer.hikari.HikariConfig();
            config.setJdbcUrl(jdbcUrl);
            config.setUsername(dbUser);
            config.setPassword(dbPass);
            config.setMaximumPoolSize(MAX_POOL_SIZE);
            config.setMinimumIdle(Math.max(2, MAX_POOL_SIZE / 4));
            config.setConnectionTimeout(2000);
            
            config.addDataSourceProperty("cachePrepStmts", "true");
            config.addDataSourceProperty("prepStmtCacheSize", "250");
            config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
            
            this.dataSource = new com.zaxxer.hikari.HikariDataSource(config);
        } else {
            this.dbUser = null;
            this.dbPass = null;
            this.dbFile = new File(plugin.getDataFolder(), "failover.db");
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }
            this.jdbcUrl = "jdbc:sqlite:" + dbFile.getAbsolutePath();
            registerDriver("sqlite");

            // Initialize HikariCP connection pool for SQLite to allow concurrent reads safely
            com.zaxxer.hikari.HikariConfig config = new com.zaxxer.hikari.HikariConfig();
            config.setJdbcUrl(jdbcUrl);
            config.setPoolName("VaultX-SQLite-Pool");
            config.setMaximumPoolSize(MAX_POOL_SIZE);
            config.setMinimumIdle(Math.max(2, MAX_POOL_SIZE / 4));
            config.setConnectionTimeout(2000);
            config.setConnectionInitSql("PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL;");

            this.dataSource = new com.zaxxer.hikari.HikariDataSource(config);
        }
        initDatabase();
        startBatchWriter();
    }

    private static boolean hasClass(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private void registerDriver(String type) {
        String driver;
        if ("postgresql".equals(type) || "postgres".equals(type)) {
            driver = "org.postgresql.Driver";
        } else if ("mariadb".equals(type) && hasClass("org.mariadb.jdbc.Driver")) {
            driver = "org.mariadb.jdbc.Driver";
        } else if ("mysql".equals(type) || "mariadb".equals(type)) {
            if (hasClass("com.mysql.cj.jdbc.Driver")) {
                return;
            }
            driver = "com.mysql.jdbc.Driver";
        } else {
            driver = "org.sqlite.JDBC";
        }
        try {
            Class.forName(driver);
        } catch (ClassNotFoundException e) {
            // Might be loaded by Bukkit/Spigot automatically
        }
    }

    private Connection acquireConnection() throws SQLException {
        return dataSource.getConnection();
    }

    private void executeDatabaseOperation(DbConsumer operation, String errorMessage) {
        if (isMySQL) {
            try (Connection conn = acquireConnection()) {
                operation.accept(conn);
            } catch (SQLException e) {
                plugin.getLogger().severe("[Vault Failover] " + errorMessage + ": " + e.getMessage());
            }
        } else {
            synchronized (writeLock) {
                try (Connection conn = acquireConnection()) {
                    operation.accept(conn);
                } catch (SQLException e) {
                    plugin.getLogger().severe("[Vault Failover] " + errorMessage + ": " + e.getMessage());
                }
            }
        }
    }

    private boolean executeDatabaseOperationChecked(DbConsumer operation, String errorMessage) {
        if (isMySQL) {
            try (Connection conn = acquireConnection()) {
                operation.accept(conn);
                return true;
            } catch (SQLException e) {
                plugin.getLogger().severe("[Vault Failover] " + errorMessage + ": " + e.getMessage());
                return false;
            }
        } else {
            synchronized (writeLock) {
                try (Connection conn = acquireConnection()) {
                    operation.accept(conn);
                    return true;
                } catch (SQLException e) {
                    plugin.getLogger().severe("[Vault Failover] " + errorMessage + ": " + e.getMessage());
                    return false;
                }
            }
        }
    }

    private <R> R executeDatabaseQuery(DbFunction<R> operation, R defaultValue, String errorMessage) {
        try (Connection conn = acquireConnection()) {
            return operation.apply(conn);
        } catch (SQLException e) {
            plugin.getLogger().severe("[Vault Failover] " + errorMessage + ": " + e.getMessage());
            return defaultValue;
        }
    }

    public void close() {
        if (instance == this) {
            instance = null;
        }
        running = false;
        if (batchWriterThread != null) {
            batchWriterThread.interrupt();
            try {
                batchWriterThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            flushBatchQueues();
        } catch (Exception e) {
            plugin.getLogger().warning("[Vault Failover] Error flushing queues during shutdown: " + e.getMessage());
        }
        if (dataSource != null) {
            dataSource.close();
        }
    }

    private void initDatabase() {
        executeDatabaseOperation(conn -> {
            try (Statement stmt = conn.createStatement()) {
                String autoIncrementKey = isMySQL ? "BIGINT PRIMARY KEY AUTO_INCREMENT" : (isPostgreSQL ? "BIGSERIAL PRIMARY KEY" : "INTEGER PRIMARY KEY AUTOINCREMENT");
                String bigintType = (isMySQL || isPostgreSQL) ? "BIGINT" : "LONG";

                // Table for pending sync messages
                stmt.execute("CREATE TABLE IF NOT EXISTS pending_syncs (" +
                        "id " + autoIncrementKey + ", " +
                        "uuid VARCHAR(36) NOT NULL, " +
                        "currency VARCHAR(32) NOT NULL, " +
                        "balance DOUBLE NOT NULL, " +
                        "timestamp " + bigintType + " NOT NULL)");

                // Table for banks fallback storage
                stmt.execute("CREATE TABLE IF NOT EXISTS local_banks (" +
                        "name VARCHAR(64) PRIMARY KEY, " +
                        "balance DOUBLE NOT NULL)");

                // Table for security audits logs
                stmt.execute("CREATE TABLE IF NOT EXISTS security_audits (" +
                        "id " + autoIncrementKey + ", " +
                        "timestamp " + bigintType + " NOT NULL, " +
                        "uuid VARCHAR(36), " +
                        "name VARCHAR(64), " +
                        "amount DOUBLE, " +
                        "action VARCHAR(32), " +
                        "details TEXT)");

                // Table for custom currency balances
                stmt.execute("CREATE TABLE IF NOT EXISTS custom_currency_balances (" +
                        "uuid VARCHAR(36) NOT NULL, " +
                        "currency VARCHAR(32) NOT NULL, " +
                        "balance DOUBLE NOT NULL, " +
                        "last_updated " + bigintType + " NOT NULL DEFAULT 0, " +
                        "PRIMARY KEY (uuid, currency))");

                // Table for player transaction history
                stmt.execute("CREATE TABLE IF NOT EXISTS player_transactions (" +
                        "id " + autoIncrementKey + ", " +
                        "timestamp " + bigintType + " NOT NULL, " +
                        "uuid VARCHAR(36) NOT NULL, " +
                        "type VARCHAR(32) NOT NULL, " +
                        "currency VARCHAR(32) NOT NULL, " +
                        "amount DOUBLE NOT NULL, " +
                        "other_party VARCHAR(64), " +
                        "category VARCHAR(32) NOT NULL DEFAULT 'OTHER')");

                // Table for daily analytics
                stmt.execute("CREATE TABLE IF NOT EXISTS daily_analytics (" +
                        "day_timestamp " + bigintType + " NOT NULL, " +
                        "currency VARCHAR(32) NOT NULL, " +
                        "category VARCHAR(32) NOT NULL, " +
                        "amount_created DOUBLE NOT NULL, " +
                        "amount_destroyed DOUBLE NOT NULL, " +
                        "PRIMARY KEY (day_timestamp, currency, category))");

                // Table for pending webhooks
                stmt.execute("CREATE TABLE IF NOT EXISTS pending_webhooks (" +
                        "id " + autoIncrementKey + ", " +
                        "payload TEXT NOT NULL, " +
                        "attempts INT NOT NULL, " +
                        "next_retry " + bigintType + " NOT NULL)");

                // Table for local escrows
                stmt.execute("CREATE TABLE IF NOT EXISTS local_escrows (" +
                        "id VARCHAR(36) PRIMARY KEY, " +
                        "sender VARCHAR(36) NOT NULL, " +
                        "receiver VARCHAR(36) NOT NULL, " +
                        "amount DOUBLE NOT NULL, " +
                        "currency VARCHAR(32) NOT NULL, " +
                        "status VARCHAR(16) NOT NULL, " +
                        "timeout_at " + bigintType + " NOT NULL)");

                // Table for exchange rates
                stmt.execute("CREATE TABLE IF NOT EXISTS exchange_rates (" +
                        "currency VARCHAR(32) PRIMARY KEY, " +
                        "rate DOUBLE NOT NULL, " +
                        "last_updated " + bigintType + " NOT NULL)");

                // Table for bank accounts metadata
                stmt.execute("CREATE TABLE IF NOT EXISTS bank_accounts (" +
                        "name VARCHAR(64) PRIMARY KEY, " +
                        "owner_uuid VARCHAR(36) NOT NULL, " +
                        "created_at " + bigintType + " NOT NULL)");

                // Table for bank members role mapping
                stmt.execute("CREATE TABLE IF NOT EXISTS bank_members (" +
                        "bank_name VARCHAR(64) NOT NULL, " +
                        "uuid VARCHAR(36) NOT NULL, " +
                        "role VARCHAR(16) NOT NULL, " +
                        "PRIMARY KEY (bank_name, uuid))");

                // Table for player mailbox notifications
                stmt.execute("CREATE TABLE IF NOT EXISTS player_mailbox (" +
                        "id " + autoIncrementKey + ", " +
                        "uuid VARCHAR(36) NOT NULL, " +
                        "sender_name VARCHAR(64), " +
                        "message TEXT, " +
                        "amount DOUBLE NOT NULL, " +
                        "currency VARCHAR(32) NOT NULL, " +
                        "status VARCHAR(16) NOT NULL, " +
                        "timestamp " + bigintType + " NOT NULL)");

                // Table for dynamic settings
                stmt.execute("CREATE TABLE IF NOT EXISTS economic_settings (" +
                        "key_name VARCHAR(64) PRIMARY KEY, " +
                        "val VARCHAR(128) NOT NULL)");

                // Table for player stock portfolios
                stmt.execute("CREATE TABLE IF NOT EXISTS player_stocks (" +
                        "uuid VARCHAR(36) NOT NULL, " +
                        "commodity VARCHAR(32) NOT NULL, " +
                        "shares DOUBLE NOT NULL, " +
                        "PRIMARY KEY (uuid, commodity))");

                // Table for stock prices
                stmt.execute("CREATE TABLE IF NOT EXISTS commodity_prices (" +
                        "commodity VARCHAR(32) PRIMARY KEY, " +
                        "price DOUBLE NOT NULL, " +
                        "last_updated " + bigintType + " NOT NULL)");

                // Table for active physical checks
                stmt.execute("CREATE TABLE IF NOT EXISTS active_checks (" +
                        "id VARCHAR(36) PRIMARY KEY, " +
                        "amount DOUBLE NOT NULL, " +
                        "currency VARCHAR(32) NOT NULL, " +
                        "creator_uuid VARCHAR(36) NOT NULL, " +
                        "status VARCHAR(16) NOT NULL)");

                // Table for bank loans
                stmt.execute("CREATE TABLE IF NOT EXISTS bank_loans (" +
                        "id VARCHAR(36) PRIMARY KEY, " +
                        "borrower VARCHAR(36) NOT NULL, " +
                        "bank_name VARCHAR(64) NOT NULL, " +
                        "principal DOUBLE NOT NULL, " +
                        "remaining DOUBLE NOT NULL, " +
                        "interest_rate DOUBLE NOT NULL, " +
                        "next_billing " + bigintType + " NOT NULL, " +
                        "status VARCHAR(16) NOT NULL)");

                // Table for salary garnishments
                stmt.execute("CREATE TABLE IF NOT EXISTS player_debts (" +
                        "uuid VARCHAR(36) PRIMARY KEY, " +
                        "amount DOUBLE NOT NULL)");

                // Table for player subscriptions
                stmt.execute("CREATE TABLE IF NOT EXISTS player_subscriptions (" +
                        "id VARCHAR(36) PRIMARY KEY, " +
                        "subscriber VARCHAR(36) NOT NULL, " +
                        "target_type VARCHAR(16) NOT NULL, " +
                        "target VARCHAR(64) NOT NULL, " +
                        "amount DOUBLE NOT NULL, " +
                        "currency VARCHAR(32) NOT NULL, " +
                        "interval_hours INT NOT NULL, " +
                        "last_billing " + bigintType + " NOT NULL, " +
                        "next_billing " + bigintType + " NOT NULL, " +
                        "status VARCHAR(16) NOT NULL, " +
                        "created_at " + bigintType + " NOT NULL)");

                // Table for local name-to-UUID caching
                stmt.execute("CREATE TABLE IF NOT EXISTS local_uuid_cache (" +
                        "name VARCHAR(64) PRIMARY KEY, " +
                        "uuid VARCHAR(36) NOT NULL, " +
                        "last_updated " + bigintType + " NOT NULL)");

                // Table for persistent Discord account links
                stmt.execute("CREATE TABLE IF NOT EXISTS discord_accounts (" +
                        "uuid VARCHAR(36) PRIMARY KEY, " +
                        "discord_tag VARCHAR(64) NOT NULL)");

                // Table for economy snapshots
                stmt.execute("CREATE TABLE IF NOT EXISTS economy_snapshots (" +
                        "snapshot_id VARCHAR(64) PRIMARY KEY, " +
                        "label VARCHAR(128) NOT NULL, " +
                        "timestamp " + bigintType + " NOT NULL, " +
                        "total_accounts INT NOT NULL, " +
                        "total_net_worth DOUBLE NOT NULL)");

                stmt.execute("CREATE TABLE IF NOT EXISTS snapshot_balances (" +
                        "snapshot_id VARCHAR(64) NOT NULL, " +
                        "uuid VARCHAR(36) NOT NULL, " +
                        "currency VARCHAR(32) NOT NULL, " +
                        "balance DOUBLE NOT NULL, " +
                        "PRIMARY KEY (snapshot_id, uuid, currency))");

                // Table for crypto wallets
                stmt.execute("CREATE TABLE IF NOT EXISTS crypto_wallets (" +
                        "uuid VARCHAR(36) NOT NULL, " +
                        "crypto_name VARCHAR(32) NOT NULL, " +
                        "balance DOUBLE NOT NULL, " +
                        "PRIMARY KEY (uuid, crypto_name))");

                // Table for active auctions
                stmt.execute("CREATE TABLE IF NOT EXISTS local_auctions (" +
                        "auction_id VARCHAR(64) PRIMARY KEY, " +
                        "seller_uuid VARCHAR(36) NOT NULL, " +
                        "item_data TEXT NOT NULL, " +
                        "currency VARCHAR(32) NOT NULL, " +
                        "starting_price DOUBLE NOT NULL, " +
                        "current_bid DOUBLE NOT NULL, " +
                        "highest_bidder_uuid VARCHAR(36), " +
                        "duration_ms " + bigintType + " NOT NULL, " +
                        "expires_at_ms " + bigintType + " NOT NULL, " +
                        "is_closed INT NOT NULL)");

                // Table for pending auction item claims
                stmt.execute("CREATE TABLE IF NOT EXISTS local_pending_auction_items (" +
                        "id INTEGER PRIMARY KEY " + ((!isMySQL && !isPostgreSQL) ? "AUTOINCREMENT" : (isPostgreSQL ? "GENERATED ALWAYS AS IDENTITY" : "AUTO_INCREMENT")) + ", " +
                        "uuid VARCHAR(36) NOT NULL, " +
                        "item_data TEXT NOT NULL)");

                // Table for active stakes
                stmt.execute("CREATE TABLE IF NOT EXISTS local_stakes (" +
                        "deposit_id VARCHAR(64) PRIMARY KEY, " +
                        "player_uuid VARCHAR(36) NOT NULL, " +
                        "currency VARCHAR(32) NOT NULL, " +
                        "principal DOUBLE NOT NULL, " +
                        "interest_rate DOUBLE NOT NULL, " +
                        "staked_at_ms " + bigintType + " NOT NULL, " +
                        "lock_period_ms " + bigintType + " NOT NULL, " +
                        "is_matured INT NOT NULL, " +
                        "is_claimed INT NOT NULL)");

                // Table for credit accounts
                stmt.execute("CREATE TABLE IF NOT EXISTS local_credit_accounts (" +
                        "uuid VARCHAR(36) NOT NULL, " +
                        "currency VARCHAR(32) NOT NULL, " +
                        "overdraft_limit DOUBLE NOT NULL, " +
                        "current_used_credit DOUBLE NOT NULL, " +
                        "credit_score INT NOT NULL, " +
                        "is_frozen INT NOT NULL, " +
                        "PRIMARY KEY (uuid, currency))");

                // Table for Black Market dirty balances
                stmt.execute("CREATE TABLE IF NOT EXISTS dirty_balances (" +
                        "uuid VARCHAR(36) PRIMARY KEY, " +
                        "amount DOUBLE NOT NULL)");

                // Schema migrations
                try {
                    stmt.execute("ALTER TABLE custom_currency_balances ADD COLUMN last_updated " + bigintType + " NOT NULL DEFAULT 0");
                } catch (SQLException ignored) {}

                try {
                    stmt.execute("ALTER TABLE player_transactions ADD COLUMN category VARCHAR(32) NOT NULL DEFAULT 'OTHER'");
                } catch (SQLException ignored) {}

                // Create indexes to optimize queries
                if (isMySQL) {
                    createIndexMySQL(conn, "player_transactions", "idx_player_transactions_uuid_id", "uuid, id");
                    createIndexMySQL(conn, "player_transactions", "idx_player_transactions_timestamp", "timestamp");
                    createIndexMySQL(conn, "security_audits", "idx_security_audits_uuid_id", "uuid, id");
                    createIndexMySQL(conn, "security_audits", "idx_security_audits_timestamp", "timestamp");
                    createIndexMySQL(conn, "player_mailbox", "idx_player_mailbox_uuid_status", "uuid, status");
                    createIndexMySQL(conn, "bank_members", "idx_bank_members_uuid", "uuid");
                    createIndexMySQL(conn, "local_escrows", "idx_local_escrows_actors", "sender, receiver");
                    createIndexMySQL(conn, "local_escrows", "idx_local_escrows_timeout", "status, timeout_at");
                    createIndexMySQL(conn, "pending_webhooks", "idx_pending_webhooks_retry", "next_retry");
                    createIndexMySQL(conn, "player_subscriptions", "idx_player_subscriptions_subscriber", "subscriber");
                    createIndexMySQL(conn, "local_uuid_cache", "idx_local_uuid_cache_uuid", "uuid");
                } else {
                    try {
                        stmt.execute("CREATE INDEX IF NOT EXISTS idx_player_transactions_uuid_id ON player_transactions (uuid, id)");
                    } catch (SQLException e) {
                        plugin.getLogger().warning("[Vault Failover] Could not create index on player_transactions: " + e.getMessage());
                    }
                    try {
                        stmt.execute("CREATE INDEX IF NOT EXISTS idx_player_transactions_timestamp ON player_transactions (timestamp)");
                    } catch (SQLException e) {
                        plugin.getLogger().warning("[Vault Failover] Could not create timestamp index on player_transactions: " + e.getMessage());
                    }
                    try {
                        stmt.execute("CREATE INDEX IF NOT EXISTS idx_security_audits_uuid_id ON security_audits (uuid, id)");
                    } catch (SQLException e) {
                        plugin.getLogger().warning("[Vault Failover] Could not create index on security_audits: " + e.getMessage());
                    }
                    try {
                        stmt.execute("CREATE INDEX IF NOT EXISTS idx_security_audits_timestamp ON security_audits (timestamp)");
                    } catch (SQLException e) {
                        plugin.getLogger().warning("[Vault Failover] Could not create timestamp index on security_audits: " + e.getMessage());
                    }
                    try {
                        stmt.execute("CREATE INDEX IF NOT EXISTS idx_player_mailbox_uuid_status ON player_mailbox (uuid, status)");
                    } catch (SQLException e) {
                        plugin.getLogger().warning("[Vault Failover] Could not create index on player_mailbox: " + e.getMessage());
                    }
                    try {
                        stmt.execute("CREATE INDEX IF NOT EXISTS idx_bank_members_uuid ON bank_members (uuid)");
                    } catch (SQLException e) {
                        plugin.getLogger().warning("[Vault Failover] Could not create index on bank_members: " + e.getMessage());
                    }
                    try {
                        stmt.execute("CREATE INDEX IF NOT EXISTS idx_local_escrows_actors ON local_escrows (sender, receiver)");
                    } catch (SQLException e) {
                        plugin.getLogger().warning("[Vault Failover] Could not create index on local_escrows: " + e.getMessage());
                    }
                    try {
                        stmt.execute("CREATE INDEX IF NOT EXISTS idx_local_escrows_timeout ON local_escrows (status, timeout_at)");
                    } catch (SQLException e) {
                        plugin.getLogger().warning("[Vault Failover] Could not create index on local_escrows: " + e.getMessage());
                    }
                    try {
                        stmt.execute("CREATE INDEX IF NOT EXISTS idx_pending_webhooks_retry ON pending_webhooks (next_retry)");
                    } catch (SQLException e) {
                        plugin.getLogger().warning("[Vault Failover] Could not create index on pending_webhooks: " + e.getMessage());
                    }
                    try {
                        stmt.execute("CREATE INDEX IF NOT EXISTS idx_player_subscriptions_subscriber ON player_subscriptions (subscriber)");
                    } catch (SQLException e) {
                        plugin.getLogger().warning("[Vault Failover] Could not create index on player_subscriptions: " + e.getMessage());
                    }
                    try {
                        stmt.execute("CREATE INDEX IF NOT EXISTS idx_local_uuid_cache_uuid ON local_uuid_cache (uuid)");
                    } catch (SQLException e) {
                        plugin.getLogger().warning("[Vault Failover] Could not create index on local_uuid_cache: " + e.getMessage());
                    }
                }
            }
        }, "Failed to initialize database");
    }

    private void createIndexMySQL(Connection conn, String tableName, String indexName, String columns) {
        String checkQuery = "SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ? LIMIT 1";
        try (PreparedStatement pstmt = conn.prepareStatement(checkQuery)) {
            pstmt.setString(1, tableName);
            pstmt.setString(2, indexName);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (!rs.next()) {
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute("CREATE INDEX " + indexName + " ON " + tableName + " (" + columns + ")");
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("[Vault Failover] Could not create MySQL index " + indexName + " on table " + tableName + ": " + e.getMessage());
        }
    }

    public void queueBalanceSync(UUID uuid, String currency, double balance) {
        syncQueue.offer(new PendingSyncRecord(
                uuid.toString(),
                currency == null ? "default" : currency,
                balance,
                System.currentTimeMillis()
        ));
    }

    public void processQueue(VaultRedisManager redis) {
        String selectQuery = "SELECT id, uuid, currency, balance, timestamp FROM pending_syncs ORDER BY id ASC";
        String deleteQuery = "DELETE FROM pending_syncs WHERE id = ?";

        executeDatabaseOperation(conn -> {
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

                    // Reconciliation: check Redis timestamp
                    long redisTimestamp = redis.getCustomCurrencyTimestamp(uuid, currency);
                    if (localTimestamp >= redisTimestamp) {
                        // Local write is newer! Update Redis.
                        redis.publishBalanceUpdateDirect(uuid, currency, balance, localTimestamp);
                    } else {
                        // Redis write is newer! Update local database (and cache if needed)
                        double redisBalance = redis.getCustomCurrencyBalanceDirect(uuid, currency);
                        if (currency.equalsIgnoreCase("default")) {
                            saveCustomCurrencyBalance(uuid, "default", redisBalance, redisTimestamp);
                            updateDelegateBalance(uuid, redisBalance);
                        } else {
                            saveCustomCurrencyBalance(uuid, currency, redisBalance, redisTimestamp);
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

    public void saveBankBalance(String bankName, double balance) {
        net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
            String query = isMySQL
                    ? "INSERT INTO local_banks (name, balance) VALUES (?, ?) ON DUPLICATE KEY UPDATE balance = VALUES(balance)"
                    : "INSERT INTO local_banks (name, balance) VALUES (?, ?) ON CONFLICT(name) DO UPDATE SET balance = excluded.balance";
            executeDatabaseOperation(conn -> {
                try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                    pstmt.setString(1, bankName.toLowerCase());
                    pstmt.setDouble(2, balance);
                    pstmt.executeUpdate();
                }
            }, "Failed to save bank balance locally");
        });
    }

    public double getBankBalance(String bankName) {
        String query = "SELECT balance FROM local_banks WHERE name = ?";
        return executeDatabaseQuery(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, bankName.toLowerCase());
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getDouble("balance");
                    }
                }
            }
            return 0.0;
        }, 0.0, "Failed to fetch bank balance");
    }

    public Map<String, Double> loadAllBanks() {
        String query = "SELECT name, balance FROM local_banks";
        return executeDatabaseQuery(conn -> {
            Map<String, Double> banks = new HashMap<>();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(query)) {
                while (rs.next()) {
                    banks.put(rs.getString("name").toLowerCase(), rs.getDouble("balance"));
                }
            }
            return banks;
        }, new HashMap<>(), "Failed to load local banks");
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

    public java.util.List<AuditRecord> getSecurityAudits(UUID uuid, int page, int pageSize) {
        String query = "SELECT timestamp, uuid, name, amount, action, details FROM security_audits WHERE uuid = ? ORDER BY id DESC LIMIT ? OFFSET ?";
        return executeDatabaseQuery(conn -> {
            java.util.List<AuditRecord> list = new java.util.ArrayList<>();
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
        }, new java.util.ArrayList<>(), "Failed to load security audits");
    }

    public java.util.List<AuditRecord> getSecurityAuditsAll(int page, int pageSize) {
        String query = "SELECT timestamp, uuid, name, amount, action, details FROM security_audits ORDER BY id DESC LIMIT ? OFFSET ?";
        return executeDatabaseQuery(conn -> {
            java.util.List<AuditRecord> list = new java.util.ArrayList<>();
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
        }, new java.util.ArrayList<>(), "Failed to load security audits");
    }

    public void saveCustomCurrencyBalance(UUID uuid, String currency, double balance) {
        saveCustomCurrencyBalance(uuid, currency, balance, System.currentTimeMillis());
    }

    public void saveCustomCurrencyBalance(UUID uuid, String currency, double balance, long timestamp) {
        String query = isMySQL
                ? "INSERT INTO custom_currency_balances (uuid, currency, balance, last_updated) VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE balance = VALUES(balance), last_updated = VALUES(last_updated)"
                : "INSERT INTO custom_currency_balances (uuid, currency, balance, last_updated) VALUES (?, ?, ?, ?) ON CONFLICT(uuid, currency) DO UPDATE SET balance = excluded.balance, last_updated = excluded.last_updated";
        executeDatabaseOperation(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, uuid.toString());
                pstmt.setString(2, currency.toLowerCase());
                pstmt.setDouble(3, balance);
                pstmt.setLong(4, timestamp);
                pstmt.executeUpdate();
            }
        }, "Failed to save custom currency balance locally");
    }

    public long getCustomCurrencyTimestamp(UUID uuid, String currency) {
        String query = "SELECT last_updated FROM custom_currency_balances WHERE uuid = ? AND currency = ?";
        return executeDatabaseQuery(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, uuid.toString());
                pstmt.setString(2, currency.toLowerCase());
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getLong("last_updated");
                    }
                }
            }
            return 0L;
        }, 0L, "Failed to fetch custom currency timestamp");
    }

    public double getCustomCurrencyBalance(UUID uuid, String currency) {
        String query = "SELECT balance FROM custom_currency_balances WHERE uuid = ? AND currency = ?";
        return executeDatabaseQuery(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, uuid.toString());
                pstmt.setString(2, currency.toLowerCase());
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getDouble("balance");
                    }
                }
            }
            return 0.0;
        }, 0.0, "Failed to fetch custom currency balance");
    }

    public void saveUuidCache(String name, UUID uuid) {
        if (name == null || uuid == null) return;
        net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
            String query = isMySQL
                    ? "INSERT INTO local_uuid_cache (name, uuid, last_updated) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE uuid = VALUES(uuid), last_updated = VALUES(last_updated)"
                    : "INSERT INTO local_uuid_cache (name, uuid, last_updated) VALUES (?, ?, ?) ON CONFLICT(name) DO UPDATE SET uuid = excluded.uuid, last_updated = excluded.last_updated";
            executeDatabaseOperation(conn -> {
                try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                    pstmt.setString(1, name.toLowerCase());
                    pstmt.setString(2, uuid.toString());
                    pstmt.setLong(3, System.currentTimeMillis());
                    pstmt.executeUpdate();
                }
            }, "Failed to save UUID cache locally");
        });
        
        // Also save to Redis if enabled and online
        VaultRedisManager redis = VaultRedisManager.getInstance();
        if (redis != null && redis.isOnline()) {
            redis.saveUuidToRedis(name, uuid);
        }
    }

    public UUID getUuidFromCache(String name) {
        if (name == null) return null;
        String query = "SELECT uuid FROM local_uuid_cache WHERE name = ?";
        String uuidStr = executeDatabaseQuery(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, name.toLowerCase());
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getString("uuid");
                    }
                }
            }
            return null;
        }, null, "Failed to query local UUID cache");
        
        if (uuidStr != null) {
            try {
                return UUID.fromString(uuidStr);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }

    public String getNameFromCache(UUID uuid) {
        if (uuid == null) return null;
        String query = "SELECT name FROM local_uuid_cache WHERE uuid = ?";
        return executeDatabaseQuery(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, uuid.toString());
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getString("name");
                    }
                }
            }
            return null;
        }, null, "Failed to query local name cache");
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

    public void savePlayerTransaction(UUID uuid, String type, String currency, double amount, String otherParty) {
        String category = determineCategory(otherParty);
        transactionQueue.offer(new PlayerTransactionRecord(
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

    public java.util.List<PlayerTransactionRecord> getPlayerTransactions(UUID uuid, int page, int pageSize) {
        String query = "SELECT timestamp, uuid, type, currency, amount, other_party, category FROM player_transactions WHERE uuid = ? ORDER BY id DESC LIMIT ? OFFSET ?";
        return executeDatabaseQuery(conn -> {
            java.util.List<PlayerTransactionRecord> list = new java.util.ArrayList<>();
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
        }, new java.util.ArrayList<>(), "Failed to load player transactions");
    }

    public double getTotalMoneySupply(String currency) {
        String query = "SELECT SUM(balance) FROM user_balances WHERE currency = ?";
        return executeDatabaseQuery(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, currency != null ? currency.toLowerCase() : "default");
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) return rs.getDouble(1);
                }
            }
            return 0.0;
        }, 0.0, "Failed to get total money supply");
    }

    public double getAverageAccountBalance(String currency) {
        String query = "SELECT AVG(balance) FROM user_balances WHERE currency = ?";
        return executeDatabaseQuery(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, currency != null ? currency.toLowerCase() : "default");
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) return rs.getDouble(1);
                }
            }
            return 0.0;
        }, 0.0, "Failed to get average account balance");
    }

    public double getTransactionVolume24h(String currency) {
        long timestamp24h = System.currentTimeMillis() - (24 * 60 * 60 * 1000L);
        String query = "SELECT SUM(amount) FROM player_transactions WHERE currency = ? AND timestamp >= ?";
        return executeDatabaseQuery(conn -> {
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
            java.util.List<PlayerTransactionRecord> txList = new java.util.ArrayList<>();
            transactionQueue.drainTo(txList);
            if (!txList.isEmpty()) {
                long start = System.currentTimeMillis();
                String query = "INSERT INTO player_transactions (timestamp, uuid, type, currency, amount, other_party, category) VALUES (?, ?, ?, ?, ?, ?, ?)";
                boolean success = executeDatabaseOperationChecked(conn -> {
                    boolean autoCommit = conn.getAutoCommit();
                    conn.setAutoCommit(false);
                    try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                        for (PlayerTransactionRecord record : txList) {
                            pstmt.setLong(1, record.timestamp);
                            pstmt.setString(2, record.uuid);
                            pstmt.setString(3, record.type);
                            pstmt.setString(4, record.currency);
                            pstmt.setDouble(5, record.amount);
                            pstmt.setString(6, record.otherParty);
                            pstmt.setString(7, record.category);
                            pstmt.addBatch();
                        }
                        pstmt.executeBatch();
                        conn.commit();
                    } catch (Exception e) {
                        try {
                            conn.rollback();
                        } catch (SQLException ex) {
                            // Suppress rollback exception to preserve primary exception
                        }
                        if (e instanceof SQLException) {
                            throw (SQLException) e;
                        } else {
                            throw new SQLException(e);
                        }
                    } finally {
                        conn.setAutoCommit(autoCommit);
                    }
                }, "Failed to batch insert player transactions");
                
                if (success) {
                    long duration = System.currentTimeMillis() - start;
                    totalBatchWriteTimeMs.addAndGet(duration);
                    batchWriteCount.incrementAndGet();
                } else {
                    plugin.getLogger().warning("[Vault Failover] Re-queueing " + txList.size() + " transactions due to database write failure.");
                    for (PlayerTransactionRecord rec : txList) {
                        if (!transactionQueue.offer(rec)) break;
                    }
                }
            }
        }

        if (!auditQueue.isEmpty()) {
            java.util.List<AuditRecord> auditList = new java.util.ArrayList<>();
            auditQueue.drainTo(auditList);
            if (!auditList.isEmpty()) {
                long start = System.currentTimeMillis();
                String query = "INSERT INTO security_audits (timestamp, uuid, name, amount, action, details) VALUES (?, ?, ?, ?, ?, ?)";
                boolean success = executeDatabaseOperationChecked(conn -> {
                    boolean autoCommit = conn.getAutoCommit();
                    conn.setAutoCommit(false);
                    try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                        for (AuditRecord record : auditList) {
                            pstmt.setLong(1, record.timestamp);
                            pstmt.setString(2, record.uuid);
                            pstmt.setString(3, record.name);
                            pstmt.setDouble(4, record.amount);
                            pstmt.setString(5, record.action);
                            pstmt.setString(6, record.details);
                            pstmt.addBatch();
                        }
                        pstmt.executeBatch();
                        conn.commit();
                    } catch (Exception e) {
                        try {
                            conn.rollback();
                        } catch (SQLException ex) {
                            // Suppress rollback exception to preserve primary exception
                        }
                        if (e instanceof SQLException) {
                            throw (SQLException) e;
                        } else {
                            throw new SQLException(e);
                        }
                    } finally {
                        conn.setAutoCommit(autoCommit);
                    }
                }, "Failed to batch insert security audits");
                
                if (success) {
                    long duration = System.currentTimeMillis() - start;
                    totalBatchWriteTimeMs.addAndGet(duration);
                    batchWriteCount.incrementAndGet();
                } else {
                    plugin.getLogger().warning("[Vault Failover] Re-queueing " + auditList.size() + " security audits due to database write failure.");
                    for (AuditRecord rec : auditList) {
                        if (!auditQueue.offer(rec)) break;
                    }
                }
            }
        }

        if (!syncQueue.isEmpty()) {
            java.util.List<PendingSyncRecord> syncList = new java.util.ArrayList<>();
            syncQueue.drainTo(syncList);
            if (!syncList.isEmpty()) {
                long start = System.currentTimeMillis();
                String query = "INSERT INTO pending_syncs (uuid, currency, balance, timestamp) VALUES (?, ?, ?, ?)";
                boolean success = executeDatabaseOperationChecked(conn -> {
                    boolean autoCommit = conn.getAutoCommit();
                    conn.setAutoCommit(false);
                    try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                        for (PendingSyncRecord record : syncList) {
                            pstmt.setString(1, record.uuid);
                            pstmt.setString(2, record.currency);
                            pstmt.setDouble(3, record.balance);
                            pstmt.setLong(4, record.timestamp);
                            pstmt.addBatch();
                        }
                        pstmt.executeBatch();
                        conn.commit();
                    } catch (Exception e) {
                        try {
                            conn.rollback();
                        } catch (SQLException ex) {
                            // Suppress rollback exception to preserve primary exception
                        }
                        if (e instanceof SQLException) {
                            throw (SQLException) e;
                        } else {
                            throw new SQLException(e);
                        }
                    } finally {
                        conn.setAutoCommit(autoCommit);
                    }
                }, "Failed to batch insert pending syncs");
                
                if (success) {
                    long duration = System.currentTimeMillis() - start;
                    totalBatchWriteTimeMs.addAndGet(duration);
                    batchWriteCount.incrementAndGet();
                } else {
                    plugin.getLogger().warning("[Vault Failover] Re-queueing " + syncList.size() + " pending syncs due to database write failure.");
                    for (PendingSyncRecord rec : syncList) {
                        if (!syncQueue.offer(rec)) break;
                    }
                }
            }
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

    // --- NEW HELPER METHODS FOR VAULTX FEATURES ---

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

    public java.util.List<AnalyticsReportEntry> getAnalyticsReport(int days) {
        long minTimestamp = System.currentTimeMillis() - (days * 24L * 60 * 60 * 1000);
        String query = "SELECT currency, category, " +
                " SUM(CASE WHEN type = 'DEPOSIT' THEN amount ELSE 0 END) as created, " +
                " SUM(CASE WHEN type = 'WITHDRAW' THEN amount ELSE 0 END) as destroyed " +
                " FROM player_transactions " +
                " WHERE timestamp >= ? " +
                " GROUP BY currency, category";
        return executeDatabaseQuery(conn -> {
            java.util.List<AnalyticsReportEntry> list = new java.util.ArrayList<>();
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
        }, new java.util.ArrayList<>(), "Failed to get analytics report");
    }

    public double getTotalCurrencyBalanceSum(String currency) {
        String key = currency == null ? "default" : currency.toLowerCase();
        String query = "SELECT SUM(balance) as total FROM custom_currency_balances WHERE LOWER(currency) = ?";
        return executeDatabaseQuery(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, key);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getDouble("total");
                    }
                }
            }
            return 0.0;
        }, 0.0, "Failed to calculate " + key + " total money supply sum");
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

        executeDatabaseOperation(conn -> {
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
                            try { conn.rollback(); } catch (SQLException ex) {}
                            throw e;
                        } finally {
                            conn.setAutoCommit(autoCommit);
                        }
                    }
                }
            }
        }, "Failed to run analytics aggregation");

        // Auto prune old transactions and security audits
        int retentionDays = plugin.getConfig().getInt("storage.transaction-history-retention-days", 30);
        if (retentionDays > 0) {
            long cutOffTime = now - (retentionDays * 24L * 60L * 60L * 1000L);
            executeDatabaseOperation(conn -> {
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

        // Prune unclaimed mailbox records older than mailbox.retention-days
        int mailboxRetentionDays = plugin.getConfig().getInt("mailbox.retention-days", 60);
        if (mailboxRetentionDays > 0) {
            long mailCutoff = now - (mailboxRetentionDays * 24L * 60L * 60L * 1000L);
            executeDatabaseOperation(conn -> {
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

    public void savePendingWebhook(String payload, int attempts, long nextRetry) {
        String query = "INSERT INTO pending_webhooks (payload, attempts, next_retry) VALUES (?, ?, ?)";
        executeDatabaseOperation(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, payload);
                pstmt.setInt(2, attempts);
                pstmt.setLong(3, nextRetry);
                pstmt.executeUpdate();
            }
        }, "Failed to save pending webhook");
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

    public java.util.List<PendingWebhookRecord> getPendingWebhooks(long now) {
        String query = "SELECT id, payload, attempts, next_retry FROM pending_webhooks WHERE next_retry <= ? ORDER BY id ASC";
        return executeDatabaseQuery(conn -> {
            java.util.List<PendingWebhookRecord> list = new java.util.ArrayList<>();
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
        }, new java.util.ArrayList<>(), "Failed to get pending webhooks");
    }

    public void updatePendingWebhook(long id, int attempts, long nextRetry) {
        String query = "UPDATE pending_webhooks SET attempts = ?, next_retry = ? WHERE id = ?";
        executeDatabaseOperation(conn -> {
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
        executeDatabaseOperation(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setLong(1, id);
                pstmt.executeUpdate();
            }
        }, "Failed to delete pending webhook");
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

    public void saveLocalEscrow(String id, UUID sender, UUID receiver, double amount, String currency, String status, long timeoutAt) {
        String query = "REPLACE INTO local_escrows (id, sender, receiver, amount, currency, status, timeout_at) VALUES (?, ?, ?, ?, ?, ?, ?)";
        executeDatabaseOperation(conn -> {
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
        return executeDatabaseQuery(conn -> {
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
        executeDatabaseOperation(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, id);
                pstmt.executeUpdate();
            }
        }, "Failed to delete local escrow");
    }

    public java.util.List<LocalEscrowRecord> getLocalEscrowsForPlayer(UUID playerUuid) {
        String query = "SELECT id, sender, receiver, amount, currency, status, timeout_at FROM local_escrows WHERE sender = ? OR receiver = ?";
        return executeDatabaseQuery(conn -> {
            java.util.List<LocalEscrowRecord> list = new java.util.ArrayList<>();
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
        }, new java.util.ArrayList<>(), "Failed to get local escrows for player");
    }

    public java.util.List<LocalEscrowRecord> getExpiredPendingLocalEscrows(long now) {
        String query = "SELECT id, sender, receiver, amount, currency, status, timeout_at FROM local_escrows WHERE status = 'PENDING' AND timeout_at <= ?";
        return executeDatabaseQuery(conn -> {
            java.util.List<LocalEscrowRecord> list = new java.util.ArrayList<>();
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
        }, new java.util.ArrayList<>(), "Failed to get expired pending local escrows");
    }

    public double getAverageBatchWriteLatencyMs() {
        long count = batchWriteCount.get();
        if (count == 0) return 0.0;
        return (double) totalBatchWriteTimeMs.get() / count;
    }

    public static class LocalCurrencyStats {
        public final double totalMoney;
        public final long accountsCount;
        public final double averageBalance;

        public LocalCurrencyStats(double totalMoney, long accountsCount, double averageBalance) {
            this.totalMoney = totalMoney;
            this.accountsCount = accountsCount;
            this.averageBalance = averageBalance;
        }
    }

    public LocalCurrencyStats getLocalCurrencyStats(String currency) {
        String query = "SELECT COUNT(uuid) as count, SUM(balance) as total FROM custom_currency_balances WHERE currency = ?";
        return executeDatabaseQuery(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, currency.toLowerCase());
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        long count = rs.getLong("count");
                        double total = rs.getDouble("total");
                        double avg = count > 0 ? total / count : 0.0;
                        return new LocalCurrencyStats(total, count, avg);
                    }
                }
            }
            return new LocalCurrencyStats(0.0, 0, 0.0);
        }, new LocalCurrencyStats(0.0, 0, 0.0), "Failed to get local currency stats");
    }

    // --- EXCHANGE RATES METHODS ---
    public void saveExchangeRate(String currency, double rate) {
        String query = "REPLACE INTO exchange_rates (currency, rate, last_updated) VALUES (?, ?, ?)";
        executeDatabaseOperation(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, currency.toLowerCase());
                pstmt.setDouble(2, rate);
                pstmt.setLong(3, System.currentTimeMillis());
                pstmt.executeUpdate();
            }
        }, "Failed to save exchange rate");
    }

    public Map<String, Double> getExchangeRates() {
        String query = "SELECT currency, rate FROM exchange_rates";
        return executeDatabaseQuery(conn -> {
            Map<String, Double> map = new HashMap<>();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(query)) {
                while (rs.next()) {
                    map.put(rs.getString("currency").toLowerCase(), rs.getDouble("rate"));
                }
            }
            return map;
        }, new HashMap<>(), "Failed to get exchange rates");
    }

    // --- SHARED BANK ACCOUNTS METHODS ---
    public void createBankAccount(String name, UUID ownerUuid) {
        net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
            String query1 = "REPLACE INTO bank_accounts (name, owner_uuid, created_at) VALUES (?, ?, ?)";
            String query2 = "REPLACE INTO bank_members (bank_name, uuid, role) VALUES (?, ?, 'OWNER')";
            executeDatabaseOperation(conn -> {
                boolean autoCommit = conn.getAutoCommit();
                conn.setAutoCommit(false);
                try {
                    try (PreparedStatement pstmt = conn.prepareStatement(query1)) {
                        pstmt.setString(1, name.toLowerCase());
                        pstmt.setString(2, ownerUuid.toString());
                        pstmt.setLong(3, System.currentTimeMillis());
                        pstmt.executeUpdate();
                    }
                    try (PreparedStatement pstmt = conn.prepareStatement(query2)) {
                        pstmt.setString(1, name.toLowerCase());
                        pstmt.setString(2, ownerUuid.toString());
                        pstmt.executeUpdate();
                    }
                    conn.commit();
                } catch (SQLException e) {
                    conn.rollback();
                    throw e;
                } finally {
                    conn.setAutoCommit(autoCommit);
                }
            }, "Failed to create bank account");
        });
    }

    public void deleteBankAccount(String name) {
        net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
            String query1 = "DELETE FROM bank_accounts WHERE name = ?";
            String query2 = "DELETE FROM bank_members WHERE bank_name = ?";
            String query3 = "DELETE FROM local_banks WHERE name = ?";
            executeDatabaseOperation(conn -> {
                boolean autoCommit = conn.getAutoCommit();
                conn.setAutoCommit(false);
                try {
                    try (PreparedStatement pstmt = conn.prepareStatement(query1)) {
                        pstmt.setString(1, name.toLowerCase());
                        pstmt.executeUpdate();
                    }
                    try (PreparedStatement pstmt = conn.prepareStatement(query2)) {
                        pstmt.setString(1, name.toLowerCase());
                        pstmt.executeUpdate();
                    }
                    try (PreparedStatement pstmt = conn.prepareStatement(query3)) {
                        pstmt.setString(1, name.toLowerCase());
                        pstmt.executeUpdate();
                    }
                    conn.commit();
                } catch (SQLException e) {
                    conn.rollback();
                    throw e;
                } finally {
                    conn.setAutoCommit(autoCommit);
                }
            }, "Failed to delete bank account");
        });
    }

    public UUID getBankAccountOwner(String name) {
        String query = "SELECT owner_uuid FROM bank_accounts WHERE name = ?";
        return executeDatabaseQuery(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, name.toLowerCase());
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return UUID.fromString(rs.getString("owner_uuid"));
                    }
                }
            }
            return null;
        }, null, "Failed to get bank account owner");
    }

    public void addBankMember(String bankName, UUID uuid, String role) {
        String query = "REPLACE INTO bank_members (bank_name, uuid, role) VALUES (?, ?, ?)";
        executeDatabaseOperation(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, bankName.toLowerCase());
                pstmt.setString(2, uuid.toString());
                pstmt.setString(3, role.toUpperCase());
                pstmt.executeUpdate();
            }
        }, "Failed to add bank member");
    }

    public void removeBankMember(String bankName, UUID uuid) {
        String query = "DELETE FROM bank_members WHERE bank_name = ? AND uuid = ?";
        executeDatabaseOperation(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, bankName.toLowerCase());
                pstmt.setString(2, uuid.toString());
                pstmt.executeUpdate();
            }
        }, "Failed to remove bank member");
    }

    public Map<UUID, String> getBankMembers(String bankName) {
        String query = "SELECT uuid, role FROM bank_members WHERE bank_name = ?";
        return executeDatabaseQuery(conn -> {
            Map<UUID, String> map = new HashMap<>();
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, bankName.toLowerCase());
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        map.put(UUID.fromString(rs.getString("uuid")), rs.getString("role").toUpperCase());
                    }
                }
            }
            return map;
        }, new HashMap<>(), "Failed to get bank members");
    }

    public java.util.List<String> getBanksForPlayer(UUID uuid) {
        String query = "SELECT bank_name FROM bank_members WHERE uuid = ? AND role NOT LIKE 'INVITED_%'";
        return executeDatabaseQuery(conn -> {
            java.util.List<String> list = new java.util.ArrayList<>();
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, uuid.toString());
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        list.add(rs.getString("bank_name").toLowerCase());
                    }
                }
            }
            return list;
        }, new java.util.ArrayList<>(), "Failed to get banks for player");
    }

    public java.util.Map<String, String> getPendingInvitesForPlayer(UUID uuid) {
        String query = "SELECT bank_name, role FROM bank_members WHERE uuid = ? AND role LIKE 'INVITED_%'";
        return executeDatabaseQuery(conn -> {
            java.util.Map<String, String> invites = new java.util.HashMap<>();
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, uuid.toString());
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        String role = rs.getString("role").replace("INVITED_", "");
                        invites.put(rs.getString("bank_name").toLowerCase(), role);
                    }
                }
            }
            return invites;
        }, new java.util.HashMap<>(), "Failed to get pending invites");
    }

    public String getBankRole(String bankName, UUID uuid) {
        String query = "SELECT role FROM bank_members WHERE bank_name = ? AND uuid = ?";
        return executeDatabaseQuery(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, bankName.toLowerCase());
                pstmt.setString(2, uuid.toString());
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getString("role").toUpperCase();
                    }
                }
            }
            return null;
        }, null, "Failed to get bank role");
    }

    // --- OFFLINE MAILBOX METHODS ---
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

    public void addMail(UUID uuid, String senderName, String message, double amount, String currency) {
        String query = "INSERT INTO player_mailbox (uuid, sender_name, message, amount, currency, status, timestamp) VALUES (?, ?, ?, ?, ?, 'PENDING', ?)";
        executeDatabaseOperation(conn -> {
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

    public java.util.List<MailRecord> getPendingMail(UUID uuid) {
        String query = "SELECT id, uuid, sender_name, message, amount, currency, status, timestamp FROM player_mailbox WHERE uuid = ? AND status = 'PENDING' ORDER BY id ASC";
        return executeDatabaseQuery(conn -> {
            java.util.List<MailRecord> list = new java.util.ArrayList<>();
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
        }, new java.util.ArrayList<>(), "Failed to get pending mail");
    }

    public void claimMail(int mailId) {
        String query = "UPDATE player_mailbox SET status = 'CLAIMED' WHERE id = ?";
        executeDatabaseOperation(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setInt(1, mailId);
                pstmt.executeUpdate();
            }
        }, "Failed to claim mail");
    }

    public void deleteMail(int mailId) {
        String query = "DELETE FROM player_mailbox WHERE id = ?";
        executeDatabaseOperation(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setInt(1, mailId);
                pstmt.executeUpdate();
            }
        }, "Failed to delete mail");
    }

    // --- CENTRAL BANK BULK UPDATES ---
    public void applyBulkInterest(String currency, double factor, long timestamp) {
        String query = "UPDATE custom_currency_balances SET balance = balance * ?, last_updated = ? WHERE currency = ? AND balance > 0";
        executeDatabaseOperation(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setDouble(1, factor);
                pstmt.setLong(2, timestamp);
                pstmt.setString(3, currency.toLowerCase());
                pstmt.executeUpdate();
            }
        }, "Failed to apply bulk interest to custom currencies");
    }

    public void applyBulkBankInterest(double factor) {
        String query = "UPDATE local_banks SET balance = balance * ?";
        executeDatabaseOperation(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setDouble(1, factor);
                pstmt.executeUpdate();
            }
        }, "Failed to apply bulk bank interest");
    }

    public void applyBulkWealthTax(double threshold, double taxRate, long timestamp) {
        String query = "UPDATE custom_currency_balances SET balance = balance - ((balance - ?) * ?), last_updated = ? WHERE currency != 'default' AND balance > ?";
        executeDatabaseOperation(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setDouble(1, threshold);
                pstmt.setDouble(2, taxRate);
                pstmt.setLong(3, timestamp);
                pstmt.setDouble(4, threshold);
                pstmt.executeUpdate();
            }
        }, "Failed to apply bulk wealth tax");
    }

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

    public java.util.List<CustomBalanceRecord> getAllCustomBalances() {
        String query = "SELECT uuid, currency, balance FROM custom_currency_balances";
        return executeDatabaseQuery(conn -> {
            java.util.List<CustomBalanceRecord> list = new java.util.ArrayList<>();
            try (PreparedStatement pstmt = conn.prepareStatement(query);
                 ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    list.add(new CustomBalanceRecord(
                            UUID.fromString(rs.getString("uuid")),
                            rs.getString("currency").toLowerCase(),
                            rs.getDouble("balance")
                    ));
                }
            }
            return list;
        }, new java.util.ArrayList<>(), "Failed to get all custom balances");
    }

    public java.util.List<CustomBalanceRecord> getCustomBalancesUpdatedSince(long timestamp) {
        String query = "SELECT uuid, currency, balance FROM custom_currency_balances WHERE last_updated >= ?";
        return executeDatabaseQuery(conn -> {
            java.util.List<CustomBalanceRecord> list = new java.util.ArrayList<>();
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setLong(1, timestamp);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        list.add(new CustomBalanceRecord(
                                UUID.fromString(rs.getString("uuid")),
                                rs.getString("currency").toLowerCase(),
                                rs.getDouble("balance")
                        ));
                    }
                }
            }
            return list;
        }, new java.util.ArrayList<>(), "Failed to get custom balances updated since " + timestamp);
    }

    public double getCustomWealthTaxSum(String currency, double threshold, double taxRate) {
        String query = "SELECT SUM((balance - ?) * ?) FROM custom_currency_balances WHERE currency = ? AND balance > ?";
        return executeDatabaseQuery(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setDouble(1, threshold);
                pstmt.setDouble(2, taxRate);
                pstmt.setString(3, currency.toLowerCase());
                pstmt.setDouble(4, threshold);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getDouble(1);
                    }
                }
            }
            return 0.0;
        }, 0.0, "Failed to calculate custom wealth tax sum");
    }

    public Map<String, Double> getAllBankBalances() {
        String query = "SELECT name, balance FROM local_banks";
        return executeDatabaseQuery(conn -> {
            Map<String, Double> map = new HashMap<>();
            try (PreparedStatement pstmt = conn.prepareStatement(query);
                 ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    map.put(rs.getString("name").toLowerCase(), rs.getDouble("balance"));
                }
            }
            return map;
        }, new HashMap<>(), "Failed to get all bank balances");
    }

    public void saveSetting(String key, String value) {
        String query = isMySQL 
            ? "INSERT INTO economic_settings (key_name, val) VALUES (?, ?) ON DUPLICATE KEY UPDATE val = ?"
            : "INSERT OR REPLACE INTO economic_settings (key_name, val) VALUES (?, ?)";
        executeDatabaseOperation(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, key);
                pstmt.setString(2, value);
                if (isMySQL) {
                    pstmt.setString(3, value);
                }
                pstmt.executeUpdate();
            }
        }, "Failed to save setting " + key);
    }

    public String getSetting(String key) {
        String query = "SELECT val FROM economic_settings WHERE key_name = ?";
        return executeDatabaseQuery(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, key);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getString("val");
                    }
                }
            }
            return null;
        }, null, "Failed to get setting " + key);
    }

    public void saveCheck(String id, double amount, String currency, UUID creatorUuid) {
        String query = "INSERT INTO active_checks (id, amount, currency, creator_uuid, status) VALUES (?, ?, ?, ?, 'ACTIVE')";
        executeDatabaseOperation(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, id);
                pstmt.setDouble(2, amount);
                pstmt.setString(3, currency);
                pstmt.setString(4, creatorUuid.toString());
                pstmt.executeUpdate();
            }
        }, "Failed to save check " + id);
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

    public LocalCheckRecord getCheck(String id) {
        String query = "SELECT amount, currency, creator_uuid, status FROM active_checks WHERE id = ?";
        return executeDatabaseQuery(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, id);
                try (ResultSet rs = pstmt.executeQuery()) {
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
        executeDatabaseOperation(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, status);
                pstmt.setString(2, id);
                pstmt.executeUpdate();
            }
        }, "Failed to update check status " + id);
    }

    public double getPlayerStockShares(UUID uuid, String commodity) {
        String query = "SELECT shares FROM player_stocks WHERE uuid = ? AND commodity = ?";
        return executeDatabaseQuery(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, uuid.toString());
                pstmt.setString(2, commodity.toLowerCase());
                try (ResultSet rs = pstmt.executeQuery()) {
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
            String query = isMySQL 
                ? "INSERT INTO player_stocks (uuid, commodity, shares) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE shares = ?"
                : "INSERT OR REPLACE INTO player_stocks (uuid, commodity, shares) VALUES (?, ?, ?)";
            executeDatabaseOperation(conn -> {
                try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                    pstmt.setString(1, uuid.toString());
                    pstmt.setString(2, commodity.toLowerCase());
                    pstmt.setDouble(3, shares);
                    if (isMySQL) {
                        pstmt.setDouble(4, shares);
                    }
                    pstmt.executeUpdate();
                }
            }, "Failed to update player stock shares");
        });
    }

    public static class StockPortfolioEntry {
        public final String commodity;
        public final double shares;
        public StockPortfolioEntry(String commodity, double shares) {
            this.commodity = commodity;
            this.shares = shares;
        }
    }

    public java.util.List<StockPortfolioEntry> getPlayerPortfolio(UUID uuid) {
        String query = "SELECT commodity, shares FROM player_stocks WHERE uuid = ?";
        return executeDatabaseQuery(conn -> {
            java.util.List<StockPortfolioEntry> portfolio = new java.util.ArrayList<>();
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, uuid.toString());
                try (ResultSet rs = pstmt.executeQuery()) {
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
        return executeDatabaseQuery(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, commodity.toLowerCase());
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getDouble("price");
                    }
                }
            }
            return 0.0;
        }, 0.0, "Failed to get commodity price");
    }

    public void updateCommodityPrice(String commodity, double price) {
        String query = isMySQL 
            ? "INSERT INTO commodity_prices (commodity, price, last_updated) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE price = ?, last_updated = ?"
            : "INSERT OR REPLACE INTO commodity_prices (commodity, price, last_updated) VALUES (?, ?, ?)";
        long now = System.currentTimeMillis();
        executeDatabaseOperation(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, commodity.toLowerCase());
                pstmt.setDouble(2, price);
                pstmt.setLong(3, now);
                if (isMySQL) {
                    pstmt.setDouble(4, price);
                    pstmt.setLong(5, now);
                }
                pstmt.executeUpdate();
            }
        }, "Failed to update commodity price");
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

    public void saveLoan(LoanRecord loan) {
        String query = "INSERT INTO bank_loans (id, borrower, bank_name, principal, remaining, interest_rate, next_billing, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        executeDatabaseOperation(conn -> {
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
        executeDatabaseOperation(conn -> {
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
        executeDatabaseOperation(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setLong(1, nextBilling);
                pstmt.setString(2, id);
                pstmt.executeUpdate();
            }
        }, "Failed to update loan billing timestamp " + id);
    }

    public java.util.List<LoanRecord> getActiveLoans() {
        String query = "SELECT id, borrower, bank_name, principal, remaining, interest_rate, next_billing, status FROM bank_loans WHERE status = 'ACTIVE'";
        return executeDatabaseQuery(conn -> {
            java.util.List<LoanRecord> list = new java.util.ArrayList<>();
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
        }, new java.util.ArrayList<>(), "Failed to get active loans");
    }

    public java.util.List<LoanRecord> getLoansForPlayer(UUID uuid) {
        String query = "SELECT id, borrower, bank_name, principal, remaining, interest_rate, next_billing, status FROM bank_loans WHERE borrower = ?";
        return executeDatabaseQuery(conn -> {
            java.util.List<LoanRecord> list = new java.util.ArrayList<>();
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
        }, new java.util.ArrayList<>(), "Failed to get loans for player");
    }

    public double getPlayerDebt(UUID uuid) {
        String query = "SELECT amount FROM player_debts WHERE uuid = ?";
        return executeDatabaseQuery(conn -> {
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
            String query = isMySQL 
                ? "INSERT INTO player_debts (uuid, amount) VALUES (?, ?) ON DUPLICATE KEY UPDATE amount = ?"
                : "INSERT OR REPLACE INTO player_debts (uuid, amount) VALUES (?, ?)";
            executeDatabaseOperation(conn -> {
                try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                    pstmt.setString(1, uuid.toString());
                    pstmt.setDouble(2, amount);
                    if (isMySQL) {
                        pstmt.setDouble(3, amount);
                    }
                    pstmt.executeUpdate();
                }
            }, "Failed to update player debt");
        });
    }

    public double getOutstandingBankShares(String bankName) {
        String query = "SELECT SUM(shares) AS total FROM player_stocks WHERE commodity = ?";
        return executeDatabaseQuery(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, "bank:" + bankName.toLowerCase());
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getDouble("total");
                    }
                }
            }
            return 0.0;
        }, 0.0, "Failed to get outstanding bank shares");
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

    public java.util.List<BankShareholderRecord> getBankShareholders() {
        String query = "SELECT uuid, commodity, shares FROM player_stocks WHERE commodity LIKE 'bank:%'";
        return executeDatabaseQuery(conn -> {
            java.util.List<BankShareholderRecord> list = new java.util.ArrayList<>();
            try (PreparedStatement pstmt = conn.prepareStatement(query);
                 ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    String comm = rs.getString("commodity");
                    if (comm.startsWith("bank:")) {
                        String bank = comm.substring(5);
                        list.add(new BankShareholderRecord(
                            UUID.fromString(rs.getString("uuid")),
                            bank,
                            rs.getDouble("shares")
                        ));
                    }
                }
            }
            return list;
        }, new java.util.ArrayList<>(), "Failed to get bank shareholders");
    }

    // --- PLAYER SUBSCRIPTIONS METHODS ---
    public static class SubscriptionRecord {
        public final String id;
        public final UUID subscriber;
        public final String targetType; // "PLAYER" or "BANK"
        public final String target;
        public final double amount;
        public final String currency;
        public final int intervalHours;
        public final long lastBilling;
        public final long nextBilling;
        public final String status; // "ACTIVE" or "SUSPENDED"
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

    public void saveSubscription(SubscriptionRecord sub) {
        String query = isMySQL 
            ? "INSERT INTO player_subscriptions (id, subscriber, target_type, target, amount, currency, interval_hours, last_billing, next_billing, status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
              "ON DUPLICATE KEY UPDATE amount = ?, currency = ?, interval_hours = ?, last_billing = ?, next_billing = ?, status = ?"
            : "INSERT OR REPLACE INTO player_subscriptions (id, subscriber, target_type, target, amount, currency, interval_hours, last_billing, next_billing, status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        executeDatabaseOperation(conn -> {
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
                if (isMySQL) {
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
        executeDatabaseOperation(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, id);
                pstmt.executeUpdate();
            }
        }, "Failed to delete subscription " + id);
    }

    public void updateSubscriptionBilling(String id, long lastBilling, long nextBilling, String status) {
        String query = "UPDATE player_subscriptions SET last_billing = ?, next_billing = ?, status = ? WHERE id = ?";
        executeDatabaseOperation(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setLong(1, lastBilling);
                pstmt.setLong(2, nextBilling);
                pstmt.setString(3, status.toUpperCase());
                pstmt.setString(4, id);
                pstmt.executeUpdate();
            }
        }, "Failed to update subscription billing " + id);
    }

    public java.util.List<SubscriptionRecord> getSubscriptionsForSubscriber(UUID subscriberUuid) {
        String query = "SELECT id, subscriber, target_type, target, amount, currency, interval_hours, last_billing, next_billing, status, created_at FROM player_subscriptions WHERE subscriber = ?";
        return executeDatabaseQuery(conn -> {
            java.util.List<SubscriptionRecord> list = new java.util.ArrayList<>();
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
        }, new java.util.ArrayList<>(), "Failed to get subscriptions for subscriber");
    }

    public java.util.List<SubscriptionRecord> getDueSubscriptions(long now) {
        String query = "SELECT id, subscriber, target_type, target, amount, currency, interval_hours, last_billing, next_billing, status, created_at FROM player_subscriptions WHERE status = 'ACTIVE' AND next_billing <= ?";
        return executeDatabaseQuery(conn -> {
            java.util.List<SubscriptionRecord> list = new java.util.ArrayList<>();
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
        }, new java.util.ArrayList<>(), "Failed to get due subscriptions");
    }

    public SubscriptionRecord getSubscription(String id) {
        String query = "SELECT id, subscriber, target_type, target, amount, currency, interval_hours, last_billing, next_billing, status, created_at FROM player_subscriptions WHERE id = ?";
        return executeDatabaseQuery(conn -> {
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

    public double getTotalBankLoansDebt() {
        String query = "SELECT SUM(remaining) AS total FROM bank_loans WHERE status = 'ACTIVE'";
        return executeDatabaseQuery(conn -> {
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
        return executeDatabaseQuery(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query);
                 ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getDouble("total");
                }
            }
            return 0.0;
        }, 0.0, "Failed to get total player debts");
    }

    private final Map<String, CachedLeaderboard> localLeaderboardCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static class CachedLeaderboard {
        final long timestamp;
        final java.util.List<net.milkbowl.vault.redis.VaultRedisManager.LeaderboardEntry> entries;
        CachedLeaderboard(long timestamp, java.util.List<net.milkbowl.vault.redis.VaultRedisManager.LeaderboardEntry> entries) {
            this.timestamp = timestamp;
            this.entries = entries;
        }
    }

    public java.util.List<net.milkbowl.vault.redis.VaultRedisManager.LeaderboardEntry> getLocalLeaderboard(String currency, int limit) {
        String cacheKey = (currency == null ? "default" : currency.toLowerCase()) + ":" + limit;
        long now = System.currentTimeMillis();
        CachedLeaderboard cached = localLeaderboardCache.get(cacheKey);
        if (cached != null && (now - cached.timestamp) < 10000L) {
            return cached.entries;
        }
        String query = "SELECT c.uuid, c.balance, u.name FROM custom_currency_balances c LEFT JOIN local_uuid_cache u ON c.uuid = u.uuid WHERE c.currency = ? AND c.balance > 0 ORDER BY c.balance DESC LIMIT ?";
        java.util.List<net.milkbowl.vault.redis.VaultRedisManager.LeaderboardEntry> result = executeDatabaseQuery(conn -> {
            java.util.List<net.milkbowl.vault.redis.VaultRedisManager.LeaderboardEntry> list = new java.util.ArrayList<>();
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, (currency == null ? "default" : currency).toLowerCase());
                pstmt.setInt(2, limit);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        UUID uuid = UUID.fromString(rs.getString("uuid"));
                        double balance = rs.getDouble("balance");
                        String name = rs.getString("name");
                        if (name == null || name.isEmpty()) {
                            name = net.milkbowl.vault.util.UUIDCache.getName(uuid);
                        }
                        if (name == null || name.isEmpty()) {
                            name = "Unknown";
                        }
                        list.add(new net.milkbowl.vault.redis.VaultRedisManager.LeaderboardEntry(name, balance));
                    }
                }
            }
            return list;
        }, new java.util.ArrayList<>(), "Failed to get local leaderboard");
        localLeaderboardCache.put(cacheKey, new CachedLeaderboard(now, result));
        return result;
    }

    public void saveDiscordLink(UUID uuid, String discordTag) {
        net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
            String query = isMySQL
                    ? "INSERT INTO discord_accounts (uuid, discord_tag) VALUES (?, ?) ON DUPLICATE KEY UPDATE discord_tag = VALUES(discord_tag)"
                    : "INSERT INTO discord_accounts (uuid, discord_tag) VALUES (?, ?) ON CONFLICT(uuid) DO UPDATE SET discord_tag = excluded.discord_tag";
            executeDatabaseOperation(conn -> {
                try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                    pstmt.setString(1, uuid.toString());
                    pstmt.setString(2, discordTag);
                    pstmt.executeUpdate();
                }
            }, "Failed to save Discord account link");
        });
    }

    public void removeDiscordLink(UUID uuid) {
        net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
            String query = "DELETE FROM discord_accounts WHERE uuid = ?";
            executeDatabaseOperation(conn -> {
                try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                    pstmt.setString(1, uuid.toString());
                    pstmt.executeUpdate();
                }
            }, "Failed to remove Discord account link");
        });
    }

    public Map<UUID, String> loadAllDiscordLinks() {
        String query = "SELECT uuid, discord_tag FROM discord_accounts";
        return executeDatabaseQuery(conn -> {
            Map<UUID, String> map = new HashMap<>();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(query)) {
                while (rs.next()) {
                    try {
                        map.put(UUID.fromString(rs.getString("uuid")), rs.getString("discord_tag"));
                    } catch (Exception ignored) {}
                }
            }
            return map;
        }, new HashMap<>(), "Failed to load Discord account links");
    }

    public Map<UUID, Double> getTopBalances(String currency, int limit) {
        String query = "SELECT uuid, balance FROM player_balances WHERE LOWER(currency) = LOWER(?) ORDER BY balance DESC LIMIT ?";
        return executeDatabaseQuery(conn -> {
            Map<UUID, Double> map = new java.util.LinkedHashMap<>();
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, currency == null ? "default" : currency);
                pstmt.setInt(2, limit > 0 ? limit : 10);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        try {
                            map.put(UUID.fromString(rs.getString("uuid")), rs.getDouble("balance"));
                        } catch (Exception ignored) {}
                    }
                }
            }
            return map;
        }, new java.util.LinkedHashMap<>(), "Failed to get top balances");
    }

    public int getPlayerRank(UUID uuid, String currency) {
        if (uuid == null) return -1;
        String query = "SELECT COUNT(*) + 1 AS rank FROM player_balances WHERE LOWER(currency) = LOWER(?) AND balance > (SELECT balance FROM player_balances WHERE uuid = ? AND LOWER(currency) = LOWER(?))";
        return executeDatabaseQuery(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, currency == null ? "default" : currency);
                pstmt.setString(2, uuid.toString());
                pstmt.setString(3, currency == null ? "default" : currency);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt("rank");
                    }
                }
            }
            return -1;
        }, -1, "Failed to get player rank");
    }

    public boolean createSnapshot(String snapshotId, String label, long timestamp, int totalAccounts, double totalNetWorth, Map<UUID, Map<String, Double>> allBalances) {
        String insertSnapshot = "INSERT INTO economy_snapshots (snapshot_id, label, timestamp, total_accounts, total_net_worth) VALUES (?, ?, ?, ?, ?)";
        String insertBalance = "INSERT INTO snapshot_balances (snapshot_id, uuid, currency, balance) VALUES (?, ?, ?, ?)";

        return executeDatabaseQuery(conn -> {
            boolean origAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement pstmt = conn.prepareStatement(insertSnapshot)) {
                    pstmt.setString(1, snapshotId);
                    pstmt.setString(2, label != null ? label : "Snapshot " + snapshotId);
                    pstmt.setLong(3, timestamp);
                    pstmt.setInt(4, totalAccounts);
                    pstmt.setDouble(5, totalNetWorth);
                    pstmt.executeUpdate();
                }

                try (PreparedStatement pstmt = conn.prepareStatement(insertBalance)) {
                    for (Map.Entry<UUID, Map<String, Double>> entry : allBalances.entrySet()) {
                        String uStr = entry.getKey().toString();
                        for (Map.Entry<String, Double> bEntry : entry.getValue().entrySet()) {
                            pstmt.setString(1, snapshotId);
                            pstmt.setString(2, uStr);
                            pstmt.setString(3, bEntry.getKey().toLowerCase());
                            pstmt.setDouble(4, bEntry.getValue());
                            pstmt.addBatch();
                        }
                    }
                    pstmt.executeBatch();
                }
                conn.commit();
                return true;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(origAutoCommit);
            }
        }, false, "Failed to save economy snapshot");
    }

    public List<EconomySnapshot> getSnapshotsFromDb(int limit) {
        String query = "SELECT snapshot_id, label, timestamp, total_accounts, total_net_worth FROM economy_snapshots ORDER BY timestamp DESC LIMIT ?";
        List<EconomySnapshot> res = executeDatabaseQuery(conn -> {
            List<EconomySnapshot> list = new ArrayList<>();
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setInt(1, limit > 0 ? limit : 50);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        list.add(new EconomySnapshot(
                            rs.getString("snapshot_id"),
                            rs.getLong("timestamp"),
                            rs.getString("label"),
                            rs.getInt("total_accounts"),
                            rs.getDouble("total_net_worth")
                        ));
                    }
                }
            }
            return list;
        }, new ArrayList<EconomySnapshot>(), "Failed to get snapshots from DB");
        return res;
    }

    public Map<UUID, Map<String, Double>> getSnapshotBalances(String snapshotId) {
        String query = "SELECT uuid, currency, balance FROM snapshot_balances WHERE snapshot_id = ?";
        return executeDatabaseQuery(conn -> {
            Map<UUID, Map<String, Double>> map = new HashMap<>();
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, snapshotId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        try {
                            UUID u = UUID.fromString(rs.getString("uuid"));
                            String cur = rs.getString("currency").toLowerCase();
                            double bal = rs.getDouble("balance");
                            map.computeIfAbsent(u, k -> new HashMap<>()).put(cur, bal);
                        } catch (Exception ignored) {}
                    }
                }
            }
            return map;
        }, new HashMap<>(), "Failed to load snapshot balances");
    }

    public Map<String, Double> getPlayerSnapshotBalances(UUID uuid, String snapshotId) {
        String query = "SELECT currency, balance FROM snapshot_balances WHERE snapshot_id = ? AND uuid = ?";
        return executeDatabaseQuery(conn -> {
            Map<String, Double> map = new HashMap<>();
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, snapshotId);
                pstmt.setString(2, uuid.toString());
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        map.put(rs.getString("currency").toLowerCase(), rs.getDouble("balance"));
                    }
                }
            }
            return map;
        }, new HashMap<>(), "Failed to load player snapshot balances");
    }

    public boolean deleteSnapshotFromDb(String snapshotId) {
        String del1 = "DELETE FROM economy_snapshots WHERE snapshot_id = ?";
        String del2 = "DELETE FROM snapshot_balances WHERE snapshot_id = ?";
        return executeDatabaseQuery(conn -> {
            try (PreparedStatement p1 = conn.prepareStatement(del1);
                 PreparedStatement p2 = conn.prepareStatement(del2)) {
                p1.setString(1, snapshotId);
                p1.executeUpdate();
                p2.setString(1, snapshotId);
                p2.executeUpdate();
                return true;
            }
        }, false, "Failed to delete snapshot");
    }

    // ========================================================
    //      Crypto Wallets Persistence
    // ========================================================

    public void saveCryptoWallet(UUID uuid, String cryptoName, double amount) {
        String sql = isPostgreSQL ?
                "INSERT INTO crypto_wallets (uuid, crypto_name, balance) VALUES (?, ?, ?) ON CONFLICT (uuid, crypto_name) DO UPDATE SET balance = EXCLUDED.balance" :
                (isMySQL ? "INSERT INTO crypto_wallets (uuid, crypto_name, balance) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE balance = VALUES(balance)" :
                        "REPLACE INTO crypto_wallets (uuid, crypto_name, balance) VALUES (?, ?, ?)");
        executeDatabaseOperation(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, cryptoName);
                ps.setDouble(3, amount);
                ps.executeUpdate();
            }
        }, "Failed to save crypto wallet");
    }

    public void deleteCryptoWallet(UUID uuid, String cryptoName) {
        String sql = "DELETE FROM crypto_wallets WHERE uuid = ? AND crypto_name = ?";
        executeDatabaseOperation(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, cryptoName);
                ps.executeUpdate();
            }
        }, "Failed to delete crypto wallet");
    }

    public Map<UUID, Map<String, Double>> loadAllCryptoWallets() {
        String query = "SELECT uuid, crypto_name, balance FROM crypto_wallets";
        return executeDatabaseQuery(conn -> {
            Map<UUID, Map<String, Double>> result = new HashMap<>();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(query)) {
                while (rs.next()) {
                    try {
                        UUID uuid = UUID.fromString(rs.getString("uuid"));
                        String name = rs.getString("crypto_name");
                        double bal = rs.getDouble("balance");
                        result.computeIfAbsent(uuid, k -> new HashMap<>()).put(name, bal);
                    } catch (Exception ignored) {}
                }
            }
            return result;
        }, new HashMap<>(), "Failed to load all crypto wallets");
    }

    // ========================================================
    //      Active Auctions Persistence
    // ========================================================

    public void saveAuction(VaultAuctionAPI.AuctionListing listing) {
        if (listing == null) return;
        String sql = isPostgreSQL ?
                "INSERT INTO local_auctions (auction_id, seller_uuid, item_data, currency, starting_price, current_bid, highest_bidder_uuid, duration_ms, expires_at_ms, is_closed) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (auction_id) DO UPDATE SET current_bid = EXCLUDED.current_bid, highest_bidder_uuid = EXCLUDED.highest_bidder_uuid, is_closed = EXCLUDED.is_closed" :
                (isMySQL ? "INSERT INTO local_auctions (auction_id, seller_uuid, item_data, currency, starting_price, current_bid, highest_bidder_uuid, duration_ms, expires_at_ms, is_closed) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE current_bid = VALUES(current_bid), highest_bidder_uuid = VALUES(highest_bidder_uuid), is_closed = VALUES(is_closed)" :
                        "REPLACE INTO local_auctions (auction_id, seller_uuid, item_data, currency, starting_price, current_bid, highest_bidder_uuid, duration_ms, expires_at_ms, is_closed) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
        executeDatabaseOperation(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, listing.auctionId());
                ps.setString(2, listing.sellerUuid().toString());
                ps.setString(3, ItemSerializer.serializeItem(listing.item()));
                ps.setString(4, listing.currency());
                ps.setDouble(5, listing.startingPrice());
                ps.setDouble(6, listing.currentBid());
                ps.setString(7, listing.highestBidderUuid() != null ? listing.highestBidderUuid().toString() : null);
                ps.setLong(8, listing.durationMs());
                ps.setLong(9, listing.expiresAtMs());
                ps.setInt(10, listing.isClosed() ? 1 : 0);
                ps.executeUpdate();
            }
        }, "Failed to save auction listing");
    }

    public void deleteAuction(String auctionId) {
        String sql = "DELETE FROM local_auctions WHERE auction_id = ?";
        executeDatabaseOperation(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, auctionId);
                ps.executeUpdate();
            }
        }, "Failed to delete auction");
    }

    public Map<String, VaultAuctionAPI.AuctionListing> loadAllAuctions() {
        String query = "SELECT auction_id, seller_uuid, item_data, currency, starting_price, current_bid, highest_bidder_uuid, duration_ms, expires_at_ms, is_closed FROM local_auctions WHERE is_closed = 0";
        return executeDatabaseQuery(conn -> {
            Map<String, VaultAuctionAPI.AuctionListing> result = new HashMap<>();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(query)) {
                while (rs.next()) {
                    try {
                        String id = rs.getString("auction_id");
                        UUID seller = UUID.fromString(rs.getString("seller_uuid"));
                        ItemStack item = ItemSerializer.deserializeItem(rs.getString("item_data"));
                        String curr = rs.getString("currency");
                        double startPrice = rs.getDouble("starting_price");
                        double currentBid = rs.getDouble("current_bid");
                        String hbStr = rs.getString("highest_bidder_uuid");
                        UUID highestBidder = (hbStr != null && !hbStr.isEmpty()) ? UUID.fromString(hbStr) : null;
                        long duration = rs.getLong("duration_ms");
                        long expiresAt = rs.getLong("expires_at_ms");
                        boolean isClosed = rs.getInt("is_closed") == 1;

                        if (item != null) {
                            result.put(id, new VaultAuctionAPI.AuctionListing(
                                    id, seller, item, curr, startPrice, currentBid, highestBidder, duration, expiresAt, isClosed
                            ));
                        }
                    } catch (Exception ignored) {}
                }
            }
            return result;
        }, new HashMap<>(), "Failed to load active auctions");
    }

    public void savePendingAuctionItem(UUID uuid, ItemStack item) {
        if (uuid == null || item == null) return;
        String sql = "INSERT INTO local_pending_auction_items (uuid, item_data) VALUES (?, ?)";
        executeDatabaseOperation(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, ItemSerializer.serializeItem(item));
                ps.executeUpdate();
            }
        }, "Failed to save pending auction item");
    }

    public Map<UUID, List<ItemStack>> loadAllPendingAuctionItems() {
        String query = "SELECT uuid, item_data FROM local_pending_auction_items";
        return executeDatabaseQuery(conn -> {
            Map<UUID, List<ItemStack>> result = new HashMap<>();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(query)) {
                while (rs.next()) {
                    try {
                        UUID uuid = UUID.fromString(rs.getString("uuid"));
                        ItemStack item = ItemSerializer.deserializeItem(rs.getString("item_data"));
                        if (item != null) {
                            result.computeIfAbsent(uuid, k -> new ArrayList<>()).add(item);
                        }
                    } catch (Exception ignored) {}
                }
            }
            return result;
        }, new HashMap<>(), "Failed to load pending auction items");
    }

    public void deletePendingAuctionItems(UUID uuid) {
        if (uuid == null) return;
        String sql = "DELETE FROM local_pending_auction_items WHERE uuid = ?";
        executeDatabaseOperation(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            }
        }, "Failed to delete pending auction items");
    }

    // ========================================================
    //      Active Stakes Persistence
    // ========================================================

    public void saveStake(VaultStakingAPI.StakeDeposit deposit) {
        if (deposit == null) return;
        String sql = isPostgreSQL ?
                "INSERT INTO local_stakes (deposit_id, player_uuid, currency, principal, interest_rate, staked_at_ms, lock_period_ms, is_matured, is_claimed) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (deposit_id) DO UPDATE SET is_matured = EXCLUDED.is_matured, is_claimed = EXCLUDED.is_claimed" :
                (isMySQL ? "INSERT INTO local_stakes (deposit_id, player_uuid, currency, principal, interest_rate, staked_at_ms, lock_period_ms, is_matured, is_claimed) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE is_matured = VALUES(is_matured), is_claimed = VALUES(is_claimed)" :
                        "REPLACE INTO local_stakes (deposit_id, player_uuid, currency, principal, interest_rate, staked_at_ms, lock_period_ms, is_matured, is_claimed) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)");
        executeDatabaseOperation(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, deposit.depositId());
                ps.setString(2, deposit.playerUuid().toString());
                ps.setString(3, deposit.currency());
                ps.setDouble(4, deposit.principal());
                ps.setDouble(5, deposit.interestRate());
                ps.setLong(6, deposit.stakedAtMs());
                ps.setLong(7, deposit.lockPeriodMs());
                ps.setInt(8, deposit.isMatured() ? 1 : 0);
                ps.setInt(9, deposit.isClaimed() ? 1 : 0);
                ps.executeUpdate();
            }
        }, "Failed to save stake deposit");
    }

    public Map<String, VaultStakingAPI.StakeDeposit> loadAllStakes() {
        String query = "SELECT deposit_id, player_uuid, currency, principal, interest_rate, staked_at_ms, lock_period_ms, is_matured, is_claimed FROM local_stakes WHERE is_claimed = 0";
        return executeDatabaseQuery(conn -> {
            Map<String, VaultStakingAPI.StakeDeposit> result = new HashMap<>();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(query)) {
                while (rs.next()) {
                    try {
                        String id = rs.getString("deposit_id");
                        UUID player = UUID.fromString(rs.getString("player_uuid"));
                        String curr = rs.getString("currency");
                        double principal = rs.getDouble("principal");
                        double rate = rs.getDouble("interest_rate");
                        long stakedAt = rs.getLong("staked_at_ms");
                        long lockPeriod = rs.getLong("lock_period_ms");
                        boolean matured = rs.getInt("is_matured") == 1;
                        boolean claimed = rs.getInt("is_claimed") == 1;

                        result.put(id, new VaultStakingAPI.StakeDeposit(
                                id, player, curr, principal, rate, stakedAt, lockPeriod, matured, claimed
                        ));
                    } catch (Exception ignored) {}
                }
            }
            return result;
        }, new HashMap<>(), "Failed to load active stakes");
    }

    // ========================================================
    //      Credit Accounts Persistence
    // ========================================================

    public void saveCreditAccount(VaultCreditAPI.CreditAccount account) {
        if (account == null) return;
        String sql = isPostgreSQL ?
                "INSERT INTO local_credit_accounts (uuid, currency, overdraft_limit, current_used_credit, credit_score, is_frozen) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (uuid, currency) DO UPDATE SET overdraft_limit = EXCLUDED.overdraft_limit, current_used_credit = EXCLUDED.current_used_credit, credit_score = EXCLUDED.credit_score, is_frozen = EXCLUDED.is_frozen" :
                (isMySQL ? "INSERT INTO local_credit_accounts (uuid, currency, overdraft_limit, current_used_credit, credit_score, is_frozen) VALUES (?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE overdraft_limit = VALUES(overdraft_limit), current_used_credit = VALUES(current_used_credit), credit_score = VALUES(credit_score), is_frozen = VALUES(is_frozen)" :
                        "REPLACE INTO local_credit_accounts (uuid, currency, overdraft_limit, current_used_credit, credit_score, is_frozen) VALUES (?, ?, ?, ?, ?, ?)");
        executeDatabaseOperation(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, account.playerUuid().toString());
                ps.setString(2, account.currency());
                ps.setDouble(3, account.overdraftLimit());
                ps.setDouble(4, account.currentUsedCredit());
                ps.setInt(5, account.creditScore());
                ps.setInt(6, account.isFrozen() ? 1 : 0);
                ps.executeUpdate();
            }
        }, "Failed to save credit account");
    }

    public Map<UUID, Map<String, VaultCreditAPI.CreditAccount>> loadAllCreditAccounts() {
        String query = "SELECT uuid, currency, overdraft_limit, current_used_credit, credit_score, is_frozen FROM local_credit_accounts";
        return executeDatabaseQuery(conn -> {
            Map<UUID, Map<String, VaultCreditAPI.CreditAccount>> result = new HashMap<>();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(query)) {
                while (rs.next()) {
                    try {
                        UUID uuid = UUID.fromString(rs.getString("uuid"));
                        String curr = rs.getString("currency");
                        double limit = rs.getDouble("overdraft_limit");
                        double used = rs.getDouble("current_used_credit");
                        int score = rs.getInt("credit_score");
                        boolean frozen = rs.getInt("is_frozen") == 1;

                        VaultCreditAPI.CreditAccount acc = new VaultCreditAPI.CreditAccount(uuid, curr, limit, used, score, frozen);
                        result.computeIfAbsent(uuid, k -> new HashMap<>()).put(curr, acc);
                    } catch (Exception ignored) {}
                }
            }
            return result;
        }, new HashMap<>(), "Failed to load all credit accounts");
    }

    // ========================================================
    //      Black Market Dirty Balances Persistence
    // ========================================================

    public void saveDirtyBalance(UUID uuid, double amount) {
        String sql = isPostgreSQL ?
                "INSERT INTO dirty_balances (uuid, amount) VALUES (?, ?) ON CONFLICT (uuid) DO UPDATE SET amount = EXCLUDED.amount" :
                (isMySQL ? "INSERT INTO dirty_balances (uuid, amount) VALUES (?, ?) ON DUPLICATE KEY UPDATE amount = VALUES(amount)" :
                        "REPLACE INTO dirty_balances (uuid, amount) VALUES (?, ?)");
        executeDatabaseOperation(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setDouble(2, amount);
                ps.executeUpdate();
            }
        }, "Failed to save dirty balance");
    }

    public void deleteDirtyBalance(UUID uuid) {
        String sql = "DELETE FROM dirty_balances WHERE uuid = ?";
        executeDatabaseOperation(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            }
        }, "Failed to delete dirty balance");
    }

    public Map<UUID, Double> loadAllDirtyBalances() {
        String query = "SELECT uuid, amount FROM dirty_balances";
        return executeDatabaseQuery(conn -> {
            Map<UUID, Double> result = new HashMap<>();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(query)) {
                while (rs.next()) {
                    try {
                        UUID uuid = UUID.fromString(rs.getString("uuid"));
                        double amt = rs.getDouble("amount");
                        result.put(uuid, amt);
                    } catch (Exception ignored) {}
                }
            }
            return result;
        }, new HashMap<>(), "Failed to load all dirty balances");
    }

    public Map<String, Double> loadCryptoWalletForPlayer(UUID uuid) {
        if (uuid == null) return new HashMap<>();
        String sql = "SELECT crypto_name, balance FROM crypto_wallets WHERE uuid = ?";
        return executeDatabaseQuery(conn -> {
            Map<String, Double> result = new HashMap<>();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.put(rs.getString("crypto_name"), rs.getDouble("balance"));
                    }
                }
            }
            return result;
        }, new HashMap<>(), "Failed to load crypto wallet for player");
    }

    public Map<String, VaultCreditAPI.CreditAccount> loadCreditAccountsForPlayer(UUID uuid) {
        if (uuid == null) return new HashMap<>();
        String sql = "SELECT currency, overdraft_limit, current_used_credit, credit_score, is_frozen FROM local_credit_accounts WHERE uuid = ?";
        return executeDatabaseQuery(conn -> {
            Map<String, VaultCreditAPI.CreditAccount> result = new HashMap<>();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String curr = rs.getString("currency");
                        double limit = rs.getDouble("overdraft_limit");
                        double used = rs.getDouble("current_used_credit");
                        int score = rs.getInt("credit_score");
                        boolean frozen = rs.getInt("is_frozen") == 1;
                        result.put(curr, new VaultCreditAPI.CreditAccount(uuid, curr, limit, used, score, frozen));
                    }
                }
            }
            return result;
        }, new HashMap<>(), "Failed to load credit accounts for player");
    }

    public double loadDirtyBalanceForPlayer(UUID uuid) {
        if (uuid == null) return 0.0;
        String sql = "SELECT amount FROM dirty_balances WHERE uuid = ?";
        return executeDatabaseQuery(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return rs.getDouble("amount");
                    }
                }
            }
            return 0.0;
        }, 0.0, "Failed to load dirty balance for player");
    }
}


