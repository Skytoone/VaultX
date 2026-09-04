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

    public void registerServices(ServiceRegistry registry) {
        ServicesManager sm = plugin.getServer().getServicesManager();
        if (sm == null) return;

        if (registry.getExchangeRateManager() != null) {
            sm.register(net.milkbowl.vault.economy.CurrencyExchangeAPI.class, registry.getExchangeRateManager(), plugin, ServicePriority.Normal);
        }

        var wrappedEconomies = registry.getWrappedEconomies();
        if (wrappedEconomies != null && !wrappedEconomies.isEmpty()) {
            OptimizedEconomy primaryEcon = wrappedEconomies.get(0);
            sm.register(net.milkbowl.vault.economy.VaultLeaderboardAPI.class, primaryEcon, plugin, ServicePriority.Normal);
            sm.register(net.milkbowl.vault.economy.VaultBatchTransactionAPI.class, primaryEcon, plugin, ServicePriority.Normal);
            sm.register(net.milkbowl.vault.economy.VaultFormatAPI.class, primaryEcon, plugin, ServicePriority.Normal);
            sm.register(net.milkbowl.vault.economy.VaultMailboxAPI.class, primaryEcon, plugin, ServicePriority.Normal);
            sm.register(net.milkbowl.vault.economy.VaultBoosterAPI.class, primaryEcon, plugin, ServicePriority.Normal);
            sm.register(net.milkbowl.vault.economy.VaultLockAPI.class, primaryEcon, plugin, ServicePriority.Normal);
            sm.register(net.milkbowl.vault.economy.VaultSubscriptionAPI.class, primaryEcon, plugin, ServicePriority.Normal);
            sm.register(net.milkbowl.vault.economy.VaultAnalyticsAPI.class, primaryEcon, plugin, ServicePriority.Normal);
            sm.register(net.milkbowl.vault.economy.VaultCurrencyRegistry.class, primaryEcon, plugin, ServicePriority.Normal);
            sm.register(net.milkbowl.vault.economy.VaultAuditAPI.class, primaryEcon, plugin, ServicePriority.Normal);
            sm.register(net.milkbowl.vault.economy.VaultCheckAPI.class, primaryEcon, plugin, ServicePriority.Normal);
            sm.register(net.milkbowl.vault.economy.VaultLoanAPI.class, primaryEcon, plugin, ServicePriority.Normal);
            sm.register(net.milkbowl.vault.economy.VaultInflationAPI.class, primaryEcon, plugin, ServicePriority.Normal);
            sm.register(net.milkbowl.vault.economy.VaultMilestoneAPI.class, primaryEcon, plugin, ServicePriority.Normal);
            sm.register(net.milkbowl.vault.economy.VaultCryptoAPI.class, primaryEcon, plugin, ServicePriority.Normal);
            sm.register(net.milkbowl.vault.economy.VaultSnapshotAPI.class, primaryEcon, plugin, ServicePriority.Normal);
            sm.register(net.milkbowl.vault.economy.VaultAsyncEconomy.class, primaryEcon, plugin, ServicePriority.Normal);
            sm.register(net.milkbowl.vault.economy.VaultMultiSigAPI.class, primaryEcon, plugin, ServicePriority.Normal);
            sm.register(net.milkbowl.vault.economy.VaultAMMExchangeAPI.class, primaryEcon, plugin, ServicePriority.Normal);
            sm.register(net.milkbowl.vault.economy.VaultSmartContractAPI.class, primaryEcon, plugin, ServicePriority.Normal);
            sm.register(net.milkbowl.vault.economy.VaultStandingOrderAPI.class, primaryEcon, plugin, ServicePriority.Normal);
            sm.register(net.milkbowl.vault.economy.VaultCashbackLoyaltyAPI.class, primaryEcon, plugin, ServicePriority.Normal);
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
