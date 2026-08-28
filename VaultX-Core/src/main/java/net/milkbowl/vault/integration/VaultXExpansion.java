package net.milkbowl.vault.integration;

import net.milkbowl.vault.Vault;
import net.milkbowl.vault.economy.MultiCurrencyEconomy;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Official PlaceholderAPI Expansion for VaultX multi-currency and economy features.
 */
public class VaultXExpansion extends PlaceholderExpansion {

    private final Vault plugin;

    public VaultXExpansion(Vault plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "vaultx";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Skynex";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (params.equalsIgnoreCase("version")) {
            return getVersion();
        }

        if (player == null) return "";

        List<net.milkbowl.vault.economy.OptimizedEconomy> econs = Vault.getWrappedEconomies();
        if (econs.isEmpty()) return "";
        net.milkbowl.vault.economy.OptimizedEconomy econ = econs.get(0);

        // %vaultx_balance%
        if (params.equalsIgnoreCase("balance")) {
            return String.format("%.2f", econ.getBalance(player));
        }

        // %vaultx_balance_<currency>%
        if (params.startsWith("balance_")) {
            String currency = params.substring(8);
            if (currency.startsWith("formatted_")) {
                String curr = currency.substring(10);
                return econ.formatCurrency(curr, econ.getCurrencyBalance(player, curr));
            }
            return String.format("%.2f", econ.getCurrencyBalance(player, currency));
        }

        // %vaultx_symbol_<currency>%
        if (params.startsWith("symbol_")) {
            String currency = params.substring(7);
            return econ.getCurrencySymbol(currency);
        }

        // %vaultx_multiplier_<currency>%
        if (params.startsWith("multiplier_")) {
            String currency = params.substring(11);
            return String.format("%.2f", econ.getGlobalMultiplier(currency));
        }

        return null;
    }
}
