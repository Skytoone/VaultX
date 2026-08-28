package net.milkbowl.vault.command;

import net.milkbowl.vault.Vault;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class CommandRegistrationManager {

    private static final String[] ECONOMY_COMMANDS = {
            "money", "pay", "baltop", "bank", "loan",
            "mailbox", "escrow", "stocks", "exchange", "eco", "check",
            "payday", "transactions", "subscribe", "dynamicpricing"
    };

    @SuppressWarnings("unchecked")
    public static void registerCommands(Plugin plugin, CommandExecutor executor, TabCompleter tabCompleter) {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("commands");
        if (section == null) {
            plugin.getLogger()
                    .warning("No 'commands' section found in config.yml. Default static commands will remain.");
            return;
        }

        SimpleCommandMap commandMap;
        Map<String, Command> knownCommands;
        try {
            Field commandMapField = plugin.getServer().getPluginManager().getClass().getDeclaredField("commandMap");
            commandMapField.setAccessible(true);
            commandMap = (SimpleCommandMap) commandMapField.get(plugin.getServer().getPluginManager());

            Field knownCommandsField = SimpleCommandMap.class.getDeclaredField("knownCommands");
            knownCommandsField.setAccessible(true);
            knownCommands = (Map<String, Command>) knownCommandsField.get(commandMap);
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to access Bukkit commandMap via reflection: " + e.getMessage());
            return;
        }

        String fallbackPrefix = plugin.getName().toLowerCase();

        // 1. Collect all original commands mapped to their original names
        Map<String, Command> originalCommandsToReplace = new java.util.HashMap<>();
        for (String originalName : ECONOMY_COMMANDS) {
            Command cmd = knownCommands.get(originalName);
            if (cmd == null) {
                cmd = knownCommands.get(fallbackPrefix + ":" + originalName);
            }
            if (cmd != null) {
                originalCommandsToReplace.put(originalName, cmd);
            }
        }

        // 2. Identify all keys pointing to original commands or old dynamic commands in one pass
        List<String> keysToRemove = new ArrayList<>();
        List<Command> commandsToUnregister = new ArrayList<>();

        for (Map.Entry<String, Command> entry : knownCommands.entrySet()) {
            Command cmd = entry.getValue();
            if (cmd instanceof VaultXDynamicCommand) {
                keysToRemove.add(entry.getKey());
                if (!commandsToUnregister.contains(cmd)) {
                    commandsToUnregister.add(cmd);
                }
            } else if (originalCommandsToReplace.containsValue(cmd)) {
                keysToRemove.add(entry.getKey());
                if (!commandsToUnregister.contains(cmd)) {
                    commandsToUnregister.add(cmd);
                }
            }
        }

        // 3. Clean up the map and unregister commands
        for (String key : keysToRemove) {
            knownCommands.remove(key);
        }
        for (Command cmd : commandsToUnregister) {
            cmd.unregister(commandMap);
        }

        // 4. Register new custom commands
        for (String originalName : ECONOMY_COMMANDS) {
            ConfigurationSection cmdSection = section.getConfigurationSection(originalName);
            if (cmdSection == null) {
                // If it's not configured, we keep it as it is
                continue;
            }

            boolean enabled = cmdSection.getBoolean("enabled", true);
            String customName = cmdSection.getString("name", originalName);
            String permission = cmdSection.getString("permission");
            List<String> aliases = cmdSection.getStringList("aliases");
            if (aliases == null) {
                aliases = Collections.emptyList();
            }

            // If enabled, register the new dynamic command
            if (enabled) {
                Command originalCmd = originalCommandsToReplace.get(originalName);
                String description = originalCmd != null ? originalCmd.getDescription() : "";
                String usage = originalCmd != null ? originalCmd.getUsage() : ("/" + customName);
                if (permission == null || permission.isEmpty()) {
                    permission = originalCmd != null ? originalCmd.getPermission() : null;
                }

                String permissionMessage = Vault.getMessage("general.no-permission",
                        "§cYou do not have permission to use that command!");

                VaultXDynamicCommand dynamicCmd = new VaultXDynamicCommand(
                        customName,
                        description,
                        usage,
                        aliases,
                        permission,
                        permissionMessage,
                        executor,
                        tabCompleter,
                        originalName);

                commandMap.register(fallbackPrefix, dynamicCmd);
            }
        }
    }
}
