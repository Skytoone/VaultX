package fr.skynex.vaultx.util;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.MultiCurrencyEconomy;
import net.milkbowl.vault.economy.VaultAsyncEconomy;
import net.milkbowl.vault.economy.CurrencyExchangeAPI;
import net.milkbowl.vault.economy.VaultLeaderboardAPI;
import net.milkbowl.vault.economy.VaultBatchTransactionAPI;
import net.milkbowl.vault.economy.VaultFormatAPI;
import net.milkbowl.vault.economy.VaultMailboxAPI;
import net.milkbowl.vault.economy.VaultBoosterAPI;
import net.milkbowl.vault.economy.VaultLockAPI;
import net.milkbowl.vault.economy.VaultSubscriptionAPI;
import net.milkbowl.vault.economy.VaultAnalyticsAPI;
import net.milkbowl.vault.economy.VaultCurrencyRegistry;
import net.milkbowl.vault.economy.VaultAuditAPI;
import net.milkbowl.vault.economy.VaultEscrowAPI;
import net.milkbowl.vault.economy.VaultFirewallAPI;
import net.milkbowl.vault.economy.VaultBlackMarketAPI;
import net.milkbowl.vault.economy.VaultStockAPI;
import net.milkbowl.vault.economy.VaultCheckAPI;
import net.milkbowl.vault.economy.VaultLoanAPI;
import net.milkbowl.vault.economy.VaultInflationAPI;
import net.milkbowl.vault.economy.VaultMilestoneAPI;
import net.milkbowl.vault.economy.VaultCryptoAPI;
import net.milkbowl.vault.economy.VaultAuctionAPI;
import net.milkbowl.vault.economy.VaultStakingAPI;
import net.milkbowl.vault.economy.VaultTaxAPI;
import net.milkbowl.vault.economy.VaultCreditAPI;

import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import java.util.Optional;

/**
 * Developer utility class providing 1-line convenient access to all VaultX services.
 */
public final class VaultXHook {

    private VaultXHook() {}

    /**
     * @return Standard Vault Economy provider instance if present.
     */
    public static Optional<Economy> getEconomy() {
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        return rsp != null ? Optional.ofNullable(rsp.getProvider()) : Optional.empty();
    }

    /**
     * @return VaultX MultiCurrencyEconomy provider instance if available.
     */
    public static Optional<MultiCurrencyEconomy> getMultiCurrencyEconomy() {
        return getEconomy().filter(e -> e instanceof MultiCurrencyEconomy).map(e -> (MultiCurrencyEconomy) e);
    }

    /**
     * @return VaultX VaultAsyncEconomy provider instance if available.
     */
    public static Optional<VaultAsyncEconomy> getAsyncEconomy() {
        return getEconomy().filter(e -> e instanceof VaultAsyncEconomy).map(e -> (VaultAsyncEconomy) e);
    }

    /**
     * @return VaultX CurrencyExchangeAPI service provider if available.
     */
    public static Optional<CurrencyExchangeAPI> getExchangeAPI() {
        RegisteredServiceProvider<CurrencyExchangeAPI> rsp = Bukkit.getServicesManager().getRegistration(CurrencyExchangeAPI.class);
        return rsp != null ? Optional.ofNullable(rsp.getProvider()) : Optional.empty();
    }

    /**
     * @return VaultX VaultLeaderboardAPI service provider if available.
     */
    public static Optional<VaultLeaderboardAPI> getLeaderboardAPI() {
        RegisteredServiceProvider<VaultLeaderboardAPI> rsp = Bukkit.getServicesManager().getRegistration(VaultLeaderboardAPI.class);
        return rsp != null ? Optional.ofNullable(rsp.getProvider()) : Optional.empty();
    }

    /**
     * @return VaultX VaultBatchTransactionAPI service provider if available.
     */
    public static Optional<VaultBatchTransactionAPI> getBatchAPI() {
        RegisteredServiceProvider<VaultBatchTransactionAPI> rsp = Bukkit.getServicesManager().getRegistration(VaultBatchTransactionAPI.class);
        return rsp != null ? Optional.ofNullable(rsp.getProvider()) : Optional.empty();
    }

