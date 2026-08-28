package net.milkbowl.vault.util;

import net.milkbowl.vault.economy.*;
import java.util.Optional;

/**
 * @deprecated Use {@link fr.skynex.vaultx.util.VaultXHook} instead.
 */
@Deprecated
public final class VaultXHook {

    private VaultXHook() {}

    public static Optional<Economy> getEconomy() { return fr.skynex.vaultx.util.VaultXHook.getEconomy(); }
    public static Optional<MultiCurrencyEconomy> getMultiCurrencyEconomy() { return fr.skynex.vaultx.util.VaultXHook.getMultiCurrencyEconomy(); }
    public static Optional<VaultAsyncEconomy> getAsyncEconomy() { return fr.skynex.vaultx.util.VaultXHook.getAsyncEconomy(); }
    public static Optional<CurrencyExchangeAPI> getExchangeAPI() { return fr.skynex.vaultx.util.VaultXHook.getExchangeAPI(); }
    public static Optional<VaultLeaderboardAPI> getLeaderboardAPI() { return fr.skynex.vaultx.util.VaultXHook.getLeaderboardAPI(); }
    public static Optional<VaultBatchTransactionAPI> getBatchAPI() { return fr.skynex.vaultx.util.VaultXHook.getBatchAPI(); }
    public static Optional<VaultFormatAPI> getFormatAPI() { return fr.skynex.vaultx.util.VaultXHook.getFormatAPI(); }
    public static Optional<VaultMailboxAPI> getMailboxAPI() { return fr.skynex.vaultx.util.VaultXHook.getMailboxAPI(); }
    public static Optional<VaultBoosterAPI> getBoosterAPI() { return fr.skynex.vaultx.util.VaultXHook.getBoosterAPI(); }
    public static Optional<VaultLockAPI> getLockAPI() { return fr.skynex.vaultx.util.VaultXHook.getLockAPI(); }
    public static Optional<VaultSubscriptionAPI> getSubscriptionAPI() { return fr.skynex.vaultx.util.VaultXHook.getSubscriptionAPI(); }
    public static Optional<VaultAnalyticsAPI> getAnalyticsAPI() { return fr.skynex.vaultx.util.VaultXHook.getAnalyticsAPI(); }
    public static Optional<VaultCurrencyRegistry> getCurrencyRegistry() { return fr.skynex.vaultx.util.VaultXHook.getCurrencyRegistry(); }
    public static Optional<VaultAuditAPI> getAuditAPI() { return fr.skynex.vaultx.util.VaultXHook.getAuditAPI(); }
}
