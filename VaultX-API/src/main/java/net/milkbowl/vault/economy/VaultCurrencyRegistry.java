package net.milkbowl.vault.economy;

import org.bukkit.OfflinePlayer;
import java.util.List;

/**
 * Interface allowing third-party plugins to dynamically register custom currency providers inside VaultX.
 */
public interface VaultCurrencyRegistry {

    interface CustomCurrencyProvider {
        double getBalance(OfflinePlayer player);
        EconomyResponse withdraw(OfflinePlayer player, double amount);
        EconomyResponse deposit(OfflinePlayer player, double amount);
        String getSymbol();
    }

    /**
     * Registers a custom currency provider dynamically.
     * @param currency Currency ID (e.g. "crypto", "mana")
     * @param provider CustomCurrencyProvider implementation
     * @return true if registered successfully
     */
    boolean registerCurrency(String currency, CustomCurrencyProvider provider);

    /**
     * Dynamically registers a new native VaultX currency programmatically.
     * VaultX will natively handle storage, RAM caching, pub/sub sync, exchange rates, and symbols for this currency.
     * @param currency Currency ID (e.g. "gems", "tokens", "credits")
     * @param symbol Display symbol (e.g. "💎", "🪙")
     * @param startingBalance Default starting balance for new accounts
     * @param exchangeRate Exchange rate relative to default currency (1.0 = equal value)
     * @return true if registered successfully
     */
    boolean registerCurrency(String currency, String symbol, double startingBalance, double exchangeRate);

    /**
     * Dynamically registers a new native VaultX currency programmatically with default 0.0 starting balance and 1.0 exchange rate.
     * @param currency Currency ID (e.g. "gems", "tokens")
     * @param symbol Display symbol (e.g. "💎", "🪙")
     * @return true if registered successfully
     */
    default boolean registerCurrency(String currency, String symbol) {
        return registerCurrency(currency, symbol, 0.0, 1.0);
    }

    /**
     * Unregisters a custom currency.
     * @param currency Currency ID
     * @return true if unregistered successfully
     */
    boolean unregisterCurrency(String currency);

    /**
     * @return List of all custom currencies registered dynamically
     */
    List<String> getRegisteredCustomCurrencies();
}
