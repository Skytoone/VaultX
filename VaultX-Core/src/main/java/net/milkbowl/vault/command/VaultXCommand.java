package net.milkbowl.vault.command;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.MultiCurrencyEconomy;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

public class VaultXCommand implements CommandExecutor, TabCompleter {

    private final Plugin plugin;
    private final Map<String, String> displayNameCache = new ConcurrentHashMap<>();

    public Economy getEconomy() {
        if (plugin == null || plugin.getServer() == null || plugin.getServer().getServicesManager() == null) {
            return null;
        }
        org.bukkit.plugin.RegisteredServiceProvider<Economy> rsp = plugin.getServer().getServicesManager().getRegistration(Economy.class);
        return rsp != null ? rsp.getProvider() : null;
    }

    public double parsePositiveDouble(String input) throws NumberFormatException {
        double val = Double.parseDouble(input);
        if (val <= 0 || Double.isNaN(val) || Double.isInfinite(val)) {
            throw new NumberFormatException("Must be positive");
        }
        return val;
    }

    public double parseNonNegativeDouble(String input) throws NumberFormatException {
        double val = Double.parseDouble(input);
        if (val < 0 || Double.isNaN(val) || Double.isInfinite(val)) {
            throw new NumberFormatException("Must be non-negative");
        }
        return val;
    }

    private final Map<String, SubCommand> subCommandMap = new ConcurrentHashMap<>();
    private final List<SubCommand> subCommandList = new CopyOnWriteArrayList<>();

    public void registerSubCommand(SubCommand subCmd) {
        subCommandList.add(subCmd);
        subCommandMap.put(subCmd.getName().toLowerCase(), subCmd);
        for (String alias : subCmd.getAliases()) {
            subCommandMap.put(alias.toLowerCase(), subCmd);
        }
    }

    public Map<String, SubCommand> getSubCommands() {
        return java.util.Collections.unmodifiableMap(subCommandMap);
    }

    public VaultXCommand(Plugin plugin) {
        this.plugin = plugin;
        registerDefaultSubCommands();
    }

    private void registerDefaultSubCommands() {
        registerSubCommand(new net.milkbowl.vault.command.subcommands.BalanceSubCommand(this));
        registerSubCommand(new net.milkbowl.vault.command.subcommands.PaySubCommand(this));
        registerSubCommand(new net.milkbowl.vault.command.subcommands.TopSubCommand(this));
        registerSubCommand(new net.milkbowl.vault.command.subcommands.BankSubCommand(this));
        registerSubCommand(new net.milkbowl.vault.command.subcommands.LoanSubCommand(this));
        registerSubCommand(new net.milkbowl.vault.command.subcommands.MailboxSubCommand(this));
        registerSubCommand(new net.milkbowl.vault.command.subcommands.EscrowSubCommand(this));
        registerSubCommand(new net.milkbowl.vault.command.subcommands.StocksSubCommand(this));
        registerSubCommand(new net.milkbowl.vault.command.subcommands.ExchangeSubCommand(this));
        registerSubCommand(new net.milkbowl.vault.command.subcommands.AdminSubCommand(this));
        registerSubCommand(new net.milkbowl.vault.command.subcommands.CheckSubCommand(this));
        registerSubCommand(new net.milkbowl.vault.command.subcommands.CryptoSubCommand(this));
        registerSubCommand(new net.milkbowl.vault.command.subcommands.StakingSubCommand(this));
        registerSubCommand(new net.milkbowl.vault.command.subcommands.BlackMarketSubCommand(this));
        registerSubCommand(new net.milkbowl.vault.command.subcommands.DiscordSubCommand(this));
        registerSubCommand(new net.milkbowl.vault.command.subcommands.TransactionsSubCommand(this));
        registerSubCommand(new net.milkbowl.vault.command.subcommands.SubscribeSubCommand(this));
        registerSubCommand(new net.milkbowl.vault.command.subcommands.PaydaySubCommand(this));
        registerSubCommand(new net.milkbowl.vault.command.subcommands.DynamicPricingSubCommand(this));
        registerSubCommand(new net.milkbowl.vault.command.subcommands.AuctionSubCommand(this));
        registerSubCommand(new net.milkbowl.vault.command.subcommands.CreditSubCommand(this));
        registerSubCommand(new net.milkbowl.vault.command.subcommands.AnalyticsSubCommand(this));
        registerSubCommand(new net.milkbowl.vault.command.subcommands.BoosterSubCommand(this));
        registerSubCommand(new net.milkbowl.vault.command.subcommands.AuditSubCommand(this));
        registerSubCommand(new net.milkbowl.vault.command.subcommands.PayOfflineSubCommand(this));
        registerSubCommand(new net.milkbowl.vault.command.subcommands.StatsSubCommand(this));
        registerSubCommand(new net.milkbowl.vault.command.subcommands.LogsSubCommand(this));
        registerSubCommand(new net.milkbowl.vault.command.subcommands.ReportSubCommand(this));
        registerSubCommand(new net.milkbowl.vault.command.subcommands.MenuSubCommand(this));
    }

