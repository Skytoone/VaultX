package net.milkbowl.vault.economy;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Modern VaultX API for regional, territorial, and transaction taxation rules.
 */
public interface VaultTaxAPI {

    record TaxRule(
            String taxId,
            String regionOrWorld,
            String currency,
            double percentageRate,
            double fixedFee,
            boolean applyOnTransfer,
            boolean applyOnMarketplace
    ) {}

    /**
     * Registers or updates a regional/territorial taxation rule.
     *
     * @param rule Tax rule specifications
     * @return Future containing boolean success flag
     */
    CompletableFuture<Boolean> registerTaxRuleAsync(TaxRule rule);

    /**
     * Unregisters a regional taxation rule.
     *
     * @param taxId Tax rule unique identifier
     * @return Future containing boolean success flag
     */
    CompletableFuture<Boolean> unregisterTaxRuleAsync(String taxId);

    /**
     * Calculates applicable tax for a given amount and region/world.
     *
     * @param regionOrWorld Region or World ID
     * @param currency Currency ID
     * @param amount Transaction amount
     * @return Calculated tax amount
     */
    double calculateTax(String regionOrWorld, String currency, double amount);

    /**
     * Retrieves all active tax rules registered in the system.
     *
     * @return Map of taxId to TaxRule
     */
    CompletableFuture<Map<String, TaxRule>> getActiveTaxRulesAsync();
}
