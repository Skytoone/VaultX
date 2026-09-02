package net.milkbowl.vault.economy;

import net.milkbowl.vault.Vault;
import net.milkbowl.vault.economy.events.VaultInflationUpdateEvent;
import net.milkbowl.vault.redis.LocalFailoverManager;
import net.milkbowl.vault.util.FoliaScheduler;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enterprise Dynamic Pricing & Inflation Engine for VaultX.
 * Calculates money supply, baseline inflation ratios, and provides
 * real-time adjusted buy/sell prices and tax multipliers.
 */
public class DynamicPricingManager {

    private final Plugin plugin;
    private BukkitTask schedulerTask;

    private boolean enabled = true;
    private int updateIntervalMinutes = 15;
    private double elasticity = 1.0;
    private double minMultiplier = 0.5;
    private double maxMultiplier = 5.0;
    private boolean invertSellPrices = false;

    private final Map<String, Double> baselineSupplies = new ConcurrentHashMap<>();
    private final Map<String, Double> cachedMultipliers = new ConcurrentHashMap<>();
    private final Map<String, Double> cachedSupplies = new ConcurrentHashMap<>();
    private final Map<String, Long> lastRecalculation = new ConcurrentHashMap<>();

    public DynamicPricingManager(Plugin plugin) {
        this.plugin = plugin;
        loadConfig();
        if (enabled) {
            startScheduler();
            // Initial recalculation after 5 seconds delay
            FoliaScheduler.runLaterAsync(plugin, this::recalculateAllAsync, 100L);
        }
    }

    public void reloadConfig() {
        close();
        loadConfig();
        if (enabled) {
            startScheduler();
            recalculateAllAsync();
        }
    }

    public void close() {
        if (schedulerTask != null) {
            schedulerTask.cancel();
            schedulerTask = null;
        }
    }

    private void loadConfig() {
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("dynamic-pricing");
        if (sec == null) {
            // Default settings if section missing
            enabled = true;
            updateIntervalMinutes = 15;
            elasticity = 1.0;
            minMultiplier = 0.5;
            maxMultiplier = 5.0;
            invertSellPrices = false;
            baselineSupplies.put("default", 1000000.0);
            return;
        }

        this.enabled = sec.getBoolean("enabled", true);
        this.updateIntervalMinutes = Math.max(1, sec.getInt("update-interval-minutes", 15));
        this.elasticity = Math.max(0.0, sec.getDouble("elasticity", 1.0));
        this.minMultiplier = sec.getDouble("min-multiplier", 0.5);
        this.maxMultiplier = sec.getDouble("max-multiplier", 5.0);
        this.invertSellPrices = sec.getBoolean("invert-sell-prices", false);

        baselineSupplies.clear();
        ConfigurationSection baseSec = sec.getConfigurationSection("baseline-supplies");
        if (baseSec != null) {
            for (String key : baseSec.getKeys(false)) {
                baselineSupplies.put(key.toLowerCase(), baseSec.getDouble(key, 1000000.0));
            }
        }
        if (!baselineSupplies.containsKey("default")) {
            baselineSupplies.put("default", 1000000.0);
        }
    }

    private void startScheduler() {
        long ticks = updateIntervalMinutes * 60L * 20L;
        schedulerTask = FoliaScheduler.runTimerAsync(plugin, this::recalculateAllAsync, ticks, ticks);
    }

    public void recalculateAllAsync() {
        if (!enabled) return;

        Set<String> currencies = new HashSet<>();
        currencies.add("default");

        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp != null && rsp.getProvider() instanceof MultiCurrencyEconomy) {
            List<String> list = ((MultiCurrencyEconomy) rsp.getProvider()).getSupportedCurrencies();
            if (list != null) {
                for (String c : list) {
                    if (c != null && !c.isEmpty()) {
                        currencies.add(c.toLowerCase());
                    }
                }
            }
        }

