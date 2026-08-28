package net.milkbowl.vault.economy;

import java.util.Map;

/**
 * Interface providing real-time exchange rates and conversion capabilities between multi-currencies in VaultX.
 */
public interface CurrencyExchangeAPI {

    /**
     * Gets the exchange multiplier from source currency to target currency.
     * @param fromCurrency Source currency ID (e.g. "gems")
     * @param toCurrency Target currency ID (e.g. "dollars")
     * @return Exchange multiplier (1.0 if identical or unregistered)
     */
    double getExchangeRate(String fromCurrency, String toCurrency);

    /**
     * Converts an amount from source currency to target currency.
     * @param fromCurrency Source currency ID
     * @param toCurrency Target currency ID
     * @param amount Source amount
     * @return Converted amount in target currency
     */
    double convert(String fromCurrency, String toCurrency, double amount);

    /**
     * @return Map of all active currency exchange rates configured in VaultX.
     */
    Map<String, Double> getAllExchangeRates();
}
