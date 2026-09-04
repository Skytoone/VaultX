package net.milkbowl.vault.economy.service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.economy.VaultAsyncEconomy;

/**
 * Service encapsulating bank check creation, lore serialization, details parsing, and redemption.
 */
public class BankCheckService {

    private final Plugin plugin;
    private final ExecutorService asyncExecutor;
    private final VaultAsyncEconomy economy;

    public BankCheckService(Plugin plugin, ExecutorService asyncExecutor, VaultAsyncEconomy economy) {
        this.plugin = plugin;
        this.asyncExecutor = asyncExecutor;
        this.economy = economy;
    }

    public CompletableFuture<ItemStack> createCheckAsync(OfflinePlayer issuer, String currency, double amount) {
        if (!plugin.getConfig().getBoolean("checks.enabled", true)) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.supplyAsync(() -> {
            ItemStack check = new ItemStack(Material.PAPER);
            ItemMeta meta = check.getItemMeta();
            if (meta != null) {
                meta.setDisplayName("§6§lBank Check §7(§e" + amount + " " + currency + "§7)");
                meta.setLore(List.of("§7Issued by: §f" + (issuer != null ? issuer.getName() : "Bank"),
                        "§7Amount: §a" + amount, "§7Currency: §e" + currency, "§8[VaultX Check]"));
                check.setItemMeta(meta);
            }
            return check;
        }, asyncExecutor);
    }

    public boolean isCheck(ItemStack item) {
        if (!plugin.getConfig().getBoolean("checks.enabled", true))
            return false;
        if (item == null || !item.hasItemMeta() || !item.getItemMeta().hasLore())
            return false;
        var lore = item.getItemMeta().getLore();
        return lore != null && lore.stream().anyMatch(l -> l.contains("[VaultX Check]"));
    }

    public net.milkbowl.vault.economy.VaultCheckAPI.CheckDetails getCheckDetails(ItemStack item) {
        if (!plugin.getConfig().getBoolean("checks.enabled", true) || !isCheck(item))
            return null;
        var lore = item.getItemMeta().getLore();
        double amt = 0;
        String curr = "default";
        if (lore != null) {
            for (String line : lore) {
                if (line.contains("Amount: ")) {
                    try {
                        amt = Double.parseDouble(line.split("Amount: ")[1].replace("§a", "").trim());
                    } catch (Exception ignored) {
                    }
                } else if (line.contains("Currency: ")) {
                    curr = line.split("Currency: ")[1].replace("§e", "").trim();
                }
            }
        }
        return new net.milkbowl.vault.economy.VaultCheckAPI.CheckDetails(
                UUID.randomUUID().toString(), "Server Bank", curr, amt, System.currentTimeMillis());
    }

    public CompletableFuture<EconomyResponse> redeemCheckAsync(OfflinePlayer player, ItemStack item) {
        if (!plugin.getConfig().getBoolean("checks.enabled", true)) {
            return CompletableFuture.completedFuture(new EconomyResponse(0, 0,
                    EconomyResponse.ResponseType.FAILURE, "Checks feature is disabled in config.yml"));
        }
        net.milkbowl.vault.economy.VaultCheckAPI.CheckDetails details = getCheckDetails(item);
        if (details == null) {
            return CompletableFuture.completedFuture(
                    new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Invalid bank check"));
        }
        return economy.depositPlayerAsync(player, details.amount());
    }
}