        for (String currency : currencies) {
            recalculateSupplyAndMultiplier(currency);
        }
    }

    public double recalculateSupplyAndMultiplier(String currency) {
        String key = currency == null ? "default" : currency.toLowerCase();
        double currentSupply = calculateMoneySupply(key);
        cachedSupplies.put(key, currentSupply);

        double baseline = baselineSupplies.getOrDefault(key, 0.0);
        if (baseline <= 0.0) {
            // Auto-initialize baseline supply to current supply if 0 or unconfigured
            baseline = Math.max(1.0, currentSupply);
            setBaselineMemoryAndConfig(key, baseline);
        }

        double oldMultiplier = cachedMultipliers.getOrDefault(key, 1.0);
        double rawRatio = currentSupply / Math.max(1.0, baseline);
        
        // Elasticity formula: 1.0 + elasticity * (rawRatio - 1.0)
        double calculatedMultiplier = 1.0 + elasticity * (rawRatio - 1.0);

        // Clamp between min and max bounds
        double newMultiplier = Math.max(minMultiplier, Math.min(maxMultiplier, calculatedMultiplier));

        cachedMultipliers.put(key, newMultiplier);
        lastRecalculation.put(key, System.currentTimeMillis());

        // Dispatch Bukkit Event if multiplier changed noticeably
        if (Math.abs(oldMultiplier - newMultiplier) > 0.0001) {
            try {
                Bukkit.getPluginManager().callEvent(new VaultInflationUpdateEvent(key, oldMultiplier, newMultiplier, currentSupply, baseline));
            } catch (Throwable t) {
                // Event dispatch protection
            }
        }

        return newMultiplier;
    }

    private double calculateMoneySupply(String currency) {
        final java.util.concurrent.atomic.DoubleAdder onlineSupplyAdder = new java.util.concurrent.atomic.DoubleAdder();
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        Economy econ = rsp != null ? rsp.getProvider() : null;

        if (econ != null) {
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            FoliaScheduler.runSync(plugin, () -> {
                try {
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        try {
                            if (currency.equalsIgnoreCase("default")) {
                                onlineSupplyAdder.add(Math.max(0.0, econ.getBalance(player)));
                            } else if (econ instanceof MultiCurrencyEconomy) {
                                onlineSupplyAdder.add(Math.max(0.0, ((MultiCurrencyEconomy) econ).getCurrencyBalance(player, currency)));
                            }
                        } catch (Throwable ignored) {}
                    }
                } finally {
                    latch.countDown();
                }
            });
            try {
                latch.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        double supply = onlineSupplyAdder.sum();

        // Query database failover manager for offline/total balance sum if available
        LocalFailoverManager failover = Vault.getFailoverManager();
        if (failover != null) {
            try {
                double dbSum = failover.getTotalCurrencyBalanceSum(currency);
                if (dbSum > supply) {
                    supply = dbSum;
                }
            } catch (Throwable ignored) {}
        }

        return Math.max(0.0, supply);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public double getInflationMultiplier(String currency) {
        if (!enabled) return 1.0;
        String key = currency == null ? "default" : currency.toLowerCase();
        return cachedMultipliers.getOrDefault(key, 1.0);
    }

    public double getAdjustedBuyPrice(String currency, double basePrice) {
        if (basePrice <= 0) return basePrice;
        return basePrice * getInflationMultiplier(currency);
    }

    public double getAdjustedSellPrice(String currency, double basePrice) {
        if (basePrice <= 0) return basePrice;
        double mult = getInflationMultiplier(currency);
        if (invertSellPrices && mult > 0) {
            return basePrice / mult;
        }
        return basePrice * mult;
    }

    public double getAdjustedTaxPercent(String currency, double baseTaxPercent) {
        if (baseTaxPercent <= 0) return baseTaxPercent;
        return baseTaxPercent * getInflationMultiplier(currency);
    }

    public double getCurrentMoneySupply(String currency) {
        String key = currency == null ? "default" : currency.toLowerCase();
        return cachedSupplies.getOrDefault(key, 0.0);
    }

    public double getBaselineMoneySupply(String currency) {
        String key = currency == null ? "default" : currency.toLowerCase();
        return baselineSupplies.getOrDefault(key, 1000000.0);
    }

    public void setBaselineMoneySupply(String currency, double amount) {
        String key = currency == null ? "default" : currency.toLowerCase();
        setBaselineMemoryAndConfig(key, amount);
        recalculateSupplyAndMultiplier(key);
    }

    private void setBaselineMemoryAndConfig(String currencyKey, double amount) {
        baselineSupplies.put(currencyKey, amount);
        plugin.getConfig().set("dynamic-pricing.baseline-supplies." + currencyKey, amount);
        plugin.saveConfig();
    }

    public double getElasticity() {
        return elasticity;
    }

    public double getMinMultiplier() {
        return minMultiplier;
    }

    public double getMaxMultiplier() {
        return maxMultiplier;
    }

    public boolean isInvertSellPrices() {
        return invertSellPrices;
    }

    public long getLastRecalculationTime(String currency) {
        String key = currency == null ? "default" : currency.toLowerCase();
        return lastRecalculation.getOrDefault(key, 0L);
    }
}
