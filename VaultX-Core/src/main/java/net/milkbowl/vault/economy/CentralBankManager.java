package net.milkbowl.vault.economy;

import net.milkbowl.vault.Vault;
import net.milkbowl.vault.redis.LocalFailoverManager;
import net.milkbowl.vault.redis.VaultRedisManager;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public class CentralBankManager {

    private final Plugin plugin;
    private final ExchangeRateManager exchangeRateManager;
    private BukkitTask mainTask;

    private boolean enabled = false;
    private boolean interestEnabled = false;
    private int interestInterval = 60;
    private double bankBonusPercent = 0.01;
    private final Map<String, Double> interestRates = new HashMap<>();

    private boolean taxesEnabled = false;
    private double payTaxPercent = 2.0;
    private double exchangeTaxPercent = 1.0;
    private String treasuryAccount = "tresor_public";

    private boolean wealthTaxEnabled = false;
    private double wealthTaxThreshold = 1000000.0;
    private double wealthTaxPercent = 0.05;

    public CentralBankManager(Plugin plugin, ExchangeRateManager exchangeRateManager) {
        this.plugin = plugin;
        this.exchangeRateManager = exchangeRateManager;
        loadConfig();
        if (enabled && (interestEnabled || wealthTaxEnabled)) {
            startScheduler();
        }
    }

    public void close() {
        if (mainTask != null) {
            mainTask.cancel();
            mainTask = null;
        }
    }

    private void loadConfig() {
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("central-bank");
        if (sec != null) {
            this.interestEnabled = sec.getBoolean("interest.enabled", false);
            this.interestInterval = sec.getInt("interest.interval-minutes", 60);
            this.bankBonusPercent = sec.getDouble("interest.bank-bonus-percent", 0.01);

            ConfigurationSection rateSec = sec.getConfigurationSection("interest.rates");
            if (rateSec != null) {
                for (String key : rateSec.getKeys(false)) {
                    interestRates.put(key.toLowerCase(), rateSec.getDouble(key, 0.0));
                }
            }

            this.taxesEnabled = sec.getBoolean("taxes.enabled", false);
            this.payTaxPercent = sec.getDouble("taxes.pay-tax-percent", 2.0);
            this.exchangeTaxPercent = sec.getDouble("taxes.exchange-tax-percent", 1.0);
            this.treasuryAccount = sec.getString("taxes.treasury-account", "tresor_public");

            this.wealthTaxEnabled = sec.getBoolean("wealth-tax.enabled", false);
            this.wealthTaxThreshold = sec.getDouble("wealth-tax.threshold", 1000000.0);
            this.wealthTaxPercent = sec.getDouble("wealth-tax.tax-percent", 0.05);

            this.enabled = true;
        }
    }

    private void startScheduler() {
        long ticks = interestInterval * 60L * 20L;
        mainTask = net.milkbowl.vault.util.FoliaScheduler.runTimerAsync(plugin, this::processCentralBankOperations, ticks,
                ticks);
    }

    private void processCentralBankOperations() {
        LocalFailoverManager failover = Vault.getFailoverManager();
        if (failover == null) return;

        // Prevent duplicate central bank operations across multi-server network
        long now = System.currentTimeMillis();
        String lastRunStr = failover.getSetting("last_central_bank_run");
        if (lastRunStr != null) {
            try {
                long lastRun = Long.parseLong(lastRunStr);
                long intervalMs = interestInterval * 60L * 1000L;
                // If last run was less than 90% of the interval ago, skip to prevent double interest/tax
                if (now - lastRun < (intervalMs * 0.9)) {
                    plugin.getLogger().info("[Vault Central Bank] Central bank operations already processed recently by another server. Skipping.");
                    return;
                }
            } catch (NumberFormatException ignored) {}
        }

        VaultRedisManager redis = VaultRedisManager.getInstance();
        boolean hasRedisLock = false;
        String redisLockVal = UUID.randomUUID().toString();
        if (redis != null && redis.isOnline()) {
            int lockExpireMs = (int) Math.max(600000L, interestInterval * 60L * 1000L);
            hasRedisLock = redis.acquireLock("central_bank_run", redisLockVal, lockExpireMs);
            if (!hasRedisLock) {
                plugin.getLogger().info("[Vault Central Bank] Distributed lock 'central_bank_run' is held by another server. Skipping.");
                return;
            }
        }

        try {
            plugin.getLogger().info("[Vault Central Bank] Processing periodic interest and wealth taxes...");
            // Save run timestamp
            failover.saveSetting("last_central_bank_run", String.valueOf(now));

            long runTimestamp = now;

            // 1. Calculate & apply custom currency wealth tax total (so we can deposit it
            // to Treasury bank)
            final Map<String, Double> customWealthTaxesToTreasury = new HashMap<>();
            if (wealthTaxEnabled && wealthTaxPercent > 0) {
                for (String currency : exchangeRateManager.getRates().keySet()) {
                    if (currency.equals("default"))
                        continue;
                    double currencyTaxSum = calculateCustomWealthTaxSum(currency, wealthTaxThreshold,
                            wealthTaxPercent / 100.0);
                    if (currencyTaxSum > 0) {
                        customWealthTaxesToTreasury.put(currency, currencyTaxSum);
                    }
                }
            }

            // Custom currency interest
            if (interestEnabled) {
                for (Map.Entry<String, Double> entry : interestRates.entrySet()) {
                    String currency = entry.getKey();
                    if (currency.equals("default"))
                        continue;
                    double rate = entry.getValue();
                    if (rate > 0) {
                        double factor = 1.0 + (rate / 100.0);
                        failover.applyBulkInterest(currency, factor, runTimestamp);
                    }
                }
            }

            // Bank interest (default rate + bonus)
            if (interestEnabled && bankBonusPercent >= 0) {
                double defaultRate = interestRates.getOrDefault("default", 0.0);
                double bankRate = defaultRate + bankBonusPercent;
                if (bankRate > 0) {
                    double factor = 1.0 + (bankRate / 100.0);
                    failover.applyBulkBankInterest(factor);
                }
            }

            // Custom currency wealth tax
            if (wealthTaxEnabled && wealthTaxPercent > 0) {
                failover.applyBulkWealthTax(wealthTaxThreshold, wealthTaxPercent / 100.0, runTimestamp);
            }

            // 3. Sync SQL updates to Redis and memory cache (only for changed records)
            syncDatabaseBalancesToNetwork(failover, runTimestamp);

            // 4. Run default currency updates on the main thread for online players
            net.milkbowl.vault.util.FoliaScheduler.runSync(plugin, () -> {
                RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
                if (rsp == null)
                    return;
                Economy econ = rsp.getProvider();

                double defaultRate = interestRates.getOrDefault("default", 0.0);
                double totalDefaultWealthTaxDeducted = 0.0;

                for (Player player : Bukkit.getOnlinePlayers()) {
                    double balance = econ.getBalance(player);
                    double interest = 0.0;
                    double wealthTax = 0.0;

                    // Calculate interest
                    if (interestEnabled && defaultRate > 0) {
                        interest = balance * (defaultRate / 100.0);
                    }

                    // Calculate wealth tax
                    if (wealthTaxEnabled && wealthTaxPercent > 0 && balance > wealthTaxThreshold) {
                        wealthTax = (balance - wealthTaxThreshold) * (wealthTaxPercent / 100.0);
                    }

                    double netChange = interest - wealthTax;
                    if (netChange > 0) {
                        econ.depositPlayer(player, netChange);
                        // Notify player
                        notifyPlayer(player, netChange, true);
                    } else if (netChange < 0) {
                        econ.withdrawPlayer(player, -netChange);
                        // Notify player
                        notifyPlayer(player, -netChange, false);
                    }

                    if (wealthTax > 0) {
                        totalDefaultWealthTaxDeducted += wealthTax;
                    }
                }

                // Deposit all taxes to Treasury
                double totalTreasuryDeposit = totalDefaultWealthTaxDeducted;

                // Convert and add custom wealth taxes
                for (Map.Entry<String, Double> entry : customWealthTaxesToTreasury.entrySet()) {
                    double rate = exchangeRateManager.getRate(entry.getKey());
                    double taxValInDefault = entry.getValue() * rate;
                    totalTreasuryDeposit += taxValInDefault;
                }

                if (totalTreasuryDeposit > 0) {
                    if (econ.hasBankSupport()) {
                        if (!econ.getBanks().contains(treasuryAccount.toLowerCase())) {
                            econ.createBank(treasuryAccount.toLowerCase(), (org.bukkit.OfflinePlayer) null);
                        }
                        econ.bankDeposit(treasuryAccount.toLowerCase(), totalTreasuryDeposit);
                    }
                }
            });

            plugin.getLogger().info("[Vault Central Bank] Periodic processing completed successfully.");
        } finally {
            if (redis != null && hasRedisLock) {
                redis.releaseLock("central_bank_run", redisLockVal);
            }
        }
    }

    private double calculateCustomWealthTaxSum(String currency, double threshold, double taxRate) {
        LocalFailoverManager failover = Vault.getFailoverManager();
        if (failover == null) return 0.0;
        return failover.getCustomWealthTaxSum(currency, threshold, taxRate);
    }

    private void syncDatabaseBalancesToNetwork(LocalFailoverManager failover, long runTimestamp) {
        VaultRedisManager redis = VaultRedisManager.getInstance();
        boolean redisOnline = (redis != null && redis.isOnline());

        // Sync custom currency balances (only those modified during this run)
        List<LocalFailoverManager.CustomBalanceRecord> records = failover.getCustomBalancesUpdatedSince(runTimestamp);

        if (redisOnline) {
            redis.publishBalanceUpdatesBulk(records, runTimestamp);
        }

        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        OptimizedEconomy optEcon = (rsp != null && rsp.getProvider() instanceof OptimizedEconomy) ? (OptimizedEconomy) rsp.getProvider() : null;

        if (optEcon != null) {
            for (LocalFailoverManager.CustomBalanceRecord rec : records) {
                // Only update memory cache for locally online players
                if (Bukkit.getPlayer(rec.uuid) != null) {
                    optEcon.updateCacheFromRedis(rec.uuid, rec.currency, rec.balance);
                }
            }
        }

        // Sync bank balances
        Map<String, Double> banks = failover.getAllBankBalances();
        if (redisOnline) {
            redis.setBankBalancesBulk(banks);
        }
        if (optEcon != null) {
            for (Map.Entry<String, Double> entry : banks.entrySet()) {
                optEcon.updateBankCacheFromRedis(entry.getKey(), entry.getValue());
            }
        }
    }

    private void notifyPlayer(Player player, double amount, boolean isGain) {
        // Already running on the main thread, direct execution
        if (isGain) {
            player.sendMessage(Vault.getMessage("central-bank.interest-received", "&a&l[Central Bank] &aYou received &e+%amount%$ &aof interest on your account.")
                    .replace("%amount%", String.format("%.2f", amount)));
        } else {
            player.sendMessage(Vault.getMessage("central-bank.tax-paid", "&c&l[Central Bank] &cYou paid &e-%amount%$ &aof wealth tax (Wealth Tax).")
                    .replace("%amount%", String.format("%.2f", amount)));
        }
    }

    public double getPayTaxPercent() {
        return taxesEnabled ? Vault.getDynamicTaxPercent("pay", payTaxPercent) : 0.0;
    }

    public double getExchangeTaxPercent() {
        return taxesEnabled ? Vault.getDynamicTaxPercent("exchange", exchangeTaxPercent) : 0.0;
    }

    public String getTreasuryAccount() {
        return treasuryAccount;
    }

    public boolean isTaxesEnabled() {
        return taxesEnabled;
    }
}

