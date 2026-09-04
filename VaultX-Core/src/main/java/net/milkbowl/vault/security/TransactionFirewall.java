package net.milkbowl.vault.security;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListSet;
import net.milkbowl.vault.economy.VaultFirewallAPI;
import net.milkbowl.vault.economy.events.VaultPlayerFreezeEvent;

public class TransactionFirewall implements VaultFirewallAPI {

    private final Plugin plugin;
    private final File frozenFile;
    private final File auditLogFile;
    private YamlConfiguration frozenConfig;
    private final FirewallWebhookNotifier webhookNotifier;
    private final AnomalyDetector anomalyDetector;
    private final Object configLock = new Object();
    private final Object auditLogLock = new Object();

    private boolean enabled;
    private double maxSingleTransaction;
    private boolean autoFreeze;
    private boolean spikeEnabled;
    private double spikeFactor;
    private int timeWindowSeconds;
    private double minimumThreshold;
    private double largeTransactionThreshold;
    private boolean inflationAlertEnabled;
    private double inflationAlertPercentThreshold;

    private static class SupplySnapshot {
        final long timestamp;
        final double supply;
        SupplySnapshot(long timestamp, double supply) {
            this.timestamp = timestamp;
            this.supply = supply;
        }
    }
    private final List<SupplySnapshot> supplySnapshots = new ArrayList<>();
    private final Set<UUID> frozenPlayers = new ConcurrentSkipListSet<>();
    private org.bukkit.scheduler.BukkitTask inflationTask;

    public TransactionFirewall(Plugin plugin) {
        this.plugin = plugin;
        this.frozenFile = new File(plugin.getDataFolder(), "frozen_players.yml");
        this.auditLogFile = new File(plugin.getDataFolder(), "security_audit.log");
        this.webhookNotifier = new FirewallWebhookNotifier(plugin);
        this.anomalyDetector = new AnomalyDetector(plugin);
        loadConfig();
        loadFrozenPlayers();
        startInflationMonitor();
    }

    public void close() {
        if (inflationTask != null) {
            try {
                inflationTask.cancel();
            } catch (Exception ignored) {}
            inflationTask = null;
        }
        if (webhookNotifier != null) {
            try {
                webhookNotifier.close();
            } catch (Exception ignored) {}
        }
        anomalyDetector.clear();
        synchronized (supplySnapshots) {
            supplySnapshots.clear();
        }
    }

    public void loadConfig() {
        this.enabled = plugin.getConfig().getBoolean("security.firewall.enabled", true);
        this.maxSingleTransaction = plugin.getConfig().getDouble("security.firewall.max-single-transaction", 10000000.0);
        this.autoFreeze = plugin.getConfig().getBoolean("security.firewall.auto-freeze", true);
        this.spikeEnabled = plugin.getConfig().getBoolean("security.firewall.spike-detection.enabled", true);
        this.spikeFactor = plugin.getConfig().getDouble("security.firewall.spike-detection.factor", 5.0);
        this.timeWindowSeconds = plugin.getConfig().getInt("security.firewall.spike-detection.time-window-seconds", 60);
        this.minimumThreshold = plugin.getConfig().getDouble("security.firewall.spike-detection.minimum-threshold", 10000.0);
        this.largeTransactionThreshold = plugin.getConfig().getDouble("security.firewall.large-transaction-threshold", 500000.0);
        this.inflationAlertEnabled = plugin.getConfig().getBoolean("security.firewall.inflation-alert.enabled", true);
        this.inflationAlertPercentThreshold = plugin.getConfig().getDouble("security.firewall.inflation-alert.threshold-percent", 5.0);
        if (webhookNotifier != null) {
            webhookNotifier.loadConfig();
        }
    }

    public DiscordWebhookNotifier getWebhookNotifier() {
        return webhookNotifier.getDiscordNotifier();
    }

    private void loadFrozenPlayers() {
        synchronized (configLock) {
            if (!frozenFile.exists()) {
                frozenConfig = new YamlConfiguration();
                return;
            }
            frozenConfig = YamlConfiguration.loadConfiguration(frozenFile);
            List<String> list = frozenConfig.getStringList("frozen");
            for (String s : list) {
                try {
                    frozenPlayers.add(UUID.fromString(s));
                } catch (IllegalArgumentException ignored) {}
            }
        }
    }

