package net.milkbowl.vault.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;

public class DatabaseConnectionManager {

    private final Plugin plugin;
    private final File dbFile;
    private final boolean isMySQL;
    private final boolean isPostgreSQL;
    private final String jdbcUrl;
    private final String dbUser;
    private final String dbPass;
    private final Object writeLock = new Object();
    private HikariDataSource dataSource;
    private static final int MAX_POOL_SIZE = 10;

    @FunctionalInterface
    public interface DbConsumer {
        void accept(Connection conn) throws SQLException;
    }

    @FunctionalInterface
    public interface DbFunction<R> {
        R apply(Connection conn) throws SQLException;
    }

    public DatabaseConnectionManager(Plugin plugin) {
        this.plugin = plugin;
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

            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(jdbcUrl);
            config.setUsername(dbUser);
            config.setPassword(dbPass);
            config.setMaximumPoolSize(MAX_POOL_SIZE);
            config.setMinimumIdle(Math.max(2, MAX_POOL_SIZE / 4));
            config.setConnectionTimeout(2000);

            config.addDataSourceProperty("cachePrepStmts", "true");
            config.addDataSourceProperty("prepStmtCacheSize", "250");
            config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
            config.addDataSourceProperty("useServerPrepStmts", "true");

            this.dataSource = new HikariDataSource(config);
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

            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(jdbcUrl);
            config.setUsername(dbUser);
            config.setPassword(dbPass);
            config.setMaximumPoolSize(MAX_POOL_SIZE);
            config.setMinimumIdle(Math.max(2, MAX_POOL_SIZE / 4));
            config.setConnectionTimeout(2000);

            config.addDataSourceProperty("cachePrepStmts", "true");
            config.addDataSourceProperty("prepStmtCacheSize", "250");
            config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

            this.dataSource = new HikariDataSource(config);
        } else {
            this.dbUser = null;
            this.dbPass = null;
            this.dbFile = new File(plugin.getDataFolder(), "failover.db");
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }
            this.jdbcUrl = "jdbc:sqlite:" + dbFile.getAbsolutePath();
            registerDriver("sqlite");

            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(jdbcUrl);
            config.setPoolName("VaultX-SQLite-Pool");
            config.setMaximumPoolSize(MAX_POOL_SIZE);
            config.setMinimumIdle(Math.max(2, MAX_POOL_SIZE / 4));
            config.setConnectionTimeout(2000);
            config.setConnectionInitSql("PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL;");

            this.dataSource = new HikariDataSource(config);
        }
        
        new DatabaseSchemaMigrator(this).initDatabase();
    }

    public Plugin getPlugin() {
        return plugin;
    }

    public boolean isMySQL() {
        return isMySQL;
    }

    public boolean isPostgreSQL() {
        return isPostgreSQL;
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
        } catch (ClassNotFoundException ignored) {
        }
    }

    public Connection acquireConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public void executeDatabaseOperation(DbConsumer operation, String errorMessage) {
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

    public boolean executeDatabaseOperationChecked(DbConsumer operation, String errorMessage) {
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

    public <R> R executeDatabaseQuery(DbFunction<R> operation, R defaultValue, String errorMessage) {
        try (Connection conn = acquireConnection()) {
            return operation.apply(conn);
        } catch (SQLException e) {
            plugin.getLogger().severe("[Vault Failover] " + errorMessage + ": " + e.getMessage());
            return defaultValue;
        }
    }

    public void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }
}
