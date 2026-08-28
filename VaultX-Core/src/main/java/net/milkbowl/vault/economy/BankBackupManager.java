package net.milkbowl.vault.economy;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import java.io.File;
import java.io.IOException;
import java.util.Map;
import net.milkbowl.vault.redis.VaultRedisManager;

public class BankBackupManager {

    private final Plugin plugin;
    private final File backupFile;
    private final int intervalMinutes;

    private final Object fileLock = new Object();
    private org.bukkit.scheduler.BukkitTask backupTask;

    public BankBackupManager(Plugin plugin, boolean enabled, int intervalMinutes) {
        this.plugin = plugin;
        this.intervalMinutes = intervalMinutes;
        this.backupFile = new File(plugin.getDataFolder(), "banks_backup.yml");
        
        if (enabled) {
            loadBackup();
            startTask();
        }
    }

    public void close() {
        if (backupTask != null) {
            try {
                backupTask.cancel();
            } catch (Exception ignored) {}
            backupTask = null;
        }
        performSave();
    }

    private void loadBackup() {
        FileConfiguration backupConfig;
        synchronized (fileLock) {
            if (!backupFile.exists()) return;
            backupConfig = YamlConfiguration.loadConfiguration(backupFile);
        }
        
        net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
            try {
                VaultRedisManager redis = VaultRedisManager.getInstance();
                synchronized (fileLock) {
                    if (backupConfig != null && backupConfig.contains("banks") && backupConfig.getConfigurationSection("banks") != null) {
                        for (String bank : backupConfig.getConfigurationSection("banks").getKeys(false)) {
                            double bal = backupConfig.getDouble("banks." + bank);
                            if (redis != null && redis.isOnline()) {
                                // Restore if redis doesn't have it (assume 0 means empty or just created)
                                if (redis.getBankBalance(bank) == 0.0 && bal > 0.0) {
                                    redis.setBankBalance(bank, bal);
                                    plugin.getLogger().info("[Vault BankBackup] Restored bank " + bank + " (" + bal + ") from backup to Redis.");
                                }
                            } else {
                                // Restore to Local Database if Redis is offline or disabled
                                if (net.milkbowl.vault.Vault.getFailoverManager() != null && net.milkbowl.vault.Vault.getFailoverManager().getBankBalance(bank) == 0.0 && bal > 0.0) {
                                    net.milkbowl.vault.Vault.getFailoverManager().saveBankBalance(bank, bal);
                                    plugin.getLogger().info("[Vault BankBackup] Restored bank " + bank + " (" + bal + ") from backup to Local Database.");
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().warning("[Vault BankBackup] Failed to restore backup: " + e.getMessage());
            }
        });
    }

    private void performSave() {
        try {
            org.bukkit.plugin.RegisteredServiceProvider<Economy> rsp = plugin.getServer().getServicesManager().getRegistration(Economy.class);
            if (rsp != null && rsp.getProvider() instanceof OptimizedEconomy) {
                OptimizedEconomy econ = (OptimizedEconomy) rsp.getProvider();
                Map<String, Double> banks = econ.getBankBalances();
                
                YamlConfiguration taskConfig = new YamlConfiguration();
                for (Map.Entry<String, Double> entry : banks.entrySet()) {
                    taskConfig.set("banks." + entry.getKey(), entry.getValue());
                }
                synchronized (fileLock) {
                    taskConfig.save(backupFile);
                }
            }
        } catch (IOException e) {
            plugin.getLogger().warning("[Vault BankBackup] Failed to save backup: " + e.getMessage());
        } catch (Exception ignored) {}
    }

    private void startTask() {
        long ticks = Math.max(1200L, (long) intervalMinutes * 60L * 20L);
        backupTask = net.milkbowl.vault.util.FoliaScheduler.runTimerAsync(plugin, this::performSave, ticks, ticks);
    }
}

