package net.milkbowl.vault.economy;

import org.bukkit.OfflinePlayer;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Modern VaultX API for managing player overdraft limits, credit lines, and credit scores.
 */
public interface VaultCreditAPI {

    record CreditAccount(
            UUID playerUuid,
            String currency,
            double overdraftLimit,
            double currentUsedCredit,
            int creditScore,
            boolean isFrozen
    ) {}

    /**
     * Retrieves the credit account details for a player.
     *
     * @param player Target player
     * @param currency Currency ID
     * @return Future containing CreditAccount
     */
    CompletableFuture<CreditAccount> getCreditAccountAsync(OfflinePlayer player, String currency);

    /**
     * Sets or updates the maximum allowed overdraft credit limit for a player.
     *
     * @param player Target player
     * @param currency Currency ID
     * @param limit New overdraft limit
     * @return Future containing EconomyResponse
     */
    CompletableFuture<EconomyResponse> setOverdraftLimitAsync(OfflinePlayer player, String currency, double limit);

    /**
     * Recalculates and updates a player's credit score (300-850).
     *
     * @param player Target player
     * @return Future containing new credit score
     */
    CompletableFuture<Integer> updateCreditScoreAsync(OfflinePlayer player);
}
