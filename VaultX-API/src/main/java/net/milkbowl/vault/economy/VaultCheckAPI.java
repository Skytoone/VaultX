package net.milkbowl.vault.economy;

import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;

import java.util.concurrent.CompletableFuture;

/**
 * Enterprise API for creating, validating, and redeeming physical bank checks and banknotes in VaultX.
 */
public interface VaultCheckAPI {

    record CheckDetails(String checkId, String issuerName, String currency, double amount, long issueTimestamp) {}

    /**
     * Issues a physical bank check item for a player.
     *
     * @param issuer Player issuing the check
     * @param currency Currency ID (e.g. "dollars", "gems")
     * @param amount Check monetary amount
     * @return Future containing the physical check ItemStack
     */
    CompletableFuture<ItemStack> createCheckAsync(OfflinePlayer issuer, String currency, double amount);

    /**
     * Checks if an ItemStack is a valid VaultX bank check.
     *
     * @param item Target ItemStack
     * @return True if valid bank check
     */
    boolean isCheck(ItemStack item);

    /**
     * Inspects details of a physical bank check item.
     *
     * @param item Target bank check ItemStack
     * @return CheckDetails or null if invalid
     */
    CheckDetails getCheckDetails(ItemStack item);

    /**
     * Redeems a physical bank check for a player, depositing funds to their account.
     *
     * @param player Player redeeming the check
     * @param item Physical bank check ItemStack
     * @return Future containing EconomyResponse
     */
    CompletableFuture<EconomyResponse> redeemCheckAsync(OfflinePlayer player, ItemStack item);
}
