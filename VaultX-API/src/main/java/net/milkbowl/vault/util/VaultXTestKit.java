package net.milkbowl.vault.util;

import net.milkbowl.vault.economy.MultiCurrencyEconomy;

/**
 * @deprecated Use {@link fr.skynex.vaultx.util.VaultXTestKit} instead.
 */
@Deprecated
public final class VaultXTestKit {

    private VaultXTestKit() {}

    public static MultiCurrencyEconomy createMockEconomy(double defaultStartingBalance) {
        return fr.skynex.vaultx.util.VaultXTestKit.createMockEconomy(defaultStartingBalance);
    }
}
