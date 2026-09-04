package net.milkbowl.vault.command.subcommands;

import net.milkbowl.vault.Vault;
import net.milkbowl.vault.command.SubCommand;
import net.milkbowl.vault.command.VaultXCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;

public class MenuSubCommand implements SubCommand {

    private final VaultXCommand parent;

    public MenuSubCommand(VaultXCommand parent) {
        this.parent = parent;
    }

    @Override
    public String getName() {
        return "menu";
    }

    @Override
    public List<String> getAliases() {
        return Collections.emptyList();
    }

    @Override
    public String getPermission() {
        return "vault.command.menu";
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(parent.getMsg("commands.menu.only-players", "§cOnly players can open the menu."));
            return true;
        }

        if (Vault.getVaultXGUI() != null) {
            Vault.getVaultXGUI().openDashboard(player);
        } else {
            player.sendMessage(parent.getMsg("commands.menu.disabled", "§cThe GUI dashboard is currently unavailable."));
        }
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String label, String[] args) {
        return Collections.emptyList();
    }
}
