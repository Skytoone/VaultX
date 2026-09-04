package net.milkbowl.vault.bootstrap;

import net.milkbowl.vault.Vault;
import net.milkbowl.vault.economy.OptimizedEconomy;
import net.milkbowl.vault.util.ConfigUpdater;
import net.milkbowl.vault.util.UpdateChecker;

import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;

import java.io.File;

/**
 * Handles plugin startup, config migration, auto-update checking, and Bukkit ServicesManager registrations.
 */
public class PluginBootstrap {

    private final Vault plugin;

    public PluginBootstrap(Vault plugin) {
        this.plugin = plugin;
    }

    public void initializeConfig() {
        File vaultFolder = plugin.getDataFolder();
        if (!vaultFolder.exists()) {
            File vaultXFolder = new File(vaultFolder.getParentFile(), "VaultX");
            if (vaultXFolder.exists()) {
                plugin.getLogger().info("Found existing VaultX configuration folder. Migrating to Vault...");
                if (vaultXFolder.renameTo(vaultFolder)) {
                    plugin.getLogger().info("VaultX configuration folder successfully migrated to Vault.");
                } else {
                    plugin.getLogger().warning("Failed to migrate VaultX configuration folder to Vault.");
                }
            }
        }

        File configFile = new File(plugin.getDataFolder(), "config.yml");
        if (configFile.exists()) {
            try {
                ConfigUpdater.updateConfig(configFile, plugin.getResource("config.yml"));
            } catch (Exception e) {
                plugin.getLogger().warning("Could not auto-update config.yml: " + e.getMessage());
            }
            plugin.reloadConfig();
        } else {
            plugin.saveDefaultConfig();
        }
    }

    public void checkUpdates() {
        if (plugin.getConfig().getBoolean("settings.check-updates", true)) {
            new UpdateChecker(plugin, 135253).getVersion(version -> {
                if (isNewerVersion(plugin.getDescription().getVersion(), version)) {
                    plugin.getLogger().warning("A new update is available (" + version
                            + ")! Download it here: https://www.spigotmc.org/resources/vaultx.135253/");
                } else {
                    plugin.getLogger().info("The plugin is up to date.");
                }
            });
        }
    }

    public void initializeStorageProvider() {
        String syncProvider = plugin.getConfig().getString("storage.sync-provider", "redis").toLowerCase();
        if ("postgresql".equals(syncProvider) || "postgres".equals(syncProvider)) {
            String host = plugin.getConfig().getString("storage.postgresql.host", "127.0.0.1");
            int port = plugin.getConfig().getInt("storage.postgresql.port", 5432);
            String db = plugin.getConfig().getString("storage.postgresql.database", "vaultx");
            String username = plugin.getConfig().getString("storage.postgresql.username", "postgres");
            String password = plugin.getConfig().getString("storage.postgresql.password", "");
            String serverId = plugin.getConfig().getString("redis.server-id", "server-1");
            String channel = plugin.getConfig().getString("storage.postgresql.channel", "vaultx_sync");
            String properties = plugin.getConfig().getString("storage.postgresql.properties", "");

            new net.milkbowl.vault.redis.VaultPostgresManager(plugin, host, port, db, username, password, serverId, channel, properties);
        } else if (plugin.getConfig().getBoolean("redis.enabled", false)) {
            String host = plugin.getConfig().getString("redis.host", "127.0.0.1");
            int port = plugin.getConfig().getInt("redis.port", 6379);
            String password = plugin.getConfig().getString("redis.password", "");
            String serverId = plugin.getConfig().getString("redis.server-id", "server-1");
            String channel = plugin.getConfig().getString("redis.channel", "vaultx:economy:sync");

            new net.milkbowl.vault.redis.VaultRedisManager(plugin, host, port, password, serverId, channel);
        }
    }

    public void initializeIntegrations() {
        if (org.bukkit.Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            net.milkbowl.vault.util.VaultPlaceholderExpansion expansion = new net.milkbowl.vault.util.VaultPlaceholderExpansion(plugin);
            expansion.register();
            plugin.getServer().getPluginManager().registerEvents(expansion, plugin);
        }

        if (org.bukkit.Bukkit.getPluginManager().isPluginEnabled("Skript")) {
            try {
                net.milkbowl.vault.skript.SkriptVaultXHook.register();
                plugin.getLogger().info("Successfully registered custom Skript currency addon.");
            } catch (Throwable e) {
                plugin.getLogger().warning("Failed to register custom Skript currency addon: " + e.getMessage());
            }
        }
    }

