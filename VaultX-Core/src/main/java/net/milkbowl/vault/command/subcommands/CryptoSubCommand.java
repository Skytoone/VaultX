package net.milkbowl.vault.command.subcommands;

import net.milkbowl.vault.Vault;
import net.milkbowl.vault.command.SubCommand;
import net.milkbowl.vault.command.VaultXCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ForkJoinPool;

public class CryptoSubCommand implements SubCommand {

    private final VaultXCommand parent;

    public CryptoSubCommand(VaultXCommand parent) {
        this.parent = parent;
    }

    @Override
    public String getName() {
        return "crypto";
    }

    @Override
    public List<String> getAliases() {
        return Collections.emptyList();
    }

    @Override
    public String getPermission() {
        return "vault.command.crypto";
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        if (Vault.getCryptoManager() == null) {
            sender.sendMessage("§cCrypto feature is not initialized.");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /vaultx crypto <wallet|mine|transfer> ...");
            return true;
        }
        String sub = args[1].toLowerCase();
        if (sub.equals("wallet")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cOnly players can view wallets.");
                return true;
            }
            String crypto = args.length > 2 ? args[2] : "BTC";
            Vault.getCryptoManager().getWalletAsync(player, crypto, ForkJoinPool.commonPool())
                    .thenAccept(wallet -> {
                        parent.runSync(() -> sender.sendMessage("§aWallet Address: §f" + wallet.walletAddress()
                                + " §7| Balance: §e" + wallet.balance() + " " + wallet.cryptoName()));
                    });
        } else if (sub.equals("mine")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cOnly players can mine crypto.");
                return true;
            }
            String crypto = args.length > 2 ? args[2] : "BTC";
            double amount = args.length > 3 ? Double.parseDouble(args[3]) : 1.0;
            Vault.getCryptoManager()
                    .mineTokensAsync(player, crypto, amount, ForkJoinPool.commonPool())
                    .thenAccept(res -> {
                        parent.runSync(() -> sender.sendMessage(
                                res.transactionSuccess() ? "§a" + res.errorMessage : "§c" + res.errorMessage));
                    });
        } else if (sub.equals("transfer")) {
            if (args.length < 5) {
                sender.sendMessage("§cUsage: /vaultx crypto transfer <fromAddress> <toAddress> <crypto> <amount>");
                return true;
            }
            String from = args[2];
            String to = args[3];
            String crypto = args[4];
            double amount = Double.parseDouble(args[5]);
            Vault.getCryptoManager()
                    .transferCryptoAsync(from, to, crypto, amount, ForkJoinPool.commonPool())
                    .thenAccept(success -> {
                        parent.runSync(() -> sender.sendMessage(success ? "§aCrypto transfer successful!"
                                : "§cCrypto transfer failed. Check address or balance."));
                    });
        }
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String label, String[] args) {
        return Collections.emptyList();
    }
}
