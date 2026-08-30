package net.milkbowl.vault.economy;

import org.bukkit.OfflinePlayer;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Modern VaultX API for time-locked savings accounts and high-yield currency staking.
 */
public interface VaultStakingAPI {

    record StakeDeposit(
            String depositId,
            UUID playerUuid,
            String currency,
            double principal,
            double interestRate,
            long stakedAtMs,
            long lockPeriodMs,
            boolean isMatured,
            boolean isClaimed
    ) {}

    /**
     * Deposits principal into a time-locked high-yield staking account.
     *
     * @param player Target player
     * @param currency Currency ID
     * @param amount Amount to stake
     * @param durationDays Lock period in days
     * @return Future containing EconomyResponse
     */
    CompletableFuture<EconomyResponse> createStakeAsync(OfflinePlayer player, String currency, double amount, int durationDays);

    /**
     * Claims payout and matured interest for a stake deposit.
     *
     * @param player Target player
     * @param depositId Stake deposit ID
     * @return Future containing EconomyResponse
     */
    CompletableFuture<EconomyResponse> claimStakeAsync(OfflinePlayer player, String depositId);

    /**
     * Retrieves all active staking deposits for a player.
     *
     * @param player Target player
     * @return Future containing list of active StakeDeposits
     */
    CompletableFuture<List<StakeDeposit>> getActiveStakesAsync(OfflinePlayer player);
}
