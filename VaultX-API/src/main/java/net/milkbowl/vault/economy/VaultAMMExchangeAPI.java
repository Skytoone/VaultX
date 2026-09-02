package net.milkbowl.vault.economy;

import org.bukkit.OfflinePlayer;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Automated Market Maker (AMM) & Dynamic Bonding-Curve Liquidity Pool API for VaultX.
 */
public interface VaultAMMExchangeAPI {

    class LiquidityPool {
        public final String poolId;
        public final String baseCurrency;
        public final String targetCurrency;
        public final double baseReserve;
        public final double targetReserve;
        public final double feePercentage;

        public LiquidityPool(String poolId, String baseCurrency, String targetCurrency, double baseReserve, double targetReserve, double feePercentage) {
            this.poolId = poolId;
            this.baseCurrency = baseCurrency;
            this.targetCurrency = targetCurrency;
            this.baseReserve = baseReserve;
            this.targetReserve = targetReserve;
            this.feePercentage = feePercentage;
        }
    }

    class SwapQuote {
        public final String currencyIn;
        public final double amountIn;
        public final String currencyOut;
        public final double amountOut;
        public final double priceImpactPercentage;
        public final double feeAmount;

        public SwapQuote(String currencyIn, double amountIn, String currencyOut, double amountOut, double priceImpactPercentage, double feeAmount) {
            this.currencyIn = currencyIn;
            this.amountIn = amountIn;
            this.currencyOut = currencyOut;
            this.amountOut = amountOut;
            this.priceImpactPercentage = priceImpactPercentage;
            this.feeAmount = feeAmount;
        }
    }

    class SwapResult {
        public final boolean success;
        public final String message;
        public final double amountReceived;

        public SwapResult(boolean success, String message, double amountReceived) {
            this.success = success;
            this.message = message;
            this.amountReceived = amountReceived;
        }
    }

    CompletableFuture<LiquidityPool> createPoolAsync(String baseCurrency, String targetCurrency, double initialBaseReserve, double initialTargetReserve, double feePercentage);

    CompletableFuture<SwapQuote> getSwapQuoteAsync(String poolId, String currencyIn, double amountIn);

    CompletableFuture<SwapResult> executeSwapAsync(String poolId, OfflinePlayer player, String currencyIn, double amountIn, double minAmountOut);

    CompletableFuture<Boolean> addLiquidityAsync(String poolId, OfflinePlayer provider, double baseAmount, double targetAmount);

    CompletableFuture<List<LiquidityPool>> getAllPoolsAsync();
}
