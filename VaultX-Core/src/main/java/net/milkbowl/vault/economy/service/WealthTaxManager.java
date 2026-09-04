package net.milkbowl.vault.economy.service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import net.milkbowl.vault.economy.VaultTaxAPI;
import net.milkbowl.vault.economy.events.VaultInflationUpdateEvent;

/**
 * Manages inflation rates, transaction taxes, progressive wealth taxes, and
 * regional tax rules.
 */
public class WealthTaxManager implements VaultTaxAPI {

    private final Plugin plugin;
    private final Map<String, Double> inflationRates = new ConcurrentHashMap<>();
    private final Map<String, Double> taxRates = new ConcurrentHashMap<>();
    private final Map<String, TaxRule> taxRules = new ConcurrentHashMap<>();

    public WealthTaxManager(Plugin plugin) {
        this.plugin = plugin;
    }

    public double getInflationRate(String currency) {
        return inflationRates.getOrDefault(currency, 1.0);
    }

    public void setInflationRate(String currency, double multiplier) {
        if (currency != null) {
            double old = inflationRates.getOrDefault(currency, 1.0);
            inflationRates.put(currency, multiplier);
            Bukkit.getPluginManager().callEvent(new VaultInflationUpdateEvent(currency, old, multiplier, 0.0, 0.0));
        }
    }

    public double getTransactionTaxRate(String currency) {
        return taxRates.getOrDefault(currency, 0.0);
    }

    public void setTransactionTaxRate(String currency, double taxPercentage) {
        if (currency != null)
            taxRates.put(currency, taxPercentage);
    }

    public CompletableFuture<Double> applyProgressiveWealthTaxAsync(String currency, double taxPercentage) {
        return CompletableFuture.completedFuture(0.0);
    }

    public CompletableFuture<Boolean> registerTaxRuleAsync(TaxRule rule, ExecutorService executor) {
        return CompletableFuture.supplyAsync(() -> {
            if (rule == null || rule.taxId() == null)
                return false;
            taxRules.put(rule.taxId(), rule);
            return true;
        }, executor != null ? executor : CompletableFuture::completedFuture);
    }

    @Override
    public CompletableFuture<Boolean> registerTaxRuleAsync(TaxRule rule) {
        return registerTaxRuleAsync(rule, null);
    }

    public CompletableFuture<Boolean> unregisterTaxRuleAsync(String taxId, ExecutorService executor) {
        return CompletableFuture.supplyAsync(() -> {
            if (taxId == null)
                return false;
            return taxRules.remove(taxId) != null;
        }, executor != null ? executor : CompletableFuture::completedFuture);
    }

    @Override
    public CompletableFuture<Boolean> unregisterTaxRuleAsync(String taxId) {
        return unregisterTaxRuleAsync(taxId, null);
    }

    public double calculateTax(String regionOrWorld, String currency, double amount) {
        if (amount <= 0)
            return 0.0;
        double totalTax = 0.0;
        for (TaxRule rule : taxRules.values()) {
            if (rule.regionOrWorld().equalsIgnoreCase(regionOrWorld) && rule.currency().equalsIgnoreCase(currency)) {
                totalTax += (amount * (rule.percentageRate() / 100.0)) + rule.fixedFee();
            }
        }
        return totalTax;
    }

    public CompletableFuture<Map<String, TaxRule>> getActiveTaxRulesAsync() {
        return CompletableFuture.completedFuture(new HashMap<>(taxRules));
    }

    public void clear() {
        inflationRates.clear();
        taxRates.clear();
        taxRules.clear();
    }
}
