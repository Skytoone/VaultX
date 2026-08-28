package net.milkbowl.vault.economy;

import java.util.Locale;

/**
 * Interface providing rich currency formatting options with symbol support and localization.
 */
public interface VaultFormatAPI {

    /**
     * Formats an amount with the currency symbol and default formatting.
     * @param currency Currency ID (e.g. "default", "gems", "tokens")
     * @param amount Amount to format
     * @return Formatted string (e.g., "$1,500.00" or "500 💎")
     */
    String formatCurrency(String currency, double amount);

    /**
     * Formats an amount with localized number formatting.
     * @param currency Currency ID
     * @param amount Amount to format
     * @param locale Locale for number formatting
     * @return Formatted localized string
     */
    String formatCurrency(String currency, double amount, Locale locale);

    /**
     * Gets the configured symbol for a given currency.
     * @param currency Currency ID
     * @return Currency symbol string (e.g., "$", "💎", "🪙")
     */
    String getCurrencySymbol(String currency);
}
