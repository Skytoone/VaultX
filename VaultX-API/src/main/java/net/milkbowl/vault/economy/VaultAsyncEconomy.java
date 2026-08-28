package net.milkbowl.vault.economy;

import org.bukkit.OfflinePlayer;
import java.util.concurrent.CompletableFuture;
import java.util.List;

public interface VaultAsyncEconomy {
    
    CompletableFuture<Double> getBalanceAsync(OfflinePlayer player);
    CompletableFuture<Double> getBalanceAsync(String playerName);
    
    CompletableFuture<EconomyResponse> withdrawPlayerAsync(OfflinePlayer player, double amount);
    CompletableFuture<EconomyResponse> withdrawPlayerAsync(String playerName, double amount);
    
    CompletableFuture<EconomyResponse> depositPlayerAsync(OfflinePlayer player, double amount);
    CompletableFuture<EconomyResponse> depositPlayerAsync(String playerName, double amount);
    
    CompletableFuture<EconomyResponse> bankBalanceAsync(String name);
    CompletableFuture<EconomyResponse> bankDepositAsync(String name, double amount);
    CompletableFuture<EconomyResponse> bankWithdrawAsync(String name, double amount);
    CompletableFuture<List<String>> getBanksAsync();

    CompletableFuture<Double> getCurrencyBalanceAsync(OfflinePlayer player, String currency);
    CompletableFuture<Double> getCurrencyBalanceAsync(String playerName, String currency);
    
    CompletableFuture<EconomyResponse> withdrawCurrencyPlayerAsync(OfflinePlayer player, String currency, double amount);
    CompletableFuture<EconomyResponse> withdrawCurrencyPlayerAsync(String playerName, String currency, double amount);
    
    CompletableFuture<EconomyResponse> depositCurrencyPlayerAsync(OfflinePlayer player, String currency, double amount);
    CompletableFuture<EconomyResponse> depositCurrencyPlayerAsync(String playerName, String currency, double amount);
}
