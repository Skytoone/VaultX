package net.milkbowl.vault.economy;

import org.bukkit.OfflinePlayer;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Enterprise API for loans, credit scores, interest rates, and automated daily repayment schedules in VaultX.
 */
public interface VaultLoanAPI {

    record LoanDetails(String loanId, UUID playerUuid, String currency, double principal, double remainingAmount, double interestRate, int durationDays, long nextDueDateMs) {}

    /**
     * Calculates a player's credit score (300 - 850) based on financial history.
     *
     * @param player Target player
     * @return Future containing credit score
     */
    CompletableFuture<Integer> getCreditScoreAsync(OfflinePlayer player);

    /**
     * Takes a bank loan for a player.
     *
     * @param player Target player
     * @param currency Currency ID
     * @param amount Principal loan amount
     * @param durationDays Repayment duration in days
     * @param interestRate Interest rate percentage (e.g. 5.0 for 5%)
     * @return Future containing EconomyResponse
     */
    CompletableFuture<EconomyResponse> takeLoanAsync(OfflinePlayer player, String currency, double amount, int durationDays, double interestRate);

    /**
     * Repays a loan for a player partially or in full.
     *
     * @param player Target player
     * @param loanId Loan identifier
     * @param amount Repayment amount
     * @return Future containing EconomyResponse
     */
    CompletableFuture<EconomyResponse> repayLoanAsync(OfflinePlayer player, String loanId, double amount);

    /**
     * Queries active loans for a player.
     *
     * @param player Target player
     * @return Future containing list of active LoanDetails
     */
    CompletableFuture<List<LoanDetails>> getActiveLoansAsync(OfflinePlayer player);
}
