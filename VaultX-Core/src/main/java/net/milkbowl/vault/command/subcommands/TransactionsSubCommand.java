package net.milkbowl.vault.command.subcommands;

import net.milkbowl.vault.Vault;
import net.milkbowl.vault.command.SubCommand;
import net.milkbowl.vault.command.VaultXCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class TransactionsSubCommand implements SubCommand {

    private final VaultXCommand parent;

    public TransactionsSubCommand(VaultXCommand parent) {
        this.parent = parent;
    }

    @Override
    public String getName() {
        return "transactions";
    }

    @Override
    public List<String> getAliases() {
        return Arrays.asList("tx", "history", "ledger");
    }

    @Override
    public String getPermission() {
        return "vault.command.transactions";
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        String sub = (args.length > 0 && args[0] != null) ? args[0].toLowerCase() : label.toLowerCase();
        if (sub.equals("history") || sub.equals("ledger")) {
            if (sender instanceof Player player) {
                Vault.getVaultXGUI().openHistory(player);
                return true;
            } else {
                sender.sendMessage(parent.getMsg("commands.transactions.only-players",
                        "§cOnly players can view their transaction history."));
                return true;
            }
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(parent.getMsg("commands.transactions.only-players",
                    "§cOnly players can check their transaction history."));
            return true;
        }

        int pageVal = 1;
        if (args.length >= 2) {
            try {
                pageVal = Integer.parseInt(args[1]);
                if (pageVal < 1) pageVal = 1;
            } catch (NumberFormatException e) {
                sender.sendMessage(parent.getMsg("commands.transactions.invalid-page", "§cInvalid page number."));
                return true;
            }
        }

        final int page = pageVal;
        net.milkbowl.vault.redis.LocalFailoverManager fm = Vault.getFailoverManager();
        if (fm == null) {
            sender.sendMessage(parent.getMsg("commands.transactions.database-error", "§cDatabase manager is not initialized."));
            return true;
        }

        sender.sendMessage(parent.getMsg("commands.transactions.fetching", "§eFetching transaction history... Please wait."));

        parent.runAsync(() -> {
            int pageSize = 8;
            List<net.milkbowl.vault.redis.LocalFailoverManager.PlayerTransactionRecord> txs = fm
                    .getPlayerTransactions(player.getUniqueId(), page, pageSize);

            parent.runSync(() -> {
                if (txs.isEmpty()) {
                    sender.sendMessage(parent.getMsg("commands.transactions.empty",
                            "§d§l[VaultX History] §cNo transaction history found on page %page%.")
                            .replace("%page%", String.valueOf(page)));
                    return;
                }

                sender.sendMessage(parent.getMsg("commands.transactions.header", "§d§l=== Your Transaction History (Page %page%) ===")
                        .replace("%page%", String.valueOf(page)));
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                for (net.milkbowl.vault.redis.LocalFailoverManager.PlayerTransactionRecord tx : txs) {
                    String time = sdf.format(new java.util.Date(tx.timestamp));
                    String actionColor = tx.type.equalsIgnoreCase("WITHDRAW") ? "§c" : "§a";
                    String actionSymbol = tx.type.equalsIgnoreCase("WITHDRAW") ? "[-] " : "[+] ";
                    String other = (tx.otherParty != null && !tx.otherParty.isEmpty()) ? " §7(" + tx.otherParty + ")" : "";
                    sender.sendMessage(parent.getMsg("commands.transactions.entry",
                            "§7[%time%] %color%%symbol%%type% §e%amount% §7[%currency%]%other%")
                            .replace("%time%", time)
                            .replace("%color%", actionColor)
                            .replace("%symbol%", actionSymbol)
                            .replace("%type%", tx.type)
                            .replace("%amount%", tx.currency.equalsIgnoreCase("default")
                                    ? (parent.getEconomy() != null ? parent.getEconomy().format(tx.amount) : String.format("%.2f", tx.amount))
                                    : String.format("%.2f", tx.amount))
                            .replace("%currency%", parent.getCurrencyDisplayName(tx.currency))
                            .replace("%other%", other));
                }
                sender.sendMessage(parent.getMsg("commands.transactions.footer", "§d§l============================================="));
            });
        });
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String label, String[] args) {
        return Collections.emptyList();
    }
}
