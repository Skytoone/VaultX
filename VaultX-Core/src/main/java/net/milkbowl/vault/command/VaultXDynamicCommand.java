package net.milkbowl.vault.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.List;

public class VaultXDynamicCommand extends Command {

    private final CommandExecutor executor;
    private final TabCompleter tabCompleter;
    private final String originalName;

    public VaultXDynamicCommand(String name, String description, String usageMessage, List<String> aliases,
                                 String permission, String permissionMessage,
                                 CommandExecutor executor, TabCompleter tabCompleter, String originalName) {
        super(name, description, usageMessage, aliases);
        this.executor = executor;
        this.tabCompleter = tabCompleter;
        this.originalName = originalName;
        if (permission != null && !permission.isEmpty()) {
            setPermission(permission);
        }
        if (permissionMessage != null && !permissionMessage.isEmpty()) {
            setPermissionMessage(permissionMessage);
        }
    }

    @Override
    public boolean execute(CommandSender sender, String commandLabel, String[] args) {
        if (getPermission() != null && !getPermission().isEmpty() && !sender.hasPermission(getPermission())) {
            String msg = getPermissionMessage();
            if (msg == null || msg.isEmpty()) {
                msg = "§cVous n'avez pas la permission d'exécuter cette commande !";
            }
            sender.sendMessage(msg);
            return true;
        }
        return executor.onCommand(sender, this, commandLabel, args);
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) throws IllegalArgumentException {
        if (tabCompleter != null) {
            return tabCompleter.onTabComplete(sender, this, alias, args);
        }
        return super.tabComplete(sender, alias, args);
    }

    public String getOriginalName() {
        return originalName;
    }
}