    private void saveFrozenPlayers() {
        final List<String> list = new ArrayList<>();
        for (UUID uuid : frozenPlayers) {
            list.add(uuid.toString());
        }
        net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
            synchronized (configLock) {
                frozenConfig.set("frozen", list);
                try {
                    frozenConfig.save(frozenFile);
                } catch (IOException e) {
                    plugin.getLogger().warning("[Vault Security] Failed to save frozen players: " + e.getMessage());
                }
            }
        });
    }

    public boolean isFrozen(OfflinePlayer player) {
        return player != null && frozenPlayers.contains(player.getUniqueId());
    }

    public void invalidateCache(OfflinePlayer player) {
        anomalyDetector.invalidateCache(player);
    }

    public void freezePlayer(OfflinePlayer player, String reason) {
        if (player == null) return;
        
        if (player.isOp()) {
            return;
        }
        if (player.isOnline() && player.getPlayer() != null) {
            if (player.getPlayer().hasPermission("vault.firewall.bypass") || player.getPlayer().hasPermission("vault.admin")) {
                return;
            }
        }
        
        freezePlayerLocal(player.getUniqueId(), reason);

        net.milkbowl.vault.redis.VaultRedisManager redis = net.milkbowl.vault.redis.VaultRedisManager.getInstance();
        if (redis != null) {
            redis.freezePlayerInRedis(player.getUniqueId(), reason);
        }
        net.milkbowl.vault.redis.VaultPostgresManager postgres = net.milkbowl.vault.redis.VaultPostgresManager.getInstance();
        if (postgres != null) {
            postgres.freezePlayer(player.getUniqueId(), reason);
        }
    }

    public void freezePlayerLocal(UUID uuid, String reason) {
        if (uuid == null) return;
        OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);

        VaultPlayerFreezeEvent event = new VaultPlayerFreezeEvent(op, reason);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return;
        }

        frozenPlayers.add(uuid);
        saveFrozenPlayers();
        
        String name = net.milkbowl.vault.util.UUIDCache.getName(uuid);
        if (name == null) name = uuid.toString();
        String msg = net.milkbowl.vault.Vault.getMessage("firewall.admin-notify-frozen", "&c&l[VaultX Security] &cPlayer &e%player% &chas been frozen! Reason: &7%reason%")
                .replace("%player%", name)
                .replace("%reason%", reason);
        Bukkit.getConsoleSender().sendMessage(msg);
        for (org.bukkit.entity.Player online : Bukkit.getOnlinePlayers()) {
            if (online.hasPermission("vault.admin")) {
                online.sendMessage(msg);
            }
        }
        logAuditAsync(op, 0, "FREEZE", "Frozen due to: " + reason);
        webhookNotifier.sendAlertAsync("FREEZE", op, "Frozen due to: " + reason, 15158332);
    }

    public void unfreezePlayer(OfflinePlayer player) {
        if (player == null) return;
        unfreezePlayerLocal(player.getUniqueId());

        net.milkbowl.vault.redis.VaultRedisManager redis = net.milkbowl.vault.redis.VaultRedisManager.getInstance();
        if (redis != null) {
            redis.unfreezePlayerInRedis(player.getUniqueId());
        }
    }

    public void unfreezePlayerLocal(UUID uuid) {
        if (uuid == null) return;
        frozenPlayers.remove(uuid);
        saveFrozenPlayers();
        
        OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
        String name = net.milkbowl.vault.util.UUIDCache.getName(uuid);
        if (name == null) name = uuid.toString();
        String msg = net.milkbowl.vault.Vault.getMessage("firewall.admin-notify-unfrozen", "&a&l[VaultX Security] &aPlayer &e%player% &ahas been unfrozen.")
                .replace("%player%", name);
        Bukkit.getConsoleSender().sendMessage(msg);
        for (org.bukkit.entity.Player online : Bukkit.getOnlinePlayers()) {
            if (online.hasPermission("vault.admin")) {
                online.sendMessage(msg);
            }
        }
        logAuditAsync(op, 0, "UNFREEZE", "Unfrozen by admin");
        webhookNotifier.sendAlertAsync("UNFREEZE", op, "Unfrozen by admin", 3066993);
    }

    public void purgePlayer(UUID uuid) {
        anomalyDetector.purgePlayer(uuid);
    }

    public Set<UUID> getLocalFrozenPlayers() {
        return new HashSet<>(frozenPlayers);
    }

    public boolean checkTransaction(OfflinePlayer player, double amount, String type, double currentBalance) {
        if (!enabled || player == null) return true;

        if (player.isOp()) {
            return true;
        }
        if (player.isOnline() && player.getPlayer() != null) {
            if (player.getPlayer().hasPermission("vault.firewall.bypass") || player.getPlayer().hasPermission("vault.admin")) {
                return true;
            }
        }

        // 1. Check if frozen
        if (frozenPlayers.contains(player.getUniqueId())) {
            if (player.isOnline() && player.getPlayer() != null) {
                player.getPlayer().sendMessage(net.milkbowl.vault.Vault.getMessage("firewall.player-frozen", "&c&l[VaultX] &cYour transactions are frozen by the security systems. Contact an admin."));
            }
            return false;
        }

        // 2. Limit per transaction
        if (amount > maxSingleTransaction) {
            String reason = "Single transaction limit exceeded: " + amount + " (max " + maxSingleTransaction + ")";
            logAuditAsync(player, amount, "BLOCKED_" + type, reason);
            webhookNotifier.sendAlertAsync("TRANSACTION_BLOCKED", player, reason, 15158332);
            if (autoFreeze) {
                freezePlayer(player, reason);
            }
            return false;
        }

        // 3. Spike detection
        if (spikeEnabled && ("DEPOSIT".equalsIgnoreCase(type) || "WITHDRAW".equalsIgnoreCase(type)) && player.isOnline()) {
            if (anomalyDetector.isSpikeDetected(player, amount, type, currentBalance, timeWindowSeconds, minimumThreshold, spikeFactor)) {
                String reason = "Spike detected (" + type.toUpperCase() + ")! Activity exceeded threshold in window (" + timeWindowSeconds + "s)";
                logAuditAsync(player, amount, "SPIKE_DETECTED", reason);
                webhookNotifier.sendAlertAsync("SPIKE_DETECTED", player, reason, 15158332);
                if (autoFreeze) {
                    freezePlayer(player, reason);
                    return false;
                }
            }
        }

        double valueInDefault = amount;
        if (type != null && type.contains("_")) {
            String cur = type.substring(type.indexOf("_") + 1);
            net.milkbowl.vault.economy.ExchangeRateManager rateManager = net.milkbowl.vault.Vault.getExchangeRateManager();
            if (rateManager != null) {
                valueInDefault = amount * rateManager.getRate(cur);
            }
        }

        if (valueInDefault >= largeTransactionThreshold) {
            String details = "Transaction of type " + type + " has value " + amount 
                    + " (equivalent to " + String.format("%.2f", valueInDefault) + " default currency, threshold: " + largeTransactionThreshold + ")";
            logAuditAsync(player, amount, "LARGE_TRANSACTION", details);
            if (webhookNotifier != null) {
                webhookNotifier.sendAlertAsync("LARGE_TRANSACTION", player, details, 15859712);
            }
        }

        return true;
    }

    public void recordTransaction(OfflinePlayer player, double amount) {
        if (!enabled || !spikeEnabled || player == null || !player.isOnline()) return;
        anomalyDetector.recordTransaction(player, amount, timeWindowSeconds);
    }

    public void notifyRateLimit(OfflinePlayer player, int count, int maxTps, int cooldownSeconds) {
        String reason = "Exceeded rate limit: " + count + " transactions/sec (limit is " + maxTps + "). Account rate-limited for " + cooldownSeconds + "s.";
        webhookNotifier.sendAlertAsync("RATE_LIMIT_EXCEEDED", player, reason, 15859712);
    }

    private void logAuditAsync(OfflinePlayer player, double amount, String action, String details) {
        UUID uuid = player != null ? player.getUniqueId() : null;
        String uuidStr = uuid != null ? uuid.toString() : "null";
        
        net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
            String name = (player != null && player.getName() != null) ? player.getName() : "Unknown";
            if (net.milkbowl.vault.Vault.getFailoverManager() != null) {
                net.milkbowl.vault.Vault.getFailoverManager().saveSecurityAudit(uuid, name, amount, action, details);
            }
            
            synchronized (auditLogLock) {
                try {
                    if (auditLogFile.exists() && auditLogFile.length() > 10 * 1024 * 1024L) {
                        File oldFile = new File(auditLogFile.getParentFile(), "security_audit.log.old");
                        if (oldFile.exists()) {
                            oldFile.delete();
                        }
                        auditLogFile.renameTo(oldFile);
                    }
                    if (!auditLogFile.exists()) {
                        auditLogFile.createNewFile();
                    }
                    try (PrintWriter out = new PrintWriter(new java.io.BufferedWriter(new FileWriter(auditLogFile, true)))) {
                        String timeStamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
                        out.printf("[%s] [%s] Player: %s (%s) | Amount: %.2f | Details: %s%n",
                                timeStamp, action, name, uuidStr, amount, details);
                    }
                } catch (IOException e) {
                    plugin.getLogger().warning("[Vault Security] Failed to write to audit log: " + e.getMessage());
                }
            }
        });
    }

    public void recordTransfer(OfflinePlayer sender, OfflinePlayer receiver, double amount) {
        if (sender == null || receiver == null || !enabled) return;

        net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
            List<UUID> cyclePath = anomalyDetector.checkCircularTransfer(sender, receiver, amount, timeWindowSeconds, minimumThreshold);
            if (cyclePath != null) {
                net.milkbowl.vault.util.FoliaScheduler.runSync(plugin, () -> {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < cyclePath.size(); i++) {
                        UUID u = cyclePath.get(i);
                        String name = net.milkbowl.vault.util.UUIDCache.getName(u);
                        if (name == null) name = u.toString();
                        if (i > 0) sb.append(" -> ");
                        sb.append(name);
                    }

                    String topology = sb.toString();
                    String details = "Circular transfer path detected: " + topology + " (Amount: " + amount + ")";
                    logAuditAsync(sender, amount, "CIRCULAR_TRANSFER", details);
                    webhookNotifier.sendAlertAsync("CIRCULAR_TRANSFER", sender, details, 15158332);

                    if (autoFreeze) {
                        for (UUID playerUuid : cyclePath) {
                            freezePlayer(Bukkit.getOfflinePlayer(playerUuid), "Suspicious circular transfer: " + topology);
                        }
                    }
                });
            }
        });
    }

    private void startInflationMonitor() {
        this.inflationTask = net.milkbowl.vault.util.FoliaScheduler.runTimerAsync(plugin, () -> {
            anomalyDetector.pruneExpiredData(timeWindowSeconds);

            if (!inflationAlertEnabled) {
                return;
            }
            net.milkbowl.vault.redis.LocalFailoverManager fm = net.milkbowl.vault.Vault.getFailoverManager();
            if (fm == null) return;
            
            net.milkbowl.vault.redis.VaultRedisManager redis = net.milkbowl.vault.redis.VaultRedisManager.getInstance();
            boolean redisOnline = redis != null && redis.isOnline();
            
            double currentSupply = 0.0;
            if (redisOnline) {
                net.milkbowl.vault.redis.VaultRedisManager.CurrencyStats stats = redis.getGlobalCurrencyStats("default");
                if (stats != null) {
                    currentSupply = stats.totalMoney;
                }
            }
            if (currentSupply == 0.0) {
                net.milkbowl.vault.redis.LocalFailoverManager.LocalCurrencyStats stats = fm.getLocalCurrencyStats("default");
                if (stats != null) {
                    currentSupply = stats.totalMoney;
                }
            }

            if (currentSupply <= 0.0) {
                return;
            }

            long now = System.currentTimeMillis();
            long oneHourAgo = now - 3600000L;

            synchronized (supplySnapshots) {
                supplySnapshots.removeIf(s -> s.timestamp < now - 4500000L);

                SupplySnapshot baseline = null;
                for (SupplySnapshot s : supplySnapshots) {
                    if (s.timestamp >= oneHourAgo) {
                        if (baseline == null || s.timestamp < baseline.timestamp) {
                            baseline = s;
                        }
                    }
                }

                if (baseline != null && baseline.supply > 0) {
                    double increasePercent = ((currentSupply - baseline.supply) / baseline.supply) * 100.0;
                    if (increasePercent >= inflationAlertPercentThreshold) {
                        String details = String.format("Money supply increased by %.2f%% in 1 hour (Baseline: %.2f, Current: %.2f)", 
                                increasePercent, baseline.supply, currentSupply);
                        plugin.getLogger().warning("[VaultX Security] INFLATION SPIKE ALERT: " + details);
                        if (webhookNotifier != null) {
                            webhookNotifier.sendAlertAsync("INFLATION_SPIKE", null, details, 15158332);
                        }
                    }
                }

                supplySnapshots.add(new SupplySnapshot(now, currentSupply));
            }
        }, 1200L, 6000L);
    }
}
