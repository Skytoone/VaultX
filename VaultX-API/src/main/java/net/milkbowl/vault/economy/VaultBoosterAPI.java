package net.milkbowl.vault.economy;

import org.bukkit.OfflinePlayer;

/**
 * Interface for managing global and player-specific economy payout multipliers and boosters.
 */
public interface VaultBoosterAPI {

    /**
     * Gets the active global multiplier for a currency.
     * @param currency Currency ID
     * @return Current active multiplier (1.0 default)
     */
    double getGlobalMultiplier(String currency);

    /**
     * Registers a temporary global multiplier booster.
     * @param currency Currency ID
     * @param multiplier Multiplier factor (e.g. 2.0 for 2x)
     * @param durationMs Duration in milliseconds
     */
    void registerGlobalBooster(String currency, double multiplier, long durationMs);

    /**
     * Calculates the boosted payout amount after applying active multipliers.
     * @param player Target player
     * @param currency Currency ID
     * @param baseAmount Base payout amount
     * @return Boosted payout amount
     */
    double calculateBoostedAmount(OfflinePlayer player, String currency, double baseAmount);
}