    public void registerServices(ServiceRegistry registry) {
        ServicesManager sm = plugin.getServer().getServicesManager();
        if (sm == null) return;

        if (registry.getExchangeRateManager() != null) {
            sm.register(net.milkbowl.vault.economy.CurrencyExchangeAPI.class, registry.getExchangeRateManager(), plugin, ServicePriority.Normal);
        }

        var wrappedEconomies = registry.getWrappedEconomies();
        if (wrappedEconomies != null && !wrappedEconomies.isEmpty()) {
            OptimizedEconomy primaryEcon = wrappedEconomies.get(0);

            var leaderboardService = new net.milkbowl.vault.economy.service.LeaderboardEconomyService(primaryEcon.getAsyncExecutor());
            registry.setLeaderboardService(leaderboardService);
            sm.register(net.milkbowl.vault.economy.VaultLeaderboardAPI.class, leaderboardService, plugin, ServicePriority.Normal);

            var batchService = new net.milkbowl.vault.economy.service.BatchTransactionService(primaryEcon, primaryEcon.getAsyncExecutor());
            registry.setBatchTransactionService(batchService);
            sm.register(net.milkbowl.vault.economy.VaultBatchTransactionAPI.class, batchService, plugin, ServicePriority.Normal);

            var asyncService = new net.milkbowl.vault.economy.service.AsyncEconomyService(primaryEcon, primaryEcon.getAsyncExecutor());
            registry.setAsyncEconomyService(asyncService);
            sm.register(net.milkbowl.vault.economy.VaultAsyncEconomy.class, asyncService, plugin, ServicePriority.Normal);

            var inflationService = new net.milkbowl.vault.economy.service.InflationEconomyService(primaryEcon.getWealthTaxManager());
            registry.setInflationEconomyService(inflationService);
            sm.register(net.milkbowl.vault.economy.VaultInflationAPI.class, inflationService, plugin, ServicePriority.Normal);

            var analyticsService = new net.milkbowl.vault.economy.service.AnalyticsService(primaryEcon.getAsyncExecutor());
            registry.setAnalyticsService(analyticsService);
            sm.register(net.milkbowl.vault.economy.VaultAnalyticsAPI.class, analyticsService, plugin, ServicePriority.Normal);

            var auditService = new net.milkbowl.vault.economy.service.AuditService(primaryEcon.getAsyncExecutor());
            registry.setAuditService(auditService);
            sm.register(net.milkbowl.vault.economy.VaultAuditAPI.class, auditService, plugin, ServicePriority.Normal);

            sm.register(net.milkbowl.vault.economy.VaultFormatAPI.class, primaryEcon.getCurrencyService(), plugin, ServicePriority.Normal);

            sm.register(net.milkbowl.vault.economy.VaultMailboxAPI.class, primaryEcon.getMailboxService(), plugin, ServicePriority.Normal);
            sm.register(net.milkbowl.vault.economy.VaultBoosterAPI.class, primaryEcon.getBoosterService(), plugin, ServicePriority.Normal);
            sm.register(net.milkbowl.vault.economy.VaultLockAPI.class, primaryEcon.getEconomyLockService(), plugin, ServicePriority.Normal);
            sm.register(net.milkbowl.vault.economy.VaultSubscriptionAPI.class, primaryEcon.getSubscriptionService(), plugin, ServicePriority.Normal);
            sm.register(net.milkbowl.vault.economy.VaultCurrencyRegistry.class, primaryEcon.getCurrencyService(), plugin, ServicePriority.Normal);
            sm.register(net.milkbowl.vault.economy.VaultCheckAPI.class, primaryEcon.getBankCheckService(), plugin, ServicePriority.Normal);
            sm.register(net.milkbowl.vault.economy.VaultLoanAPI.class, primaryEcon.getLoanEconomyService(), plugin, ServicePriority.Normal);
            sm.register(net.milkbowl.vault.economy.VaultMilestoneAPI.class, primaryEcon.getMilestoneService(), plugin, ServicePriority.Normal);
            sm.register(net.milkbowl.vault.economy.VaultCryptoAPI.class, primaryEcon.getCryptoManager(), plugin, ServicePriority.Normal);
            sm.register(net.milkbowl.vault.economy.VaultAuctionAPI.class, primaryEcon.getAuctionManager(), plugin, ServicePriority.Normal);
            sm.register(net.milkbowl.vault.economy.VaultStakingAPI.class, primaryEcon.getStakingManager(), plugin, ServicePriority.Normal);
            sm.register(net.milkbowl.vault.economy.VaultTaxAPI.class, primaryEcon.getWealthTaxManager(), plugin, ServicePriority.Normal);
            sm.register(net.milkbowl.vault.economy.VaultCreditAPI.class, primaryEcon.getCreditManager(), plugin, ServicePriority.Normal);

            var adv = primaryEcon.getAdvancedBankingService();
            if (adv != null) {
                sm.register(net.milkbowl.vault.economy.VaultSnapshotAPI.class, adv, plugin, ServicePriority.Normal);
                sm.register(net.milkbowl.vault.economy.VaultMultiSigAPI.class, adv, plugin, ServicePriority.Normal);
                sm.register(net.milkbowl.vault.economy.VaultAMMExchangeAPI.class, adv, plugin, ServicePriority.Normal);
                sm.register(net.milkbowl.vault.economy.VaultSmartContractAPI.class, adv, plugin, ServicePriority.Normal);
                sm.register(net.milkbowl.vault.economy.VaultStandingOrderAPI.class, adv, plugin, ServicePriority.Normal);
                sm.register(net.milkbowl.vault.economy.VaultCashbackLoyaltyAPI.class, adv, plugin, ServicePriority.Normal);
            }
        }
    }

    private boolean isNewerVersion(String currentVersion, String remoteVersion) {
        if (currentVersion == null || remoteVersion == null)
            return false;
        String[] currentParts = currentVersion.split("-")[0].split("\\.");
        String[] remoteParts = remoteVersion.split("-")[0].split("\\.");
        int length = Math.max(currentParts.length, remoteParts.length);

        for (int i = 0; i < length; i++) {
            int currentPart = i < currentParts.length ? parseVersionPart(currentParts[i]) : 0;
            int remotePart = i < remoteParts.length ? parseVersionPart(remoteParts[i]) : 0;

            if (remotePart > currentPart) {
                return true;
            } else if (remotePart < currentPart) {
                return false;
            }
        }
        return false;
    }

    private int parseVersionPart(String part) {
        try {
            return Integer.parseInt(part.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
