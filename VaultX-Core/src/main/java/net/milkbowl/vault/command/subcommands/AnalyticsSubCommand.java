package net.milkbowl.vault.command.subcommands;

import net.milkbowl.vault.Vault;
import net.milkbowl.vault.command.SubCommand;
import net.milkbowl.vault.command.VaultXCommand;
import org.bukkit.command.CommandSender;

import java.util.Collections;
import java.util.List;

public class AnalyticsSubCommand implements SubCommand {

    private final VaultXCommand parent;

    public AnalyticsSubCommand(VaultXCommand parent) {
        this.parent = parent;
    }

    @Override
    public String getName() {
        return "analytics";
    }

    @Override
    public List<String> getAliases() {
        return Collections.emptyList();
    }

    @Override
    public String getPermission() {
        return "vault.admin.analytics";
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        if (!sender.hasPermission("vault.admin.analytics")) {
            sender.sendMessage("§cYou do not have permission!");
            return true;
        }
        String currency = args.length > 1 ? args[1] : "default";
        Vault.getAnalyticsAPI().getTotalSupplyAsync(currency).thenAccept(total -> {
            Vault.getAnalyticsAPI().getAverageBalanceAsync(currency).thenAccept(avg -> {
                Vault.getAnalyticsAPI().getVolume24hAsync(currency).thenAccept(vol -> {
                    sender.sendMessage("§8§m--------------------------------------------------");
                    sender.sendMessage("§e§l📊 VaultX Analytics §7(" + currency.toUpperCase() + ")");
                    sender.sendMessage("§7• Total Supply: §a$" + String.format("%.2f", total));
                    sender.sendMessage("§7• Avg Balance: §a$" + String.format("%.2f", avg));
                    sender.sendMessage("§7• 24h Volume: §a$" + String.format("%.2f", vol));
                    sender.sendMessage("§8§m--------------------------------------------------");
                });
            });
        });
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String label, String[] args) {
        return Collections.emptyList();
    }
}
