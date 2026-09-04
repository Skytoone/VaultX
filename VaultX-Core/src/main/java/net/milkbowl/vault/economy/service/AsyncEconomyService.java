package net.milkbowl.vault.economy.service;

import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.economy.MultiCurrencyEconomy;
import net.milkbowl.vault.economy.VaultAsyncEconomy;
import org.bukkit.OfflinePlayer;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Specialized service for executing asynchronous economy transactions via Virtual Threads.
 */
public class AsyncEconomyService implements VaultAsyncEconomy {

    private final MultiCurrencyEconomy economy;
    private final ExecutorService asyncExecutor;

    public AsyncEconomyService(MultiCurrencyEconomy economy, ExecutorService asyncExecutor) {
        this.economy = economy;
        this.asyncExecutor = asyncExecutor;
    }

    @Override
    public CompletableFuture<Double> getBalanceAsync(OfflinePlayer player) {
        return CompletableFuture.supplyAsync(() -> economy.getBalance(player), asyncExecutor);
    }

    @Override
    public CompletableFuture<Double> getBalanceAsync(String playerName) {
        return CompletableFuture.supplyAsync(() -> economy.getBalance(playerName), asyncExecutor);
    }

    @Override
    public CompletableFuture<EconomyResponse> withdrawPlayerAsync(OfflinePlayer player, double amount) {
        return CompletableFuture.supplyAsync(() -> economy.withdrawPlayer(player, amount), asyncExecutor);
    }

    @Override
    public CompletableFuture<EconomyResponse> withdrawPlayerAsync(String playerName, double amount) {
        return CompletableFuture.supplyAsync(() -> economy.withdrawPlayer(playerName, amount), asyncExecutor);
    }

    @Override
    public CompletableFuture<EconomyResponse> depositPlayerAsync(OfflinePlayer player, double amount) {
        return CompletableFuture.supplyAsync(() -> economy.depositPlayer(player, amount), asyncExecutor);
    }

    @Override
    public CompletableFuture<EconomyResponse> depositPlayerAsync(String playerName, double amount) {
        return CompletableFuture.supplyAsync(() -> economy.depositPlayer(playerName, amount), asyncExecutor);
    }

    @Override
    public CompletableFuture<EconomyResponse> bankBalanceAsync(String name) {
        return CompletableFuture.supplyAsync(() -> economy.bankBalance(name), asyncExecutor);
    }

    @Override
    public CompletableFuture<EconomyResponse> bankDepositAsync(String name, double amount) {
        return CompletableFuture.supplyAsync(() -> economy.bankDeposit(name, amount), asyncExecutor);
    }

    @Override
    public CompletableFuture<EconomyResponse> bankWithdrawAsync(String name, double amount) {
        return CompletableFuture.supplyAsync(() -> economy.bankWithdraw(name, amount), asyncExecutor);
    }

    @Override
    public CompletableFuture<List<String>> getBanksAsync() {
        return CompletableFuture.supplyAsync(economy::getBanks, asyncExecutor);
    }

    @Override
    public CompletableFuture<Double> getCurrencyBalanceAsync(OfflinePlayer player, String currency) {
        return CompletableFuture.supplyAsync(() -> economy.getCurrencyBalance(player, currency), asyncExecutor);
    }

    @Override
    public CompletableFuture<Double> getCurrencyBalanceAsync(String playerName, String currency) {
        return CompletableFuture.supplyAsync(() -> economy.getCurrencyBalance(playerName, currency), asyncExecutor);
    }

    @Override
    public CompletableFuture<EconomyResponse> withdrawCurrencyPlayerAsync(OfflinePlayer player, String currency, double amount) {
        return CompletableFuture.supplyAsync(() -> economy.withdrawCurrencyPlayer(player, currency, amount), asyncExecutor);
    }

    @Override
    public CompletableFuture<EconomyResponse> withdrawCurrencyPlayerAsync(String playerName, String currency, double amount) {
        return CompletableFuture.supplyAsync(() -> economy.withdrawCurrencyPlayer(playerName, currency, amount), asyncExecutor);
    }

    @Override
    public CompletableFuture<EconomyResponse> depositCurrencyPlayerAsync(OfflinePlayer player, String currency, double amount) {
        return CompletableFuture.supplyAsync(() -> economy.depositCurrencyPlayer(player, currency, amount), asyncExecutor);
    }

    @Override
    public CompletableFuture<EconomyResponse> depositCurrencyPlayerAsync(String playerName, String currency, double amount) {
        return CompletableFuture.supplyAsync(() -> economy.depositCurrencyPlayer(playerName, currency, amount), asyncExecutor);
    }
}