    /**
     * @return VaultX VaultFormatAPI service provider if available.
     */
    public static Optional<VaultFormatAPI> getFormatAPI() {
        RegisteredServiceProvider<VaultFormatAPI> rsp = Bukkit.getServicesManager().getRegistration(VaultFormatAPI.class);
        return rsp != null ? Optional.ofNullable(rsp.getProvider()) : Optional.empty();
    }

    /**
     * @return VaultX VaultMailboxAPI service provider if available.
     */
    public static Optional<VaultMailboxAPI> getMailboxAPI() {
        RegisteredServiceProvider<VaultMailboxAPI> rsp = Bukkit.getServicesManager().getRegistration(VaultMailboxAPI.class);
        return rsp != null ? Optional.ofNullable(rsp.getProvider()) : Optional.empty();
    }

    /**
     * @return VaultX VaultBoosterAPI service provider if available.
     */
    public static Optional<VaultBoosterAPI> getBoosterAPI() {
        RegisteredServiceProvider<VaultBoosterAPI> rsp = Bukkit.getServicesManager().getRegistration(VaultBoosterAPI.class);
        return rsp != null ? Optional.ofNullable(rsp.getProvider()) : Optional.empty();
    }

    /**
     * @return VaultX VaultLockAPI service provider if available.
     */
    public static Optional<VaultLockAPI> getLockAPI() {
        RegisteredServiceProvider<VaultLockAPI> rsp = Bukkit.getServicesManager().getRegistration(VaultLockAPI.class);
        return rsp != null ? Optional.ofNullable(rsp.getProvider()) : Optional.empty();
    }

    /**
     * @return VaultX VaultSubscriptionAPI service provider if available.
     */
    public static Optional<VaultSubscriptionAPI> getSubscriptionAPI() {
        RegisteredServiceProvider<VaultSubscriptionAPI> rsp = Bukkit.getServicesManager().getRegistration(VaultSubscriptionAPI.class);
        return rsp != null ? Optional.ofNullable(rsp.getProvider()) : Optional.empty();
    }

    /**
     * @return VaultX VaultAnalyticsAPI service provider if available.
     */
    public static Optional<VaultAnalyticsAPI> getAnalyticsAPI() {
        RegisteredServiceProvider<VaultAnalyticsAPI> rsp = Bukkit.getServicesManager().getRegistration(VaultAnalyticsAPI.class);
        return rsp != null ? Optional.ofNullable(rsp.getProvider()) : Optional.empty();
    }

    /**
     * @return VaultX VaultCurrencyRegistry service provider if available.
     */
    public static Optional<VaultCurrencyRegistry> getCurrencyRegistry() {
        RegisteredServiceProvider<VaultCurrencyRegistry> rsp = Bukkit.getServicesManager().getRegistration(VaultCurrencyRegistry.class);
        return rsp != null ? Optional.ofNullable(rsp.getProvider()) : Optional.empty();
    }

    /**
     * @return VaultX VaultAuditAPI service provider if available.
     */
    public static Optional<VaultAuditAPI> getAuditAPI() {
        RegisteredServiceProvider<VaultAuditAPI> rsp = Bukkit.getServicesManager().getRegistration(VaultAuditAPI.class);
        return rsp != null ? Optional.ofNullable(rsp.getProvider()) : Optional.empty();
    }

    /**
     * @return VaultX VaultEscrowAPI service provider if available.
     */
    public static Optional<VaultEscrowAPI> getEscrowAPI() {
        RegisteredServiceProvider<VaultEscrowAPI> rsp = Bukkit.getServicesManager().getRegistration(VaultEscrowAPI.class);
        return rsp != null ? Optional.ofNullable(rsp.getProvider()) : Optional.empty();
    }

    /**
     * @return VaultX VaultFirewallAPI service provider if available.
     */
    public static Optional<VaultFirewallAPI> getFirewallAPI() {
        RegisteredServiceProvider<VaultFirewallAPI> rsp = Bukkit.getServicesManager().getRegistration(VaultFirewallAPI.class);
        return rsp != null ? Optional.ofNullable(rsp.getProvider()) : Optional.empty();
    }

