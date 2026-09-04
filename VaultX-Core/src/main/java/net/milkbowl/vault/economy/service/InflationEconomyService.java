package net.milkbowl.vault.economy.service;

import net.milkbowl.vault.economy.VaultInflationAPI;

import java.util.concurrent.CompletableFuture;

/**
 * Specialized service providing economy inflation control, dynamic tax rates, and server wealth regulation.
 */
public class InflationEconomyService implements VaultInflationAPI {

    private final WealthTaxManager wealthTaxManager;

    public InflationEconomyService(WealthTaxManager wealthTaxManager) {
        this.wealthTaxManager = wealthTaxManager;
    }

    @Override
    public double getInflationRate(String currency) {
        return wealthTaxManager.getInflationRate(currency);
    }

    @Override
    public void setInflationRate(String currency, double multiplier) {
        wealthTaxManager.setInflationRate(currency, multiplier);
    }

    @Override
    public double getTransactionTaxRate(String currency) {
        return wealthTaxManager.getTransactionTaxRate(currency);
    }

    @Override
    public void setTransactionTaxRate(String currency, double taxPercentage) {
        wealthTaxManager.setTransactionTaxRate(currency, taxPercentage);
    }

    @Override
    public CompletableFuture<Double> applyProgressiveWealthTaxAsync(String currency, double taxPercentage) {
        return wealthTaxManager.applyProgressiveWealthTaxAsync(currency, taxPercentage);
    }
}
