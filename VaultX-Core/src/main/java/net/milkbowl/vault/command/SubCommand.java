package net.milkbowl.vault.command;

import org.bukkit.command.CommandSender;
import java.util.List;

/**
 * Interface representing a modular subcommand within VaultX.
 */
public interface SubCommand {

    /**
     * Gets the primary name of the subcommand (e.g., "pay", "top", "admin").
     */
    String getName();

    /**
     * Gets the aliases for this subcommand.
     */
    List<String> getAliases();

    /**
     * Gets the required permission node for executing this subcommand, or null if none required.
     */
    String getPermission();

    /**
     * Executes the subcommand.
     *
     * @param sender The sender executing the command
     * @param label  The command label used
     * @param args   The command arguments
     * @return true if handled successfully
     */
    boolean execute(CommandSender sender, String label, String[] args);

    /**
     * Tab-completes arguments for this subcommand.
     *
     * @param sender The sender tab-completing
     * @param label  The command label used
     * @param args   The command arguments
     * @return List of tab completions
     */
    List<String> tabComplete(CommandSender sender, String label, String[] args);
}
