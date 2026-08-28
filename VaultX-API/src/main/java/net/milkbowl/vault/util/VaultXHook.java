package net.milkbowl.vault.util;

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
}
