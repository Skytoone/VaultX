package net.milkbowl.vault.economy.service;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;

import net.milkbowl.vault.economy.VaultCurrencyRegistry;
import net.milkbowl.vault.economy.VaultFormatAPI;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.economy.MultiCurrencyEconomy;

/**
 * Manages currency registrations, multi-currency providers, currency symbols, and formatting.
 */
public class CurrencyService implements VaultCurrencyRegistry, VaultFormatAPI {

    private final Plugin plugin;
    private final Map<String, CustomCurrencyProvider> customProviders = new ConcurrentHashMap<>();
    private final Map<String, NativeCurrencyConfig> nativeRegisteredCurrencies = new ConcurrentHashMap<>();
    private final ConfigurationSection exchangeRates;
    private final String formatSymbol;
    private final String formatPosition;
    private final boolean formatUseShort;
    private final char formatDecSep;

    private final ThreadLocal<DecimalFormat> cachedDecimalFormat = new ThreadLocal<>();

    public CurrencyService(Plugin plugin) {
        this.plugin = plugin;
        this.exchangeRates = plugin != null ? plugin.getConfig().getConfigurationSection("currency-exchange.rates") : null;
        this.formatSymbol = plugin != null ? plugin.getConfig().getString("formatting.symbol", "$") : "$";
        this.formatPosition = plugin != null ? plugin.getConfig().getString("formatting.symbol-position", "AFTER") : "AFTER";
        this.formatUseShort = plugin != null && plugin.getConfig().getBoolean("formatting.use-short-format", false);
        String decSepStr = plugin != null ? plugin.getConfig().getString("formatting.decimal-separator", ".") : ".";
        this.formatDecSep = (decSepStr != null && !decSepStr.isEmpty()) ? decSepStr.charAt(0) : '.';
    }

