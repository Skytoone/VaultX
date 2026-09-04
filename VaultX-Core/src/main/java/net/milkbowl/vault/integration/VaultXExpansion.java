package net.milkbowl.vault.integration;

import net.milkbowl.vault.Vault;
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
        return "SkyNex";
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

        // %vaultx_balance_formatted% or %vaultx_balance_formatted_<currency>%
        if (params.equalsIgnoreCase("balance_formatted")) {
            return econ.format(econ.getBalance(player));
        }

        // %vaultx_balance_<currency>%
        if (params.startsWith("balance_")) {
            String currency = params.substring(8);
            if (currency.startsWith("formatted_")) {
                String curr = currency.substring(10);
                return econ.getCurrencyService().formatCurrency(curr, econ.getCurrencyBalance(player, curr));
            }
            return String.format("%.2f", econ.getCurrencyBalance(player, currency));
        }

        // %vaultx_dirty_balance%
        if (params.equalsIgnoreCase("dirty_balance")) {
            if (Vault.getBlackMarketAPI() != null) {
                return String.format("%.2f", Vault.getBlackMarketAPI().getDirtyBalance(player));
            }
            return "0.00";
        }

        // %vaultx_credit_score%
        if (params.equalsIgnoreCase("credit_score")) {
            if (Vault.getCreditManager() != null) {
                var acc = Vault.getCreditManager().getCreditAccountAsync(player, "default", java.util.concurrent.ForkJoinPool.commonPool()).join();
                return acc != null ? String.valueOf(acc.creditScore()) : "700";
            }
            return "700";
        }

        // %vaultx_crypto_balance_<cryptoName>%
        if (params.startsWith("crypto_balance_")) {
            String cryptoName = params.substring(15);
            if (Vault.getCryptoManager() != null) {
                var wallet = Vault.getCryptoManager().getWalletAsync(player, cryptoName, java.util.concurrent.ForkJoinPool.commonPool()).join();
                return wallet != null ? String.format("%.4f", wallet.balance()) : "0.0000";
            }
            return "0.0000";
        }

        // %vaultx_symbol_<currency>%
        if (params.startsWith("symbol_")) {
            String currency = params.substring(7);
            return econ.getCurrencyService().getCurrencySymbol(currency);
        }

        // %vaultx_multiplier_<currency>%
        if (params.startsWith("multiplier_")) {
            String currency = params.substring(11);
            return String.format("%.2f", econ.getBoosterService().getGlobalMultiplier(currency));
        }

        return null;
    }
}
