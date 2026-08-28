package net.milkbowl.vault.economy;

import java.util.concurrent.CompletableFuture;

/**
 * Interface providing economy server-wide analytics, metrics, and money supply insights.
 */
public interface VaultAnalyticsAPI {

    /**
     * Gets the total money supply in circulation for a currency.
     * @param currency Currency ID
     * @return Future containing total money supply
     */
    CompletableFuture<Double> getTotalSupplyAsync(String currency);

    /**
     * Gets the average account balance for a currency.
     * @param currency Currency ID
     * @return Future containing average balance
     */
    CompletableFuture<Double> getAverageBalanceAsync(String currency);

    /**
     * Gets the 24-hour total transaction volume for a currency.
     * @param currency Currency ID
     * @return Future containing 24h volume
     */
    CompletableFuture<Double> getVolume24hAsync(String currency);
}
