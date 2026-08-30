package net.milkbowl.vault.redis;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import net.milkbowl.vault.economy.OptimizedEconomy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * VaultPostgresManager handles cross-server synchronization and RAM cache invalidation
 * using PostgreSQL's native LISTEN / NOTIFY mechanism.
 */
public class VaultPostgresManager {

    private static VaultPostgresManager instance;

    private final Plugin plugin;
    private final String serverId;
    private final String channel;
    private final String jdbcUrl;
    private final String dbUser;
    private final String dbPass;
    private final LocalFailoverManager failoverManager;
    private final ExecutorService executor;

    private volatile boolean running = true;
    private Thread listenerThread;
    private Connection listenerConnection;

    public VaultPostgresManager(Plugin plugin, String host, int port, String database, String username, String password, String serverId, String channel, String properties) {
        this.plugin = plugin;
        this.serverId = serverId;
        this.channel = (channel == null || channel.isEmpty()) ? "vaultx_sync" : channel;
        this.dbUser = username;
        this.dbPass = password;
        this.failoverManager = net.milkbowl.vault.Vault.getFailoverManager();
        this.executor = Executors.newSingleThreadExecutor();

        String props = (properties != null && !properties.isEmpty()) ? "?" + properties : "";
        this.jdbcUrl = "jdbc:postgresql://" + host + ":" + port + "/" + database + props;

        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            plugin.getLogger().warning("[VaultPostgres] PostgreSQL Driver org.postgresql.Driver not found: " + e.getMessage());
        }

        instance = this;
        startListener();

