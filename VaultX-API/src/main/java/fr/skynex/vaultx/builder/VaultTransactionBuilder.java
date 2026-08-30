package fr.skynex.vaultx.builder;

import fr.skynex.vaultx.util.VaultXHook;
import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.economy.MultiCurrencyEconomy;
import org.bukkit.OfflinePlayer;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Fluent builder pattern for constructing, validating, and executing complex asynchronous VaultX transfers.
 */
public class VaultTransactionBuilder {

    private OfflinePlayer sender;
    private OfflinePlayer receiver;
    private String currency = "default";
    private double amount = 0.0;
    private boolean applyTax = false;
    private String reason = null;

    private VaultTransactionBuilder() {}

    public static VaultTransactionBuilder create() {
        return new VaultTransactionBuilder();
    }

    public VaultTransactionBuilder from(OfflinePlayer sender) {
        this.sender = sender;
        return this;
    }

    public VaultTransactionBuilder to(OfflinePlayer receiver) {
        this.receiver = receiver;
        return this;
    }

    public VaultTransactionBuilder currency(String currency) {
        this.currency = currency != null ? currency : "default";
        return this;
    }

    public VaultTransactionBuilder amount(double amount) {
        this.amount = amount;
        return this;
    }

    public VaultTransactionBuilder applyTax(boolean applyTax) {
        this.applyTax = applyTax;
        return this;
    }

    public VaultTransactionBuilder reason(String reason) {
        this.reason = reason;
        return this;
    }

    /**
     * Asynchronously executes the transaction using the registered MultiCurrencyEconomy service.
     *
     * @return Future containing EconomyResponse
     */
    public CompletableFuture<EconomyResponse> executeAsync() {
        if (amount <= 0) {
            return CompletableFuture.completedFuture(
                    new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Transaction amount must be strictly greater than zero.")
            );
        }

        return CompletableFuture.supplyAsync(() -> {
            var multiEconOpt = VaultXHook.getMultiCurrencyEconomy();
            if (multiEconOpt.isEmpty()) {
                return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "VaultX MultiCurrencyEconomy service provider is not registered.");
            }

            MultiCurrencyEconomy econ = multiEconOpt.get();

            if (sender != null) {
                var withdrawRes = econ.withdrawCurrencyPlayer(sender, currency, amount);
                if (!withdrawRes.transactionSuccess()) {
                    return withdrawRes;
                }
            }

            if (receiver != null) {
                var depositRes = econ.depositCurrencyPlayer(receiver, currency, amount);
                if (!depositRes.transactionSuccess()) {
                    // Rollback sender if deposit fails
                    if (sender != null) {
                        econ.depositCurrencyPlayer(sender, currency, amount);
                    }
                    return depositRes;
                }
            }

            double newBal = sender != null ? econ.getCurrencyBalance(sender, currency) : (receiver != null ? econ.getCurrencyBalance(receiver, currency) : 0);
            return new EconomyResponse(amount, newBal, EconomyResponse.ResponseType.SUCCESS, reason);
        });
    }
}
