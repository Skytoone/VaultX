package net.milkbowl.vault.economy;

import org.bukkit.OfflinePlayer;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Cashback, Reward Points, and Loyalty Tier Status API for VaultX.
 */
public interface VaultCashbackLoyaltyAPI {

    class LoyaltyProfile {
        public final UUID playerUuid;
        public final double totalSpentAllTime;
        public final String tierName;
        public final double cashbackPercentage;
        public final double loyaltyPoints;

        public LoyaltyProfile(UUID playerUuid, double totalSpentAllTime, String tierName, double cashbackPercentage, double loyaltyPoints) {
            this.playerUuid = playerUuid;
            this.totalSpentAllTime = totalSpentAllTime;
            this.tierName = tierName;
            this.cashbackPercentage = cashbackPercentage;
            this.loyaltyPoints = loyaltyPoints;
        }
    }

    class CashbackResult {
        public final boolean success;
        public final double cashbackCredited;
        public final double pointsEarned;

        public CashbackResult(boolean success, double cashbackCredited, double pointsEarned) {
            this.success = success;
            this.cashbackCredited = cashbackCredited;
            this.pointsEarned = pointsEarned;
        }
    }

    CompletableFuture<CashbackResult> processPurchaseCashbackAsync(OfflinePlayer player, double purchaseAmount, String purchaseCurrency, String cashbackCurrency);

    CompletableFuture<LoyaltyProfile> getLoyaltyProfileAsync(OfflinePlayer player);

    CompletableFuture<Boolean> redeemLoyaltyPointsAsync(OfflinePlayer player, double pointsAmount, String rewardCurrency, double exchangeRate);
}
