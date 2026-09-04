package net.milkbowl.vault.economy.service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.economy.OptimizedEconomy;
import net.milkbowl.vault.economy.VaultLoanAPI;
import net.milkbowl.vault.economy.VaultLoanAPI.LoanDetails;

/**
 * Service encapsulating credit score computations and loan creation/repayment workflows.
 */
public class LoanEconomyService implements VaultLoanAPI {

    private final Plugin plugin;
    private final ExecutorService asyncExecutor;
    private final OptimizedEconomy economy;

    public LoanEconomyService(Plugin plugin, ExecutorService asyncExecutor, OptimizedEconomy economy) {
        this.plugin = plugin;
        this.asyncExecutor = asyncExecutor;
        this.economy = economy;
    }

    public CompletableFuture<Integer> getCreditScoreAsync(OfflinePlayer player) {
        return CompletableFuture.supplyAsync(() -> {
            double bal = economy.getBalance(player);
            int score = 650 + (int) Math.min(200, bal / 1000.0);
            return Math.min(850, Math.max(300, score));
        }, asyncExecutor);
    }

    public CompletableFuture<EconomyResponse> takeLoanAsync(OfflinePlayer player, String currency,
            double amount, int durationDays, double interestRate) {
        if (!plugin.getConfig().getBoolean("loans.enabled", true)) {
            return CompletableFuture.completedFuture(new EconomyResponse(0, 0,
                    EconomyResponse.ResponseType.FAILURE, "Loans feature is disabled in config.yml"));
        }
        return CompletableFuture.supplyAsync(() -> economy.depositCurrencyPlayer(player, currency, amount), asyncExecutor);
    }

    public CompletableFuture<EconomyResponse> repayLoanAsync(OfflinePlayer player, String loanId, double amount) {
        if (!plugin.getConfig().getBoolean("loans.enabled", true)) {
            return CompletableFuture.completedFuture(new EconomyResponse(0, 0,
                    EconomyResponse.ResponseType.FAILURE, "Loans feature is disabled in config.yml"));
        }
        return CompletableFuture.supplyAsync(() -> economy.withdrawPlayer(player, amount), asyncExecutor);
    }

    public CompletableFuture<List<LoanDetails>> getActiveLoansAsync(OfflinePlayer player) {
        return CompletableFuture.completedFuture(List.of());
    }
}