        plugin.getLogger().info("[VaultPostgres] PostgreSQL LISTEN/NOTIFY synchronization enabled on channel '" + this.channel + "' for server: " + serverId);
    }

    public static VaultPostgresManager getInstance() {
        return instance;
    }

    public LocalFailoverManager getFailoverManager() {
        return failoverManager;
    }

    private void startListener() {
        listenerThread = new Thread(() -> {
            while (running) {
                try {
                    if (listenerConnection == null || listenerConnection.isClosed()) {
                        listenerConnection = DriverManager.getConnection(jdbcUrl, dbUser, dbPass);
                        try (Statement stmt = listenerConnection.createStatement()) {
                            stmt.execute("LISTEN " + channel);
                        }
                        plugin.getLogger().info("[VaultPostgres] Connected and listening on channel: " + channel);
                    }

                    org.postgresql.PGConnection pgConn = listenerConnection.unwrap(org.postgresql.PGConnection.class);
                    org.postgresql.PGNotification[] notifications = pgConn.getNotifications(1000);

                    if (notifications != null) {
                        for (org.postgresql.PGNotification notification : notifications) {
                            String payload = notification.getParameter();
                            if (payload != null && !payload.isEmpty()) {
                                handleSyncMessage(payload);
                            }
                        }
                    }
                } catch (SQLException e) {
                    if (running) {
                        plugin.getLogger().warning("[VaultPostgres] Listener connection error: " + e.getMessage() + ". Retrying in 5 seconds...");
                        closeListenerConnection();
                        try {
                            Thread.sleep(5000);
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        }
                    }
                } catch (Exception e) {
                    if (running) {
                        plugin.getLogger().warning("[VaultPostgres] Unexpected error in listener: " + e.getMessage());
                    }
                }
            }
        }, "VaultX-Postgres-Listener");

        listenerThread.setDaemon(true);
        listenerThread.start();
    }

    private void handleSyncMessage(String message) {
        String[] parts = message.split(":");
        if (parts.length >= 4) {
            String originServer = parts[0];
            if (originServer.equals(this.serverId)) {
                return; // Ignore messages published by self
            }

            if ("BANK_MEMBER_UPDATE".equals(parts[1])) {
                try {
                    String bankName = parts[2];
                    UUID uuid = UUID.fromString(parts[3]);
                    String role = parts[4];
                    if ("REMOVE".equals(role)) {
                        if (failoverManager != null) failoverManager.removeBankMember(bankName, uuid);
                    } else {
                        if (failoverManager != null) failoverManager.addBankMember(bankName, uuid, role);
                    }
                } catch (Exception ignored) {}
                return;
            }

            if ("BANK".equals(parts[1])) {
                try {
                    String bankName = parts[2];
                    double balance = Double.parseDouble(parts[3]);
                    org.bukkit.plugin.RegisteredServiceProvider<net.milkbowl.vault.economy.Economy> rsp = Bukkit
                            .getServicesManager().getRegistration(net.milkbowl.vault.economy.Economy.class);
                    if (rsp != null && rsp.getProvider() instanceof OptimizedEconomy) {
                        ((OptimizedEconomy) rsp.getProvider()).updateBankCacheFromRedis(bankName, balance);
                    }
                } catch (Exception ignored) {}
                return;
            }

            if ("FREEZE".equals(parts[1])) {
                try {
                    final UUID uuid = UUID.fromString(parts[2]);
                    final String reason = parts.length > 3 ? parts[3] : "Synchronized from PostgreSQL";
                    net.milkbowl.vault.util.FoliaScheduler.runSync(plugin, () -> {
                        if (net.milkbowl.vault.Vault.getFirewall() != null) {
                            net.milkbowl.vault.Vault.getFirewall().freezePlayerLocal(uuid, reason);
                        }
                    });
                } catch (Exception ignored) {}
                return;
            }

            if ("UNFREEZE".equals(parts[1])) {
                try {
                    final UUID uuid = UUID.fromString(parts[2]);
                    net.milkbowl.vault.util.FoliaScheduler.runSync(plugin, () -> {
                        if (net.milkbowl.vault.Vault.getFirewall() != null) {
                            net.milkbowl.vault.Vault.getFirewall().unfreezePlayerLocal(uuid);
                        }
                    });
                } catch (Exception ignored) {}
                return;
            }

            // Player balance update: serverId:uuid:currency:balance
            try {
                UUID uuid = UUID.fromString(parts[1]);
                String currency = parts[2];
                double balance = Double.parseDouble(parts[3]);

                org.bukkit.plugin.RegisteredServiceProvider<net.milkbowl.vault.economy.Economy> rsp = Bukkit
                        .getServicesManager().getRegistration(net.milkbowl.vault.economy.Economy.class);
                if (rsp != null && rsp.getProvider() instanceof OptimizedEconomy) {
                    ((OptimizedEconomy) rsp.getProvider()).updateCacheFromRedis(uuid, currency, balance);
                }
            } catch (Exception ignored) {}
        }
    }

    public void publishSyncMessage(String message) {
        if (!running) return;
        executor.submit(() -> {
            try (Connection conn = DriverManager.getConnection(jdbcUrl, dbUser, dbPass);
                 PreparedStatement stmt = conn.prepareStatement("SELECT pg_notify(?, ?)")) {
                stmt.setString(1, channel);
                stmt.setString(2, message);
                stmt.execute();
            } catch (SQLException e) {
                plugin.getLogger().warning("[VaultPostgres] Failed to publish NOTIFY event: " + e.getMessage());
            }
        });
    }

    public void updateBalance(UUID uuid, String currency, double balance) {
        String payload = serverId + ":" + uuid.toString() + ":" + (currency == null ? "default" : currency) + ":" + balance;
        publishSyncMessage(payload);
    }

    public void setBankBalance(String bankName, double balance) {
        String payload = serverId + ":BANK:" + bankName + ":" + balance;
        publishSyncMessage(payload);
    }

    public void freezePlayer(UUID uuid, String reason) {
        String payload = serverId + ":FREEZE:" + uuid.toString() + ":" + (reason == null ? "Frozen" : reason);
        publishSyncMessage(payload);
    }

    public void unfreezePlayer(UUID uuid) {
        String payload = serverId + ":UNFREEZE:" + uuid.toString();
        publishSyncMessage(payload);
    }

    private void closeListenerConnection() {
        if (listenerConnection != null) {
            try {
                listenerConnection.close();
            } catch (Exception ignored) {}
            listenerConnection = null;
        }
    }

    public void close() {
        running = false;
        closeListenerConnection();
        executor.shutdownNow();
        if (instance == this) {
            instance = null;
        }
        plugin.getLogger().info("[VaultPostgres] PostgreSQL LISTEN/NOTIFY synchronization manager stopped.");
    }
}
