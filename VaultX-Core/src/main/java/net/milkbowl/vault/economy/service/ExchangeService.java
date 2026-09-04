package net.milkbowl.vault.economy.service;

import java.util.function.BiFunction;
import java.util.function.Function;

import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;

/**
 * Handles currency exchange logic and auto-conversion during multi-currency withdrawals.
 */
public class ExchangeService {

    private final Plugin plugin;
    private final boolean autoConvert;
    private final ConfigurationSection exchangeRates;

    public ExchangeService(Plugin plugin) {
        this.plugin = plugin;
        this.autoConvert = plugin != null && plugin.getConfig().getBoolean("currency-exchange.auto-convert", false);
        this.exchangeRates = plugin != null ? plugin.getConfig().getConfigurationSection("currency-exchange.rates") : null;
    }

    public boolean isAutoConvertEnabled() {
        return autoConvert;
    }

    public ConfigurationSection getExchangeRates() {
        return exchangeRates;
    }

    public EconomyResponse handleAutoConvertWithdraw(OfflinePlayer player, String currency, double amount,
            Function<OfflinePlayer, Double> defaultBalanceSupplier,
            BiFunction<OfflinePlayer, Double, EconomyResponse> defaultWithdrawSupplier,
            Function<String, Double> currencyBalanceSupplier) {

        if (!autoConvert || exchangeRates == null) {
            return new EconomyResponse(0, currencyBalanceSupplier.apply(currency), EconomyResponse.ResponseType.FAILURE, "Not enough funds");
        }

        double rate = exchangeRates.getDouble(currency, 0.0);
        if (rate <= 0) {
            return new EconomyResponse(0, currencyBalanceSupplier.apply(currency), EconomyResponse.ResponseType.FAILURE, "Not enough funds");
        }

        double defaultNeeded = amount * rate;
        double currentDefaultBal = defaultBalanceSupplier.apply(player);

        if (currentDefaultBal >= defaultNeeded) {
            EconomyResponse wRes = defaultWithdrawSupplier.apply(player, defaultNeeded);
            if (wRes.transactionSuccess()) {
                return new EconomyResponse(amount, currencyBalanceSupplier.apply(currency),
                        EconomyResponse.ResponseType.SUCCESS, "Auto-converted from default currency");
            } else {
                return new EconomyResponse(0, currencyBalanceSupplier.apply(currency), EconomyResponse.ResponseType.FAILURE,
                        "Failed to auto-convert from default currency");
            }
        }

        return new EconomyResponse(0, currencyBalanceSupplier.apply(currency), EconomyResponse.ResponseType.FAILURE,
                "Not enough funds (including exchange auto-convert)");
    }
}
