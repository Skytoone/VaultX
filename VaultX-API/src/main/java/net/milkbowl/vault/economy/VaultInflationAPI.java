package net.milkbowl.vault.economy;

import java.util.concurrent.CompletableFuture;

/**
 * Interface providing economy inflation control, dynamic tax rates, and server wealth regulation in VaultX.
 */
public interface VaultInflationAPI {

    /**
     * Gets the current inflation multiplier for a currency.
     *
     * @param currency Currency ID
     * @return Inflation multiplier (1.0 default)
     */
    double getInflationRate(String currency);

    /**
     * Sets the inflation rate for a currency.
     *
     * @param currency Currency ID
     * @param multiplier Inflation multiplier
     */
    void setInflationRate(String currency, double multiplier);

    /**
     * Gets the active transaction tax percentage rate for a currency.
     *
     * @param currency Currency ID
     * @return Tax rate percentage (e.g. 2.5 for 2.5%)
     */
    double getTransactionTaxRate(String currency);

    /**
     * Sets the transaction tax percentage rate for a currency.
     *
     * @param currency Currency ID
     * @param taxPercentage Tax rate percentage
     */
    void setTransactionTaxRate(String currency, double taxPercentage);

    /**
     * Triggers a progressive wealth tax deduction on top 1% wealthiest accounts.
     *
     * @param currency Currency ID
     * @param taxPercentage Progressive tax percentage
     * @return Future containing total tax collected
     */
    CompletableFuture<Double> applyProgressiveWealthTaxAsync(String currency, double taxPercentage);
}