    private final ThreadLocal<String> currentLabel = ThreadLocal.withInitial(() -> "vaultx");
    private final ThreadLocal<String> currentOriginalName = ThreadLocal.withInitial(() -> "vaultx");

    public Plugin getPlugin() {
        return plugin;
    }

    public String[] toVxArgs(String sub, String[] args) {
        String[] vxArgs = new String[args.length + 1];
        vxArgs[0] = sub;
        System.arraycopy(args, 0, vxArgs, 1, args.length);
        return vxArgs;
    }

    public void runAsync(Runnable runnable) {
        String lbl = currentLabel.get();
        String orig = currentOriginalName.get();
        net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
            String prevLbl = currentLabel.get();
            String prevOrig = currentOriginalName.get();
            currentLabel.set(lbl);
            currentOriginalName.set(orig);
            try {
                runnable.run();
            } finally {
                if (prevLbl != null)
                    currentLabel.set(prevLbl);
                else
                    currentLabel.remove();
                if (prevOrig != null)
                    currentOriginalName.set(prevOrig);
                else
                    currentOriginalName.remove();
            }
        });
    }

    public void runSync(Runnable runnable) {
        String lbl = currentLabel.get();
        String orig = currentOriginalName.get();
        net.milkbowl.vault.util.FoliaScheduler.runSync(plugin, () -> {
            String prevLbl = currentLabel.get();
            String prevOrig = currentOriginalName.get();
            currentLabel.set(lbl);
            currentOriginalName.set(orig);
            try {
                runnable.run();
            } finally {
                if (prevLbl != null)
                    currentLabel.set(prevLbl);
                else
                    currentLabel.remove();
                if (prevOrig != null)
                    currentOriginalName.set(prevOrig);
                else
                    currentOriginalName.remove();
            }
        });
    }

    public String getMsg(String path, String def, String label, String orig) {
        String msg = net.milkbowl.vault.Vault.getMessage(path, def);
        if (label != null && orig != null) {
            label = label.toLowerCase();
            orig = orig.toLowerCase();

            String mappingName = orig;
            if (orig.equals("baltop")) {
                mappingName = "top";
            } else if (orig.equals("eco")) {
                mappingName = "admin";
            }

            msg = msg.replace("/vaultx " + mappingName, "/" + label)
                    .replace("/vx " + mappingName, "/" + label);

            if (orig.equals("money")) {
                msg = msg.replace("/vaultx balance", "/" + label).replace("/vx balance", "/" + label)
                        .replace("/vaultx bal", "/" + label).replace("/vx bal", "/" + label);
            } else if (orig.equals("baltop")) {
                msg = msg.replace("/vaultx rich", "/" + label).replace("/vx rich", "/" + label);
            } else if (orig.equals("exchange")) {
                msg = msg.replace("/vaultx exchange", "/" + label).replace("/vx exchange", "/" + label);
            }
        }
        return msg;
    }

    public String getMsg(String path, String def) {
        return getMsg(path, def, currentLabel.get(), currentOriginalName.get());
    }

    public List<String> getCurrencies(Economy econ) {
        if (econ instanceof MultiCurrencyEconomy) {
            return ((MultiCurrencyEconomy) econ).getSupportedCurrencies();
        }
        List<String> list = new ArrayList<>();
        list.add("default");
        return list;
    }

    public OfflinePlayer resolvePlayerFast(String name) {
        return net.milkbowl.vault.util.UUIDCache.getOfflinePlayerFast(name);
    }

    public String getPlayerNameSafe(OfflinePlayer player, String fallback) {
        if (player == null)
            return fallback;
        String name = player.getName();
        return name != null ? name : fallback;
    }

    public String getCurrencyDisplayName(String currency) {
        if (currency == null) {
            return "default";
        }
        String normalizedKey = currency.toLowerCase();
        if (normalizedKey.equals("default")) {
            return "default";
        }
        return displayNameCache.computeIfAbsent(normalizedKey, key -> {
            org.bukkit.configuration.ConfigurationSection symbols = plugin.getConfig()
                    .getConfigurationSection("currency-exchange.symbols");
            if (symbols != null) {
                String sym = symbols.getString(key);
                if (sym != null) {
                    return org.bukkit.ChatColor.translateAlternateColorCodes('&', sym);
                }
                for (String symKey : symbols.getKeys(false)) {
                    if (symKey.equalsIgnoreCase(key)) {
                        return org.bukkit.ChatColor.translateAlternateColorCodes('&', symbols.getString(symKey));
                    }
                }
            }

            org.bukkit.configuration.file.YamlConfiguration guiCfg = net.milkbowl.vault.Vault.getGuiConfig();
            if (guiCfg != null) {
                String name = guiCfg.getString("currencies." + key + ".name");
                if (name != null) {
                    return org.bukkit.ChatColor
                            .stripColor(org.bukkit.ChatColor.translateAlternateColorCodes('&', name));
                }
            }

            org.bukkit.configuration.ConfigurationSection section = plugin.getConfig()
                    .getConfigurationSection("currency-exchange.rates");
            if (section != null) {
                for (String rateKey : section.getKeys(false)) {
                    if (rateKey.equalsIgnoreCase(key)) {
                        return rateKey;
                    }
                }
            }

            if (key.length() > 0) {
                return key.substring(0, 1).toUpperCase() + key.substring(1).toLowerCase();
            }
            return key;
        });
    }

    public boolean checkPermission(CommandSender sender, String originalCmdName, String defaultPerm) {
        String perm = defaultPerm;
        org.bukkit.configuration.ConfigurationSection section = plugin.getConfig().getConfigurationSection("commands");
        if (section != null) {
            org.bukkit.configuration.ConfigurationSection cmdSec = section.getConfigurationSection(originalCmdName);
            if (cmdSec != null) {
                String configured = cmdSec.getString("permission");
                if (configured != null && !configured.isEmpty()) {
                    perm = configured;
                }
            }
        }
        if (perm != null && !perm.isEmpty() && !sender.hasPermission(perm)) {
            sender.sendMessage(
                    getMsg("general.no-permission", "§cYou do not have permission to execute this command!"));
            return false;
        }
        return true;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Economy econ = getEconomy();
        if (econ == null) {
            sender.sendMessage(getMsg("general.economy-not-found",
                    "§cNo economy provider is registered!"));
            return true;
        }

        String cmdName = command instanceof VaultXDynamicCommand
                ? ((VaultXDynamicCommand) command).getOriginalName().toLowerCase()
                : command.getName().toLowerCase();
        currentOriginalName.set(cmdName);
        currentLabel.set(label.toLowerCase());

        SubCommand directSub = subCommandMap.get(cmdName);
        if (directSub != null && (args.length == 0 || !args[0].equalsIgnoreCase(directSub.getName()))) {
            if (!checkPermission(sender, directSub.getName(), directSub.getPermission())) {
                return true;
            }
            return directSub.execute(sender, label, toVxArgs(directSub.getName(), args));
        }

        if (args.length == 0) {
            if (!checkPermission(sender, "vaultx", "vault.use"))
                return true;
            sendHelp(sender, label);
            return true;
        }

        String sub = args[0].toLowerCase();
        SubCommand subCmd = subCommandMap.get(sub);
        if (subCmd != null) {
            if (!checkPermission(sender, subCmd.getName(), subCmd.getPermission())) {
                return true;
            }
            return subCmd.execute(sender, label, args);
        }

        if (!checkPermission(sender, "vaultx", "vault.use"))
            return true;
        sendHelp(sender, label);
        return true;
    }

    private final CommandHelpRenderer helpRenderer = new CommandHelpRenderer(this);

    public void sendHelp(CommandSender sender, String label) {
        helpRenderer.sendHelp(sender, label);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> suggestions = new ArrayList<>();
        Economy econ = getEconomy();
        if (econ == null)
            return suggestions;

        String cmdName = command instanceof VaultXDynamicCommand
                ? ((VaultXDynamicCommand) command).getOriginalName().toLowerCase()
                : command.getName().toLowerCase();
        currentOriginalName.set(cmdName);
        currentLabel.set(alias.toLowerCase());

        SubCommand directSub = subCommandMap.get(cmdName);
        if (directSub != null && (args.length == 0 || !args[0].equalsIgnoreCase(directSub.getName()))) {
            return directSub.tabComplete(sender, alias, toVxArgs(directSub.getName(), args));
        }

        if (args.length == 1) {
            for (SubCommand subCmd : subCommandList) {
                if (subCmd.getPermission() == null || sender.hasPermission(subCmd.getPermission())) {
                    suggestions.add(subCmd.getName());
                    for (String a : subCmd.getAliases()) {
                        suggestions.add(a);
                    }
                }
            }
            return suggestions.stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }

        String sub = args[0].toLowerCase();
        SubCommand subCmd = subCommandMap.get(sub);
        if (subCmd != null) {
            return subCmd.tabComplete(sender, alias, args);
        }

        return java.util.Collections.emptyList();
    }
}
