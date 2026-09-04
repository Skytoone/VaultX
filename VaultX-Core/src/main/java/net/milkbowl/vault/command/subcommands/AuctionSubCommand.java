package net.milkbowl.vault.command.subcommands;

import net.milkbowl.vault.Vault;
import net.milkbowl.vault.command.SubCommand;
import net.milkbowl.vault.command.VaultXCommand;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.economy.MultiCurrencyEconomy;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ForkJoinPool;

public class AuctionSubCommand implements SubCommand {

    private final VaultXCommand parent;

    public AuctionSubCommand(VaultXCommand parent) {
        this.parent = parent;
    }

    @Override
    public String getName() {
        return "auction";
    }

    @Override
    public List<String> getAliases() {
        return Collections.emptyList();
    }

    @Override
    public String getPermission() {
        return "vault.command.auction";
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        if (Vault.getAuctionManager() == null) {
            sender.sendMessage("§cAuction feature is not initialized.");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /vaultx auction <create|bid|cancel|list> ...");
            return true;
        }

        Economy econ = parent.getEconomy();
        String sub = args[1].toLowerCase();

        if (sub.equals("create")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cOnly players can create auctions.");
                return true;
            }
            if (args.length < 4) {
                sender.sendMessage("§cUsage: /vaultx auction create <startingPrice> <durationMinutes> [currency]");
                return true;
            }
            double price = Double.parseDouble(args[2]);
            long minutes = Long.parseLong(args[3]);
            String curr = args.length > 4 ? args[4] : "default";
            ItemStack item = player.getInventory().getItemInHand();
            if (item == null || item.getType() == Material.AIR) {
                sender.sendMessage("§cHold an item in your main hand to list in auction.");
                return true;
            }
            Vault.getAuctionManager().createAuctionAsync(player, item, curr, price, minutes, ForkJoinPool.commonPool())
                    .thenAccept(listing -> {
                        parent.runSync(() -> {
                            if (listing != null) {
                                player.getInventory().setItemInHand(null);
                                sender.sendMessage("§aAuction created successfully! ID: " + listing.auctionId());
                            } else {
                                sender.sendMessage("§cFailed to create auction.");
                            }
                        });
                    });
        } else if (sub.equals("bid")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cOnly players can bid.");
                return true;
            }
            if (args.length < 4) {
                sender.sendMessage("§cUsage: /vaultx auction bid <auctionId> <amount>");
                return true;
            }
            String auctionId = args[2];
            double amount = Double.parseDouble(args[3]);
            Vault.getAuctionManager().placeBidAsync(player, auctionId, amount,
                    (p, c) -> (econ instanceof MultiCurrencyEconomy m) ? m.getCurrencyBalance(p, c)
                            : (c.equalsIgnoreCase("default") && econ != null ? econ.getBalance(p) : 0.0),
                    (p, c, a) -> (econ instanceof MultiCurrencyEconomy m) ? m.withdrawCurrencyPlayer(p, c, a)
                            : (c.equalsIgnoreCase("default") && econ != null ? econ.withdrawPlayer(p, a)
                                    : new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Not supported")),
                    (p, c, a) -> (econ instanceof MultiCurrencyEconomy m) ? m.depositCurrencyPlayer(p, c, a)
                            : (c.equalsIgnoreCase("default") && econ != null ? econ.depositPlayer(p, a)
                                    : new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Not supported")),
                    ForkJoinPool.commonPool()).thenAccept(res -> {
                        parent.runSync(() -> sender.sendMessage(
                                res.transactionSuccess() ? "§a" + res.errorMessage : "§c" + res.errorMessage));
                    });
        } else if (sub.equals("cancel")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cOnly players can cancel auctions.");
                return true;
            }
            if (args.length < 3) {
                sender.sendMessage("§cUsage: /vaultx auction cancel <auctionId>");
                return true;
            }
            String auctionId = args[2];
            Vault.getAuctionManager()
                    .cancelAuctionAsync(player, auctionId, ForkJoinPool.commonPool())
                    .thenAccept(res -> {
                        parent.runSync(() -> sender.sendMessage(
                                res.transactionSuccess() ? "§aAuction cancelled!" : "§c" + res.errorMessage));
                    });
        } else if (sub.equals("list")) {
            Vault.getAuctionManager().getActiveAuctionsAsync(ForkJoinPool.commonPool())
                    .thenAccept(auctions -> {
                        parent.runSync(() -> {
                            sender.sendMessage("§e=== Active Auctions ===");
                            for (var a : auctions) {
                                sender.sendMessage("§7[" + a.auctionId() + "] §f" + a.item().getType() + " §8| Bid: §a"
                                        + a.currentBid() + " " + a.currency());
                            }
                        });
                    });
        }
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String label, String[] args) {
        return Collections.emptyList();
    }
}