    /**
     * @return VaultX VaultBlackMarketAPI service provider if available.
     */
    public static Optional<VaultBlackMarketAPI> getBlackMarketAPI() {
        RegisteredServiceProvider<VaultBlackMarketAPI> rsp = Bukkit.getServicesManager().getRegistration(VaultBlackMarketAPI.class);
        return rsp != null ? Optional.ofNullable(rsp.getProvider()) : Optional.empty();
    }

    /**
     * @return VaultX VaultStockAPI service provider if available.
     */
    public static Optional<VaultStockAPI> getStockAPI() {
        RegisteredServiceProvider<VaultStockAPI> rsp = Bukkit.getServicesManager().getRegistration(VaultStockAPI.class);
        return rsp != null ? Optional.ofNullable(rsp.getProvider()) : Optional.empty();
    }

    /**
     * @return VaultX VaultCheckAPI service provider if available.
     */
    public static Optional<VaultCheckAPI> getCheckAPI() {
        RegisteredServiceProvider<VaultCheckAPI> rsp = Bukkit.getServicesManager().getRegistration(VaultCheckAPI.class);
        return rsp != null ? Optional.ofNullable(rsp.getProvider()) : Optional.empty();
    }

    /**
     * @return VaultX VaultLoanAPI service provider if available.
     */
    public static Optional<VaultLoanAPI> getLoanAPI() {
        RegisteredServiceProvider<VaultLoanAPI> rsp = Bukkit.getServicesManager().getRegistration(VaultLoanAPI.class);
        return rsp != null ? Optional.ofNullable(rsp.getProvider()) : Optional.empty();
    }

    /**
     * @return VaultX VaultInflationAPI service provider if available.
     */
    public static Optional<VaultInflationAPI> getInflationAPI() {
        RegisteredServiceProvider<VaultInflationAPI> rsp = Bukkit.getServicesManager().getRegistration(VaultInflationAPI.class);
        return rsp != null ? Optional.ofNullable(rsp.getProvider()) : Optional.empty();
    }

    /**
     * @return VaultX VaultMilestoneAPI service provider if available.
     */
    public static Optional<VaultMilestoneAPI> getMilestoneAPI() {
        RegisteredServiceProvider<VaultMilestoneAPI> rsp = Bukkit.getServicesManager().getRegistration(VaultMilestoneAPI.class);
        return rsp != null ? Optional.ofNullable(rsp.getProvider()) : Optional.empty();
    }

    /**
     * @return VaultX VaultCryptoAPI service provider if available.
     */
    public static Optional<VaultCryptoAPI> getCryptoAPI() {
        RegisteredServiceProvider<VaultCryptoAPI> rsp = Bukkit.getServicesManager().getRegistration(VaultCryptoAPI.class);
        return rsp != null ? Optional.ofNullable(rsp.getProvider()) : Optional.empty();
    }

    /**
     * @return VaultX VaultAuctionAPI service provider if available.
     */
    public static Optional<VaultAuctionAPI> getAuctionAPI() {
        RegisteredServiceProvider<VaultAuctionAPI> rsp = Bukkit.getServicesManager().getRegistration(VaultAuctionAPI.class);
        return rsp != null ? Optional.ofNullable(rsp.getProvider()) : Optional.empty();
    }

    /**
     * @return VaultX VaultStakingAPI service provider if available.
     */
    public static Optional<VaultStakingAPI> getStakingAPI() {
        RegisteredServiceProvider<VaultStakingAPI> rsp = Bukkit.getServicesManager().getRegistration(VaultStakingAPI.class);
        return rsp != null ? Optional.ofNullable(rsp.getProvider()) : Optional.empty();
    }

    /**
     * @return VaultX VaultTaxAPI service provider if available.
     */
    public static Optional<VaultTaxAPI> getTaxAPI() {
        RegisteredServiceProvider<VaultTaxAPI> rsp = Bukkit.getServicesManager().getRegistration(VaultTaxAPI.class);
        return rsp != null ? Optional.ofNullable(rsp.getProvider()) : Optional.empty();
    }

    /**
     * @return VaultX VaultCreditAPI service provider if available.
     */
    public static Optional<VaultCreditAPI> getCreditAPI() {
        RegisteredServiceProvider<VaultCreditAPI> rsp = Bukkit.getServicesManager().getRegistration(VaultCreditAPI.class);
        return rsp != null ? Optional.ofNullable(rsp.getProvider()) : Optional.empty();
    }
}
