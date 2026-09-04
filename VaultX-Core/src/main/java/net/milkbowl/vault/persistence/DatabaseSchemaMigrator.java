package net.milkbowl.vault.persistence;

import org.bukkit.plugin.Plugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class DatabaseSchemaMigrator {

    private final DatabaseConnectionManager dbManager;

    public DatabaseSchemaMigrator(DatabaseConnectionManager dbManager) {
        this.dbManager = dbManager;
    }

    public void initDatabase() {
        Plugin plugin = dbManager.getPlugin();
        boolean isMySQL = dbManager.isMySQL();
        boolean isPostgreSQL = dbManager.isPostgreSQL();

        dbManager.executeDatabaseOperation(conn -> {
            try (Statement stmt = conn.createStatement()) {
                String autoIncrementKey = isMySQL ? "BIGINT PRIMARY KEY AUTO_INCREMENT" : (isPostgreSQL ? "BIGSERIAL PRIMARY KEY" : "INTEGER PRIMARY KEY AUTOINCREMENT");
                String bigintType = (isMySQL || isPostgreSQL) ? "BIGINT" : "LONG";

                stmt.execute("CREATE TABLE IF NOT EXISTS pending_syncs (" +
                        "id " + autoIncrementKey + ", " +
                        "uuid VARCHAR(36) NOT NULL, " +
                        "currency VARCHAR(32) NOT NULL, " +
                        "balance DOUBLE NOT NULL, " +
                        "timestamp " + bigintType + " NOT NULL)");

                stmt.execute("CREATE TABLE IF NOT EXISTS local_banks (" +
                        "name VARCHAR(64) PRIMARY KEY, " +
                        "balance DOUBLE NOT NULL)");

                stmt.execute("CREATE TABLE IF NOT EXISTS security_audits (" +
                        "id " + autoIncrementKey + ", " +
                        "timestamp " + bigintType + " NOT NULL, " +
                        "uuid VARCHAR(36), " +
                        "name VARCHAR(64), " +
                        "amount DOUBLE, " +
                        "action VARCHAR(32), " +
                        "details TEXT)");

                stmt.execute("CREATE TABLE IF NOT EXISTS custom_currency_balances (" +
                        "uuid VARCHAR(36) NOT NULL, " +
                        "currency VARCHAR(32) NOT NULL, " +
                        "balance DOUBLE NOT NULL, " +
                        "last_updated " + bigintType + " NOT NULL DEFAULT 0, " +
                        "PRIMARY KEY (uuid, currency))");

                stmt.execute("CREATE TABLE IF NOT EXISTS user_balances (" +
                        "uuid VARCHAR(36) NOT NULL, " +
                        "currency VARCHAR(32) NOT NULL, " +
                        "balance DOUBLE NOT NULL, " +
                        "last_updated " + bigintType + " NOT NULL DEFAULT 0, " +
                        "PRIMARY KEY (uuid, currency))");

                try {
                    stmt.execute("CREATE VIEW IF NOT EXISTS user_balances AS SELECT uuid, currency, balance, last_updated FROM custom_currency_balances");
                } catch (SQLException ignored) {}

                stmt.execute("CREATE TABLE IF NOT EXISTS player_transactions (" +
                        "id " + autoIncrementKey + ", " +
                        "timestamp " + bigintType + " NOT NULL, " +
                        "uuid VARCHAR(36) NOT NULL, " +
                        "type VARCHAR(32) NOT NULL, " +
                        "currency VARCHAR(32) NOT NULL, " +
                        "amount DOUBLE NOT NULL, " +
                        "other_party VARCHAR(64), " +
                        "category VARCHAR(32) NOT NULL DEFAULT 'OTHER')");

                stmt.execute("CREATE TABLE IF NOT EXISTS daily_analytics (" +
                        "day_timestamp " + bigintType + " NOT NULL, " +
                        "currency VARCHAR(32) NOT NULL, " +
                        "category VARCHAR(32) NOT NULL, " +
                        "amount_created DOUBLE NOT NULL, " +
                        "amount_destroyed DOUBLE NOT NULL, " +
                        "PRIMARY KEY (day_timestamp, currency, category))");

                stmt.execute("CREATE TABLE IF NOT EXISTS pending_webhooks (" +
                        "id " + autoIncrementKey + ", " +
                        "payload TEXT NOT NULL, " +
                        "attempts INT NOT NULL, " +
                        "next_retry " + bigintType + " NOT NULL)");

                stmt.execute("CREATE TABLE IF NOT EXISTS local_escrows (" +
                        "id VARCHAR(36) PRIMARY KEY, " +
                        "sender VARCHAR(36) NOT NULL, " +
                        "receiver VARCHAR(36) NOT NULL, " +
                        "amount DOUBLE NOT NULL, " +
                        "currency VARCHAR(32) NOT NULL, " +
                        "status VARCHAR(16) NOT NULL, " +
                        "timeout_at " + bigintType + " NOT NULL)");

                stmt.execute("CREATE TABLE IF NOT EXISTS exchange_rates (" +
                        "currency VARCHAR(32) PRIMARY KEY, " +
                        "rate DOUBLE NOT NULL, " +
                        "last_updated " + bigintType + " NOT NULL)");

                stmt.execute("CREATE TABLE IF NOT EXISTS bank_accounts (" +
                        "name VARCHAR(64) PRIMARY KEY, " +
                        "owner_uuid VARCHAR(36) NOT NULL, " +
                        "created_at " + bigintType + " NOT NULL)");

                stmt.execute("CREATE TABLE IF NOT EXISTS bank_members (" +
                        "bank_name VARCHAR(64) NOT NULL, " +
                        "uuid VARCHAR(36) NOT NULL, " +
                        "role VARCHAR(16) NOT NULL, " +
                        "PRIMARY KEY (bank_name, uuid))");

                stmt.execute("CREATE TABLE IF NOT EXISTS player_mailbox (" +
                        "id " + autoIncrementKey + ", " +
                        "uuid VARCHAR(36) NOT NULL, " +
                        "sender_name VARCHAR(64), " +
                        "message TEXT, " +
                        "amount DOUBLE NOT NULL, " +
                        "currency VARCHAR(32) NOT NULL, " +
                        "status VARCHAR(16) NOT NULL, " +
                        "timestamp " + bigintType + " NOT NULL)");

                stmt.execute("CREATE TABLE IF NOT EXISTS economic_settings (" +
                        "key_name VARCHAR(64) PRIMARY KEY, " +
                        "val VARCHAR(128) NOT NULL)");

                stmt.execute("CREATE TABLE IF NOT EXISTS player_stocks (" +
                        "uuid VARCHAR(36) NOT NULL, " +
                        "commodity VARCHAR(32) NOT NULL, " +
                        "shares DOUBLE NOT NULL, " +
                        "PRIMARY KEY (uuid, commodity))");

                stmt.execute("CREATE TABLE IF NOT EXISTS commodity_prices (" +
                        "commodity VARCHAR(32) PRIMARY KEY, " +
                        "price DOUBLE NOT NULL, " +
                        "last_updated " + bigintType + " NOT NULL)");

                stmt.execute("CREATE TABLE IF NOT EXISTS active_checks (" +
                        "id VARCHAR(36) PRIMARY KEY, " +
                        "amount DOUBLE NOT NULL, " +
                        "currency VARCHAR(32) NOT NULL, " +
                        "creator_uuid VARCHAR(36) NOT NULL, " +
                        "status VARCHAR(16) NOT NULL)");

                stmt.execute("CREATE TABLE IF NOT EXISTS bank_loans (" +
                        "id VARCHAR(36) PRIMARY KEY, " +
                        "borrower VARCHAR(36) NOT NULL, " +
                        "bank_name VARCHAR(64) NOT NULL, " +
                        "principal DOUBLE NOT NULL, " +
                        "remaining DOUBLE NOT NULL, " +
                        "interest_rate DOUBLE NOT NULL, " +
                        "next_billing " + bigintType + " NOT NULL, " +
                        "status VARCHAR(16) NOT NULL)");

                stmt.execute("CREATE TABLE IF NOT EXISTS player_debts (" +
                        "uuid VARCHAR(36) PRIMARY KEY, " +
                        "amount DOUBLE NOT NULL)");

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

                stmt.execute("CREATE TABLE IF NOT EXISTS local_uuid_cache (" +
                        "name VARCHAR(64) PRIMARY KEY, " +
                        "uuid VARCHAR(36) NOT NULL, " +
                        "last_updated " + bigintType + " NOT NULL)");

                stmt.execute("CREATE TABLE IF NOT EXISTS discord_accounts (" +
                        "uuid VARCHAR(36) PRIMARY KEY, " +
                        "discord_tag VARCHAR(64) NOT NULL)");

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

                stmt.execute("CREATE TABLE IF NOT EXISTS crypto_wallets (" +
                        "uuid VARCHAR(36) NOT NULL, " +
                        "crypto_name VARCHAR(32) NOT NULL, " +
                        "balance DOUBLE NOT NULL, " +
                        "PRIMARY KEY (uuid, crypto_name))");

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

                stmt.execute("CREATE TABLE IF NOT EXISTS local_pending_auction_items (" +
                        "id INTEGER PRIMARY KEY " + ((!isMySQL && !isPostgreSQL) ? "AUTOINCREMENT" : (isPostgreSQL ? "GENERATED ALWAYS AS IDENTITY" : "AUTO_INCREMENT")) + ", " +
                        "uuid VARCHAR(36) NOT NULL, " +
                        "item_data TEXT NOT NULL)");

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

                stmt.execute("CREATE TABLE IF NOT EXISTS local_credit_accounts (" +
                        "uuid VARCHAR(36) NOT NULL, " +
                        "currency VARCHAR(32) NOT NULL, " +
                        "overdraft_limit DOUBLE NOT NULL, " +
                        "current_used_credit DOUBLE NOT NULL, " +
                        "credit_score INT NOT NULL, " +
                        "is_frozen INT NOT NULL, " +
                        "PRIMARY KEY (uuid, currency))");

                stmt.execute("CREATE TABLE IF NOT EXISTS dirty_balances (" +
                        "uuid VARCHAR(36) PRIMARY KEY, " +
                        "amount DOUBLE NOT NULL)");

                try {
                    stmt.execute("ALTER TABLE custom_currency_balances ADD COLUMN last_updated " + bigintType + " NOT NULL DEFAULT 0");
                } catch (SQLException ignored) {}

                try {
                    stmt.execute("ALTER TABLE player_transactions ADD COLUMN category VARCHAR(32) NOT NULL DEFAULT 'OTHER'");
                } catch (SQLException ignored) {}

                if (isMySQL) {
                    createIndexMySQL(conn, plugin, "player_transactions", "idx_player_transactions_uuid_id", "uuid, id");
                    createIndexMySQL(conn, plugin, "player_transactions", "idx_player_transactions_timestamp", "timestamp");
                    createIndexMySQL(conn, plugin, "security_audits", "idx_security_audits_uuid_id", "uuid, id");
                    createIndexMySQL(conn, plugin, "security_audits", "idx_security_audits_timestamp", "timestamp");
                    createIndexMySQL(conn, plugin, "player_mailbox", "idx_player_mailbox_uuid_status", "uuid, status");
                    createIndexMySQL(conn, plugin, "bank_members", "idx_bank_members_uuid", "uuid");
                    createIndexMySQL(conn, plugin, "local_escrows", "idx_local_escrows_actors", "sender, receiver");
                    createIndexMySQL(conn, plugin, "local_escrows", "idx_local_escrows_timeout", "status, timeout_at");
                    createIndexMySQL(conn, plugin, "pending_webhooks", "idx_pending_webhooks_retry", "next_retry");
                    createIndexMySQL(conn, plugin, "player_subscriptions", "idx_player_subscriptions_subscriber", "subscriber");
                    createIndexMySQL(conn, plugin, "local_uuid_cache", "idx_local_uuid_cache_uuid", "uuid");
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

    private void createIndexMySQL(Connection conn, Plugin plugin, String tableName, String indexName, String columns) {
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
}
