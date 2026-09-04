package net.milkbowl.vault.command.subcommands;

import net.milkbowl.vault.Vault;
import net.milkbowl.vault.command.SubCommand;
import net.milkbowl.vault.command.VaultXCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;

public class StocksSubCommand implements SubCommand {

    private final VaultXCommand parent;

    public StocksSubCommand(VaultXCommand parent) {
        this.parent = parent;
    }

    @Override
    public String getName() {
        return "stocks";
    }

    @Override
    public List<String> getAliases() {
        return Collections.singletonList("bourse");
    }

    @Override
    public String getPermission() {
        return "vault.command.stocks";
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(parent.getMsg("commands.stocks.only-players", "§cOnly players can open the stock market."));
            return true;
        }
        if (Vault.getVaultXGUI() != null) {
            Vault.getVaultXGUI().openStocks(player);
        } else {
            player.sendMessage(parent.getMsg("commands.stocks.gui-disabled", "§cThe GUI menu is not enabled."));
        }
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String label, String[] args) {
        return Collections.emptyList();
    }
}
