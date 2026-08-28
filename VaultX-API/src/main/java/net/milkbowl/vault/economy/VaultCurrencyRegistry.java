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
