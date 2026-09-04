package net.milkbowl.vault.economy.service;

import net.milkbowl.vault.Vault;
import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.economy.VaultMailboxAPI;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.BiFunction;

/**
 * Handles offline payments and mailbox integration logic.
 */
public class MailboxService implements VaultMailboxAPI {

    private final Plugin plugin;
    private final ExecutorService asyncExecutor;

    public MailboxService(Plugin plugin, ExecutorService asyncExecutor) {
        this.plugin = plugin;
        this.asyncExecutor = asyncExecutor;
    }

    public CompletableFuture<Boolean> sendOfflinePaymentAsync(
            UUID targetUuid,
            String currency,
            double amount,
            String sourceReason,
            BiFunction<OfflinePlayer, String, BiFunction<Double, Boolean, EconomyResponse>> depositFunction) {
        return CompletableFuture.supplyAsync(() -> {
            if (targetUuid == null || amount <= 0) {
                return false;
            }
            OfflinePlayer target = Bukkit.getOfflinePlayer(targetUuid);
            if (target.isOnline()) {
                EconomyResponse res = depositFunction.apply(target, currency).apply(amount, true);
                return res.transactionSuccess();
            } else if (plugin.getConfig().getBoolean("mailbox.enabled", true) && Vault.getMailboxManager() != null) {
                Vault.getMailboxManager().sendOfflineMail(
                        targetUuid,
                        sourceReason != null ? sourceReason : "System",
                        sourceReason != null ? sourceReason : "Offline Payment",
                        amount,
                        currency != null ? currency : "default"
                );
                return true;
            } else {
                EconomyResponse res = depositFunction.apply(target, currency).apply(amount, true);
                return res.transactionSuccess();
            }
        }, asyncExecutor);
    }

    @Override
    public CompletableFuture<Boolean> sendOfflinePaymentAsync(UUID targetUuid, String currency, double amount, String sourceReason) {
        return sendOfflinePaymentAsync(targetUuid, currency, amount, sourceReason, (player, curr) -> (amt, async) -> {
            var registry = net.milkbowl.vault.Vault.getServiceRegistry();
            var econ = (registry != null && !registry.getWrappedEconomies().isEmpty()) ? registry.getWrappedEconomies().get(0) : null;
            if (econ != null) {
                return econ.depositCurrencyPlayer(player, curr, amt);
            }
            return new net.milkbowl.vault.economy.EconomyResponse(0, 0, net.milkbowl.vault.economy.EconomyResponse.ResponseType.FAILURE, "No economy provider");
        });
    }

    @Override
    public CompletableFuture<Boolean> sendOfflinePaymentAsync(OfflinePlayer target, String currency, double amount, String sourceReason) {
        return target != null ? sendOfflinePaymentAsync(target.getUniqueId(), currency, amount, sourceReason) : CompletableFuture.completedFuture(false);
    }
}
