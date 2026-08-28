package net.milkbowl.vault.economy;

import org.bukkit.OfflinePlayer;

/**
 * VaultX Modern API extension for native multi-currency support.
 * Allows plugins to check or modify balances of custom currencies (e.g., "gems", "tokens")
 * without needing separate Economy implementations.
 */
public interface MultiCurrencyEconomy extends Economy {

    /**
     * @return A list of all currencies supported by this economy provider.
     */
    java.util.List<String> getSupportedCurrencies();

    /**
     * Gets balance of a player in a specific currency.
     */
    double getCurrencyBalance(OfflinePlayer player, String currency);
    
    /**
     * Gets balance of a player in a specific currency.
     */
    double getCurrencyBalance(String playerName, String currency);

    /**
     * Withdraws an amount from a player in a specific currency.
     */
    EconomyResponse withdrawCurrencyPlayer(OfflinePlayer player, String currency, double amount);
    
    /**
     * Withdraws an amount from a player in a specific currency.
     */
    EconomyResponse withdrawCurrencyPlayer(String playerName, String currency, double amount);

    /**
     * Deposits an amount to a player in a specific currency.
     */
    EconomyResponse depositCurrencyPlayer(OfflinePlayer player, String currency, double amount);
    
    /**
     * Deposits an amount to a player in a specific currency.
     */
    EconomyResponse depositCurrencyPlayer(String playerName, String currency, double amount);

    /**
     * Checks if the player has an account for the given currency.
     */
    boolean hasCurrencyAccount(OfflinePlayer player, String currency);

    /**
     * Checks if the player has at least the specified amount in the given currency.
     */
    boolean hasCurrency(OfflinePlayer player, String currency, double amount);
}
