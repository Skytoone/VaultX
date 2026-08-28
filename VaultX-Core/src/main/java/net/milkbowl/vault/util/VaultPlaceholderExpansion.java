package net.milkbowl.vault.util;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.MultiCurrencyEconomy;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServiceRegisterEvent;

public class VaultPlaceholderExpansion extends PlaceholderExpansion implements Listener {

    private final Plugin plugin;
    private Economy cachedEconomy;
    private java.util.Set<String> cachedCurrencies;
    private long lastCurrenciesFetch;

    public VaultPlaceholderExpansion(Plugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onServiceRegister(ServiceRegisterEvent event) {
        if (event.getProvider().getService().equals(Economy.class)) {
            cachedEconomy = null;
            cachedCurrencies = null;
        }
    }

    private Economy getEconomy() {
        if (cachedEconomy == null) {
            RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
            if (rsp != null) {
                cachedEconomy = rsp.getProvider();
            }
        }
        return cachedEconomy;
    }

    @Override
    public String getIdentifier() {
        return "vaultx";
    }

    @Override
    public String getAuthor() {
        return "Skytoone";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (params == null) {
            return null;
        }

        Economy econ = getEconomy();
        if (econ == null) {
            return null;
        }

        String cleanParams = params;
        if (cleanParams.toLowerCase().startsWith("eco_")) {
            cleanParams = cleanParams.substring(4);
        }

        // format: top_name_<position>_<currency>
        // format: top_balance_<position>_<currency>
        // format: top_balance_formatted_<position>_<currency>
        if (cleanParams.startsWith("top_")) {
            return handleTopPlaceholder(econ, cleanParams);
        }

        if (cleanParams.startsWith("inflation_") || cleanParams.startsWith("dynamic_") || cleanParams.startsWith("money_supply") || cleanParams.startsWith("baseline_supply")) {
            String dpResult = handleDynamicPricingPlaceholder(econ, cleanParams);
            if (dpResult != null) {
                return dpResult;
            }
        }

        // Check if the placeholder is a valid balance/balance_formatted request
        boolean isBalance = false;
        boolean isFormatted = false;
        String currency = null;

        if (cleanParams.equalsIgnoreCase("balance")) {
            isBalance = true;
            isFormatted = false;
            currency = "default";
        } else if (cleanParams.equalsIgnoreCase("balance_formatted")) {
            isBalance = true;
            isFormatted = true;
            currency = "default";
        } else if (cleanParams.startsWith("balance_")) {
            String sub = cleanParams.substring(8);
            if (sub.startsWith("formatted_")) {
                isBalance = true;
                isFormatted = true;
                currency = sub.substring(10);
            } else {
                isBalance = true;
                isFormatted = false;
                currency = sub;
            }
        }

        if (isBalance) {
            if (!isSupportedCurrency(econ, currency)) {
                return null;
            }
            if (player == null) {
                if (isFormatted) {
                    return econ.format(0.0);
                } else {
                    return "0.00";
                }
            }
            double balance = getBalanceFor(econ, player, currency);
            if (isFormatted) {
                return econ.format(balance);
            } else {
                return String.format(java.util.Locale.US, "%.2f", balance);
            }
        }

        return null;
    }

    private boolean isSupportedCurrency(Economy econ, String currency) {
        if (currency == null) {
            return false;
        }
        if (currency.equalsIgnoreCase("default") || currency.isEmpty()) {
            return true;
        }
        if (econ instanceof MultiCurrencyEconomy) {
            long now = System.currentTimeMillis();
            if (cachedCurrencies == null || (now - lastCurrenciesFetch > 5000)) {
                java.util.List<String> list = ((MultiCurrencyEconomy) econ).getSupportedCurrencies();
                java.util.Set<String> set = new java.util.HashSet<>();
                if (list != null) {
                    for (String c : list) {
                        if (c != null) set.add(c.toLowerCase());
                    }
                }
                cachedCurrencies = set;
                lastCurrenciesFetch = now;
            }
            return cachedCurrencies != null && cachedCurrencies.contains(currency.toLowerCase());
        }
        return false;
    }

    private String handleTopPlaceholder(Economy econ, String params) {
        String[] parts = params.split("_");
        if (parts.length < 3) {
            return null;
        }
        String type = parts[1]; // "name" or "balance"
        if (!type.equals("name") && !type.equals("balance")) {
            return null;
        }

        boolean formatted = false;
        int positionIndex = 2;

        if (type.equals("balance") && parts.length > 3 && parts[2].equals("formatted")) {
            formatted = true;
            positionIndex = 3;
        }

        int position;
        try {
            position = Integer.parseInt(parts[positionIndex]);
        } catch (NumberFormatException e) {
            return null;
        }

        StringBuilder currencyBuilder = new StringBuilder();
        for (int i = positionIndex + 1; i < parts.length; i++) {
            if (currencyBuilder.length() > 0) {
                currencyBuilder.append("_");
            }
            currencyBuilder.append(parts[i]);
        }
        String currency = currencyBuilder.toString().toLowerCase();
        if (currency.isEmpty()) {
            currency = "default";
        }

        if (!isSupportedCurrency(econ, currency)) {
            return null;
        }

        net.milkbowl.vault.redis.VaultRedisManager redis = net.milkbowl.vault.redis.VaultRedisManager.getInstance();
        if (redis == null) {
            if (type.equals("name")) {
                return "---";
            } else {
                return formatted ? econ.format(0.0) : "0.00";
            }
        }

        java.util.List<net.milkbowl.vault.redis.VaultRedisManager.LeaderboardEntry> leaderboard = redis.getLeaderboard(currency);
        if (leaderboard == null || position < 1 || position > leaderboard.size()) {
            if (type.equals("name")) {
                return "---";
            } else {
                return formatted ? econ.format(0.0) : "0.00";
            }
        }

        net.milkbowl.vault.redis.VaultRedisManager.LeaderboardEntry entry = leaderboard.get(position - 1);
        if (entry == null) {
            if (type.equals("name")) {
                return "---";
            } else {
                return formatted ? econ.format(0.0) : "0.00";
            }
        }

        if (type.equals("name")) {
            return entry.name != null ? entry.name : "---";
        } else {
            if (formatted) {
                return econ.format(entry.balance);
            } else {
                return String.format(java.util.Locale.US, "%.2f", entry.balance);
            }
        }
    }

    private double getBalanceFor(Economy econ, OfflinePlayer player, String currency) {
        if (currency == null || currency.equalsIgnoreCase("default") || currency.isEmpty()) {
            return econ.getBalance(player);
        }
        if (econ instanceof MultiCurrencyEconomy) {
            return ((MultiCurrencyEconomy) econ).getCurrencyBalance(player, currency);
        }
        return econ.getBalance(player);
    }

    private String handleDynamicPricingPlaceholder(Economy econ, String params) {
        net.milkbowl.vault.economy.DynamicPricingManager dpManager = net.milkbowl.vault.Vault.getDynamicPricingManager();
        if (dpManager == null || !dpManager.isEnabled()) {
            return null;
        }

        String lower = params.toLowerCase();

        if (lower.equals("inflation_multiplier") || lower.startsWith("inflation_multiplier_")) {
            String currency = lower.equals("inflation_multiplier") ? "default" : lower.substring("inflation_multiplier_".length());
            double mult = dpManager.getInflationMultiplier(currency);
            return String.format(java.util.Locale.US, "%.2f", mult);
        }

        if (lower.equals("inflation_percent") || lower.startsWith("inflation_percent_")) {
            String currency = lower.equals("inflation_percent") ? "default" : lower.substring("inflation_percent_".length());
            double mult = dpManager.getInflationMultiplier(currency);
            double pct = (mult - 1.0) * 100.0;
            return String.format(java.util.Locale.US, "%+.1f%%", pct);
        }

        if (lower.equals("money_supply") || lower.startsWith("money_supply_")) {
            String currency = lower.equals("money_supply") ? "default" : lower.substring("money_supply_".length());
            double supply = dpManager.getCurrentMoneySupply(currency);
            return String.format(java.util.Locale.US, "%.2f", supply);
        }

        if (lower.equals("baseline_supply") || lower.startsWith("baseline_supply_")) {
            String currency = lower.equals("baseline_supply") ? "default" : lower.substring("baseline_supply_".length());
            double base = dpManager.getBaselineMoneySupply(currency);
            return String.format(java.util.Locale.US, "%.2f", base);
        }

        if (lower.startsWith("dynamic_price_")) {
            String sub = lower.substring("dynamic_price_".length());
            boolean formatted = false;
            if (sub.startsWith("formatted_")) {
                formatted = true;
                sub = sub.substring("formatted_".length());
            }

            String[] parts = sub.split("_");
            if (parts.length >= 1) {
                try {
                    double basePrice = Double.parseDouble(parts[0]);
                    String currency = parts.length > 1 ? parts[1] : "default";
                    double adjusted = dpManager.getAdjustedBuyPrice(currency, basePrice);
                    if (formatted) {
                        return econ.format(adjusted);
                    } else {
                        return String.format(java.util.Locale.US, "%.2f", adjusted);
                    }
                } catch (NumberFormatException ignored) {}
            }
        }

        if (lower.startsWith("dynamic_sell_price_")) {
            String sub = lower.substring("dynamic_sell_price_".length());
            boolean formatted = false;
            if (sub.startsWith("formatted_")) {
                formatted = true;
                sub = sub.substring("formatted_".length());
            }

            String[] parts = sub.split("_");
            if (parts.length >= 1) {
                try {
                    double basePrice = Double.parseDouble(parts[0]);
                    String currency = parts.length > 1 ? parts[1] : "default";
                    double adjusted = dpManager.getAdjustedSellPrice(currency, basePrice);
                    if (formatted) {
                        return econ.format(adjusted);
                    } else {
                        return String.format(java.util.Locale.US, "%.2f", adjusted);
                    }
                } catch (NumberFormatException ignored) {}
            }
        }

        if (lower.startsWith("dynamic_tax_")) {
            String sub = lower.substring("dynamic_tax_".length());
            String[] parts = sub.split("_");
            if (parts.length >= 1) {
                try {
                    double baseTax = Double.parseDouble(parts[0]);
                    String currency = parts.length > 1 ? parts[1] : "default";
                    double adjusted = dpManager.getAdjustedTaxPercent(currency, baseTax);
                    return String.format(java.util.Locale.US, "%.2f", adjusted);
                } catch (NumberFormatException ignored) {}
            }
        }

        return null;
    }
}
