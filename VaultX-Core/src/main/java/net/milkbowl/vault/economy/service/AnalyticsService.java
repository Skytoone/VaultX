package net.milkbowl.vault.economy.service;

import net.milkbowl.vault.economy.VaultAnalyticsAPI;
import net.milkbowl.vault.redis.LocalFailoverManager;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Specialized service providing economy server-wide analytics, metrics, and money supply insights.
 */
public class AnalyticsService implements VaultAnalyticsAPI {

    private final ExecutorService asyncExecutor;

    public AnalyticsService(ExecutorService asyncExecutor) {
        this.asyncExecutor = asyncExecutor;
    }

    private LocalFailoverManager failover() {
        return net.milkbowl.vault.Vault.getFailoverManager();
    }

    @Override
    public CompletableFuture<Double> getTotalSupplyAsync(String currency) {
        return CompletableFuture.supplyAsync(() -> {
            LocalFailoverManager fm = failover();
            return fm != null ? fm.getTotalMoneySupply(currency == null ? "default" : currency) : 0.0;
        }, asyncExecutor);
    }

    @Override
    public CompletableFuture<Double> getAverageBalanceAsync(String currency) {
        return CompletableFuture.supplyAsync(() -> {
            LocalFailoverManager fm = failover();
            return fm != null ? fm.getAverageAccountBalance(currency == null ? "default" : currency) : 0.0;
        }, asyncExecutor);
    }

    @Override
    public CompletableFuture<Double> getVolume24hAsync(String currency) {
        return CompletableFuture.supplyAsync(() -> {
            LocalFailoverManager fm = failover();
            return fm != null ? fm.getTransactionVolume24h(currency == null ? "default" : currency) : 0.0;
        }, asyncExecutor);
    }
}