    private DecimalFormat getDecimalFormat() {
        DecimalFormat df = cachedDecimalFormat.get();
        if (df == null) {
            String decSepStr = plugin != null ? plugin.getConfig().getString("formatting.decimal-separator", ".") : ".";
            String thousandSepStr = plugin != null ? plugin.getConfig().getString("formatting.thousands-separator", ",") : ",";
            int decimals = plugin != null ? plugin.getConfig().getInt("formatting.decimal-places", 2) : 2;

            char decSep = (decSepStr != null && !decSepStr.isEmpty()) ? decSepStr.charAt(0) : '.';
            char thousandSep = (thousandSepStr != null && !thousandSepStr.isEmpty()) ? thousandSepStr.charAt(0) : ',';

            DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.US);
            symbols.setDecimalSeparator(decSep);
            symbols.setGroupingSeparator(thousandSep);

            df = new DecimalFormat("#,##0" + (decimals > 0 ? "." + "0".repeat(decimals) : ""), symbols);
            cachedDecimalFormat.set(df);
        }
        return df;
    }

    public boolean registerCurrency(String currency, CustomCurrencyProvider provider) {
        if (currency == null || provider == null)
            return false;
        customProviders.put(currency.toLowerCase(), provider);
        return true;
    }

    public boolean registerCurrency(String currency, String symbol, double startingBalance, double exchangeRate) {
        if (currency == null || currency.trim().isEmpty())
            return false;
        String key = currency.toLowerCase().trim();
        nativeRegisteredCurrencies.put(key,
                new NativeCurrencyConfig(key, symbol == null ? "$" : symbol, startingBalance, exchangeRate));
        return true;
    }

    public boolean unregisterCurrency(String currency) {
        if (currency == null)
            return false;
        String key = currency.toLowerCase().trim();
        boolean removedProvider = customProviders.remove(key) != null;
        boolean removedNative = nativeRegisteredCurrencies.remove(key) != null;
        return removedProvider || removedNative;
    }

    public List<String> getRegisteredCustomCurrencies() {
        Set<String> all = new HashSet<>(customProviders.keySet());
        all.addAll(nativeRegisteredCurrencies.keySet());
        return new ArrayList<>(all);
    }

    @SuppressWarnings("unchecked")
    public List<String> getSupportedCurrencies(Economy delegate) {
        if (delegate instanceof MultiCurrencyEconomy) {
            return ((MultiCurrencyEconomy) delegate).getSupportedCurrencies();
        }
        if (delegate != null) {
            try {
                java.lang.reflect.Method m = delegate.getClass().getMethod("getSupportedCurrencies");
                Object res = m.invoke(delegate);
                if (res instanceof List)
                    return (List<String>) res;
            } catch (Throwable ignored) {
            }
            try {
                java.lang.reflect.Method m = delegate.getClass().getMethod("getCurrencies");
                Object res = m.invoke(delegate);
                if (res instanceof List)
                    return (List<String>) res;
            } catch (Throwable ignored) {
            }
        }
        List<String> currencies = new ArrayList<>();
        currencies.add("default");
        if (exchangeRates != null) {
            for (String key : exchangeRates.getKeys(false)) {
                String normalized = key.toLowerCase();
                if (!normalized.equals("default") && !currencies.contains(normalized)) {
                    currencies.add(normalized);
                }
            }
        }
        for (String c : customProviders.keySet()) {
            if (!currencies.contains(c)) {
                currencies.add(c);
            }
        }
        for (String c : nativeRegisteredCurrencies.keySet()) {
            if (!currencies.contains(c)) {
                currencies.add(c);
            }
        }
        return currencies;
    }

    @Override
    public String getCurrencySymbol(String currency) {
        if (currency == null || currency.equalsIgnoreCase("default"))
            return "$";
        String key = currency.toLowerCase();
        NativeCurrencyConfig cfg = nativeRegisteredCurrencies.get(key);
        if (cfg != null)
            return cfg.symbol;
        if (currency.equalsIgnoreCase("gems"))
            return "💎";
        if (currency.equalsIgnoreCase("tokens"))
            return "🪙";
        if (currency.equalsIgnoreCase("coins"))
            return "🪙";
        return currency.toUpperCase();
    }

    @Override
    public String formatCurrency(String currency, double amount) {
        return formatCurrency(currency, amount, Locale.getDefault());
    }

    @Override
    public String formatCurrency(String currency, double amount, Locale locale) {
        String sym = getCurrencySymbol(currency);
        NumberFormat nf = NumberFormat.getNumberInstance(locale != null ? locale : Locale.getDefault());
        nf.setMinimumFractionDigits(2);
        nf.setMaximumFractionDigits(2);
        return nf.format(amount) + " " + sym;
    }

    public String format(double amount, Economy delegate) {
        if (delegate != null)
            return delegate.format(amount);

        if (formatUseShort) {
            String formatted;
            if (amount >= 1_000_000_000) {
                formatted = String.format(Locale.US, "%.2fB", amount / 1_000_000_000.0).replace('.', formatDecSep);
            } else if (amount >= 1_000_000) {
                formatted = String.format(Locale.US, "%.2fM", amount / 1_000_000.0).replace('.', formatDecSep);
            } else if (amount >= 1_000) {
                formatted = String.format(Locale.US, "%.2fk", amount / 1_000.0).replace('.', formatDecSep);
            } else {
                formatted = getDecimalFormat().format(amount);
            }
            return "BEFORE".equalsIgnoreCase(formatPosition) ? formatSymbol + formatted : formatted + formatSymbol;
        }

        String val = getDecimalFormat().format(amount);
        return "BEFORE".equalsIgnoreCase(formatPosition) ? formatSymbol + val : val + formatSymbol;
    }

    public Double invokeDelegateGetCurrencyBalance(Economy delegate, OfflinePlayer player, String currency) {
        if (delegate == null)
            return null;
        if (delegate instanceof MultiCurrencyEconomy) {
            return ((MultiCurrencyEconomy) delegate).getCurrencyBalance(player, currency);
        }
        try {
            java.lang.reflect.Method m = delegate.getClass().getMethod("getCurrencyBalance", OfflinePlayer.class,
                    String.class);
            return (Double) m.invoke(delegate, player, currency);
        } catch (Throwable ignored) {
        }
        try {
            java.lang.reflect.Method m = delegate.getClass().getMethod("getBalance", OfflinePlayer.class, String.class);
            return (Double) m.invoke(delegate, player, currency);
        } catch (Throwable ignored) {
        }
        return null;
    }

    public Double invokeDelegateGetCurrencyBalance(Economy delegate, String playerName, String currency) {
        if (delegate == null)
            return null;
        if (delegate instanceof MultiCurrencyEconomy) {
            return ((MultiCurrencyEconomy) delegate).getCurrencyBalance(playerName, currency);
        }
        try {
            java.lang.reflect.Method m = delegate.getClass().getMethod("getCurrencyBalance", String.class, String.class);
            return (Double) m.invoke(delegate, playerName, currency);
        } catch (Throwable ignored) {
        }
        try {
            java.lang.reflect.Method m = delegate.getClass().getMethod("getBalance", String.class, String.class);
            return (Double) m.invoke(delegate, playerName, currency);
        } catch (Throwable ignored) {
        }
        return null;
    }

    public EconomyResponse invokeDelegateWithdrawCurrency(Economy delegate, OfflinePlayer player, String currency, double amount) {
        if (delegate == null)
            return null;
        if (delegate instanceof MultiCurrencyEconomy) {
            return ((MultiCurrencyEconomy) delegate).withdrawCurrencyPlayer(player, currency, amount);
        }
        try {
            java.lang.reflect.Method m = delegate.getClass().getMethod("withdrawCurrencyPlayer", OfflinePlayer.class,
                    String.class, double.class);
            return (EconomyResponse) m.invoke(delegate, player, currency, amount);
        } catch (Throwable ignored) {
        }
        try {
            java.lang.reflect.Method m = delegate.getClass().getMethod("withdrawPlayer", OfflinePlayer.class,
                    String.class, double.class);
            return (EconomyResponse) m.invoke(delegate, player, currency, amount);
        } catch (Throwable ignored) {
        }
        return null;
    }

    public EconomyResponse invokeDelegateDepositCurrency(Economy delegate, OfflinePlayer player, String currency, double amount) {
        if (delegate == null)
            return null;
        if (delegate instanceof MultiCurrencyEconomy) {
            return ((MultiCurrencyEconomy) delegate).depositCurrencyPlayer(player, currency, amount);
        }
        try {
            java.lang.reflect.Method m = delegate.getClass().getMethod("depositCurrencyPlayer", OfflinePlayer.class,
                    String.class, double.class);
            return (EconomyResponse) m.invoke(delegate, player, currency, amount);
        } catch (Throwable ignored) {
        }
        try {
            java.lang.reflect.Method m = delegate.getClass().getMethod("depositPlayer", OfflinePlayer.class,
                    String.class, double.class);
            return (EconomyResponse) m.invoke(delegate, player, currency, amount);
        } catch (Throwable ignored) {
        }
        return null;
    }

    public boolean hasCurrencyAccount(Economy delegate, OfflinePlayer player, String currency) {
        if (delegate != null) {
            if (delegate instanceof MultiCurrencyEconomy) {
                return ((MultiCurrencyEconomy) delegate).hasCurrencyAccount(player, currency);
            }
            try {
                java.lang.reflect.Method m = delegate.getClass().getMethod("hasCurrencyAccount", OfflinePlayer.class,
                        String.class);
                return (Boolean) m.invoke(delegate, player, currency);
            } catch (Throwable ignored) {
            }
            try {
                java.lang.reflect.Method m = delegate.getClass().getMethod("hasAccount", OfflinePlayer.class,
                        String.class);
                return (Boolean) m.invoke(delegate, player, currency);
            } catch (Throwable ignored) {
            }
        }
        return delegate != null ? delegate.hasAccount(player) : true;
    }

    public void clear() {
        customProviders.clear();
        nativeRegisteredCurrencies.clear();
    }

    public static class NativeCurrencyConfig {
        public final String currencyId;
        public final String symbol;
        public final double startingBalance;
        public final double exchangeRate;

        public NativeCurrencyConfig(String currencyId, String symbol, double startingBalance, double exchangeRate) {
            this.currencyId = currencyId;
            this.symbol = symbol;
            this.startingBalance = startingBalance;
            this.exchangeRate = exchangeRate;
        }
    }
}
