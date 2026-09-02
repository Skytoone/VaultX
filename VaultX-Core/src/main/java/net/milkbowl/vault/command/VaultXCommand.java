package net.milkbowl.vault.command;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.MultiCurrencyEconomy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import net.milkbowl.vault.Vault;
import net.milkbowl.vault.economy.ExchangeRateManager.ConversionResult;
import net.milkbowl.vault.redis.LocalFailoverManager;
import java.util.Date;

public class VaultXCommand implements CommandExecutor, TabCompleter {

    private final Plugin plugin;
    private final java.util.Map<String, String> displayNameCache = new java.util.concurrent.ConcurrentHashMap<>();

    public static double parsePositiveDouble(String input) throws NumberFormatException {
        if (input == null)
            throw new NumberFormatException("Null input");
        double val = Double.parseDouble(input);
        if (Double.isNaN(val) || Double.isInfinite(val) || val <= 0) {
            throw new NumberFormatException("Invalid positive number: " + input);
        }
        return val;
    }

    public static double parseNonNegativeDouble(String input) throws NumberFormatException {
        if (input == null)
            throw new NumberFormatException("Null input");
        double val = Double.parseDouble(input);
        if (Double.isNaN(val) || Double.isInfinite(val) || val < 0) {
            throw new NumberFormatException("Invalid non-negative number: " + input);
        }
        return val;
    }

    public VaultXCommand(Plugin plugin) {
        this.plugin = plugin;
    }

    private final ThreadLocal<String> currentLabel = ThreadLocal.withInitial(() -> "vaultx");
    private final ThreadLocal<String> currentOriginalName = ThreadLocal.withInitial(() -> "vaultx");

    private String[] toVxArgs(String sub, String[] args) {
        String[] vxArgs = new String[args.length + 1];
        vxArgs[0] = sub;
        System.arraycopy(args, 0, vxArgs, 1, args.length);
        return vxArgs;
    }

    private void runAsync(Runnable runnable) {
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

    private void runSync(Runnable runnable) {
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

    private String getMsg(String path, String def, String label, String orig) {
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

    private String getMsg(String path, String def) {
        return getMsg(path, def, currentLabel.get(), currentOriginalName.get());
    }

    private Economy getEconomy() {
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        return rsp != null ? rsp.getProvider() : null;
    }

    private List<String> getCurrencies(Economy econ) {
        if (econ instanceof MultiCurrencyEconomy) {
            return ((MultiCurrencyEconomy) econ).getSupportedCurrencies();
        }
        List<String> list = new ArrayList<>();
        list.add("default");
        return list;
    }

    private OfflinePlayer resolvePlayerFast(String name) {
        return net.milkbowl.vault.util.UUIDCache.getOfflinePlayerFast(name);
    }

    private String getPlayerNameSafe(OfflinePlayer player, String fallback) {
        if (player == null)
            return fallback;
        String name = player.getName();
        return name != null ? name : fallback;
    }

    private String getCurrencyDisplayName(String currency) {
        if (currency == null) {
            return "default";
        }
        String normalizedKey = currency.toLowerCase();
        if (normalizedKey.equals("default")) {
            return "default";
        }
        return displayNameCache.computeIfAbsent(normalizedKey, key -> {
            // 1. Try to get chat symbol from config.yml (supporting color codes)
            org.bukkit.configuration.ConfigurationSection symbols = plugin.getConfig()
                    .getConfigurationSection("currency-exchange.symbols");
            if (symbols != null) {
                String sym = symbols.getString(key);
                if (sym != null) {
                    return org.bukkit.ChatColor.translateAlternateColorCodes('&', sym);
                }
                // case-insensitive match fallback
                for (String symKey : symbols.getKeys(false)) {
                    if (symKey.equalsIgnoreCase(key)) {
                        return org.bukkit.ChatColor.translateAlternateColorCodes('&', symbols.getString(symKey));
                    }
                }
            }

            // 2. Try to get display name from gui.yml (stripping colors)
            org.bukkit.configuration.file.YamlConfiguration guiCfg = net.milkbowl.vault.Vault.getGuiConfig();
            if (guiCfg != null) {
                String name = guiCfg.getString("currencies." + key + ".name");
                if (name != null) {
                    return org.bukkit.ChatColor
                            .stripColor(org.bukkit.ChatColor.translateAlternateColorCodes('&', name));
                }
            }

            // 3. Fallback: use currency name from config.yml (preserving its original
            // casing)
            org.bukkit.configuration.ConfigurationSection section = plugin.getConfig()
                    .getConfigurationSection("currency-exchange.rates");
            if (section != null) {
                for (String rateKey : section.getKeys(false)) {
                    if (rateKey.equalsIgnoreCase(key)) {
                        return rateKey;
                    }
                }
            }

            // 4. Last resort fallback
            if (key.length() > 0) {
                return key.substring(0, 1).toUpperCase() + key.substring(1).toLowerCase();
            }
            return key;
        });
    }

    private boolean checkPermission(CommandSender sender, String originalCmdName, String defaultPerm) {
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
                    "§c§l[VaultX] §cAucun fournisseur d'économie n'est enregistré !"));
            return true;
        }

        String cmdName = command instanceof VaultXDynamicCommand
                ? ((VaultXDynamicCommand) command).getOriginalName().toLowerCase()
                : command.getName().toLowerCase();
        currentOriginalName.set(cmdName);
        currentLabel.set(label.toLowerCase());

        if (cmdName.equals("money") || cmdName.equals("balance") || cmdName.equals("bal")) {
            if (!checkPermission(sender, "money", "vault.command.money"))
                return true;
            handleBalance(sender, econ, toVxArgs("balance", args));
            return true;
        } else if (cmdName.equals("pay")) {
            if (!checkPermission(sender, "pay", "vault.command.pay"))
                return true;
            handlePay(sender, econ, toVxArgs("pay", args));
            return true;
        } else if (cmdName.equals("baltop") || cmdName.equals("moneytop")) {
            if (!checkPermission(sender, "baltop", "vault.top"))
                return true;
            handleTop(sender, econ, toVxArgs("top", args));
            return true;
        } else if (cmdName.equals("bank") || cmdName.equals("sharedbank")) {
            if (!checkPermission(sender, "bank", "vault.command.bank"))
                return true;
            handleBank(sender, econ, toVxArgs("bank", args));
            return true;
        } else if (cmdName.equals("loan") || cmdName.equals("loans")) {
            if (!checkPermission(sender, "loan", "vault.command.loan"))
                return true;
            handleLoan(sender, econ, toVxArgs("loan", args));
            return true;
        } else if (cmdName.equals("mailbox") || cmdName.equals("mail")) {
            if (!checkPermission(sender, "mailbox", "vault.command.mailbox"))
                return true;
            handleMailbox(sender, toVxArgs("mailbox", args));
            return true;
        } else if (cmdName.equals("escrow")) {
            if (!checkPermission(sender, "escrow", "vault.command.escrow"))
                return true;
            handleEscrow(sender, toVxArgs("escrow", args));
            return true;
        } else if (cmdName.equals("stocks") || cmdName.equals("stockmarket")) {
            if (!checkPermission(sender, "stocks", "vault.command.stocks"))
                return true;
            handleStocks(sender, econ, toVxArgs("stocks", args));
            return true;
        } else if (cmdName.equals("exchange") || cmdName.equals("forex") || cmdName.equals("convert")) {
            if (!checkPermission(sender, "exchange", "vault.convert"))
                return true;
            handleExchange(sender, toVxArgs("exchange", args));
            return true;
        } else if (cmdName.equals("eco") || cmdName.equals("economy")) {
            if (!checkPermission(sender, "eco", "vault.admin"))
                return true;
            handleAdmin(sender, econ, toVxArgs("admin", args));
            return true;
        } else if (cmdName.equals("check") || cmdName.equals("cheque")) {
            if (!checkPermission(sender, "check", "vault.command.check"))
                return true;
            // Auto-shortcut: if first argument is a number, insert "write" before it
            if (args.length > 0 && !args[0].equalsIgnoreCase("write")) {
                try {
                    parsePositiveDouble(args[0]);
                    String[] newArgs = new String[args.length + 1];
                    newArgs[0] = "write";
                    System.arraycopy(args, 0, newArgs, 1, args.length);
                    args = newArgs;
                } catch (NumberFormatException ignored) {
                }
            }
            handleCheck(sender, econ, toVxArgs("check", args));
            return true;
        } else if (cmdName.equals("payday") || cmdName.equals("salary")) {
            if (!checkPermission(sender, "payday", "vault.command.payday"))
                return true;
            handlePayday(sender, args);
            return true;
        } else if (cmdName.equals("transactions") || cmdName.equals("tx") || cmdName.equals("history")) {
            if (!checkPermission(sender, "transactions", "vault.command.transactions"))
                return true;
            handleTransactions(sender, toVxArgs("transactions", args));
            return true;
        } else if (cmdName.equals("subscribe") || cmdName.equals("subscriptions")) {
            if (!checkPermission(sender, "subscribe", "vault.command.subscribe"))
                return true;
            handleSubscribe(sender, econ, toVxArgs("subscribe", args));
            return true;
        } else if (cmdName.equals("blackmarket") || cmdName.equals("launder") || cmdName.equals("marchenoir")) {
            if (!checkPermission(sender, "blackmarket", "vault.command.blackmarket"))
                return true;
            handleBlackMarket(sender, toVxArgs("blackmarket", args));
            return true;
        } else if (cmdName.equals("dirty")) {
            if (!checkPermission(sender, "blackmarket", "vault.command.blackmarket"))
                return true;
            handleDirty(sender, toVxArgs("dirty", args));
            return true;
        } else if (cmdName.equals("discord") || cmdName.equals("linkdiscord")) {
            if (!checkPermission(sender, "discord", "vault.command.discord"))
                return true;
            handleDiscord(sender, toVxArgs("discord", args));
            return true;
        }

        if (args.length == 0) {
            if (!checkPermission(sender, "vaultx", "vault.use"))
                return true;
            sendHelp(sender, label);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "balance":
            case "bal":
                if (!checkPermission(sender, "money", "vault.command.money"))
                    break;
                handleBalance(sender, econ, args);
                break;
            case "pay":
                if (!checkPermission(sender, "pay", "vault.command.pay"))
                    break;
                handlePay(sender, econ, args);
                break;
            case "top":
            case "rich":
                if (!checkPermission(sender, "baltop", "vault.top"))
                    break;
                handleTop(sender, econ, args);
                break;
            case "transactions":
            case "tx":
                if (!checkPermission(sender, "transactions", "vault.command.transactions"))
                    break;
                handleTransactions(sender, args);
                break;
            case "subscribe":
            case "subscription":
            case "subscriptions":
                if (!checkPermission(sender, "subscribe", "vault.command.subscribe"))
                    break;
                handleSubscribe(sender, econ, args);
                break;
            case "blackmarket":
            case "launder":
            case "marchenoir":
                if (!checkPermission(sender, "blackmarket", "vault.command.blackmarket"))
                    break;
                handleBlackMarket(sender, args);
                break;
            case "dirty":
                if (!checkPermission(sender, "blackmarket", "vault.command.blackmarket"))
                    break;
                handleDirty(sender, args);
                break;
            case "discord":
                if (!checkPermission(sender, "discord", "vault.command.discord"))
                    break;
                handleDiscord(sender, args);
                break;
            case "history":
            case "ledger":
                if (!checkPermission(sender, "transactions", "vault.command.transactions"))
                    break;
                if (sender instanceof Player) {
                    net.milkbowl.vault.Vault.getVaultXGUI().openHistory((Player) sender);
                } else {
                    sender.sendMessage(getMsg("commands.transactions.only-players",
                            "§cOnly players can view their transaction history."));
                }
                break;
            case "admin":
                if (!checkPermission(sender, "eco", "vault.admin"))
                    break;
                handleAdmin(sender, econ, args);
                break;
            case "escrow":
                if (!checkPermission(sender, "escrow", "vault.command.escrow"))
                    break;
                handleEscrow(sender, args);
                break;
            case "menu":
            case "gui":
                if (!checkPermission(sender, "vaultx", "vault.use"))
                    break;
                handleMenu(sender);
                break;
            case "exchange":
            case "forex":
            case "convert":
                if (!checkPermission(sender, "exchange", "vault.convert"))
                    break;
                handleExchange(sender, args);
                break;
            case "mailbox":
            case "mail":
                if (!checkPermission(sender, "mailbox", "vault.command.mailbox"))
                    break;
                handleMailbox(sender, args);
                break;
            case "analytics":
                handleAnalytics(sender, args);
                break;
            case "booster":
            case "boosters":
                handleBoosterCommand(sender, args);
                break;
            case "audit":
                handleAuditLogCommand(sender, args);
                break;
            case "payoffline":
                handlePayOffline(sender, args);
                break;
            case "bank":
                if (!checkPermission(sender, "bank", "vault.command.bank"))
                    break;
                handleBank(sender, econ, args);
                break;
            case "check":
                if (!checkPermission(sender, "check", "vault.command.check"))
                    break;
                handleCheck(sender, econ, args);
                break;
            case "loan":
                if (!checkPermission(sender, "loan", "vault.command.loan"))
                    break;
                handleLoan(sender, econ, args);
                break;
            case "stocks":
                if (!checkPermission(sender, "stocks", "vault.command.stocks"))
                    break;
                handleStocks(sender, econ, args);
                break;
            case "stats":
            case "metrics":
                if (!checkPermission(sender, "stats", "vault.admin"))
                    break;
                if (sender instanceof Player && (args.length < 2 || !args[1].equalsIgnoreCase("text"))) {
                    if (net.milkbowl.vault.Vault.getVaultXGUI() != null) {
                        net.milkbowl.vault.Vault.getVaultXGUI().openAdminStats((Player) sender);
                    } else {
                        sender.sendMessage(getMsg("commands.menu.gui-not-enabled", "§cThe GUI menu is not enabled."));
                    }
                } else {
                    handleMacroStatsText(sender, econ);
                }
                break;
            case "logs":
                if (!checkPermission(sender, "logs", "vault.admin"))
                    break;
                handleLogs(sender, econ, args);
                break;
            case "dynamicpricing":
            case "dp":
            case "dynamic":
                if (!checkPermission(sender, "dynamicpricing", "vault.admin"))
                    break;
                handleDynamicPricing(sender, econ, args);
                break;
            case "crypto":
                handleCrypto(sender, args);
                break;
            case "credit":
                handleCredit(sender, args);
                break;
            case "staking":
            case "stake":
                handleStaking(sender, econ, args);
                break;
            case "auction":
            case "ah":
                handleAuction(sender, econ, args);
                break;
            default:
                if (!checkPermission(sender, "vaultx", "vault.use"))
                    break;
                sendHelp(sender, label);
                break;
        }

        return true;
    }

    private void handleCheck(CommandSender sender, Economy econ, String[] args) {
        if (!plugin.getConfig().getBoolean("checks.enabled", true)) {
            sender.sendMessage(getMsg("commands.check.disabled", "§cPhysical checks are disabled on this server."));
            return;
        }
        if (!(sender instanceof Player)) {
            sender.sendMessage(getMsg("commands.check.only-players", "§cOnly players can sign physical checks."));
            return;
        }
        Player player = (Player) sender;
        if (args.length < 3 || !args[1].equalsIgnoreCase("write")) {
            player.sendMessage(getMsg("commands.check.usage", "§cUsage: /vaultx check write <amount> [currency]"));
            return;
        }

        double amount;
        try {
            amount = parsePositiveDouble(args[2]);
        } catch (NumberFormatException e) {
            player.sendMessage(
                    getMsg("commands.check.invalid-amount", "§cInvalid amount. It must be a positive number."));
            return;
        }

        double minAmount = plugin.getConfig().getDouble("checks.min-amount", 1.0);
        double maxAmount = plugin.getConfig().getDouble("checks.max-amount", 10000000.0);
        double creationFee = plugin.getConfig().getDouble("checks.creation-fee", 10.0);

        if (amount < minAmount) {
            player.sendMessage(getMsg("commands.check.min-amount", "§cThe check amount must be at least %amount%!")
                    .replace("%amount%", econ.format(minAmount)));
            return;
        }
        if (maxAmount > 0 && amount > maxAmount) {
            player.sendMessage(getMsg("commands.check.max-amount", "§cThe check amount cannot exceed %amount%!")
                    .replace("%amount%", econ.format(maxAmount)));
            return;
        }

        String currency = args.length >= 4 ? args[3] : "default";

        // Check if player has enough money (amount + fee)
        double totalRequired = amount + (currency.equalsIgnoreCase("default") ? creationFee : 0);
        double balance = 0;
        if (currency.equalsIgnoreCase("default")) {
            balance = econ.getBalance(player);
        } else if (econ instanceof MultiCurrencyEconomy) {
            balance = ((MultiCurrencyEconomy) econ).getCurrencyBalance(player, currency);
        } else {
            player.sendMessage(getMsg("commands.check.unsupported-currency", "§cThis currency is not supported."));
            return;
        }

        if (balance < totalRequired) {
            player.sendMessage(getMsg("commands.check.insufficient-funds",
                    "§cYou do not have enough funds to sign this check (including creation fees)."));
            return;
        }

        // Withdraw money from player (including creation fee for default currency)
        EconomyResponse wRes;
        if (currency.equalsIgnoreCase("default")) {
            wRes = econ.withdrawPlayer(player, totalRequired);
        } else {
            wRes = ((MultiCurrencyEconomy) econ).withdrawCurrencyPlayer(player, currency, amount);
        }

        if (!wRes.transactionSuccess()) {
            player.sendMessage(getMsg("commands.check.withdraw-failed", "§cFailed to withdraw funds for the check."));
            return;
        }

        // Generate check ID
        String checkId = UUID.randomUUID().toString();
        net.milkbowl.vault.redis.LocalFailoverManager fm = Vault.getFailoverManager();
        if (fm != null) {
            fm.saveCheck(checkId, amount, currency, player.getUniqueId());
        }

        // Create physical check item
        org.bukkit.inventory.ItemStack item = new org.bukkit.inventory.ItemStack(org.bukkit.Material.PAPER, 1);
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§6§lEconomic Check");
            List<String> lore = new ArrayList<>();
            String displayVal = currency.equalsIgnoreCase("default")
                    ? (econ != null ? econ.format(amount) : String.valueOf(amount))
                    : String.format("%.2f %s", amount, getCurrencyDisplayName(currency));
            lore.add("§7Value: §e" + displayVal);
            lore.add("§7Signed by: §f" + player.getName());
            lore.add("§7Réseau : §d§lVaultX Verified Check");
            lore.add("§8ID: " + checkId);
            meta.setLore(lore);
            item.setItemMeta(meta);
        }

        // Add item to player inventory
        java.util.HashMap<Integer, org.bukkit.inventory.ItemStack> leftover = player.getInventory().addItem(item);
        if (leftover != null && !leftover.isEmpty()) {
            for (org.bukkit.inventory.ItemStack leftoverItem : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftoverItem);
            }
        }
        String formatted = currency.equalsIgnoreCase("default")
                ? (econ != null ? econ.format(amount) : String.valueOf(amount))
                : String.format("%.2f %s", amount, getCurrencyDisplayName(currency));
        player.sendMessage(getMsg("commands.check.write-success", "§a§l✔ §aYou signed a check of §e%amount%&a!")
                .replace("%amount%", formatted));
    }

    private void handleBalance(CommandSender sender, Economy econ, String[] args) {
        if (args.length >= 2 && (args[1].equalsIgnoreCase("top") || args[1].equalsIgnoreCase("rich"))) {
            String[] newArgs = new String[args.length - 1];
            newArgs[0] = args[1];
            System.arraycopy(args, 2, newArgs, 1, args.length - 2);
            handleTop(sender, econ, newArgs);
            return;
        }

        OfflinePlayer target;
        String currency = "default";
        List<String> currencies = getCurrencies(econ);

        if (args.length == 1) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(
                        getMsg("general.only-players", "§cOnly players can execute this command."));
                return;
            }
            target = (Player) sender;
        } else if (args.length == 2) {
            String matchedCurrency = null;
            for (String curr : currencies) {
                if (curr.equalsIgnoreCase(args[1])) {
                    matchedCurrency = curr;
                    break;
                }
            }
            if (matchedCurrency != null) {
                if (!(sender instanceof Player)) {
                    sender.sendMessage(
                            getMsg("general.only-players", "§cOnly players can execute this command."));
                    return;
                }
                target = (Player) sender;
                currency = matchedCurrency;
            } else {
                target = resolvePlayerFast(args[1]);
            }
        } else {
            target = resolvePlayerFast(args[1]);
            currency = args[2];
        }

        if (target == null || (!net.milkbowl.vault.util.UUIDCache.hasPlayedBeforeFast(target) && !target.isOnline())) {
            sender.sendMessage(getMsg("general.player-not-found", "§cPlayer not found."));
            return;
        }

        if (!currency.equalsIgnoreCase("default")) {
            boolean supported = false;
            for (String curr : currencies) {
                if (curr.equalsIgnoreCase(currency)) {
                    supported = true;
                    currency = curr; // normalize case
                    break;
                }
            }
            if (!supported) {
                sender.sendMessage(getMsg("commands.check.unsupported-currency", "§cThis currency is not supported."));
                return;
            }
        }

        double balance;
        if (currency.equalsIgnoreCase("default")) {
            balance = econ.getBalance(target);
        } else if (econ instanceof MultiCurrencyEconomy) {
            balance = ((MultiCurrencyEconomy) econ).getCurrencyBalance(target, currency);
        } else {
            sender.sendMessage(
                    getMsg("general.economy-not-found", "§cNo economy provider is registered!"));
            return;
        }

        String formatted;
        if (currency.equalsIgnoreCase("default")) {
            formatted = econ.format(balance);
        } else {
            formatted = String.format("%.2f %s", balance, getCurrencyDisplayName(currency));
        }

        if (target.getUniqueId().equals(((sender instanceof Player) ? ((Player) sender).getUniqueId() : null))) {
            sender.sendMessage(getMsg("commands.balance.current", "&fYour current balance is: &e%amount%")
                    .replace("%amount%", formatted));
        } else {
            sender.sendMessage(getMsg("commands.balance.other", "&fPlayer &e%player%&f's balance: &e%amount%")
                    .replace("%player%", getPlayerNameSafe(target, args.length > 1 ? args[1] : "Unknown"))
                    .replace("%amount%", formatted));
        }
    }

    private void handlePay(CommandSender sender, Economy econ, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(getMsg("general.only-players", "§cOnly players can execute this command."));
            return;
        }

        Player player = (Player) sender;
        if (args.length < 3) {
            sender.sendMessage(getMsg("commands.pay.usage", "&cUsage: /vaultx pay <player> <amount> [currency]"));
            return;
        }

        OfflinePlayer target = resolvePlayerFast(args[1]);
        if (target == null || (!net.milkbowl.vault.util.UUIDCache.hasPlayedBeforeFast(target) && !target.isOnline())) {
            sender.sendMessage(getMsg("general.player-not-found", "§cPlayer not found."));
            return;
        }

        if (player.getUniqueId().equals(target.getUniqueId())) {
            sender.sendMessage(
                    getMsg("commands.pay.self-pay", "&cYou cannot send money to yourself."));
            return;
        }

        double amount;
        try {
            amount = parsePositiveDouble(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage(
                    getMsg("general.invalid-amount", "&cInvalid amount. It must be a positive number."));
            return;
        }

        String currency = args.length >= 4 ? args[3] : "default";

        double senderBalance;
        if (currency.equalsIgnoreCase("default")) {
            senderBalance = econ.getBalance(player);
        } else if (econ instanceof MultiCurrencyEconomy) {
            senderBalance = ((MultiCurrencyEconomy) econ).getCurrencyBalance(player, currency);
        } else {
            sender.sendMessage(
                    getMsg("general.economy-not-found", "§cNo economy provider is registered!"));
            return;
        }

        if (senderBalance < amount) {
            sender.sendMessage(getMsg("commands.pay.insufficient-funds", "&cYou do not have enough funds."));
            return;
        }

        double tax = 0;
        double depositAmount = amount;
        net.milkbowl.vault.economy.CentralBankManager cb = net.milkbowl.vault.Vault.getCentralBankManager();
        if (cb != null && cb.isTaxesEnabled()) {
            double taxPct = cb.getPayTaxPercent();
            if (taxPct > 0) {
                tax = amount * (taxPct / 100.0);
                depositAmount = amount - tax;
            }
        }

        EconomyResponse withdrawRes;
        boolean targetOnline = target.isOnline() && target.getPlayer() != null;

        if (currency.equalsIgnoreCase("default")) {
            withdrawRes = econ.withdrawPlayer(player, amount);
            if (withdrawRes.transactionSuccess()) {
                if (targetOnline) {
                    EconomyResponse depositRes = econ.depositPlayer(target, depositAmount);
                    if (!depositRes.transactionSuccess()) {
                        econ.depositPlayer(player, amount);
                        sender.sendMessage(getMsg("commands.pay.deposit-failed",
                                "§cFailed to deposit funds into recipient's account."));
                        return;
                    }
                } else {
                    Vault.getMailboxManager().sendOfflineMail(target.getUniqueId(), player.getName(),
                            "Payment received from " + player.getName(), depositAmount, "default");
                }

                if (tax > 0 && cb != null) {
                    econ.bankDeposit(cb.getTreasuryAccount(), tax);
                }

                if (Vault.getFirewall() != null) {
                    Vault.getFirewall().recordTransfer(player, target, amount);
                }
            } else {
                sender.sendMessage(getMsg("commands.pay.tx-failed", "&cTransaction failed: %error%")
                        .replace("%error%", withdrawRes.errorMessage));
                return;
            }
        } else if (econ instanceof MultiCurrencyEconomy) {
            MultiCurrencyEconomy mcEcon = (MultiCurrencyEconomy) econ;
            withdrawRes = mcEcon.withdrawCurrencyPlayer(player, currency, amount);
            if (withdrawRes.transactionSuccess()) {
                if (targetOnline) {
                    EconomyResponse depositRes = mcEcon.depositCurrencyPlayer(target, currency, depositAmount);
                    if (!depositRes.transactionSuccess()) {
                        mcEcon.depositCurrencyPlayer(player, currency, amount);
                        sender.sendMessage(getMsg("commands.pay.deposit-failed",
                                "§cFailed to deposit funds into recipient's account."));
                        return;
                    }
                } else {
                    Vault.getMailboxManager().sendOfflineMail(target.getUniqueId(), player.getName(),
                            "Payment received from " + player.getName(), depositAmount, currency);
                }

                if (tax > 0 && cb != null) {
                    double rate = Vault.getExchangeRateManager().getRate(currency);
                    double taxInDefault = tax * rate;
                    econ.bankDeposit(cb.getTreasuryAccount(), taxInDefault);
                }

                if (Vault.getFirewall() != null) {
                    double rate = Vault.getExchangeRateManager().getRate(currency);
                    double amountInDefault = amount * rate;
                    Vault.getFirewall().recordTransfer(player, target, amountInDefault);
                }
            } else {
                sender.sendMessage(getMsg("commands.pay.tx-failed", "&cTransaction failed: %error%")
                        .replace("%error%", withdrawRes.errorMessage));
                return;
            }
        } else {
            sender.sendMessage(
                    getMsg("general.economy-not-found", "§cNo economy provider is registered!"));
            return;
        }

        String formattedAmount = currency.equalsIgnoreCase("default") ? econ.format(amount)
                : String.format("%.2f %s", amount, getCurrencyDisplayName(currency));
        String formattedTax = String.format("%.2f %s", tax, getCurrencyDisplayName(currency));

        if (tax > 0) {
            String msg = getMsg("commands.pay.success-sender",
                    "&a&l✔ &aYou sent &e%amount% &ato &e%receiver%&a.")
                    .replace("%amount%", formattedAmount)
                    .replace("%receiver%", target.getName());
            String taxMsg = getMsg("commands.pay.fee-applied", " &7(including &e%fee% &7tax)")
                    .replace("%fee%", formattedTax);
            sender.sendMessage(msg + taxMsg);
        } else {
            sender.sendMessage(
                    getMsg("commands.pay.success-sender", "&a&l✔ &aYou sent &e%amount% &ato &e%receiver%&a.")
                            .replace("%amount%", formattedAmount)
                            .replace("%receiver%", target.getName()));
        }

        if (targetOnline) {
            String formattedDep = currency.equalsIgnoreCase("default") ? econ.format(depositAmount)
                    : String.format("%.2f %s", depositAmount, getCurrencyDisplayName(currency));
            target.getPlayer().sendMessage(
                    getMsg("commands.pay.success-receiver", "&a&l✔ &aYou received &e%amount% &afrom &e%sender%&a.")
                            .replace("%amount%", formattedDep)
                            .replace("%sender%", player.getName()));
        }
    }

    private void handleAdmin(CommandSender sender, Economy econ, String[] args) {
        if (!sender.hasPermission("vault.admin")) {
            sender.sendMessage(
                    getMsg("commands.admin.no-permission", "§cYou do not have permission to execute admin commands."));
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(getMsg("commands.admin.usage",
                    "§cUsage: /vaultx admin [give/take/set/freeze/unfreeze/audit/stats/report] ..."));
            return;
        }

        String action = args[1].toLowerCase();

        if (action.equalsIgnoreCase("snapshot")) {
            net.milkbowl.vault.economy.VaultSnapshotAPI snapshotAPI = Vault.getSnapshotAPI();
            if (snapshotAPI == null) {
                sender.sendMessage("§cSnapshot API is not available.");
                return;
            }

            if (args.length < 3) {
                sender.sendMessage(
                        "§cUsage: /vaultx admin snapshot <create [label] | list | rollback <id> [player] | delete <id>>");
                return;
            }

            String sub = args[2].toLowerCase();
            if (sub.equals("create")) {
                String snapLabel = args.length >= 4
                        ? String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length))
                        : "Manual Admin Snapshot";
                sender.sendMessage("§eCreating atomic economy snapshot...");
                snapshotAPI.createSnapshotAsync(snapLabel).thenAccept(snap -> {
                    sender.sendMessage(
                            "§a✔ Snapshot created! ID: §e" + snap.snapshotId() + " §7(" + snap.totalAccountsCaptured()
                                    + " accounts, Net Worth: $" + String.format("%.2f", snap.totalNetWorth()) + ")");
                });
            } else if (sub.equals("list")) {
                snapshotAPI.getSnapshotsAsync(10).thenAccept(list -> {
                    if (list.isEmpty()) {
                        sender.sendMessage("§cNo snapshots found.");
                        return;
                    }
                    sender.sendMessage("§6--- VaultX Economy Snapshots ---");
                    for (var snap : list) {
                        sender.sendMessage("§e" + snap.snapshotId() + " §7- " + snap.label() + " §8("
                                + snap.totalAccountsCaptured() + " accounts, $"
                                + String.format("%.2f", snap.totalNetWorth()) + ")");
                    }
                });
            } else if (sub.equals("rollback")) {
                if (args.length < 4) {
                    sender.sendMessage("§cUsage: /vaultx admin snapshot rollback <snapshotId> [player]");
                    return;
                }
                String snapId = args[3];
                if (args.length >= 5) {
                    String targetPlayer = args[4];
                    OfflinePlayer target = resolvePlayerFast(targetPlayer);
                    if (target == null) {
                        sender.sendMessage("§cPlayer not found.");
                        return;
                    }
                    sender.sendMessage("§eRolling back player " + targetPlayer + " to snapshot " + snapId + "...");
                    snapshotAPI.restorePlayerSnapshotAsync(target.getUniqueId(), snapId).thenAccept(success -> {
                        if (success) {
                            sender.sendMessage("§a✔ Successfully restored player " + targetPlayer + " to snapshot "
                                    + snapId + "!");
                        } else {
                            sender.sendMessage(
                                    "§cFailed to restore player snapshot. Snapshot ID or player data not found.");
                        }
                    });
                } else {
                    sender.sendMessage("§eRolling back ENTIRE SERVER economy to snapshot " + snapId + "...");
                    snapshotAPI.restoreServerSnapshotAsync(snapId).thenAccept(success -> {
                        if (success) {
                            sender.sendMessage(
                                    "§a✔ SERVER ECONOMY ROLLBACK COMPLETE! All account balances restored to snapshot "
                                            + snapId + "!");
                        } else {
                            sender.sendMessage("§cFailed to restore server snapshot. Invalid Snapshot ID.");
                        }
                    });
                }
            } else if (sub.equals("delete")) {
                if (args.length < 4) {
                    sender.sendMessage("§cUsage: /vaultx admin snapshot delete <snapshotId>");
                    return;
                }
                String snapId = args[3];
                snapshotAPI.deleteSnapshotAsync(snapId).thenAccept(success -> {
                    if (success) {
                        sender.sendMessage("§a✔ Deleted snapshot " + snapId);
                    } else {
                        sender.sendMessage("§cFailed to delete snapshot.");
                    }
                });
            } else {
                sender.sendMessage(
                        "§cUsage: /vaultx admin snapshot <create [label] | list | rollback <id> [player] | delete <id>>");
            }
            return;
        }

        if (action.equalsIgnoreCase("tax")) {
            if (args.length < 5 || !args[2].equalsIgnoreCase("set")) {
                sender.sendMessage(getMsg("commands.admin.tax.usage",
                        "&cUsage: /vaultx admin tax set <payday/pay/exchange> <percent>"));
                return;
            }
            String taxType = args[3].toLowerCase();
            if (!taxType.equals("payday") && !taxType.equals("pay") && !taxType.equals("exchange")) {
                sender.sendMessage(getMsg("commands.admin.tax.invalid-type",
                        "&cInvalid tax type. Choices: payday, pay, exchange"));
                return;
            }
            double percent;
            try {
                percent = parseNonNegativeDouble(args[4]);
                if (percent > 100)
                    throw new NumberFormatException();
            } catch (NumberFormatException e) {
                sender.sendMessage(getMsg("commands.admin.tax.invalid-percent",
                        "&cInvalid percentage. It must be a number between 0 and 100."));
                return;
            }
            Vault.setDynamicTaxPercent(taxType, percent);
            sender.sendMessage(
                    getMsg("commands.admin.tax.success", "&a&l✔ &aTax for &e%type% &ahas been set to &e%percent%%&a.")
                            .replace("%type%", taxType)
                            .replace("%percent%", String.valueOf(percent)));
            return;
        }

        if (action.equalsIgnoreCase("treasury")) {
            net.milkbowl.vault.economy.CentralBankManager cb = Vault.getCentralBankManager();
            String treasuryAccount = cb != null ? cb.getTreasuryAccount() : "tresor_public";
            double balance = econ.bankBalance(treasuryAccount).balance;
            if (balance == 0 && !econ.bankBalance(treasuryAccount).transactionSuccess()) {
                balance = Vault.getFailoverManager().getBankBalance(treasuryAccount);
            }
            double paydayTax = Vault.getDynamicTaxPercent("payday",
                    plugin.getConfig().getDouble("paydays.tax.percent", 10.0));
            double payTax = cb != null ? cb.getPayTaxPercent() : 2.0;
            double exchangeTax = cb != null ? cb.getExchangeTaxPercent() : 1.0;

            sender.sendMessage(getMsg("commands.admin.treasury.header", "&b&l=== VaultX Public Treasury ==="));
            sender.sendMessage(getMsg("commands.admin.treasury.balance", "&fState Balance: &e%amount%")
                    .replace("%amount%", econ.format(balance)));
            sender.sendMessage(getMsg("commands.admin.treasury.account", "&fTreasury Account: &e%account%")
                    .replace("%account%", treasuryAccount));
            sender.sendMessage(getMsg("commands.admin.treasury.taxes-header", "&b-- Current Taxes --"));
            sender.sendMessage(getMsg("commands.admin.treasury.taxes-payday", "  &fSalary Tax: &e%percent%%")
                    .replace("%percent%", String.valueOf(paydayTax)));
            sender.sendMessage(getMsg("commands.admin.treasury.taxes-pay", "  &fPayment Tax: &e%percent%%")
                    .replace("%percent%", String.valueOf(payTax)));
            sender.sendMessage(
                    getMsg("commands.admin.treasury.taxes-exchange", "  &fExchange Tax (Forex): &e%percent%%")
                            .replace("%percent%", String.valueOf(exchangeTax)));
            sender.sendMessage(getMsg("commands.admin.treasury.footer", "&b&l======================================="));
            return;
        }

        if (action.equalsIgnoreCase("subvention")) {
            if (args.length < 4) {
                sender.sendMessage(getMsg("commands.admin.subvention.usage",
                        "&cUsage: /vaultx admin subvention <bank_name> <amount>"));
                return;
            }
            String bName = args[2].toLowerCase();
            double amount;
            try {
                amount = parsePositiveDouble(args[3]);
            } catch (NumberFormatException e) {
                sender.sendMessage(getMsg("commands.admin.subvention.invalid-amount",
                        "&cInvalid amount. It must be a positive number."));
                return;
            }

            net.milkbowl.vault.redis.LocalFailoverManager fm = Vault.getFailoverManager();
            if (fm.getBankAccountOwner(bName) == null) {
                sender.sendMessage(getMsg("commands.admin.subvention.bank-not-found",
                        "&cShared bank account '%bank%' does not exist.").replace("%bank%", bName));
                return;
            }

            net.milkbowl.vault.economy.CentralBankManager cb = Vault.getCentralBankManager();
            String treasuryAccount = cb != null ? cb.getTreasuryAccount() : "tresor_public";
            double treasuryBalance = econ.bankBalance(treasuryAccount).balance;
            if (treasuryBalance == 0 && !econ.bankBalance(treasuryAccount).transactionSuccess()) {
                treasuryBalance = fm.getBankBalance(treasuryAccount);
            }

            if (treasuryBalance < amount) {
                sender.sendMessage(getMsg("commands.admin.subvention.insufficient-funds",
                        "&cThe Public Treasury does not have enough funds (%amount% available).")
                        .replace("%amount%", econ.format(treasuryBalance)));
                return;
            }

            // Deduct from Treasury, deposit to bank
            econ.bankWithdraw(treasuryAccount, amount);
            econ.bankDeposit(bName, amount);

            // Sync failover
            double currentTreasury = fm.getBankBalance(treasuryAccount);
            fm.saveBankBalance(treasuryAccount, Math.max(0, currentTreasury - amount));
            double currentBank = fm.getBankBalance(bName);
            fm.saveBankBalance(bName, currentBank + amount);

            sender.sendMessage(getMsg("commands.admin.subvention.success",
                    "&a&l✔ &aSubvention of &e%amount% &agranted to bank &e%bank%&a.")
                    .replace("%amount%", econ.format(amount))
                    .replace("%bank%", bName));
            return;
        }
        if (action.equalsIgnoreCase("bank")) {
            if (args.length < 4) {
                sender.sendMessage(getMsg("commands.admin.bank-usage",
                        "§cUsage: /vaultx admin bank [delete/addmember/removemember] <nom> ..."));
                return;
            }
            String bankSub = args[2].toLowerCase();
            String bName = args[3].toLowerCase();

            if (bankSub.equals("delete")) {
                econ.deleteBank(bName);
                Vault.getFailoverManager().deleteBankAccount(bName);
                sender.sendMessage(getMsg("commands.admin.bank-delete-success",
                        "§a§l✔ §aShared bank account §e%name% §ahas been deleted by the administration.")
                        .replace("%name%", bName));
                return;
            }

            if (bankSub.equals("addmember")) {
                if (args.length < 6) {
                    sender.sendMessage(getMsg("commands.admin.bank-addmember-usage",
                            "§cUsage: /vaultx admin bank addmember <name> <player> <role>"));
                    return;
                }
                OfflinePlayer target = resolvePlayerFast(args[4]);
                if (target == null) {
                    sender.sendMessage(getMsg("general.player-not-found", "§cPlayer not found."));
                    return;
                }
                String role = args[5].toUpperCase();
                if (!role.equals("OWNER") && !role.equals("MANAGER") && !role.equals("MEMBER")
                        && !role.equals("VIEWER")) {
                    sender.sendMessage(getMsg("commands.admin.bank-role-invalid",
                            "§cInvalid role: OWNER, MANAGER, MEMBER, VIEWER."));
                    return;
                }
                Vault.getFailoverManager().addBankMember(bName, target.getUniqueId(), role);
                sender.sendMessage(getMsg("commands.admin.bank-addmember-success",
                        "§a§l✔ §aPlayer §e%player% §aadded to account §e%name% §awith role §e%role%.")
                        .replace("%player%", getPlayerNameSafe(target, args[4]))
                        .replace("%name%", bName)
                        .replace("%role%", role));
                return;
            }

            if (bankSub.equals("removemember")) {
                if (args.length < 5) {
                    sender.sendMessage(getMsg("commands.admin.bank-removemember-usage",
                            "§cUsage: /vaultx admin bank removemember <name> <player>"));
                    return;
                }
                OfflinePlayer target = resolvePlayerFast(args[4]);
                if (target == null) {
                    sender.sendMessage(getMsg("general.player-not-found", "§cPlayer not found."));
                    return;
                }
                Vault.getFailoverManager().removeBankMember(bName, target.getUniqueId());
                sender.sendMessage(getMsg("commands.admin.bank-removemember-success",
                        "§a§l✔ §aPlayer §e%player% §aremoved from account §e%name% §aby the administration.")
                        .replace("%player%", getPlayerNameSafe(target, args[4]))
                        .replace("%name%", bName));
                return;
            }

            sender.sendMessage(getMsg("commands.admin.bank-action-unknown",
                    "§cUnknown admin bank action. Choices: delete, addmember, removemember."));
            return;
        }

        if (action.equalsIgnoreCase("stats")) {
            handleStats(sender, econ);
            return;
        }
        if (action.equalsIgnoreCase("report")) {
            handleReport(sender, args);
            return;
        }
        if (action.equalsIgnoreCase("audit")) {
            handleAudit(sender, args);
            return;
        }

        if (args.length < 3) {
            sender.sendMessage(
                    getMsg("commands.admin.usage-full",
                            "§cUsage: /vaultx admin [give/take/set/freeze/unfreeze/audit/stats/report] [player] ..."));
            return;
        }

        OfflinePlayer target = resolvePlayerFast(args[2]);
        if (target == null || (!net.milkbowl.vault.util.UUIDCache.hasPlayedBeforeFast(target) && !target.isOnline())) {
            sender.sendMessage(getMsg("commands.admin.player-not-found", "§cPlayer '%player%' not found.")
                    .replace("%player%", args[2]));
            return;
        }

        if (action.equalsIgnoreCase("freeze")) {
            StringBuilder reason = new StringBuilder();
            if (args.length >= 4) {
                for (int i = 3; i < args.length; i++) {
                    if (reason.length() > 0)
                        reason.append(" ");
                    reason.append(args[i]);
                }
            } else {
                reason.append(getMsg("commands.admin.freeze-reason-manual", "Manual freeze by admin"));
            }
            net.milkbowl.vault.Vault.getFirewall().freezePlayer(target, reason.toString());
            sender.sendMessage(getMsg("commands.admin.freeze-success", "§a§l✔ §aPlayer §e%player% §ahas been frozen.")
                    .replace("%player%", getPlayerNameSafe(target, args[2])));
            return;
        } else if (action.equalsIgnoreCase("unfreeze")) {
            net.milkbowl.vault.Vault.getFirewall().unfreezePlayer(target);
            sender.sendMessage(
                    getMsg("commands.admin.unfreeze-success", "§a§l✔ §aPlayer §e%player% §ahas been unfrozen.")
                            .replace("%player%", getPlayerNameSafe(target, args[2])));
            return;
        }

        if (args.length < 5) {
            sender.sendMessage(getMsg("commands.admin.usage-give-take-set",
                    "§cUsage: /vaultx admin [give/take/set] [player] [amount] [currency]"));
            return;
        }

        double amount;
        try {
            amount = parseNonNegativeDouble(args[3]);
        } catch (NumberFormatException e) {
            sender.sendMessage(getMsg("general.invalid-amount", "§cInvalid amount. Must be a positive number."));
            return;
        }

        String currency = args[4];
        EconomyResponse response = null;

        if (currency.equalsIgnoreCase("default")) {
            switch (action) {
                case "give":
                    response = econ.depositPlayer(target, amount);
                    break;
                case "take":
                    response = econ.withdrawPlayer(target, amount);
                    break;
                case "set":
                    double current = econ.getBalance(target);
                    if (current < amount) {
                        response = econ.depositPlayer(target, amount - current);
                    } else if (current > amount) {
                        response = econ.withdrawPlayer(target, current - amount);
                    } else {
                        response = new EconomyResponse(0, current, EconomyResponse.ResponseType.SUCCESS, "");
                    }
                    break;
                default:
                    sender.sendMessage(getMsg("commands.admin.invalid-action",
                            "§cUnknown action. Use give, take, set, freeze, or unfreeze."));
                    return;
            }
        } else if (econ instanceof MultiCurrencyEconomy) {
            MultiCurrencyEconomy mcEcon = (MultiCurrencyEconomy) econ;
            switch (action) {
                case "give":
                    response = mcEcon.depositCurrencyPlayer(target, currency, amount);
                    break;
                case "take":
                    response = mcEcon.withdrawCurrencyPlayer(target, currency, amount);
                    break;
                case "set":
                    double current = mcEcon.getCurrencyBalance(target, currency);
                    if (current < amount) {
                        response = mcEcon.depositCurrencyPlayer(target, currency, amount - current);
                    } else if (current > amount) {
                        response = mcEcon.withdrawCurrencyPlayer(target, currency, current - amount);
                    } else {
                        response = new EconomyResponse(0, current, EconomyResponse.ResponseType.SUCCESS, "");
                    }
                    break;
                default:
                    sender.sendMessage(getMsg("commands.admin.invalid-action",
                            "§cUnknown action. Use give, take, set, freeze, or unfreeze."));
                    return;
            }
        } else {
            sender.sendMessage(
                    getMsg("commands.admin.multicurrency-unsupported", "§cMulti-currency is not supported."));
            return;
        }

        if (response != null && response.transactionSuccess()) {
            sender.sendMessage(getMsg("commands.admin.success-action",
                    "§a§l[VaultX] §aSuccessfully performed §e%action% §aof §f%amount% §7(%currency%) §afor §f%player%")
                    .replace("%action%", action)
                    .replace("%amount%", econ.format(amount))
                    .replace("%currency%", currency)
                    .replace("%player%", getPlayerNameSafe(target, args[2])));
        } else {
            sender.sendMessage(getMsg("commands.admin.failure-action", "§c§l[VaultX] §cTransaction failed: %error%")
                    .replace("%error%", (response != null ? response.errorMessage : "Unknown error")));
        }
    }

    private void handleAudit(CommandSender sender, String[] args) {
        // Syntax: /vaultx admin audit [player/all] [page]
        if (args.length < 3) {
            sender.sendMessage(
                    getMsg("commands.admin.audit-usage", "§cUsage: /vaultx admin audit [player/all] [page]"));
            return;
        }

        String targetStr = args[2];
        int pageVal = 1;
        if (args.length >= 4) {
            try {
                pageVal = Integer.parseInt(args[3]);
                if (pageVal < 1)
                    pageVal = 1;
            } catch (NumberFormatException e) {
                sender.sendMessage(getMsg("commands.admin.audit-invalid-page", "§cInvalid page number. Using page 1."));
            }
        }

        final int page = pageVal;
        net.milkbowl.vault.redis.LocalFailoverManager fm = net.milkbowl.vault.Vault.getFailoverManager();

        if (fm == null) {
            sender.sendMessage(
                    getMsg("general.database-error", "§cLe gestionnaire de base de données n'est pas initialisé."));
            return;
        }

        sender.sendMessage(getMsg("commands.admin.audit-fetching", "§eFetching security audits... Please wait."));

        runAsync(() -> {
            int pageSize = 6;
            java.util.List<net.milkbowl.vault.redis.LocalFailoverManager.AuditRecord> records;

            if (targetStr.equalsIgnoreCase("all")) {
                records = fm.getSecurityAuditsAll(page, pageSize);
            } else {
                OfflinePlayer target = resolvePlayerFast(targetStr);
                if (target == null) {
                    runSync(
                            () -> sender.sendMessage(getMsg("general.player-not-found", "§cPlayer not found.")));
                    return;
                }
                records = fm.getSecurityAudits(target.getUniqueId(), page, pageSize);
            }

            runSync(() -> {
                if (records.isEmpty()) {
                    sender.sendMessage(getMsg("commands.admin.audit-empty",
                            "§e§l[VaultX Audit] §cNo audit logs found for '%target%' on page %page%.")
                            .replace("%target%", targetStr)
                            .replace("%page%", String.valueOf(page)));
                    return;
                }

                sender.sendMessage(
                        getMsg("commands.admin.audit-header", "§b§l=== VaultX Security Audits (Page %page%) ===")
                                .replace("%page%", String.valueOf(page)));
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                for (net.milkbowl.vault.redis.LocalFailoverManager.AuditRecord r : records) {
                    String time = sdf.format(new java.util.Date(r.timestamp));
                    String actionColor = r.action.startsWith("BLOCKED") || r.action.equals("FREEZE")
                            || r.action.contains("SPIKE") ? "§c" : "§a";
                    String amountStr = r.amount > 0 ? " | Amount: §e"
                            + (getEconomy() != null ? getEconomy().format(r.amount) : String.format("%.2f", r.amount))
                            : "";
                    sender.sendMessage(getMsg("commands.admin.audit-entry",
                            "§7[%time%] %color%%action% §f%player%%amount% §7- %details%")
                            .replace("%time%", time)
                            .replace("%color%", actionColor)
                            .replace("%action%", r.action)
                            .replace("%player%", r.name)
                            .replace("%amount%", amountStr)
                            .replace("%details%", r.details));
                }
                sender.sendMessage(
                        getMsg("commands.admin.audit-footer", "§b§l======================================="));
            });
        });
    }

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage(
                getMsg("commands.help.header", "§b§l=== VaultX Command Help ===").replace("%label%", label));
        sender.sendMessage(
                getMsg("commands.help.menu", "§e/%label% menu §7- Open the interactive dashboard (GUI)")
                        .replace("%label%", label));
        sender.sendMessage(getMsg("commands.help.balance", "§e/%label% balance [player] [currency] §7- View balance")
                .replace("%label%", label));
        sender.sendMessage(
                getMsg("commands.help.pay", "§e/%label% pay <player> <amount> [currency] §7- Make a payment")
                        .replace("%label%", label));
        sender.sendMessage(getMsg("commands.help.exchange",
                "§e/%label% exchange <from> <to> <amount> §7- Convert currency").replace("%label%", label));
        sender.sendMessage(
                getMsg("commands.help.mailbox", "§e/%label% mailbox [claim/list] §7- Manage your mailbox")
                        .replace("%label%", label));
        sender.sendMessage(getMsg("commands.help.bank", "§e/%label% bank §7- Manage your shared bank accounts")
                .replace("%label%", label));
        sender.sendMessage(
                getMsg("commands.help.top", "§e/%label% top [currency] [page] §7- View wealth leaderboard")
                        .replace("%label%", label));
        sender.sendMessage(
                getMsg("commands.help.transactions", "§e/%label% transactions [page] §7- View your transaction history")
                        .replace("%label%", label));
        sender.sendMessage(getMsg("commands.help.escrow",
                "§e/%label% escrow [start/release/refund/list] §7- Secure transactions (Escrow)")
                .replace("%label%", label));
        if (sender.hasPermission("vault.admin")) {
            sender.sendMessage(
                    getMsg("commands.help.admin-header", "§d-- Admin Commands --").replace("%label%", label));
            sender.sendMessage(getMsg("commands.help.admin-give",
                    "§d/%label% admin give <player> <amount> <currency> §7- Give money")
                    .replace("%label%", label));
            sender.sendMessage(getMsg("commands.help.admin-take",
                    "§d/%label% admin take <player> <amount> <currency> §7- Take money")
                    .replace("%label%", label));
            sender.sendMessage(getMsg("commands.help.admin-set",
                    "§d/%label% admin set <player> <amount> <currency> §7- Set balance").replace("%label%", label));
            sender.sendMessage(getMsg("commands.help.admin-freeze",
                    "§d/%label% admin freeze <player> [reason] §7- Freeze transactions").replace("%label%", label));
            sender.sendMessage(getMsg("commands.help.admin-unfreeze",
                    "§d/%label% admin unfreeze <player> §7- Unfreeze transactions").replace("%label%", label));
            sender.sendMessage(getMsg("commands.help.admin-bank",
                    "§d/%label% admin bank [delete/addmember/removemember] §7- Force manage banks")
                    .replace("%label%", label));
            sender.sendMessage(getMsg("commands.help.admin-audit",
                    "§d/%label% admin audit [player/all] [page] §7- View security audits")
                    .replace("%label%", label));
            sender.sendMessage(
                    getMsg("commands.help.admin-stats", "§d/%label% admin stats §7- Telemetry and performance")
                            .replace("%label%", label));
            sender.sendMessage(getMsg("commands.help.admin-report",
                    "§d/%label% admin report [days] §7- Inflation analysis report").replace("%label%", label));
        }
        sender.sendMessage(
                getMsg("commands.help.footer", "§b§l================================").replace("%label%", label));
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

        if ((cmdName.equals("money") || cmdName.equals("balance") || cmdName.equals("bal"))
                && (args.length == 0 || !args[0].equalsIgnoreCase("balance"))) {
            return onTabComplete(sender, command, alias, toVxArgs("balance", args));
        } else if (cmdName.equals("pay") && (args.length == 0 || !args[0].equalsIgnoreCase("pay"))) {
            return onTabComplete(sender, command, alias, toVxArgs("pay", args));
        } else if ((cmdName.equals("baltop") || cmdName.equals("moneytop"))
                && (args.length == 0 || !args[0].equalsIgnoreCase("top"))) {
            return onTabComplete(sender, command, alias, toVxArgs("top", args));
        } else if ((cmdName.equals("bank") || cmdName.equals("sharedbank"))
                && (args.length == 0 || !args[0].equalsIgnoreCase("bank"))) {
            return onTabComplete(sender, command, alias, toVxArgs("bank", args));
        } else if ((cmdName.equals("loan") || cmdName.equals("loans"))
                && (args.length == 0 || !args[0].equalsIgnoreCase("loan"))) {
            return onTabComplete(sender, command, alias, toVxArgs("loan", args));
        } else if ((cmdName.equals("mailbox") || cmdName.equals("mail"))
                && (args.length == 0 || !args[0].equalsIgnoreCase("mailbox"))) {
            return onTabComplete(sender, command, alias, toVxArgs("mailbox", args));
        } else if (cmdName.equals("escrow") && (args.length == 0 || !args[0].equalsIgnoreCase("escrow"))) {
            return onTabComplete(sender, command, alias, toVxArgs("escrow", args));
        } else if ((cmdName.equals("stocks") || cmdName.equals("stockmarket"))
                && (args.length == 0 || !args[0].equalsIgnoreCase("stocks"))) {
            return onTabComplete(sender, command, alias, toVxArgs("stocks", args));
        } else if ((cmdName.equals("exchange") || cmdName.equals("forex") || cmdName.equals("convert"))
                && (args.length == 0 || !args[0].equalsIgnoreCase("exchange"))) {
            return onTabComplete(sender, command, alias, toVxArgs("exchange", args));
        } else if ((cmdName.equals("eco") || cmdName.equals("economy"))
                && (args.length == 0 || !args[0].equalsIgnoreCase("admin"))) {
            return onTabComplete(sender, command, alias, toVxArgs("admin", args));
        } else if ((cmdName.equals("check") || cmdName.equals("cheque"))
                && (args.length == 0 || !args[0].equalsIgnoreCase("check"))) {
            return onTabComplete(sender, command, alias, toVxArgs("check", args));
        } else if (cmdName.equals("payday") || cmdName.equals("salary")) {
            return Collections.emptyList();
        } else if ((cmdName.equals("transactions") || cmdName.equals("tx") || cmdName.equals("history"))
                && (args.length == 0 || !args[0].equalsIgnoreCase("transactions"))) {
            return onTabComplete(sender, command, alias, toVxArgs("transactions", args));
        } else if ((cmdName.equals("subscribe") || cmdName.equals("subscriptions"))
                && (args.length == 0 || !args[0].equalsIgnoreCase("subscribe"))) {
            return onTabComplete(sender, command, alias, toVxArgs("subscribe", args));
        }

        if (args.length == 1) {
            suggestions.add("balance");
            suggestions.add("pay");
            suggestions.add("top");
            suggestions.add("transactions");
            suggestions.add("escrow");
            suggestions.add("menu");
            suggestions.add("gui");
            suggestions.add("exchange");
            suggestions.add("forex");
            suggestions.add("mailbox");
            suggestions.add("mail");
            suggestions.add("bank");
            suggestions.add("loan");
            suggestions.add("stocks");
            suggestions.add("subscribe");
            suggestions.add("check");
            suggestions.add("crypto");
            suggestions.add("milestones");
            suggestions.add("payday");
            suggestions.add("blackmarket");
            suggestions.add("discord");
            if (sender.hasPermission("vault.admin")) {
                suggestions.add("admin");
                suggestions.add("stats");
                suggestions.add("metrics");
                suggestions.add("logs");
                suggestions.add("dynamicpricing");
            }
            return suggestions.stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }

        String sub = args[0].toLowerCase();
        if (sub.equals("subscribe")) {
            if (args.length == 2) {
                suggestions.add("create");
                suggestions.add("list");
                suggestions.add("cancel");
                suggestions.add("resume");
                return suggestions.stream()
                        .filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
            } else if (args.length == 3 && args[1].equalsIgnoreCase("create")) {
                List<String> targets = new ArrayList<>();
                Bukkit.getOnlinePlayers().forEach(p -> targets.add(p.getName()));
                if (net.milkbowl.vault.Vault.getFailoverManager() != null) {
                    targets.addAll(net.milkbowl.vault.Vault.getFailoverManager()
                            .getBanksForPlayer(((Player) sender).getUniqueId()));
                }
                return targets.stream()
                        .filter(s -> s.toLowerCase().startsWith(args[2].toLowerCase()))
                        .collect(Collectors.toList());
            } else if (args.length == 5 && args[1].equalsIgnoreCase("create")) {
                return getCurrencies(econ).stream()
                        .filter(s -> s.toLowerCase().startsWith(args[4].toLowerCase()))
                        .collect(Collectors.toList());
            } else if (args.length == 3 && (args[1].equalsIgnoreCase("cancel") || args[1].equalsIgnoreCase("resume"))) {
                if (sender instanceof Player) {
                    if (net.milkbowl.vault.Vault.getFailoverManager() != null) {
                        return net.milkbowl.vault.Vault.getFailoverManager()
                                .getSubscriptionsForSubscriber(((Player) sender).getUniqueId()).stream()
                                .map(s -> s.id)
                                .filter(s -> s.toLowerCase().startsWith(args[2].toLowerCase()))
                                .collect(Collectors.toList());
                    }
                }
            }
            return Collections.emptyList();
        }
        if (sub.equals("loan")) {
            if (args.length == 2) {
                suggestions.add("apply");
                suggestions.add("list");
                suggestions.add("info");
                return suggestions.stream()
                        .filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
            } else if (args.length == 3 && args[1].equalsIgnoreCase("apply")) {
                if (sender instanceof Player) {
                    net.milkbowl.vault.redis.LocalFailoverManager fm = net.milkbowl.vault.Vault.getFailoverManager();
                    if (fm != null) {
                        return fm.getBanksForPlayer(((Player) sender).getUniqueId()).stream()
                                .filter(s -> s.toLowerCase().startsWith(args[2].toLowerCase()))
                                .collect(Collectors.toList());
                    }
                }
            } else if (args.length == 3 && args[1].equalsIgnoreCase("info")) {
                if (sender instanceof Player) {
                    net.milkbowl.vault.redis.LocalFailoverManager fm = net.milkbowl.vault.Vault.getFailoverManager();
                    if (fm != null) {
                        return fm.getLoansForPlayer(((Player) sender).getUniqueId()).stream()
                                .map(lr -> lr.id)
                                .filter(s -> s.toLowerCase().startsWith(args[2].toLowerCase()))
                                .collect(Collectors.toList());
                    }
                }
            }
            return Collections.emptyList();
        }
        if (sub.equals("balance") || sub.equals("bal")) {
            if (args.length == 2) {
                List<String> options = new ArrayList<>();
                Bukkit.getOnlinePlayers().forEach(p -> options.add(p.getName()));
                options.addAll(getCurrencies(econ));
                return options.stream()
                        .filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
            } else if (args.length == 3) {
                return getCurrencies(econ).stream()
                        .filter(s -> s.toLowerCase().startsWith(args[2].toLowerCase()))
                        .collect(Collectors.toList());
            }
        } else if (sub.equals("pay")) {
            if (args.length == 2) {
                return Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
            } else if (args.length == 3) {
                suggestions.add("10");
                suggestions.add("100");
                suggestions.add("1000");
                return suggestions;
            } else if (args.length == 4) {
                return getCurrencies(econ).stream()
                        .filter(s -> s.toLowerCase().startsWith(args[3].toLowerCase()))
                        .collect(Collectors.toList());
            }
        } else if (sub.equals("top") || sub.equals("rich")) {
            if (args.length == 2) {
                List<String> options = new ArrayList<>(getCurrencies(econ));
                options.add("1");
                options.add("2");
                return options.stream()
                        .filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
            } else if (args.length == 3) {
                suggestions.add("1");
                suggestions.add("2");
                return suggestions.stream()
                        .filter(s -> s.toLowerCase().startsWith(args[2].toLowerCase()))
                        .collect(Collectors.toList());
            }
        } else if (sub.equals("transactions") || sub.equals("tx") || sub.equals("history")) {
            if (args.length == 2) {
                suggestions.add("1");
                suggestions.add("2");
                return suggestions.stream()
                        .filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
            }
        } else if (sub.equals("escrow")) {
            if (args.length == 2) {
                suggestions.add("start");
                suggestions.add("release");
                suggestions.add("refund");
                suggestions.add("list");
                return suggestions.stream()
                        .filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
            } else if (args.length == 3) {
                if (args[1].equalsIgnoreCase("start")) {
                    return Bukkit.getOnlinePlayers().stream()
                            .map(Player::getName)
                            .filter(name -> name.toLowerCase().startsWith(args[2].toLowerCase()))
                            .collect(Collectors.toList());
                }
            } else if (args.length == 4) {
                if (args[1].equalsIgnoreCase("start")) {
                    suggestions.add("10");
                    suggestions.add("100");
                    suggestions.add("1000");
                    return suggestions;
                }
            } else if (args.length == 5) {
                if (args[1].equalsIgnoreCase("start")) {
                    return getCurrencies(econ).stream()
                            .filter(s -> s.toLowerCase().startsWith(args[4].toLowerCase()))
                            .collect(Collectors.toList());
                }
            }
        } else if (sub.equals("exchange") || sub.equals("forex")) {
            if (args.length == 2) {
                List<String> options = new ArrayList<>(getCurrencies(econ));
                options.add("rates");
                return options.stream()
                        .filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
            } else if (args.length == 3) {
                return getCurrencies(econ).stream()
                        .filter(s -> s.toLowerCase().startsWith(args[2].toLowerCase()))
                        .collect(Collectors.toList());
            } else if (args.length == 4) {
                suggestions.add("10");
                suggestions.add("100");
                suggestions.add("1000");
                return suggestions;
            }
        } else if (sub.equals("mailbox") || sub.equals("mail")) {
            if (args.length == 2) {
                suggestions.add("claim");
                suggestions.add("list");
                return suggestions.stream()
                        .filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
            }
        } else if (sub.equals("bank")) {
            if (args.length == 2) {
                suggestions.add("create");
                suggestions.add("delete");
                suggestions.add("bal");
                suggestions.add("deposit");
                suggestions.add("withdraw");
                suggestions.add("addmember");
                suggestions.add("removemember");
                suggestions.add("list");
                return suggestions.stream()
                        .filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
            } else if (args.length == 3) {
                if (sender instanceof Player && (args[1].equalsIgnoreCase("delete") || args[1].equalsIgnoreCase("bal")
                        || args[1].equalsIgnoreCase("deposit") || args[1].equalsIgnoreCase("withdraw")
                        || args[1].equalsIgnoreCase("addmember") || args[1].equalsIgnoreCase("removemember"))) {
                    net.milkbowl.vault.redis.LocalFailoverManager fm = net.milkbowl.vault.Vault.getFailoverManager();
                    if (fm != null) {
                        return fm.getBanksForPlayer(((Player) sender).getUniqueId()).stream()
                                .filter(s -> s.toLowerCase().startsWith(args[2].toLowerCase()))
                                .collect(Collectors.toList());
                    }
                }
            } else if (args.length == 4) {
                if (args[1].equalsIgnoreCase("deposit") || args[1].equalsIgnoreCase("withdraw")) {
                    suggestions.add("10");
                    suggestions.add("100");
                    suggestions.add("1000");
                    return suggestions;
                } else if (args[1].equalsIgnoreCase("addmember") || args[1].equalsIgnoreCase("removemember")) {
                    return Bukkit.getOnlinePlayers().stream()
                            .map(Player::getName)
                            .filter(name -> name.toLowerCase().startsWith(args[3].toLowerCase()))
                            .collect(Collectors.toList());
                }
            } else if (args.length == 5) {
                if (args[1].equalsIgnoreCase("addmember")) {
                    suggestions.add("OWNER");
                    suggestions.add("MANAGER");
                    suggestions.add("MEMBER");
                    suggestions.add("VIEWER");
                    return suggestions.stream()
                            .filter(s -> s.toLowerCase().startsWith(args[4].toLowerCase()))
                            .collect(Collectors.toList());
                }
            }
        } else if (sub.equals("admin") && sender.hasPermission("vault.admin")) {
            if (args.length == 2) {
                suggestions.add("give");
                suggestions.add("take");
                suggestions.add("set");
                suggestions.add("freeze");
                suggestions.add("unfreeze");
                suggestions.add("bank");
                suggestions.add("audit");
                suggestions.add("stats");
                suggestions.add("report");
                return suggestions.stream()
                        .filter(s -> s.startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
            } else if (args.length == 3) {
                if (args[1].equalsIgnoreCase("stats")) {
                    return Collections.emptyList();
                }
                if (args[1].equalsIgnoreCase("report")) {
                    suggestions.add("1");
                    suggestions.add("7");
                    suggestions.add("30");
                    return suggestions.stream()
                            .filter(s -> s.toLowerCase().startsWith(args[2].toLowerCase()))
                            .collect(Collectors.toList());
                }
                if (args[1].equalsIgnoreCase("audit")) {
                    List<String> options = Bukkit.getOnlinePlayers().stream()
                            .map(Player::getName)
                            .collect(Collectors.toList());
                    options.add("all");
                    return options.stream()
                            .filter(name -> name.toLowerCase().startsWith(args[2].toLowerCase()))
                            .collect(Collectors.toList());
                }
                if (args[1].equalsIgnoreCase("bank")) {
                    suggestions.add("delete");
                    suggestions.add("addmember");
                    suggestions.add("removemember");
                    return suggestions.stream()
                            .filter(s -> s.toLowerCase().startsWith(args[2].toLowerCase()))
                            .collect(Collectors.toList());
                }
                return Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(name -> name.toLowerCase().startsWith(args[2].toLowerCase()))
                        .collect(Collectors.toList());
            } else if (args.length == 4) {
                if (args[1].equalsIgnoreCase("stats") || args[1].equalsIgnoreCase("report")) {
                    return Collections.emptyList();
                }
                if (args[1].equalsIgnoreCase("audit")) {
                    suggestions.add("1");
                    suggestions.add("2");
                    suggestions.add("3");
                    return suggestions.stream()
                            .filter(s -> s.toLowerCase().startsWith(args[3].toLowerCase()))
                            .collect(Collectors.toList());
                }
                if (args[1].equalsIgnoreCase("freeze")) {
                    suggestions.add("Duplication_abuse");
                    suggestions.add("Suspicious_activity");
                    return suggestions.stream()
                            .filter(s -> s.toLowerCase().startsWith(args[3].toLowerCase()))
                            .collect(Collectors.toList());
                }
                if (args[1].equalsIgnoreCase("unfreeze")) {
                    return Collections.emptyList();
                }
                if (args[1].equalsIgnoreCase("bank")) {
                    net.milkbowl.vault.redis.LocalFailoverManager fm = net.milkbowl.vault.Vault.getFailoverManager();
                    if (fm != null) {
                        return fm.loadAllBanks().keySet().stream()
                                .filter(s -> s.toLowerCase().startsWith(args[3].toLowerCase()))
                                .collect(Collectors.toList());
                    }
                }
                suggestions.add("10");
                suggestions.add("100");
                suggestions.add("1000");
                return suggestions;
            } else if (args.length == 5) {
                if (args[1].equalsIgnoreCase("freeze") || args[1].equalsIgnoreCase("unfreeze")
                        || args[1].equalsIgnoreCase("audit") || args[1].equalsIgnoreCase("stats")
                        || args[1].equalsIgnoreCase("report")) {
                    return Collections.emptyList();
                }
                if (args[1].equalsIgnoreCase("bank")) {
                    if (args[2].equalsIgnoreCase("addmember") || args[2].equalsIgnoreCase("removemember")) {
                        return Bukkit.getOnlinePlayers().stream()
                                .map(Player::getName)
                                .filter(name -> name.toLowerCase().startsWith(args[4].toLowerCase()))
                                .collect(Collectors.toList());
                    }
                }
                return getCurrencies(econ).stream()
                        .filter(s -> s.toLowerCase().startsWith(args[4].toLowerCase()))
                        .collect(Collectors.toList());
            } else if (args.length == 6) {
                if (args[1].equalsIgnoreCase("bank") && args[2].equalsIgnoreCase("addmember")) {
                    suggestions.add("OWNER");
                    suggestions.add("MANAGER");
                    suggestions.add("MEMBER");
                    suggestions.add("VIEWER");
                    return suggestions.stream()
                            .filter(s -> s.toLowerCase().startsWith(args[5].toLowerCase()))
                            .collect(Collectors.toList());
                }
            }
        } else if (sub.equals("check")) {
            if (args.length == 2) {
                suggestions.add("write");
                return suggestions.stream()
                        .filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
            } else if (args.length == 3 && args[1].equalsIgnoreCase("write")) {
                suggestions.add("10");
                suggestions.add("100");
                suggestions.add("1000");
                return suggestions;
            } else if (args.length == 4 && args[1].equalsIgnoreCase("write")) {
                return getCurrencies(econ).stream()
                        .filter(s -> s.toLowerCase().startsWith(args[3].toLowerCase()))
                        .collect(Collectors.toList());
            }
        } else if (sub.equals("logs")) {
            if (args.length == 2 && sender.hasPermission("vault.admin")) {
                return Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
            }
        }

        return Collections.emptyList();
    }

    private void handleTop(CommandSender sender, Economy econ, String[] args) {
        if (!sender.hasPermission("vault.top") && !sender.hasPermission("vault.balance.top")
                && !sender.hasPermission("vault.admin")) {
            sender.sendMessage(
                    getMsg("general.no-permission", "§cYou do not have permission to execute this command!"));
            return;
        }

        String currency = "default";
        int page = 1;

        if (args.length >= 2) {
            try {
                page = Integer.parseInt(args[1]);
                if (page < 1)
                    page = 1;
            } catch (NumberFormatException e) {
                currency = args[1];
                if (args.length >= 3) {
                    try {
                        page = Integer.parseInt(args[2]);
                        if (page < 1)
                            page = 1;
                    } catch (NumberFormatException ex) {
                        sender.sendMessage(getMsg("commands.top.invalid-page", "§cInvalid page number."));
                        return;
                    }
                }
            }
        }

        net.milkbowl.vault.redis.VaultRedisManager redis = net.milkbowl.vault.redis.VaultRedisManager.getInstance();
        if (redis == null) {
            sender.sendMessage(getMsg("commands.top.redis-not-enabled",
                    "§c§l[VaultX] §cRedis is not enabled. Wealth leaderboard is unavailable."));
            return;
        }

        List<net.milkbowl.vault.redis.VaultRedisManager.LeaderboardEntry> leaderboard = redis.getLeaderboard(currency);
        List<String> excluded = plugin.getConfig().getStringList("baltop.excluded-accounts");
        if (excluded != null && !excluded.isEmpty()) {
            List<String> lowerExcluded = excluded.stream().map(String::toLowerCase)
                    .collect(java.util.stream.Collectors.toList());
            leaderboard = leaderboard.stream().filter(e -> e != null && !lowerExcluded.contains(e.name.toLowerCase()))
                    .collect(java.util.stream.Collectors.toList());
        }

        boolean hideVanished = plugin.getConfig().getBoolean("baltop.hide-vanished-players", true);
        if (hideVanished) {
            leaderboard = leaderboard.stream().filter(e -> {
                if (e == null)
                    return false;
                org.bukkit.entity.Player p = Bukkit.getPlayerExact(e.name);
                if (p != null) {
                    for (org.bukkit.metadata.MetadataValue meta : p.getMetadata("vanished")) {
                        if (meta.asBoolean())
                            return false;
                    }
                }
                return true;
            }).collect(java.util.stream.Collectors.toList());
        }
        if (leaderboard.isEmpty()) {
            sender.sendMessage(
                    getMsg("commands.top.no-data", "§e§l[VaultX Rich List] §cNo data found for currency '%currency%'.")
                            .replace("%currency%", currency));
            return;
        }

        int pageSize = 10;
        int maxPages = (int) Math.ceil((double) leaderboard.size() / pageSize);
        if (page > maxPages)
            page = maxPages;

        String divider = getMsg("commands.top.divider", "&6&m+---------------------------------------------------+");
        sender.sendMessage(divider);
        sender.sendMessage(getMsg("commands.top.header", "§6§l               VaultX Rich List (%currency%)")
                .replace("%currency%", getCurrencyDisplayName(currency))
                .replace("%page%", String.valueOf(page))
                .replace("%max%", String.valueOf(maxPages)));
        sender.sendMessage(divider);

        int start = (page - 1) * pageSize;
        int end = Math.min(start + pageSize, leaderboard.size());

        for (int i = start; i < end; i++) {
            net.milkbowl.vault.redis.VaultRedisManager.LeaderboardEntry entry = leaderboard.get(i);
            String formattedBalance;
            if (currency.equalsIgnoreCase("default")) {
                formattedBalance = econ.format(entry.balance);
            } else {
                formattedBalance = String.format("%,.2f %s", entry.balance, getCurrencyDisplayName(currency));
            }

            sender.sendMessage(getMsg("commands.top.entry", "§e #%rank%  §f%player%        §8»  §a%amount%")
                    .replace("%rank%", String.valueOf(i + 1))
                    .replace("%player%", entry.name)
                    .replace("%amount%", formattedBalance));
        }

        sender.sendMessage(divider);
        sender.sendMessage(getMsg("commands.top.footer", "§e   Page %page%/%max%  |  Use /vx top [devise] [page]")
                .replace("%currency%", getCurrencyDisplayName(currency))
                .replace("%page%", String.valueOf(page))
                .replace("%max%", String.valueOf(maxPages)));
        sender.sendMessage(divider);
    }

    private void handleSubscribe(CommandSender sender, Economy econ, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(getMsg("general.only-players", "§cOnly players can execute this command."));
            return;
        }

        Player player = (Player) sender;
        boolean subEnabled = plugin.getConfig().getBoolean("subscriptions.enabled", true);
        if (!subEnabled) {
            player.sendMessage(getMsg("commands.subscribe.disabled",
                    "§cThe recurring subscription system is disabled on this server."));
            return;
        }

        if (args.length < 2) {
            sendSubscribeHelp(player);
            return;
        }

        String action = args[1].toLowerCase();
        net.milkbowl.vault.redis.LocalFailoverManager fm = net.milkbowl.vault.Vault.getFailoverManager();
        if (fm == null) {
            player.sendMessage(getMsg("commands.subscribe.db-error", "§cDatabase communication error."));
            return;
        }

        if (action.equals("create")) {
            if (args.length < 5) {
                player.sendMessage(getMsg("commands.subscribe.create-usage",
                        "§cUsage: /subscribe create <player/bank> <amount> <hours> [currency]"));
                return;
            }

            String targetInput = args[2];
            double amount;
            try {
                amount = parsePositiveDouble(args[3]);
            } catch (NumberFormatException e) {
                player.sendMessage(getMsg("general.invalid-amount", "§cInvalid amount. It must be a positive number."));
                return;
            }

            int hours;
            try {
                hours = Integer.parseInt(args[4]);
                if (hours < 1)
                    throw new NumberFormatException();
            } catch (NumberFormatException e) {
                player.sendMessage(
                        getMsg("commands.subscribe.invalid-hours", "§cInvalid interval. It must be at least 1 hour."));
                return;
            }

            String currency = args.length >= 6 ? args[5].toLowerCase() : "default";
            if (!getCurrencies(econ).contains(currency)) {
                player.sendMessage(getMsg("general.currency-not-supported", "§cThis currency is not supported."));
                return;
            }

            String targetType;
            String targetVal;
            String targetName;

            if (fm.getBankAccountOwner(targetInput) != null) {
                targetType = "BANK";
                targetVal = targetInput.toLowerCase();
                targetName = targetInput.toUpperCase() + " (Bank)";
            } else {
                OfflinePlayer targetPlayer = resolvePlayerFast(targetInput);
                if (targetPlayer == null || (!net.milkbowl.vault.util.UUIDCache.hasPlayedBeforeFast(targetPlayer)
                        && !targetPlayer.isOnline())) {
                    player.sendMessage(getMsg("general.player-not-found", "§cPlayer or bank not found."));
                    return;
                }
                if (player.getUniqueId().equals(targetPlayer.getUniqueId())) {
                    player.sendMessage(getMsg("commands.subscribe.self-sub", "§cYou cannot subscribe to yourself."));
                    return;
                }
                targetType = "PLAYER";
                targetVal = targetPlayer.getUniqueId().toString();
                targetName = targetPlayer.getName() != null ? targetPlayer.getName() : "Unknown";
            }

            List<net.milkbowl.vault.redis.LocalFailoverManager.SubscriptionRecord> existing = fm
                    .getSubscriptionsForSubscriber(player.getUniqueId());
            int limit = plugin.getConfig().getInt("subscriptions.max-active-per-player", 10);
            if (limit > 0 && existing.stream().filter(s -> s.status.equalsIgnoreCase("ACTIVE")).count() >= limit) {
                player.sendMessage(getMsg("commands.subscribe.limit-exceeded",
                        "§cYou have reached the limit of %limit% active subscriptions.")
                        .replace("%limit%", String.valueOf(limit)));
                return;
            }

            String subId = UUID.randomUUID().toString().substring(0, 8);
            net.milkbowl.vault.redis.LocalFailoverManager.SubscriptionRecord sub = new net.milkbowl.vault.redis.LocalFailoverManager.SubscriptionRecord(
                    subId,
                    player.getUniqueId(),
                    targetType,
                    targetVal,
                    amount,
                    currency,
                    hours,
                    0,
                    System.currentTimeMillis(),
                    "ACTIVE",
                    System.currentTimeMillis());

            runAsync(() -> {
                net.milkbowl.vault.economy.SubscriptionManager sm = net.milkbowl.vault.Vault.getSubscriptionManager();
                if (sm != null) {
                    fm.saveSubscription(sub);
                    sm.processSingleSubscription(econ, sub, System.currentTimeMillis());

                    net.milkbowl.vault.redis.LocalFailoverManager.SubscriptionRecord updated = fm
                            .getSubscription(subId);
                    runSync(() -> {
                        if (updated != null && updated.status.equalsIgnoreCase("SUSPENDED")) {
                            player.sendMessage(getMsg("commands.subscribe.created-suspended",
                                    "§e§l[Subscription] §cSubscription #%id% created to %target% but immediately suspended due to insufficient balance.")
                                    .replace("%id%", subId)
                                    .replace("%target%", targetName));
                        } else {
                            player.sendMessage(getMsg("commands.subscribe.created-success",
                                    "§a§l✔ §aSubscription #%id% successfully created to %target% for %amount% every %hours% hour(s).")
                                    .replace("%id%", subId)
                                    .replace("%target%", targetName)
                                    .replace("%amount%",
                                            currency.equalsIgnoreCase("default") ? econ.format(amount)
                                                    : String.format("%.2f %s", amount,
                                                            getCurrencyDisplayName(currency)))
                                    .replace("%hours%", String.valueOf(hours)));
                        }
                    });
                }
            });

        } else if (action.equals("list")) {
            runAsync(() -> {
                List<net.milkbowl.vault.redis.LocalFailoverManager.SubscriptionRecord> subs = fm
                        .getSubscriptionsForSubscriber(player.getUniqueId());
                runSync(() -> {
                    if (subs.isEmpty()) {
                        player.sendMessage(getMsg("commands.subscribe.list-empty",
                                "§e§l[Subscription] §7You have no active or suspended subscriptions."));
                        return;
                    }
                    player.sendMessage(
                            getMsg("commands.subscribe.list-header", "&d&l📬 Your Recurring Subscriptions 📬"));
                    for (net.milkbowl.vault.redis.LocalFailoverManager.SubscriptionRecord s : subs) {
                        String tName = s.target;
                        if (s.targetType.equalsIgnoreCase("PLAYER")) {
                            OfflinePlayer pTarget = Bukkit.getOfflinePlayer(UUID.fromString(s.target));
                            tName = pTarget.getName() != null ? pTarget.getName() : "Unknown";
                        } else {
                            tName = s.target.toUpperCase() + " (Bank)";
                        }

                        String statusColor = s.status.equalsIgnoreCase("ACTIVE") ? "§a" : "§c";
                        String formattedAmt = s.currency.equalsIgnoreCase("default") ? econ.format(s.amount)
                                : String.format("%.2f %s", s.amount, getCurrencyDisplayName(s.currency));
                        player.sendMessage(getMsg("commands.subscribe.list-entry",
                                "&8- &fID: &e%id% &8| &fTarget: &b%target% &8| &fAmount: &7%amount% &8| &fFreq: &7%hours%h &8| Status: %color%%status%")
                                .replace("%id%", s.id)
                                .replace("%target%", tName)
                                .replace("%amount%", formattedAmt)
                                .replace("%hours%", String.valueOf(s.intervalHours))
                                .replace("%color%", statusColor)
                                .replace("%status%", s.status));
                    }
                });
            });

        } else if (action.equals("cancel")) {
            if (args.length < 3) {
                player.sendMessage(getMsg("commands.subscribe.cancel-usage", "§cUsage: /subscribe cancel <id>"));
                return;
            }
            String subId = args[2];
            runAsync(() -> {
                net.milkbowl.vault.redis.LocalFailoverManager.SubscriptionRecord s = fm.getSubscription(subId);
                if (s == null || !s.subscriber.equals(player.getUniqueId())) {
                    player.sendMessage(getMsg("commands.subscribe.not-found", "§cSubscription not found."));
                    return;
                }
                fm.deleteSubscription(subId);
                String tName = s.target;
                if (s.targetType.equalsIgnoreCase("PLAYER")) {
                    OfflinePlayer pTarget = Bukkit.getOfflinePlayer(UUID.fromString(s.target));
                    tName = pTarget.getName() != null ? pTarget.getName() : "Unknown";
                } else {
                    tName = s.target.toUpperCase() + " (Bank)";
                }
                final String finalTarget = tName;
                runSync(() -> {
                    player.sendMessage(getMsg("commands.subscribe.cancel-success",
                            "§a§l✔ §aSubscription #%id% to %target% has been cancelled.")
                            .replace("%id%", subId)
                            .replace("%target%", finalTarget));
                });
            });

        } else if (action.equals("resume")) {
            if (args.length < 3) {
                player.sendMessage(getMsg("commands.subscribe.resume-usage", "§cUsage: /subscribe resume <id>"));
                return;
            }
            String subId = args[2];
            runAsync(() -> {
                net.milkbowl.vault.redis.LocalFailoverManager.SubscriptionRecord s = fm.getSubscription(subId);
                if (s == null || !s.subscriber.equals(player.getUniqueId())) {
                    player.sendMessage(getMsg("commands.subscribe.not-found", "§cSubscription not found."));
                    return;
                }
                if (s.status.equalsIgnoreCase("ACTIVE")) {
                    player.sendMessage(
                            getMsg("commands.subscribe.already-active", "§cThis subscription is already active."));
                    return;
                }

                fm.updateSubscriptionBilling(subId, s.lastBilling, System.currentTimeMillis(), "ACTIVE");
                net.milkbowl.vault.economy.SubscriptionManager sm = net.milkbowl.vault.Vault.getSubscriptionManager();
                if (sm != null) {
                    net.milkbowl.vault.redis.LocalFailoverManager.SubscriptionRecord reloaded = fm
                            .getSubscription(subId);
                    sm.processSingleSubscription(econ, reloaded, System.currentTimeMillis());

                    net.milkbowl.vault.redis.LocalFailoverManager.SubscriptionRecord updated = fm
                            .getSubscription(subId);
                    runSync(() -> {
                        if (updated != null && updated.status.equalsIgnoreCase("SUSPENDED")) {
                            player.sendMessage(getMsg("commands.subscribe.resume-failed",
                                    "§c§l[Subscription] §cCould not reactivate subscription #%id% because your balance is still insufficient.")
                                    .replace("%id%", subId));
                        } else {
                            player.sendMessage(getMsg("commands.subscribe.resume-success",
                                    "§a§l✔ §aSubscription #%id% has been successfully reactivated!")
                                    .replace("%id%", subId));
                        }
                    });
                }
            });

        } else {
            sendSubscribeHelp(player);
        }
    }

    private void sendSubscribeHelp(Player player) {
        player.sendMessage(getMsg("commands.subscribe.help-header", "&d&l=== VaultX Subscription Help ==="));
        player.sendMessage(getMsg("commands.subscribe.help-create",
                "&e/subscribe create <player/bank> <amount> <hours> [currency] &7- Create a subscription"));
        player.sendMessage(getMsg("commands.subscribe.help-list",
                "&e/subscribe list &7- View your active and suspended subscriptions"));
        player.sendMessage(
                getMsg("commands.subscribe.help-cancel", "&e/subscribe cancel <id> &7- Cancel a subscription"));
        player.sendMessage(getMsg("commands.subscribe.help-resume",
                "&e/subscribe resume <id> &7- Reactivate a suspended subscription"));
        player.sendMessage(getMsg("commands.subscribe.help-footer", "&d&l================================"));
    }

    private void handleTransactions(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(getMsg("commands.transactions.only-players",
                    "§cOnly players can check their transaction history."));
            return;
        }

        Player player = (Player) sender;
        int pageVal = 1;
        if (args.length >= 2) {
            try {
                pageVal = Integer.parseInt(args[1]);
                if (pageVal < 1)
                    pageVal = 1;
            } catch (NumberFormatException e) {
                sender.sendMessage(getMsg("commands.transactions.invalid-page", "§cInvalid page number."));
                return;
            }
        }

        final int page = pageVal;
        net.milkbowl.vault.redis.LocalFailoverManager fm = net.milkbowl.vault.Vault.getFailoverManager();
        if (fm == null) {
            sender.sendMessage(
                    getMsg("commands.transactions.database-error", "§cDatabase manager is not initialized."));
            return;
        }

        sender.sendMessage(getMsg("commands.transactions.fetching", "§eFetching transaction history... Please wait."));

        runAsync(() -> {
            int pageSize = 8;
            List<net.milkbowl.vault.redis.LocalFailoverManager.PlayerTransactionRecord> txs = fm
                    .getPlayerTransactions(player.getUniqueId(), page, pageSize);

            runSync(() -> {
                if (txs.isEmpty()) {
                    sender.sendMessage(getMsg("commands.transactions.empty",
                            "§d§l[VaultX History] §cNo transaction history found on page %page%.")
                            .replace("%page%", String.valueOf(page)));
                    return;
                }

                sender.sendMessage(
                        getMsg("commands.transactions.header", "§d§l=== Your Transaction History (Page %page%) ===")
                                .replace("%page%", String.valueOf(page)));
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                for (net.milkbowl.vault.redis.LocalFailoverManager.PlayerTransactionRecord tx : txs) {
                    String time = sdf.format(new java.util.Date(tx.timestamp));
                    String actionColor = tx.type.equalsIgnoreCase("WITHDRAW") ? "§c" : "§a";
                    String actionSymbol = tx.type.equalsIgnoreCase("WITHDRAW") ? "[-] " : "[+] ";
                    String other = (tx.otherParty != null && !tx.otherParty.isEmpty()) ? " §7(" + tx.otherParty + ")"
                            : "";
                    sender.sendMessage(getMsg("commands.transactions.entry",
                            "§7[%time%] %color%%symbol%%type% §e%amount% §7[%currency%]%other%")
                            .replace("%time%", time)
                            .replace("%color%", actionColor)
                            .replace("%symbol%", actionSymbol)
                            .replace("%type%", tx.type)
                            .replace("%amount%",
                                    tx.currency.equalsIgnoreCase("default")
                                            ? (getEconomy() != null ? getEconomy().format(tx.amount)
                                                    : String.format("%.2f", tx.amount))
                                            : String.format("%.2f", tx.amount))
                            .replace("%currency%", getCurrencyDisplayName(tx.currency))
                            .replace("%other%", other));
                }
                sender.sendMessage(
                        getMsg("commands.transactions.footer", "§d§l============================================="));
            });
        });
    }

    private void handleStats(CommandSender sender, Economy econ) {
        if (!sender.hasPermission("vault.admin")) {
            sender.sendMessage(
                    getMsg("commands.admin.no-permission", "§cYou do not have permission to execute admin commands."));
            return;
        }

        sender.sendMessage(getMsg("commands.admin.stats-fetching", "§eFetching telemetry statistics... Please wait."));

        long hits = 0;
        long misses = 0;
        double hitRate = 0.0;
        if (econ instanceof net.milkbowl.vault.economy.OptimizedEconomy) {
            net.milkbowl.vault.economy.OptimizedEconomy optEcon = (net.milkbowl.vault.economy.OptimizedEconomy) econ;
            hits = optEcon.getCacheHits();
            misses = optEcon.getCacheMisses();
            long total = hits + misses;
            hitRate = total == 0 ? 0.0 : ((double) hits / total) * 100.0;
        }

        double writeLatency = 0.0;
        net.milkbowl.vault.redis.LocalFailoverManager fm = net.milkbowl.vault.Vault.getFailoverManager();
        if (fm != null) {
            writeLatency = fm.getAverageBatchWriteLatencyMs();
        }

        net.milkbowl.vault.redis.VaultRedisManager redis = net.milkbowl.vault.redis.VaultRedisManager.getInstance();
        boolean redisOnline = redis != null && redis.isOnline();

        final long finalHits = hits;
        final long finalMisses = misses;
        final double finalHitRate = hitRate;
        final double finalWriteLatency = writeLatency;

        // Run currency stats query asynchronously
        runAsync(() -> {
            List<String> currencies = getCurrencies(econ);
            java.util.Map<String, String> currencyStatsLines = new java.util.LinkedHashMap<>();

            for (String currency : currencies) {
                if (redisOnline) {
                    net.milkbowl.vault.redis.VaultRedisManager.CurrencyStats stats = redis
                            .getGlobalCurrencyStats(currency);
                    if (stats != null) {
                        String totalStr = econ.format(stats.totalMoney);
                        String avgStr = econ.format(stats.averageBalance);
                        currencyStatsLines.put(currency,
                                getMsg("commands.admin.stats-supply-details",
                                        "§7- Total Money: §a%total% §7| Accounts: §e%accounts% §7| Avg: §a%avg%")
                                        .replace("%total%", totalStr)
                                        .replace("%accounts%", String.valueOf(stats.accountsCount))
                                        .replace("%avg%", avgStr));
                    } else {
                        currencyStatsLines.put(currency,
                                getMsg("commands.admin.stats-supply-unavailable", "§7- Statistics not available."));
                    }
                } else {
                    if (fm != null) {
                        net.milkbowl.vault.redis.LocalFailoverManager.LocalCurrencyStats stats = fm
                                .getLocalCurrencyStats(currency);
                        String totalStr = econ.format(stats.totalMoney);
                        String avgStr = econ.format(stats.averageBalance);
                        currencyStatsLines.put(currency,
                                getMsg("commands.admin.stats-supply-details",
                                        "§7- Total Money: §a%total% §7| Accounts: §e%accounts% §7| Avg: §a%avg%")
                                        .replace("%total%", totalStr)
                                        .replace("%accounts%", String.valueOf(stats.accountsCount))
                                        .replace("%avg%", avgStr));
                    } else {
                        currencyStatsLines.put(currency,
                                getMsg("commands.admin.stats-supply-unavailable", "§7- Statistics not available."));
                    }
                }
            }

            runSync(() -> {
                sender.sendMessage(getMsg("commands.admin.stats-header", "§b§l=== VaultX Telemetry & Statistics ==="));
                if (econ instanceof net.milkbowl.vault.economy.OptimizedEconomy) {
                    sender.sendMessage(getMsg("commands.admin.stats-cache-rate",
                            "§fCache Hit Rate: §e%rate%% §7(%hits% hits, %misses% misses)")
                            .replace("%rate%", String.format("%.2f", finalHitRate))
                            .replace("%hits%", String.valueOf(finalHits))
                            .replace("%misses%", String.valueOf(finalMisses)));
                } else {
                    sender.sendMessage(
                            getMsg("commands.admin.stats-cache-na", "§fCache Hit Rate: §eN/A §7(Standard Vault)"));
                }
                sender.sendMessage(getMsg("commands.admin.stats-latency", "§fLocal DB Write Latency: §e%latency% ms")
                        .replace("%latency%", String.format("%.2f", finalWriteLatency)));
                sender.sendMessage(getMsg("commands.admin.stats-redis", "§fRedis Connection: %status%")
                        .replace("%status%", redisOnline ? "§aONLINE" : "§cOFFLINE"));

                sender.sendMessage(getMsg("commands.admin.stats-supply-header", "§b-- Currency Supply (%source%) --")
                        .replace("%source%", redisOnline ? "Global/Redis" : "Local DB"));
                for (java.util.Map.Entry<String, String> entry : currencyStatsLines.entrySet()) {
                    sender.sendMessage(getMsg("commands.admin.stats-supply-entry", "  §f%currency% :")
                            .replace("%currency%", entry.getKey().toUpperCase()));
                    sender.sendMessage("  " + entry.getValue());
                }
                sender.sendMessage(getMsg("commands.admin.stats-footer", "§b§l======================================"));
            });
        });
    }

    private void handleMacroStatsText(CommandSender sender, Economy econ) {
        sender.sendMessage(getMsg("commands.admin.stats-fetching", "§eFetching telemetry statistics... Please wait."));

        net.milkbowl.vault.redis.LocalFailoverManager fm = net.milkbowl.vault.Vault.getFailoverManager();
        if (fm == null) {
            sender.sendMessage(
                    getMsg("commands.transactions.database-error", "§cDatabase manager is not initialized."));
            return;
        }

        net.milkbowl.vault.redis.VaultRedisManager redis = net.milkbowl.vault.redis.VaultRedisManager.getInstance();
        boolean redisOnline = redis != null && redis.isOnline();

        runAsync(() -> {
            List<String> currencies = getCurrencies(econ);
            java.util.Map<String, net.milkbowl.vault.redis.LocalFailoverManager.LocalCurrencyStats> currencyStats = new java.util.HashMap<>();

            for (String currency : currencies) {
                if (redisOnline) {
                    net.milkbowl.vault.redis.VaultRedisManager.CurrencyStats stats = redis
                            .getGlobalCurrencyStats(currency);
                    if (stats != null) {
                        currencyStats.put(currency,
                                new net.milkbowl.vault.redis.LocalFailoverManager.LocalCurrencyStats(stats.totalMoney,
                                        stats.accountsCount, stats.averageBalance));
                    } else {
                        currencyStats.put(currency, fm.getLocalCurrencyStats(currency));
                    }
                } else {
                    currencyStats.put(currency, fm.getLocalCurrencyStats(currency));
                }
            }

            net.milkbowl.vault.economy.CentralBankManager cb = Vault.getCentralBankManager();
            final String treasuryAccount = cb != null ? cb.getTreasuryAccount() : "tresor_public";
            double cbBalanceVal = econ.bankBalance(treasuryAccount).balance;
            if (cbBalanceVal == 0 && !econ.bankBalance(treasuryAccount).transactionSuccess()) {
                cbBalanceVal = fm.getBankBalance(treasuryAccount);
            }
            final double cbBalance = cbBalanceVal;

            final double bankLoansDebt = fm.getTotalBankLoansDebt();
            final double playerDebts = fm.getTotalPlayerDebts();
            final double totalDebt = bankLoansDebt + playerDebts;

            final List<net.milkbowl.vault.redis.VaultRedisManager.LeaderboardEntry> leaderboard;
            if (redisOnline) {
                leaderboard = redis.getLeaderboard("default");
            } else {
                leaderboard = fm.getLocalLeaderboard("default", 10);
            }

            runSync(() -> {
                sender.sendMessage(
                        getMsg("commands.admin.stats-header", "§b§l=== VaultX Macroeconomic Statistics ==="));

                sender.sendMessage("§b-- Currency Supply & Accounts --");
                for (String curr : currencies) {
                    net.milkbowl.vault.redis.LocalFailoverManager.LocalCurrencyStats stats = currencyStats.get(curr);
                    double total = stats != null ? stats.totalMoney : 0.0;
                    long accs = stats != null ? stats.accountsCount : 0;
                    double avg = stats != null ? stats.averageBalance : 0.0;

                    String formatTotal = curr.equals("default") ? econ.format(total)
                            : String.format("%.2f %s", total, curr.toUpperCase());
                    String formatAvg = curr.equals("default") ? econ.format(avg)
                            : String.format("%.2f %s", avg, curr.toUpperCase());

                    sender.sendMessage("  §f" + curr.toUpperCase() + ":");
                    sender.sendMessage("    §7- Total Money Supply: §a" + formatTotal);
                    sender.sendMessage("    §7- Total Accounts: §e" + accs);
                    sender.sendMessage("    §7- Average Balance: §b" + formatAvg);
                }

                sender.sendMessage("§b-- Central Bank Reserve --");
                sender.sendMessage("  §fReserve (" + treasuryAccount + "): §e" + econ.format(cbBalance));

                sender.sendMessage("§b-- Macroeconomic Debts --");
                sender.sendMessage("  §fActive Bank Loans: §e" + econ.format(bankLoansDebt));
                sender.sendMessage("  §fPlayer Garnishments: §e" + econ.format(playerDebts));
                sender.sendMessage("  §c§lTotal Debts: §e" + econ.format(totalDebt));

                sender.sendMessage("§b-- Rich List (Top 10 Default Currency) --");
                int rank = 1;
                for (net.milkbowl.vault.redis.VaultRedisManager.LeaderboardEntry entry : leaderboard) {
                    if (rank > 10)
                        break;
                    sender.sendMessage(
                            String.format("  §e#%d  §f%s  §8»  §a%s", rank, entry.name, econ.format(entry.balance)));
                    rank++;
                }
                if (leaderboard.isEmpty()) {
                    sender.sendMessage("  §cNo rich list data available.");
                }

                sender.sendMessage(
                        getMsg("commands.admin.stats-footer", "§b§l========================================="));
            });
        });
    }

    private void handleLogs(CommandSender sender, Economy econ, String[] args) {
        if (!sender.hasPermission("vault.admin")) {
            sender.sendMessage(
                    getMsg("commands.admin.no-permission", "§cYou do not have permission to execute admin commands."));
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(getMsg("commands.logs.usage", "§cUsage: /vaultx logs <player> [page]"));
            return;
        }

        String targetName = args[1];
        int pageVal = 1;
        if (args.length >= 3) {
            try {
                pageVal = Integer.parseInt(args[2]);
                if (pageVal < 1)
                    pageVal = 1;
            } catch (NumberFormatException e) {
                sender.sendMessage(getMsg("commands.transactions.invalid-page", "§cInvalid page number."));
                return;
            }
        }

        final int page = pageVal;
        net.milkbowl.vault.redis.LocalFailoverManager fm = net.milkbowl.vault.Vault.getFailoverManager();
        if (fm == null) {
            sender.sendMessage(
                    getMsg("commands.transactions.database-error", "§cDatabase manager is not initialized."));
            return;
        }

        sender.sendMessage(getMsg("commands.logs.fetching", "§eFetching transaction logs for %player%...")
                .replace("%player%", targetName));

        runAsync(() -> {
            OfflinePlayer target = resolvePlayerFast(targetName);
            if (target == null
                    || (!net.milkbowl.vault.util.UUIDCache.hasPlayedBeforeFast(target) && !target.isOnline())) {
                runSync(() -> {
                    sender.sendMessage(getMsg("commands.admin.player-not-found", "§cPlayer '%player%' not found.")
                            .replace("%player%", targetName));
                });
                return;
            }

            int pageSize = 10;
            List<net.milkbowl.vault.redis.LocalFailoverManager.PlayerTransactionRecord> txs = fm
                    .getPlayerTransactions(target.getUniqueId(), page, pageSize);

            runSync(() -> {
                if (txs.isEmpty()) {
                    sender.sendMessage(getMsg("commands.logs.empty",
                            "§d§l[VaultX Logs] §cNo transaction logs found for %player% on page %page%.")
                            .replace("%player%", target.getName() != null ? target.getName() : targetName)
                            .replace("%page%", String.valueOf(page)));
                    return;
                }

                String resolvedName = target.getName() != null ? target.getName() : targetName;
                sender.sendMessage(
                        getMsg("commands.logs.header", "§b§l=== Transaction Logs: %player% (Page %page%) ===")
                                .replace("%player%", resolvedName)
                                .replace("%page%", String.valueOf(page)));

                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("dd/MM HH:mm");
                java.text.SimpleDateFormat sdfHover = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

                for (net.milkbowl.vault.redis.LocalFailoverManager.PlayerTransactionRecord tx : txs) {
                    String time = sdf.format(new java.util.Date(tx.timestamp));
                    String hoverTime = sdfHover.format(new java.util.Date(tx.timestamp));

                    String typeUpper = tx.type.toUpperCase();
                    boolean isDeposit = typeUpper.startsWith("DEPOSIT") || typeUpper.startsWith("CLAIMED")
                            || typeUpper.contains("DIVIDEND") || typeUpper.contains("SALARY");
                    String actionColor = isDeposit ? "§a" : "§c";
                    String actionSymbol = isDeposit ? "+" : "-";

                    String cleanType = tx.type.replace("DEPOSIT_", "").replace("WITHDRAW_", "").toUpperCase();
                    String currencyName = getCurrencyDisplayName(tx.currency);
                    String formattedAmount = tx.currency.equalsIgnoreCase("default")
                            ? (econ != null ? econ.format(tx.amount) : String.format("%.2f", tx.amount))
                            : String.format("%.2f %s", tx.amount, currencyName);

                    String other = (tx.otherParty != null && !tx.otherParty.isEmpty()) ? tx.otherParty : "System";

                    if (sender instanceof Player) {
                        Player playerSender = (Player) sender;
                        try {
                            net.md_5.bungee.api.chat.TextComponent messageLine = new net.md_5.bungee.api.chat.TextComponent(
                                    "§7[" + time + "] ");

                            net.md_5.bungee.api.chat.TextComponent typeComp = new net.md_5.bungee.api.chat.TextComponent(
                                    "§f" + cleanType + " ");
                            typeComp.setHoverEvent(new net.md_5.bungee.api.chat.HoverEvent(
                                    net.md_5.bungee.api.chat.HoverEvent.Action.SHOW_TEXT,
                                    new net.md_5.bungee.api.chat.ComponentBuilder(
                                            "§7Category: §e" + tx.category + "\n§7Raw Type: §e" + tx.type).create()));

                            net.md_5.bungee.api.chat.TextComponent amountComp = new net.md_5.bungee.api.chat.TextComponent(
                                    actionColor + actionSymbol + formattedAmount + " ");
                            amountComp.setHoverEvent(new net.md_5.bungee.api.chat.HoverEvent(
                                    net.md_5.bungee.api.chat.HoverEvent.Action.SHOW_TEXT,
                                    new net.md_5.bungee.api.chat.ComponentBuilder("§7Date: §e" + hoverTime).create()));

                            net.md_5.bungee.api.chat.TextComponent arrowComp = new net.md_5.bungee.api.chat.TextComponent(
                                    "§8» ");

                            net.md_5.bungee.api.chat.TextComponent otherComp = new net.md_5.bungee.api.chat.TextComponent(
                                    "§f" + other);
                            otherComp.setHoverEvent(new net.md_5.bungee.api.chat.HoverEvent(
                                    net.md_5.bungee.api.chat.HoverEvent.Action.SHOW_TEXT,
                                    new net.md_5.bungee.api.chat.ComponentBuilder("§7Target/Source Party").create()));

                            messageLine.addExtra(typeComp);
                            messageLine.addExtra(amountComp);
                            messageLine.addExtra(arrowComp);
                            messageLine.addExtra(otherComp);

                            playerSender.spigot().sendMessage(messageLine);
                        } catch (Throwable t) {
                            sender.sendMessage("§7[" + time + "] §f" + cleanType + " " + actionColor + actionSymbol
                                    + formattedAmount + " §8» §f" + other);
                        }
                    } else {
                        sender.sendMessage("§7[" + time + "] §f" + cleanType + " " + actionColor + actionSymbol
                                + formattedAmount + " §8» §f" + other);
                    }
                }

                if (sender instanceof Player) {
                    Player playerSender = (Player) sender;
                    try {
                        net.md_5.bungee.api.chat.TextComponent footer = new net.md_5.bungee.api.chat.TextComponent(
                                "§6§m+---------------------------------------------------+\n");

                        net.md_5.bungee.api.chat.TextComponent prev = new net.md_5.bungee.api.chat.TextComponent(
                                "§e◀ Précédent");
                        if (page > 1) {
                            prev.setClickEvent(new net.md_5.bungee.api.chat.ClickEvent(
                                    net.md_5.bungee.api.chat.ClickEvent.Action.RUN_COMMAND,
                                    "/vx logs " + targetName + " " + (page - 1)));
                            prev.setHoverEvent(new net.md_5.bungee.api.chat.HoverEvent(
                                    net.md_5.bungee.api.chat.HoverEvent.Action.SHOW_TEXT,
                                    new net.md_5.bungee.api.chat.ComponentBuilder("§ePage " + (page - 1)).create()));
                        } else {
                            prev.setColor(net.md_5.bungee.api.ChatColor.GRAY);
                        }

                        net.md_5.bungee.api.chat.TextComponent mid = new net.md_5.bungee.api.chat.TextComponent(
                                "   §7|   Page " + page + "   |   ");

                        net.md_5.bungee.api.chat.TextComponent next = new net.md_5.bungee.api.chat.TextComponent(
                                "§eSuivant ▶");
                        if (txs.size() == 10) {
                            next.setClickEvent(new net.md_5.bungee.api.chat.ClickEvent(
                                    net.md_5.bungee.api.chat.ClickEvent.Action.RUN_COMMAND,
                                    "/vx logs " + targetName + " " + (page + 1)));
                            next.setHoverEvent(new net.md_5.bungee.api.chat.HoverEvent(
                                    net.md_5.bungee.api.chat.HoverEvent.Action.SHOW_TEXT,
                                    new net.md_5.bungee.api.chat.ComponentBuilder("§ePage " + (page + 1)).create()));
                        } else {
                            next.setColor(net.md_5.bungee.api.ChatColor.GRAY);
                        }

                        footer.addExtra(prev);
                        footer.addExtra(mid);
                        footer.addExtra(next);

                        playerSender.spigot().sendMessage(footer);
                    } catch (Throwable t) {
                        sender.sendMessage("§6§m+---------------------------------------------------+");
                        sender.sendMessage("§ePage " + page + " | Use /vx logs " + targetName + " " + (page + 1)
                                + " for next page");
                    }
                } else {
                    sender.sendMessage("§6§m+---------------------------------------------------+");
                    sender.sendMessage(
                            "Page " + page + " | Use /vx logs " + targetName + " " + (page + 1) + " for next page");
                }
            });
        });
    }

    private void handleReport(CommandSender sender, String[] args) {
        if (!sender.hasPermission("vault.admin")) {
            sender.sendMessage(
                    getMsg("commands.admin.no-permission", "§cYou do not have permission to execute admin commands."));
            return;
        }

        int daysVal = 7;
        if (args.length >= 3) {
            try {
                daysVal = Integer.parseInt(args[2]);
                if (daysVal < 1)
                    daysVal = 7;
            } catch (NumberFormatException e) {
                sender.sendMessage(getMsg("commands.admin.report-invalid-days",
                        "§cInvalid number of days. Using default: 7 days."));
            }
        }

        final int days = daysVal;
        net.milkbowl.vault.redis.LocalFailoverManager fm = net.milkbowl.vault.Vault.getFailoverManager();
        if (fm == null) {
            sender.sendMessage(
                    getMsg("commands.transactions.database-error", "§cDatabase manager is not initialized."));
            return;
        }

        sender.sendMessage(
                getMsg("commands.admin.report-fetching", "§eGenerating inflation analytics report... Please wait."));

        runAsync(() -> {
            java.util.List<net.milkbowl.vault.redis.LocalFailoverManager.AnalyticsReportEntry> report = fm
                    .getAnalyticsReport(days);

            runSync(() -> {
                if (report.isEmpty()) {
                    sender.sendMessage(getMsg("commands.admin.report-empty",
                            "§d§l[VaultX Report] §cNo transaction records found for the last %days% days.")
                            .replace("%days%", String.valueOf(days)));
                    return;
                }

                sender.sendMessage(getMsg("commands.admin.report-header",
                        "§d§l=== VaultX Inflation & Ledger Report (Last %days% Days) ===")
                        .replace("%days%", String.valueOf(days)));
                Economy econ = getEconomy();
                java.util.Map<String, java.util.List<net.milkbowl.vault.redis.LocalFailoverManager.AnalyticsReportEntry>> grouped = new java.util.HashMap<>();
                for (net.milkbowl.vault.redis.LocalFailoverManager.AnalyticsReportEntry entry : report) {
                    grouped.computeIfAbsent(entry.currency.toUpperCase(), k -> new java.util.ArrayList<>()).add(entry);
                }

                for (java.util.Map.Entry<String, java.util.List<net.milkbowl.vault.redis.LocalFailoverManager.AnalyticsReportEntry>> currencyEntry : grouped
                        .entrySet()) {
                    sender.sendMessage(getMsg("commands.admin.report-currency-header", "§bCurrency: §l%currency%")
                            .replace("%currency%", currencyEntry.getKey()));
                    double totalCreated = 0;
                    double totalDestroyed = 0;
                    for (net.milkbowl.vault.redis.LocalFailoverManager.AnalyticsReportEntry entry : currencyEntry
                            .getValue()) {
                        totalCreated += entry.created;
                        totalDestroyed += entry.destroyed;
                        String createdStr = econ != null ? econ.format(entry.created)
                                : String.format("%.2f", entry.created);
                        String destroyedStr = econ != null ? econ.format(entry.destroyed)
                                : String.format("%.2f", entry.destroyed);
                        sender.sendMessage(getMsg("commands.admin.report-category-entry",
                                "  §f%category% §7- Created: §a%created% §7| Destroyed: §c%destroyed%")
                                .replace("%category%", entry.category)
                                .replace("%created%", createdStr)
                                .replace("%destroyed%", destroyedStr));
                    }
                    double netInflation = totalCreated - totalDestroyed;
                    String netColor = netInflation >= 0 ? "§a+" : "§c";
                    String netStr = econ != null ? econ.format(netInflation) : String.format("%.2f", netInflation);
                    sender.sendMessage(
                            getMsg("commands.admin.report-net-inflation", "  §7Net Inflation: %color%%amount%")
                                    .replace("%color%", netColor)
                                    .replace("%amount%", netStr));
                }
                sender.sendMessage(getMsg("commands.admin.report-footer",
                        "§d§l==========================================================="));
            });
        });
    }

    private void handleEscrow(CommandSender sender, String[] args) {
        net.milkbowl.vault.redis.EscrowManager em = net.milkbowl.vault.Vault.getEscrowManager();
        if (em == null) {
            sender.sendMessage(getMsg("commands.escrow.manager-uninitialized",
                    "§c§l[VaultX] §cEscrow manager is not initialized."));
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(
                    getMsg("commands.escrow.usage", "§cUsage: /vaultx escrow [start/release/refund/list] ..."));
            return;
        }

        String sub = args[1].toLowerCase();
        switch (sub) {
            case "start":
                if (!(sender instanceof Player)) {
                    sender.sendMessage(getMsg("commands.escrow.only-players-start",
                            "§cOnly players can start escrow transactions."));
                    return;
                }
                if (args.length < 4) {
                    sender.sendMessage(getMsg("commands.escrow.usage-start",
                            "§cUsage: /vaultx escrow start <receiver> <amount> [currency]"));
                    return;
                }
                OfflinePlayer receiver = resolvePlayerFast(args[2]);
                if (receiver == null
                        || (!net.milkbowl.vault.util.UUIDCache.hasPlayedBeforeFast(receiver) && !receiver.isOnline())) {
                    sender.sendMessage(getMsg("commands.escrow.player-never-played",
                            "§cPlayer '%player%' has never played before.").replace("%player%", args[2]));
                    return;
                }
                double amount;
                try {
                    amount = parsePositiveDouble(args[3]);
                } catch (NumberFormatException e) {
                    sender.sendMessage(
                            getMsg("general.invalid-amount", "§cInvalid amount. Must be a positive number."));
                    return;
                }
                String currency = args.length >= 5 ? args[4] : "default";

                sender.sendMessage(getMsg("commands.escrow.initiating", "§eInitiating escrow transaction..."));
                em.startEscrow((Player) sender, receiver, amount, currency, 300).thenAccept(res -> {
                    runSync(() -> {
                        if (res.success) {
                            sender.sendMessage(getMsg("commands.escrow.success-started",
                                    "§a§l✔ §aEscrow transaction started! ID: §e%id%").replace("%id%", res.escrowId));
                            sender.sendMessage(getMsg("commands.escrow.success-started-info",
                                    "§7The funds are locked. Use §f/vx escrow release %id% §7once you receive your items/services.")
                                    .replace("%id%", res.escrowId));
                        } else {
                            sender.sendMessage(getMsg("commands.escrow.failed-start", "§c§l❌ §cEscrow failed: %error%")
                                    .replace("%error%", res.message));
                        }
                    });
                });
                break;

            case "release":
                if (args.length < 3) {
                    sender.sendMessage(getMsg("commands.escrow.usage-release", "§cUsage: /vaultx escrow release <id>"));
                    return;
                }
                String releaseId = args[2];
                sender.sendMessage(
                        getMsg("commands.escrow.releasing", "§eReleasing escrow %id%...").replace("%id%", releaseId));
                em.releaseEscrow(releaseId, sender).thenAccept(res -> {
                    runSync(() -> {
                        if (res.success) {
                            sender.sendMessage(getMsg("commands.escrow.success-released",
                                    "§a§l✔ §aEscrow %id% released successfully!").replace("%id%", releaseId));
                        } else {
                            sender.sendMessage(getMsg("commands.escrow.failed-release",
                                    "§c§l❌ §cFailed to release escrow: %error%").replace("%error%", res.message));
                        }
                    });
                });
                break;

            case "refund":
                if (args.length < 3) {
                    sender.sendMessage(getMsg("commands.escrow.usage-refund", "§cUsage: /vaultx escrow refund <id>"));
                    return;
                }
                String refundId = args[2];
                sender.sendMessage(
                        getMsg("commands.escrow.refunding", "§eRefunding escrow %id%...").replace("%id%", refundId));
                em.refundEscrow(refundId, sender).thenAccept(res -> {
                    runSync(() -> {
                        if (res.success) {
                            sender.sendMessage(getMsg("commands.escrow.success-refunded",
                                    "§a§l✔ §aEscrow %id% refunded successfully!").replace("%id%", refundId));
                        } else {
                            sender.sendMessage(
                                    getMsg("commands.escrow.failed-refund", "§c§l❌ §cFailed to refund escrow: %error%")
                                            .replace("%error%", res.message));
                        }
                    });
                });
                break;

            case "list":
                if (!(sender instanceof Player)) {
                    sender.sendMessage(getMsg("commands.escrow.only-players-list",
                            "§cOnly players can list their active escrows."));
                    return;
                }
                Player player = (Player) sender;
                sender.sendMessage(getMsg("commands.escrow.fetching", "§eFetching your active escrows..."));
                em.listEscrows(player).thenAccept(list -> {
                    runSync(() -> {
                        if (list.isEmpty()) {
                            sender.sendMessage(getMsg("commands.escrow.empty",
                                    "§e§l[VaultX Escrow] §cYou have no active escrow transactions."));
                            return;
                        }
                        sender.sendMessage(getMsg("commands.escrow.header", "§b§l=== Your Active Escrows ==="));
                        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                        for (net.milkbowl.vault.redis.EscrowManager.EscrowDetails desc : list) {
                            String role = desc.sender.equals(player.getUniqueId()) ? "§a[SENDER]" : "§d[RECEIVER]";
                            String time = sdf.format(new java.util.Date(desc.timeoutAt));
                            Economy econ = getEconomy();
                            String amountStr = (econ != null) ? econ.format(desc.amount)
                                    : String.format("%.2f", desc.amount);
                            sender.sendMessage(getMsg("commands.escrow.entry",
                                    "§7- ID: §e%id% §f%role% §7| Montant: §e%amount% §7(%currency%) | Statut: §f%status% §7| Exp: %time%")
                                    .replace("%id%", desc.id)
                                    .replace("%role%", role)
                                    .replace("%amount%", amountStr)
                                    .replace("%currency%", desc.currency)
                                    .replace("%status%", desc.status)
                                    .replace("%time%", time));
                        }
                        sender.sendMessage(getMsg("commands.escrow.footer", "§b§l============================="));
                    });
                });
                break;

            default:
                sender.sendMessage(getMsg("commands.escrow.unknown-subcommand",
                        "§cUnknown escrow sub-command. Use start, release, refund, or list."));
                break;
        }
    }

    private void handleMenu(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(getMsg("commands.menu.only-players", "§cOnly players can open the menu."));
            return;
        }
        Player player = (Player) sender;
        if (net.milkbowl.vault.Vault.getVaultXGUI() != null) {
            net.milkbowl.vault.Vault.getVaultXGUI().openDashboard(player);
        } else {
            sender.sendMessage(getMsg("commands.menu.gui-not-enabled", "§c§l[VaultX] §cThe GUI menu is not enabled."));
        }
    }

    private void handleExchange(CommandSender sender, String[] args) {
        if (!sender.hasPermission("vault.convert") && !sender.hasPermission("vault.exchange")
                && !sender.hasPermission("vault.admin")) {
            sender.sendMessage(
                    getMsg("general.no-permission", "§cYou do not have permission to execute this command!"));
            return;
        }

        if (!(sender instanceof Player)) {
            if (args.length == 2 && args[1].equalsIgnoreCase("rates")) {
                sender.sendMessage(getMsg("commands.exchange.rates-header", "§b§l=== Current Exchange Rates ==="));
                java.util.Map<String, Double> rates = net.milkbowl.vault.Vault.getExchangeRateManager().getRates();
                for (java.util.Map.Entry<String, Double> entry : rates.entrySet()) {
                    sender.sendMessage(
                            getMsg("commands.exchange.rates-entry", "  §f1 §e%from% §7= §a%rate% §7(Base Currency)")
                                    .replace("%from%", entry.getKey().toUpperCase())
                                    .replace("%rate%", String.format("%.4f", entry.getValue())));
                }
                sender.sendMessage(getMsg("commands.exchange.rates-footer", "§b§l==============================="));
                return;
            }
            sender.sendMessage(getMsg("commands.exchange.only-players-convert",
                    "§cOnly players can perform currency conversions."));
            return;
        }

        Player player = (Player) sender;
        if (args.length < 4) {
            if (args.length == 2 && args[1].equalsIgnoreCase("rates")) {
                sender.sendMessage(getMsg("commands.exchange.rates-header", "§b§l=== Current Exchange Rates ==="));
                java.util.Map<String, Double> rates = net.milkbowl.vault.Vault.getExchangeRateManager().getRates();
                for (java.util.Map.Entry<String, Double> entry : rates.entrySet()) {
                    sender.sendMessage(
                            getMsg("commands.exchange.rates-entry", "  §f1 §e%from% §7= §a%rate% §7(Base Currency)")
                                    .replace("%from%", entry.getKey().toUpperCase())
                                    .replace("%rate%", String.format("%.4f", entry.getValue())));
                }
                sender.sendMessage(getMsg("commands.exchange.rates-footer", "§b§l==============================="));
                return;
            }
            if (net.milkbowl.vault.Vault.getVaultXGUI() != null) {
                net.milkbowl.vault.Vault.getVaultXGUI().openExchange(player);
            } else {
                String cmdWord = args[0].toLowerCase();
                sender.sendMessage(getMsg("commands.exchange.rates-usage",
                        "§cUsage: /vaultx " + cmdWord + " <from> <to> <amount> or rates"));
            }
            return;
        }

        double amount;
        try {
            amount = parsePositiveDouble(args[3]);
        } catch (NumberFormatException e) {
            sender.sendMessage(
                    getMsg("general.invalid-amount", "§cInvalid amount. It must be a positive number."));
            return;
        }

        sender.sendMessage(getMsg("commands.exchange.converting", "§eConverting..."));
        final String fromCurrency = args[1];
        final String toCurrency = args[2];
        final double finalAmount = amount;
        ConversionResult res = net.milkbowl.vault.Vault.getExchangeRateManager().convert(player, fromCurrency,
                toCurrency, finalAmount);
        if (res != null && res.success) {
            sender.sendMessage(getMsg("commands.exchange.success", "§a§l✔ §aConversion successful: %message%")
                    .replace("%message%", res.message));
            net.milkbowl.vault.util.VaultXVisuals.sendTransactionNotification(player, toCurrency,
                    res.receivedAmount, true);
        } else {
            sender.sendMessage(getMsg("commands.exchange.failure", "§c§l❌ §cConversion failed: %message%")
                    .replace("%message%", res != null ? res.message : "Error"));
            net.milkbowl.vault.util.VaultXVisuals.playFailureSound(player);
        }
    }

    private void handleMailbox(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(getMsg("commands.mailbox.only-players",
                    "§cOnly players can manage their mailbox."));
            return;
        }

        Player player = (Player) sender;
        if (args.length == 1) {
            if (net.milkbowl.vault.Vault.getVaultXGUI() != null) {
                net.milkbowl.vault.Vault.getVaultXGUI().openMailbox(player);
            } else {
                sender.sendMessage(getMsg("commands.mailbox.usage", "§cUsage: /vaultx mailbox [claim/list]"));
            }
            return;
        }

        String sub = args[1].toLowerCase();
        if (sub.equals("list")) {
            sender.sendMessage(getMsg("commands.mailbox.fetching", "§eRécupération de vos messages en attente..."));
            runAsync(() -> {
                java.util.List<net.milkbowl.vault.redis.LocalFailoverManager.MailRecord> mails = net.milkbowl.vault.Vault
                        .getMailboxManager().getPendingMail(player.getUniqueId());
                runSync(() -> {
                    if (mails.isEmpty()) {
                        player.sendMessage(getMsg("commands.mailbox.empty-box",
                                "§d§l[Mailbox] §cYou have no pending messages."));
                        return;
                    }
                    player.sendMessage(getMsg("commands.mailbox.list-header", "§d§l=== Your Offline Payments ==="));
                    java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm");
                    for (net.milkbowl.vault.redis.LocalFailoverManager.MailRecord mail : mails) {
                        player.sendMessage(getMsg("commands.mailbox.list-entry",
                                "  §7- §e%amount% §7from §f%sender% §7| Message: §7%msg%")
                                .replace("%amount%",
                                        String.format("%.2f", mail.amount) + " " + mail.currency.toUpperCase())
                                .replace("%sender%", mail.senderName)
                                .replace("%msg%",
                                        mail.message + " (" + sdf.format(new java.util.Date(mail.timestamp)) + ")"));
                    }
                    player.sendMessage(
                            getMsg("commands.mailbox.chat-info", "§7Type §e/vx mailbox claim §7to claim all."));
                });
            });
        } else if (sub.equals("claim") || sub.equals("claimall")) {
            net.milkbowl.vault.Vault.getMailboxManager().claimAllMail(player);
        } else {
            sender.sendMessage(getMsg("commands.mailbox.usage", "§cUsage: /vaultx mailbox [claim/claimall/list]"));
        }
    }

    private void handleBank(CommandSender sender, Economy econ, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(getMsg("general.only-players", "§cOnly players can execute this command."));
            return;
        }

        Player player = (Player) sender;
        if (args.length < 2) {
            sender.sendMessage(getMsg("bank.usage",
                    "§cUsage: /vaultx bank [create/delete/bal/deposit/withdraw/list/invite/invites/accept/deny/removemember] ..."));
            return;
        }

        String sub = args[1].toLowerCase();
        net.milkbowl.vault.redis.LocalFailoverManager fm = net.milkbowl.vault.Vault.getFailoverManager();
        if (fm == null) {
            sender.sendMessage(
                    getMsg("general.database-error", "§cThe database manager is not initialized."));
            return;
        }

        if (sub.equals("invites")) {
            runAsync(() -> {
                java.util.Map<String, String> pending = fm.getPendingInvitesForPlayer(player.getUniqueId());
                runSync(() -> {
                    if (pending.isEmpty()) {
                        sender.sendMessage(
                                getMsg("bank.invites-empty", "§cYou have no pending bank invitations."));
                        return;
                    }
                    sender.sendMessage(getMsg("bank.invites-header", "§d§l=== Pending Bank Invitations ==="));
                    for (java.util.Map.Entry<String, String> entry : pending.entrySet()) {
                        sender.sendMessage(getMsg("bank.invites-entry", "  §7- §e%name% §7| Proposed Role: §f%role%")
                                .replace("%name%", entry.getKey())
                                .replace("%role%", entry.getValue()));
                        sender.sendMessage(getMsg("bank.invites-actions",
                                "    §7Accept: §a/vx bank accept %name% §7| Deny: §c/vx bank deny %name%")
                                .replace("%name%", entry.getKey()));
                    }
                    sender.sendMessage(getMsg("bank.invites-footer", "§d§l======================================="));
                });
            });
            return;
        }

        if (sub.equals("create")) {
            if (args.length < 3) {
                sender.sendMessage(getMsg("bank.usage",
                        "§cUsage: /vaultx bank [create/delete/bal/deposit/withdraw/list/invite/invites/accept/deny/removemember] ..."));
                return;
            }
            String bankName = args[2].toLowerCase();
            if (!bankName.matches("^[a-zA-Z0-9_]{3,16}$")) {
                sender.sendMessage(getMsg("bank.invalid-name",
                        "§cBank name must be between 3 and 16 alphanumeric characters."));
                return;
            }

            double creationCost = plugin.getConfig().getDouble("banks.creation-cost", 5000.0);
            int maxAccounts = plugin.getConfig().getInt("banks.max-accounts-per-player", 3);

            if (creationCost > 0 && econ.getBalance(player) < creationCost) {
                sender.sendMessage(getMsg("bank.creation-insufficient-funds",
                        "§cCreating a bank account costs %cost%. Insufficient funds.")
                        .replace("%cost%", econ.format(creationCost)));
                return;
            }

            runAsync(() -> {
                if (maxAccounts > 0) {
                    java.util.List<String> ownedBanks = fm.getBanksForPlayer(player.getUniqueId());
                    long ownedCount = ownedBanks.stream()
                            .filter(b -> player.getUniqueId().equals(fm.getBankAccountOwner(b))).count();
                    if (ownedCount >= maxAccounts) {
                        runSync(() -> sender.sendMessage(
                                getMsg("bank.max-accounts-exceeded",
                                        "§cYou cannot own more than %limit% bank accounts.")
                                        .replace("%limit%", String.valueOf(maxAccounts))));
                        return;
                    }
                }

                UUID existingOwner = fm.getBankAccountOwner(bankName);
                if (existingOwner != null) {
                    runSync(
                            () -> sender.sendMessage(
                                    getMsg("bank.already-exists", "§cA bank account with that name already exists.")));
                    return;
                }

                if (creationCost > 0) {
                    runSync(() -> econ.withdrawPlayer(player, creationCost));
                }

                fm.createBankAccount(bankName, player.getUniqueId());
                fm.saveBankBalance(bankName, 0.0);

                runSync(() -> {
                    econ.createBank(bankName, player);
                    sender.sendMessage(getMsg("bank.create-success",
                            "§a§l✔ §aShared bank account §e%name% §asuccessfully created. You are the Owner (OWNER).")
                            .replace("%name%", bankName));
                });
            });
            return;
        }

        if (sub.equals("list")) {
            runAsync(() -> {
                java.util.List<String> bankNames = fm.getBanksForPlayer(player.getUniqueId());
                runSync(() -> {
                    if (bankNames.isEmpty()) {
                        sender.sendMessage(
                                getMsg("bank.no-banks", "§cYou are not part of any shared bank account."));
                        return;
                    }
                    sender.sendMessage(getMsg("bank.list-header", "§b§l=== Your Shared Bank Accounts ==="));
                    for (String bName : bankNames) {
                        String role = fm.getBankRole(bName, player.getUniqueId());
                        double balance = econ.bankBalance(bName).balance;
                        if (balance == 0 && !econ.bankBalance(bName).transactionSuccess()) {
                            balance = fm.getBankBalance(bName);
                        }
                        sender.sendMessage(
                                getMsg("bank.list-entry", "  §7- §e%name% §7| Role: §f%role% §7| Balance: §a%amount%")
                                        .replace("%name%", bName)
                                        .replace("%role%", role)
                                        .replace("%amount%", econ.format(balance)));
                    }
                    sender.sendMessage(getMsg("bank.list-footer", "§b§l========================================"));
                });
            });
            return;
        }

        // Subcommands requiring bank lookup and member authorization
        if (args.length < 3) {
            sender.sendMessage(getMsg("bank.usage",
                    "§cUsage: /vaultx bank [create/delete/bal/deposit/withdraw/list/invite/invites/accept/deny/removemember] ..."));
            return;
        }
        String bankName = args[2].toLowerCase();

        runAsync(() -> {
            UUID owner = fm.getBankAccountOwner(bankName);
            if (owner == null) {
                runSync(
                        () -> sender.sendMessage(
                                getMsg("bank.not-found", "§cShared bank account '%name%' does not exist.")
                                        .replace("%name%", bankName)));
                return;
            }

            String userRole = fm.getBankRole(bankName, player.getUniqueId());
            if (userRole == null) {
                runSync(
                        () -> sender.sendMessage(
                                getMsg("bank.not-member", "§cYou are not a member of the bank account '%name%'.")
                                        .replace("%name%", bankName)));
                return;
            }
            if (userRole.startsWith("INVITED_") && !sub.equals("accept") && !sub.equals("deny")) {
                runSync(
                        () -> sender.sendMessage(getMsg("bank.invited-must-accept",
                                "§cYou must first accept the invitation to perform this action.")));
                return;
            }
            runSync(() -> {
                if (sub.equals("delete")) {
                    if (!userRole.equals("OWNER")) {
                        sender.sendMessage(getMsg("bank.no-permission-action",
                                "§cOnly the Owner (OWNER) can delete this bank account."));
                        return;
                    }
                    runAsync(() -> {
                        fm.deleteBankAccount(bankName);
                        runSync(() -> {
                            econ.deleteBank(bankName);
                            sender.sendMessage(getMsg("bank.delete-success",
                                    "§a§l✔ §aShared bank account §e%name% §ahas been deleted.")
                                    .replace("%name%", bankName));
                        });
                    });
                } else if (sub.equals("bal") || sub.equals("balance")) {
                    // Fetch balance asynchronously to prevent main-thread block on cache misses
                    sender.sendMessage(getMsg("bank.fetching-balance", "§eRetrieving balance..."));
                    runAsync(() -> {
                        double balance = econ.bankBalance(bankName).balance;
                        if (balance == 0 && !econ.bankBalance(bankName).transactionSuccess()) {
                            balance = fm.getBankBalance(bankName);
                        }
                        final double finalBalance = balance;
                        runSync(() -> {
                            sender.sendMessage(
                                    getMsg("bank.balance", "§a§l[Bank] §aAccount balance for §e%name%§a: §e%amount%")
                                            .replace("%name%", bankName)
                                            .replace("%amount%", econ.format(finalBalance)));
                        });
                    });
                } else if (sub.equals("deposit")) {
                    if (userRole.equals("VIEWER")) {
                        sender.sendMessage(getMsg("bank.no-permission-action",
                                "§cYour role (VIEWER) does not allow you to make deposits."));
                        return;
                    }
                    if (args.length < 4) {
                        sender.sendMessage(getMsg("bank.usage", "§cUsage: /vaultx bank deposit <name> <amount>"));
                        return;
                    }
                    double amount;
                    try {
                        amount = parsePositiveDouble(args[3]);
                    } catch (NumberFormatException e) {
                        sender.sendMessage(getMsg("general.invalid-amount",
                                "§cInvalid amount. It must be a positive number."));
                        return;
                    }

                    double playerBal = econ.getBalance(player);
                    if (playerBal < amount) {
                        sender.sendMessage(
                                getMsg("bank.deposit-no-funds", "§cYou do not have enough funds for this deposit."));
                        return;
                    }

                    EconomyResponse wRes = econ.withdrawPlayer(player, amount);
                    if (wRes.transactionSuccess()) {
                        EconomyResponse dRes = econ.bankDeposit(bankName, amount);
                        if (dRes.transactionSuccess()) {
                            sender.sendMessage(getMsg("bank.deposit-success",
                                    "§a§l✔ §aDeposited §e%amount% §ainto account §e%name%§a.")
                                    .replace("%amount%", econ.format(amount))
                                    .replace("%name%", bankName));
                            // Sync SQLite if not native/handled automatically
                            runAsync(() -> {
                                double currentBal = fm.getBankBalance(bankName);
                                fm.saveBankBalance(bankName, currentBal + amount);
                            });
                        } else {
                            econ.depositPlayer(player, amount);
                            sender.sendMessage(getMsg("bank.deposit-failed", "§c§l❌ §cDeposit failed: %error%")
                                    .replace("%error%", dRes.errorMessage));
                        }
                    } else {
                        sender.sendMessage(getMsg("bank.withdraw-failed", "§c§l❌ §cWithdrawal failed: %error%")
                                .replace("%error%", wRes.errorMessage));
                    }
                } else if (sub.equals("withdraw")) {
                    if (userRole.equals("VIEWER")) {
                        sender.sendMessage(getMsg("bank.no-permission-action",
                                "§cYour role (VIEWER) does not allow you to make withdrawals."));
                        return;
                    }
                    if (args.length < 4) {
                        sender.sendMessage(getMsg("bank.usage", "§cUsage: /vaultx bank withdraw <name> <amount>"));
                        return;
                    }
                    double amount;
                    try {
                        amount = parsePositiveDouble(args[3]);
                    } catch (NumberFormatException e) {
                        sender.sendMessage(getMsg("general.invalid-amount",
                                "§cInvalid amount. It must be a positive number."));
                        return;
                    }

                    runAsync(() -> {
                        double bankBal = econ.bankBalance(bankName).balance;
                        if (bankBal == 0 && !econ.bankBalance(bankName).transactionSuccess()) {
                            bankBal = fm.getBankBalance(bankName);
                        }

                        final double finalBankBal = bankBal;
                        runSync(() -> {
                            if (finalBankBal < amount) {
                                sender.sendMessage(getMsg("bank.withdraw-insufficient",
                                        "§cInsufficient bank account balance (%amount% available).")
                                        .replace("%amount%", econ.format(finalBankBal)));
                                return;
                            }

                            EconomyResponse wRes = econ.bankWithdraw(bankName, amount);
                            if (wRes.transactionSuccess()) {
                                EconomyResponse dRes = econ.depositPlayer(player, amount);
                                if (dRes.transactionSuccess()) {
                                    sender.sendMessage(getMsg("bank.withdraw-success",
                                            "§a§l✔ §aWithdrew §e%amount% §afrom account §e%name%§a.")
                                            .replace("%amount%", econ.format(amount))
                                            .replace("%name%", bankName));
                                    runAsync(() -> {
                                        double currentBal = fm.getBankBalance(bankName);
                                        fm.saveBankBalance(bankName, Math.max(0, currentBal - amount));
                                    });
                                } else {
                                    econ.bankDeposit(bankName, amount);
                                    sender.sendMessage(getMsg("bank.withdraw-failed-deposit",
                                            "§c§l❌ §cFailed to deposit into your account: %error%")
                                            .replace("%error%", dRes.errorMessage));
                                }
                            } else {
                                sender.sendMessage(
                                        getMsg("bank.withdraw-failed", "§c§l❌ §cBank withdrawal failed: %error%")
                                                .replace("%error%", wRes.errorMessage));
                            }
                        });
                    });
                } else if (sub.equals("addmember")) {
                    if (!userRole.equals("OWNER") && !userRole.equals("MANAGER")) {
                        sender.sendMessage(getMsg("bank.no-permission-action",
                                "§cOnly the Owner (OWNER) and Managers (MANAGER) can add members."));
                        return;
                    }
                    if (args.length < 5) {
                        sender.sendMessage(
                                getMsg("bank.usage", "§cUsage: /vaultx bank addmember <name> <player> <role>"));
                        return;
                    }
                    OfflinePlayer target = resolvePlayerFast(args[3]);
                    if (target == null) {
                        sender.sendMessage(getMsg("general.player-not-found", "§cPlayer not found."));
                        return;
                    }
                    String targetRole = args[4].toUpperCase();
                    if (!targetRole.equals("OWNER") && !targetRole.equals("MANAGER") && !targetRole.equals("MEMBER")
                            && !targetRole.equals("VIEWER")) {
                        sender.sendMessage(getMsg("commands.admin.bank-role-invalid",
                                "§cInvalid role: OWNER, MANAGER, MEMBER, VIEWER."));
                        return;
                    }
                    if (targetRole.equals("OWNER") && !userRole.equals("OWNER")) {
                        sender.sendMessage(getMsg("bank.no-permission-action",
                                "§cOnly the current Owner can designate another Owner (OWNER)."));
                        return;
                    }

                    runAsync(() -> {
                        int maxMembers = plugin.getConfig().getInt("banks.max-members", 20);
                        Map<UUID, String> currentMembers = fm.getBankMembers(bankName);
                        // Count only real (non-invited) members
                        long realCount = currentMembers.values().stream()
                                .filter(r -> !r.startsWith("INVITED_")).count();
                        if (realCount >= maxMembers) {
                            runSync(() -> sender.sendMessage(getMsg("bank.member-limit-reached",
                                    "§c§l❌ §cThis bank has reached its maximum member limit (§e%max%§c)."
                                            .replace("%max%", String.valueOf(maxMembers)))));
                            return;
                        }
                        fm.addBankMember(bankName, target.getUniqueId(), targetRole);
                        runSync(() -> {
                            sender.sendMessage(getMsg("bank.member-added",
                                    "§a§l✔ §aPlayer §e%player% §aadded to account §e%name% §awith role §e%role%&a.")
                                    .replace("%player%", getPlayerNameSafe(target, args[3]))
                                    .replace("%name%", bankName)
                                    .replace("%role%", targetRole));
                            if (target.isOnline() && target.getPlayer() != null) {
                                target.getPlayer().sendMessage(getMsg("bank.member-added-notify",
                                        "§a§l✔ §aYou have been added to the shared bank account §e%name% §awith role §e%role%&a.")
                                        .replace("%name%", bankName)
                                        .replace("%role%", targetRole));
                            }
                        });
                    });
                } else if (sub.equals("removemember")) {
                    if (!userRole.equals("OWNER") && !userRole.equals("MANAGER")) {
                        sender.sendMessage(getMsg("bank.no-permission-action",
                                "§cOnly the Owner (OWNER) and Managers (MANAGER) can remove members."));
                        return;
                    }
                    if (args.length < 4) {
                        sender.sendMessage(getMsg("bank.usage", "§cUsage: /vaultx bank removemember <name> <player>"));
                        return;
                    }
                    OfflinePlayer target = resolvePlayerFast(args[3]);
                    if (target == null) {
                        sender.sendMessage(getMsg("general.player-not-found", "§cPlayer not found."));
                        return;
                    }

                    if (player.getUniqueId().equals(target.getUniqueId())) {
                        sender.sendMessage(getMsg("bank.member-cannot-remove-self",
                                "§cYou cannot remove yourself with this command."));
                        return;
                    }

                    runAsync(() -> {
                        String targetRole = fm.getBankRole(bankName, target.getUniqueId());
                        if (targetRole == null) {
                            runSync(
                                    () -> sender.sendMessage(getMsg("bank.member-not-found",
                                            "§cThe player is not a member of this bank account.")));
                            return;
                        }

                        if (targetRole.equals("OWNER")) {
                            runSync(() -> sender
                                    .sendMessage(getMsg("bank.member-cannot-remove-owner",
                                            "§cThe Owner (OWNER) cannot be removed from the account.")));
                            return;
                        }
                        if (targetRole.equals("MANAGER") && !userRole.equals("OWNER")) {
                            runSync(() -> sender.sendMessage(
                                    getMsg("bank.member-cannot-remove-manager",
                                            "§cOnly the Owner (OWNER) can remove a Manager (MANAGER).")));
                            return;
                        }

                        fm.removeBankMember(bankName, target.getUniqueId());
                        runSync(() -> {
                            sender.sendMessage(getMsg("bank.member-removed",
                                    "§a§l✔ §aPlayer §e%player% §aremoved from account §e%name%&a.")
                                    .replace("%player%", getPlayerNameSafe(target, args[3]))
                                    .replace("%name%", bankName));
                            if (target.isOnline() && target.getPlayer() != null) {
                                target.getPlayer()
                                        .sendMessage(getMsg("bank.member-removed-notify",
                                                "§c§lℹ §cYou have been removed from the shared bank account §e%name%&c.")
                                                .replace("%name%", bankName));
                            }
                        });
                    });
                } else if (sub.equals("invite")) {
                    if (!userRole.equals("OWNER") && !userRole.equals("MANAGER")) {
                        sender.sendMessage(getMsg("bank.no-permission-action",
                                "§cOnly the Owner (OWNER) and Managers (MANAGER) can invite members."));
                        return;
                    }
                    if (args.length < 5) {
                        sender.sendMessage(getMsg("bank.usage", "§cUsage: /vaultx bank invite <name> <player> <role>"));
                        return;
                    }
                    OfflinePlayer target = resolvePlayerFast(args[3]);
                    if (target == null) {
                        sender.sendMessage(getMsg("general.player-not-found", "§cPlayer not found."));
                        return;
                    }
                    String targetRole = args[4].toUpperCase();
                    if (!targetRole.equals("MANAGER") && !targetRole.equals("MEMBER") && !targetRole.equals("VIEWER")) {
                        sender.sendMessage(getMsg("commands.admin.bank-role-invalid",
                                "§cInvalid invitation role: MANAGER, MEMBER, VIEWER."));
                        return;
                    }
                    if (targetRole.equals("MANAGER") && !userRole.equals("OWNER")) {
                        sender.sendMessage(getMsg("bank.no-permission-action",
                                "§cOnly the Owner (OWNER) can invite a Manager (MANAGER)."));
                        return;
                    }

                    runAsync(() -> {
                        String currentRole = fm.getBankRole(bankName, target.getUniqueId());
                        if (currentRole != null) {
                            runSync(() -> {
                                if (currentRole.startsWith("INVITED_")) {
                                    sender.sendMessage(getMsg("bank.invite-already-pending",
                                            "§cThis player already has a pending invitation for this bank."));
                                } else {
                                    sender.sendMessage(getMsg("bank.invite-already-member",
                                            "§cThis player is already a member of this bank."));
                                }
                            });
                            return;
                        }

                        String inviteRole = "INVITED_" + targetRole;
                        fm.addBankMember(bankName, target.getUniqueId(), inviteRole);

                        // Broadcast to network
                        net.milkbowl.vault.redis.VaultRedisManager redis = net.milkbowl.vault.redis.VaultRedisManager
                                .getInstance();
                        if (redis != null) {
                            redis.publishBankMemberUpdate(bankName, target.getUniqueId(), inviteRole);
                        }

                        runSync(() -> {
                            sender.sendMessage(getMsg("bank.invite-sent",
                                    "§a§l✔ §aInvitation sent to §e%player% §ato join §e%name% §awith role §e%role%&a.")
                                    .replace("%player%", getPlayerNameSafe(target, args[3]))
                                    .replace("%name%", bankName)
                                    .replace("%role%", targetRole));
                            if (target.isOnline() && target.getPlayer() != null) {
                                target.getPlayer().sendMessage(getMsg("bank.invite-received",
                                        "§a§l[Bank] §aYou have been invited to join bank §e%name% §awith role §e%role%&a. Accept with §e/vx bank accept %name%&a.")
                                        .replace("%name%", bankName)
                                        .replace("%role%", targetRole));
                            }
                        });
                    });
                } else if (sub.equals("accept")) {
                    if (userRole == null || !userRole.startsWith("INVITED_")) {
                        sender.sendMessage(getMsg("bank.invites-empty",
                                "§cYou do not have a pending invitation for this bank."));
                        return;
                    }
                    final String targetRole = userRole.replace("INVITED_", "");
                    runAsync(() -> {
                        int maxMembers = plugin.getConfig().getInt("banks.max-members", 20);
                        Map<UUID, String> currentMembers = fm.getBankMembers(bankName);
                        long realCount = currentMembers.values().stream()
                                .filter(r -> !r.startsWith("INVITED_")).count();
                        if (realCount >= maxMembers) {
                            runSync(() -> sender.sendMessage(getMsg("bank.member-limit-reached",
                                    "§c§l❌ §cThis bank is full and cannot accept more members (max §e%max%§c)."
                                            .replace("%max%", String.valueOf(maxMembers)))));
                            return;
                        }
                        fm.addBankMember(bankName, player.getUniqueId(), targetRole);
                        // Broadcast update
                        net.milkbowl.vault.redis.VaultRedisManager redis = net.milkbowl.vault.redis.VaultRedisManager
                                .getInstance();
                        if (redis != null) {
                            redis.publishBankMemberUpdate(bankName, player.getUniqueId(), targetRole);
                        }
                        runSync(() -> {
                            sender.sendMessage(getMsg("bank.accept-success",
                                    "§a§l✔ §aYou accepted the invitation to join §e%name% §aas §e%role%&a.")
                                    .replace("%name%", bankName)
                                    .replace("%role%", targetRole));
                        });
                    });
                } else if (sub.equals("deny")) {
                    if (userRole == null || !userRole.startsWith("INVITED_")) {
                        sender.sendMessage(getMsg("bank.invites-empty",
                                "§cYou do not have a pending invitation for this bank."));
                        return;
                    }
                    runAsync(() -> {
                        fm.removeBankMember(bankName, player.getUniqueId());
                        // Broadcast update
                        net.milkbowl.vault.redis.VaultRedisManager redis = net.milkbowl.vault.redis.VaultRedisManager
                                .getInstance();
                        if (redis != null) {
                            redis.publishBankMemberUpdate(bankName, player.getUniqueId(), "REMOVE");
                        }
                        runSync(() -> {
                            sender.sendMessage(getMsg("bank.deny-success",
                                    "§a§l✔ §aYou declined the invitation to join §e%name%&a.")
                                    .replace("%name%", bankName));
                        });
                    });
                } else {
                    sender.sendMessage(getMsg("bank.unknown-subcommand", "§cUnknown bank subcommand."));
                }
            });
        });
    }

    private void handleLoan(CommandSender sender, Economy econ, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(getMsg("commands.loan.only-players", "§cOnly players can use the loan system."));
            return;
        }
        Player player = (Player) sender;

        if (args.length < 2) {
            player.sendMessage(getMsg("commands.loan.usage", "§cUsage: /vaultx loan [apply/list/info]"));
            return;
        }

        String sub = args[1].toLowerCase();
        net.milkbowl.vault.redis.LocalFailoverManager fm = Vault.getFailoverManager();
        if (fm == null) {
            player.sendMessage(getMsg("commands.loan.database-error", "§cDatabase error."));
            return;
        }

        if (sub.equals("apply")) {
            if (args.length < 4) {
                player.sendMessage(
                        getMsg("commands.loan.apply-usage", "§cUsage: /vaultx loan apply <bank_name> <amount>"));
                return;
            }
            String bankName = args[2].toLowerCase();
            double amount;
            try {
                amount = parsePositiveDouble(args[3]);
            } catch (NumberFormatException e) {
                player.sendMessage(
                        getMsg("commands.loan.invalid-amount", "§cInvalid amount. It must be a positive number."));
                return;
            }

            net.milkbowl.vault.economy.LoanManager lm = Vault.getLoanManager();
            if (lm != null) {
                lm.applyForLoan(player, bankName, amount);
            } else {
                player.sendMessage(getMsg("commands.loan.manager-uninitialized", "§cThe loan manager is not ready."));
            }
        } else if (sub.equals("list")) {
            runAsync(() -> {
                java.util.List<LocalFailoverManager.LoanRecord> loans = fm.getLoansForPlayer(player.getUniqueId());
                double debt = fm.getPlayerDebt(player.getUniqueId());

                runSync(() -> {
                    if (loans.isEmpty() && debt <= 0.0) {
                        player.sendMessage(getMsg("commands.loan.empty-loans",
                                "§cYou have no active loans or pending salary garnishments."));
                        return;
                    }

                    player.sendMessage(getMsg("commands.loan.list-header", "§b§l=== Your VaultX Loans & Debts ==="));
                    for (LocalFailoverManager.LoanRecord lr : loans) {
                        String statusColor = lr.status.equalsIgnoreCase("ACTIVE") ? "§a" : "§7";
                        player.sendMessage(getMsg("commands.loan.list-entry",
                                "  §7- ID: §e%id% §7| Bank: §f%bank% §7| Remaining: %color%%remaining% §7| Status: %status%")
                                .replace("%id%", lr.id)
                                .replace("%bank%", lr.bankName.toUpperCase())
                                .replace("%color%", statusColor)
                                .replace("%remaining%", econ.format(lr.remaining))
                                .replace("%status%", lr.status));
                    }
                    if (debt > 0.0) {
                        player.sendMessage(
                                getMsg("commands.loan.list-debt", "  §c- Pending salary garnishments: §e%debt%")
                                        .replace("%debt%", econ.format(debt)));
                    }
                    player.sendMessage(
                            getMsg("commands.loan.list-footer", "§b§l======================================"));
                });
            });
        } else if (sub.equals("info")) {
            if (args.length < 3) {
                player.sendMessage(getMsg("commands.loan.info-usage", "§cUsage: /vaultx loan info <id>"));
                return;
            }
            String loanId = args[2];
            runAsync(() -> {
                java.util.List<LocalFailoverManager.LoanRecord> loans = fm.getLoansForPlayer(player.getUniqueId());
                LocalFailoverManager.LoanRecord record = null;
                for (LocalFailoverManager.LoanRecord lr : loans) {
                    if (lr.id.equalsIgnoreCase(loanId)) {
                        record = lr;
                        break;
                    }
                }

                final LocalFailoverManager.LoanRecord finalRec = record;
                runSync(() -> {
                    if (finalRec == null) {
                        player.sendMessage(getMsg("commands.loan.loan-not-found", "§cLoan not found."));
                        return;
                    }

                    java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm");
                    player.sendMessage(getMsg("commands.loan.info-header", "§b§l=== Loan Details for %id% ===")
                            .replace("%id%", finalRec.id));
                    player.sendMessage(getMsg("commands.loan.info-bank", "  §fLending Bank: §e%bank%")
                            .replace("%bank%", finalRec.bankName.toUpperCase()));
                    player.sendMessage(getMsg("commands.loan.info-principal", "  §fPrincipal Amount: §e%principal%")
                            .replace("%principal%", econ.format(finalRec.principal)));
                    player.sendMessage(getMsg("commands.loan.info-remaining", "  §fRemaining to Repay: §e%remaining%")
                            .replace("%remaining%", econ.format(finalRec.remaining)));
                    player.sendMessage(getMsg("commands.loan.info-interest", "  §fInterest Rate: §e%rate%%")
                            .replace("%rate%", String.valueOf(finalRec.interestRate * 100.0)));
                    player.sendMessage(getMsg("commands.loan.info-next", "  §fNext Due Date: §e%next%")
                            .replace("%next%",
                                    finalRec.status.equalsIgnoreCase("ACTIVE")
                                            ? sdf.format(new Date(finalRec.nextBilling))
                                            : "Expired/Paid"));
                    player.sendMessage(getMsg("commands.loan.info-status", "  §fStatus: §e%status%")
                            .replace("%status%", finalRec.status));
                    player.sendMessage(
                            getMsg("commands.loan.info-footer", "§b§l======================================="));
                });
            });
        } else {
            player.sendMessage(
                    getMsg("commands.loan.unknown-subcommand", "§cUnknown subcommand. Choices: apply, list, info"));
        }
    }

    private void handleStocks(CommandSender sender, Economy econ, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(getMsg("commands.stocks.only-players", "§cOnly players can open the stock market."));
            return;
        }
        Player player = (Player) sender;
        if (Vault.getVaultXGUI() != null) {
            Vault.getVaultXGUI().openStocks(player);
        } else {
            player.sendMessage(getMsg("commands.stocks.gui-disabled", "§cThe GUI menu is not enabled."));
        }
    }

    private void handlePayday(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(getMsg("general.only-players", "§cOnly players can execute this command."));
            return;
        }

        Player player = (Player) sender;
        if (!plugin.getConfig().getBoolean("paydays.enabled", true)) {
            player.sendMessage(getMsg("payday.info-disabled", "§cThe salary system is disabled."));
            return;
        }

        net.milkbowl.vault.economy.PaydayManager pm = net.milkbowl.vault.Vault.getPaydayManager();
        if (pm == null) {
            player.sendMessage(getMsg("payday.info-disabled", "§cThe salary system is disabled."));
            return;
        }

        net.milkbowl.vault.economy.PaydayManager.PaydayBreakdown breakdown = pm.getPlayerBreakdown(player);

        long remainingMs = breakdown.nextPaydayTime - System.currentTimeMillis();
        long diffSeconds = Math.max(0, remainingMs / 1000 % 60);
        long diffMinutes = Math.max(0, remainingMs / (60 * 1000) % 60);
        long diffHours = Math.max(0, remainingMs / (60 * 60 * 1000));

        String timeStr = String.format("%02dh %02dm %02ds", diffHours, diffMinutes, diffSeconds);
        if (diffHours == 0) {
            timeStr = String.format("%02dm %02ds", diffMinutes, diffSeconds);
        }

        Economy econ = getEconomy();
        String grossStr = econ != null ? econ.format(breakdown.grossAmount)
                : String.format("%.2f", breakdown.grossAmount);
        String taxStr = econ != null ? econ.format(breakdown.taxAmount) : String.format("%.2f", breakdown.taxAmount);
        String garnishStr = econ != null ? econ.format(breakdown.garnishedAmount)
                : String.format("%.2f", breakdown.garnishedAmount);
        String netStr = econ != null ? econ.format(breakdown.netAmount) : String.format("%.2f", breakdown.netAmount);
        String remainingDebtStr = econ != null ? econ.format(breakdown.debt) : String.format("%.2f", breakdown.debt);

        player.sendMessage(getMsg("payday.info-header", "§b§l=== Your Salary & Payday Details ==="));
        player.sendMessage(getMsg("payday.info-next", "  §fNext payday in: §e%time%").replace("%time%", timeStr));
        player.sendMessage(getMsg("payday.info-group", "  §fSalary Group: §e%group%").replace("%group%",
                breakdown.group.toUpperCase()));
        player.sendMessage(getMsg("payday.info-gross", "  §fGross Salary: §e%gross%").replace("%gross%", grossStr));
        if (breakdown.taxAmount > 0.0) {
            player.sendMessage(getMsg("payday.info-tax", "  §fIncome Tax: §c-%amount% §7(%percent%%)")
                    .replace("%amount%", taxStr)
                    .replace("%percent%", String.format("%.1f", breakdown.taxPercent)));
        }
        if (breakdown.garnishedAmount > 0.0) {
            player.sendMessage(
                    getMsg("payday.info-garnishment", "  §fDebt Garnishment: §c-%amount% §7(Remaining: %remaining%)")
                            .replace("%amount%", garnishStr)
                            .replace("%remaining%", remainingDebtStr));
        }
        player.sendMessage(
                getMsg("payday.info-net", "  §fEstimated Net Payout: §a%amount%").replace("%amount%", netStr));
        player.sendMessage(getMsg("payday.info-footer", "§b§l======================================"));
    }

    private void handleDynamicPricing(CommandSender sender, Economy econ, String[] args) {
        if (!sender.hasPermission("vault.admin")) {
            sender.sendMessage(
                    getMsg("commands.admin.no-permission", "§cYou do not have permission to execute admin commands."));
            return;
        }

        net.milkbowl.vault.economy.DynamicPricingManager dpManager = net.milkbowl.vault.Vault
                .getDynamicPricingManager();
        if (dpManager == null) {
            sender.sendMessage(
                    getMsg("commands.dynamicpricing.not-active", "§c[VaultX] Dynamic Pricing system is not active."));
            return;
        }

        if (args.length <= 1 || args[1].equalsIgnoreCase("info") || args[1].equalsIgnoreCase("status")) {
            sender.sendMessage(
                    getMsg("commands.dynamicpricing.header", "§6━━━━━ §e⚡ VaultX Dynamic Pricing Engine §6━━━━━"));
            sender.sendMessage("§7Status: " + (dpManager.isEnabled() ? "§aEnabled" : "§cDisabled"));
            sender.sendMessage("§7Elasticity Factor: §e" + dpManager.getElasticity());
            sender.sendMessage("§7Multiplier Bounds: §e" + dpManager.getMinMultiplier() + "x §7- §e"
                    + dpManager.getMaxMultiplier() + "x");
            sender.sendMessage("§7Invert Sell Prices: §e" + dpManager.isInvertSellPrices());

            List<String> currencies = getCurrencies(econ);
            for (String currency : currencies) {
                String cKey = currency.toLowerCase();
                double currentSupply = dpManager.getCurrentMoneySupply(cKey);
                double baselineSupply = dpManager.getBaselineMoneySupply(cKey);
                double multiplier = dpManager.getInflationMultiplier(cKey);
                double pct = (multiplier - 1.0) * 100.0;
                String pctStr = String.format(java.util.Locale.US, "%+.1f%%", pct);

                sender.sendMessage("§8 » §f" + currency.toUpperCase() + ":");
                sender.sendMessage("§7   Current Money Supply: §e" + (econ != null ? econ.format(currentSupply)
                        : String.format(java.util.Locale.US, "%.2f", currentSupply)));
                sender.sendMessage("§7   Baseline Money Supply: §e" + (econ != null ? econ.format(baselineSupply)
                        : String.format(java.util.Locale.US, "%.2f", baselineSupply)));
                sender.sendMessage("§7   Inflation Index: §b" + String.format(java.util.Locale.US, "%.2fx", multiplier)
                        + " §8(" + (pct >= 0 ? "§c" : "§a") + pctStr + "§8)");
            }
            sender.sendMessage("§6━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            return;
        }

        String sub = args[1].toLowerCase();
        if (sub.equals("recalculate") || sub.equals("recalc") || sub.equals("refresh")) {
            dpManager.recalculateAllAsync();
            sender.sendMessage(getMsg("commands.dynamicpricing.recalc-triggered",
                    "§a[VaultX] Dynamic pricing money supply recalculation triggered asynchronously."));
            return;
        }

        if (sub.equals("setbase") || sub.equals("setbaseline")) {
            if (args.length < 3) {
                sender.sendMessage(getMsg("commands.dynamicpricing.setbase-usage",
                        "§cUsage: /vaultx dp setbase <amount> [currency]"));
                return;
            }
            try {
                double amount = parsePositiveDouble(args[2]);
                String currency = args.length >= 4 ? args[3].toLowerCase() : "default";
                dpManager.setBaselineMoneySupply(currency, amount);
                String formatted = econ != null ? econ.format(amount) : String.valueOf(amount);
                sender.sendMessage(getMsg("commands.dynamicpricing.setbase-success",
                        "§a[VaultX] Baseline money supply for §e%currency% §aset to §e%amount%§a.")
                        .replace("%currency%", currency)
                        .replace("%amount%", formatted));
            } catch (NumberFormatException e) {
                sender.sendMessage(getMsg("commands.dynamicpricing.invalid-amount", "§cInvalid amount specified."));
            }
            return;
        }

        if (sub.equals("calc") || sub.equals("calculate") || sub.equals("test")) {
            if (args.length < 3) {
                sender.sendMessage(getMsg("commands.dynamicpricing.calc-usage",
                        "§cUsage: /vaultx dp calc <basePrice> [currency]"));
                return;
            }
            try {
                double basePrice = parsePositiveDouble(args[2]);
                String currency = args.length >= 4 ? args[3].toLowerCase() : "default";
                double adjustedBuy = dpManager.getAdjustedBuyPrice(currency, basePrice);
                double adjustedSell = dpManager.getAdjustedSellPrice(currency, basePrice);
                double mult = dpManager.getInflationMultiplier(currency);

                sender.sendMessage("§6[VaultX Dynamic Pricing Simulation] §f(" + currency.toUpperCase() + " @ "
                        + String.format(java.util.Locale.US, "%.2fx", mult) + "):");
                sender.sendMessage("§7  Base Price: §e" + (econ != null ? econ.format(basePrice) : basePrice));
                sender.sendMessage(
                        "§7  Adjusted Buy Price: §a" + (econ != null ? econ.format(adjustedBuy) : adjustedBuy));
                sender.sendMessage(
                        "§7  Adjusted Sell Price: §c" + (econ != null ? econ.format(adjustedSell) : adjustedSell));
            } catch (NumberFormatException e) {
                sender.sendMessage(getMsg("commands.dynamicpricing.invalid-amount", "§cInvalid base price specified."));
            }
            return;
        }

        sender.sendMessage(getMsg("commands.dynamicpricing.unknown-subcommand",
                "§cUnknown subcommand. Use: /vx dp [info|recalculate|setbase|calc]"));
    }

    private void handleBlackMarket(CommandSender sender, String[] args) {
        if (!plugin.getConfig().getBoolean("blackmarket.enabled", true)) {
            sender.sendMessage(
                    getMsg("blackmarket.disabled", "§cThe Black Market is currently disabled on this server."));
            return;
        }
        if (!(sender instanceof Player)) {
            sender.sendMessage(getMsg("general.only-players", "§cOnly players can execute this command."));
            return;
        }
        Player player = (Player) sender;
        if (args.length >= 2 && args[1].equalsIgnoreCase("launder")) {
            if (args.length < 3) {
                player.sendMessage(getMsg("blackmarket.insufficient-dirty", "§cUsage: /blackmarket launder <amount>"));
                return;
            }
            double amount;
            try {
                amount = parsePositiveDouble(args[2]);
            } catch (NumberFormatException e) {
                player.sendMessage(getMsg("general.invalid-amount", "§cInvalid amount."));
                return;
            }
            net.milkbowl.vault.economy.BlackMarketManager bm = Vault.getBlackMarketManager();
            if (bm != null) {
                net.milkbowl.vault.economy.BlackMarketManager.LaunderingResult res = bm.launder(player, amount,
                        getEconomy());
                if (!res.isSuccess()) {
                    player.sendMessage(getMsg("blackmarket.insufficient-dirty",
                            "§cYou do not have enough dirty money to launder that amount!"));
                } else if (res.isSeized()) {
                    player.sendMessage(getMsg("blackmarket.launder-seized",
                            "§c&l🚨 [POLICE RAID] §cPolice intercepted your transaction! §c%seized% §cof dirty money was confiscated!")
                            .replace("%seized%", getEconomy() != null ? getEconomy().format(res.getDirtyLaundered())
                                    : String.valueOf(res.getDirtyLaundered())));
                } else {
                    player.sendMessage(getMsg("blackmarket.launder-success",
                            "§a&l✔ [Black Market] §aSuccessfully laundered §c%dirty% §aof dirty money! Deposited §a%clean% §aclean cash into your account (Fee: §e%fee%§a).")
                            .replace("%dirty%",
                                    getEconomy() != null ? getEconomy().format(res.getDirtyLaundered())
                                            : String.valueOf(res.getDirtyLaundered()))
                            .replace("%clean%",
                                    getEconomy() != null ? getEconomy().format(res.getCleanReceived())
                                            : String.valueOf(res.getCleanReceived()))
                            .replace("%fee%", getEconomy() != null ? getEconomy().format(res.getFeePaid())
                                    : String.valueOf(res.getFeePaid())));
                }
            }
            return;
        }

        if (Vault.getVaultXGUI() != null) {
            Vault.getVaultXGUI().openBlackMarket(player);
        }
    }

    private void handleDirty(CommandSender sender, String[] args) {
        net.milkbowl.vault.economy.BlackMarketManager bm = Vault.getBlackMarketManager();
        if (bm == null)
            return;

        if (args.length < 2 || args[1].equalsIgnoreCase("balance")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(getMsg("general.only-players", "§cOnly players can check dirty balance."));
                return;
            }
            Player player = (Player) sender;
            double bal = bm.getDirtyBalance(player);
            player.sendMessage(getMsg("blackmarket.dirty-balance",
                    "§c§l[Dirty Money] §fYou currently hold §c%amount% §fof dirty money.")
                    .replace("%amount%", getEconomy() != null ? getEconomy().format(bal) : String.valueOf(bal)));
            return;
        }

        if (args[1].equalsIgnoreCase("give") || args[1].equalsIgnoreCase("take")) {
            if (!sender.hasPermission("vault.admin")) {
                sender.sendMessage(getMsg("general.no-permission", "§cNo permission."));
                return;
            }
            if (args.length < 4) {
                sender.sendMessage("§cUsage: /vx dirty give/take <player> <amount>");
                return;
            }
            OfflinePlayer target = resolvePlayerFast(args[2]);
            if (target == null || !target.isOnline()) {
                sender.sendMessage(getMsg("general.player-not-found", "§cPlayer not found or offline."));
                return;
            }
            double amount;
            try {
                amount = parsePositiveDouble(args[3]);
            } catch (NumberFormatException e) {
                sender.sendMessage(getMsg("general.invalid-amount", "§cInvalid amount."));
                return;
            }

            if (args[1].equalsIgnoreCase("give")) {
                bm.depositDirty(target.getPlayer(), amount);
                sender.sendMessage(getMsg("blackmarket.give-success",
                        "§a&l[Black Market] §fGave §c%amount% §fof dirty money to §e%player%§f.")
                        .replace("%amount%",
                                getEconomy() != null ? getEconomy().format(amount) : String.valueOf(amount))
                        .replace("%player%", target.getName()));
            } else {
                bm.withdrawDirty(target.getPlayer(), amount);
                sender.sendMessage(getMsg("blackmarket.take-success",
                        "§a&l[Black Market] §fRemoved §c%amount% §fof dirty money from §e%player%§f.")
                        .replace("%amount%",
                                getEconomy() != null ? getEconomy().format(amount) : String.valueOf(amount))
                        .replace("%player%", target.getName()));
            }
        }
    }

    private void handleDiscord(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(getMsg("general.only-players", "§cOnly players can execute this command."));
            return;
        }
        Player player = (Player) sender;
        net.milkbowl.vault.discord.DiscordManager dm = Vault.getDiscordManager();
        if (dm == null) {
            sender.sendMessage("§cDiscord manager is disabled.");
            return;
        }

        if (args.length >= 2 && args[1].equalsIgnoreCase("status")) {
            if (dm.isLinked(player.getUniqueId())) {
                player.sendMessage(
                        getMsg("discord.already-linked", "§a&l[Discord] §fYour account is linked to tag: §e%tag%")
                                .replace("%tag%", dm.getLinkedDiscordTag(player.getUniqueId())));
            } else {
                player.sendMessage(getMsg("discord.not-linked",
                        "§cYour account is not linked to Discord yet. Use /vx discord link."));
            }
            return;
        }

        String code = dm.generateLinkCode(player.getUniqueId());
        if (code == null) {
            player.sendMessage(getMsg("discord.link-code-ratelimit",
                    "§c[Discord] §fVeuillez attendre 1 minute avant de générer un nouveau code."));
            return;
        }
        long expire = plugin.getConfig().getLong("discord.account-linking.link-code-expire-minutes", 10);
        player.sendMessage(getMsg("discord.link-code-generated",
                "§a§l[Discord] §fVotre code de vérification est : §e§l%code% §7(expire dans %expire% minutes).")
                .replace("%code%", code)
                .replace("%expire%", String.valueOf(expire)));
    }

    private void handleAnalytics(CommandSender sender, String[] args) {
        if (!sender.hasPermission("vault.admin.analytics")) {
            sender.sendMessage("§cYou do not have permission!");
            return;
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
    }

    private void handleBoosterCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("vault.admin.booster")) {
            sender.sendMessage("§cYou do not have permission!");
            return;
        }
        if (args.length < 4) {
            sender.sendMessage("§cUsage: /vaultx booster <currency> <multiplier> <duration_ms|1h|30m>");
            return;
        }
        String currency = args[1];
        double mult;
        try {
            mult = parsePositiveDouble(args[2]);
        } catch (Exception e) {
            sender.sendMessage("§cInvalid multiplier! Must be a positive number.");
            return;
        }
        long durationMs = 3600000L;
        String durStr = args[3].toLowerCase();
        try {
            if (durStr.endsWith("m"))
                durationMs = Long.parseLong(durStr.replace("m", "")) * 60000L;
            else if (durStr.endsWith("h"))
                durationMs = Long.parseLong(durStr.replace("h", "")) * 3600000L;
            else if (durStr.endsWith("s"))
                durationMs = Long.parseLong(durStr.replace("s", "")) * 1000L;
            else
                durationMs = Long.parseLong(durStr);
            if (durationMs <= 0)
                throw new NumberFormatException("Non-positive duration");
        } catch (Exception e) {
            sender.sendMessage("§cInvalid duration format! Use e.g. 1h, 30m, 60s, or milliseconds.");
            return;
        }

        Vault.getBoosterAPI().registerGlobalBooster(currency, mult, durationMs);
        Bukkit.broadcastMessage(
                "§a§l🚀 EVENT BOOSTER §f" + mult + "x multiplier activated for §e" + currency.toUpperCase() + "§f!");
    }

    private void handleAuditLogCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("vault.admin.audit")) {
            sender.sendMessage("§cYou do not have permission!");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /vaultx audit <player> [limit]");
            return;
        }
        OfflinePlayer target = resolvePlayerFast(args[1]);
        if (target == null || (target.getName() == null && !target.hasPlayedBefore())) {
            sender.sendMessage("§cPlayer '" + args[1] + "' not found.");
            return;
        }
        int limit = 15;
        if (args.length > 2) {
            try {
                limit = Integer.parseInt(args[2]);
                if (limit <= 0)
                    limit = 15;
            } catch (NumberFormatException e) {
                sender.sendMessage("§cInvalid limit number!");
                return;
            }
        }
        Vault.getAuditAPI().getPlayerTransactionHistoryAsync(target, limit).thenAccept(logs -> {
            net.milkbowl.vault.util.FoliaScheduler.runSync(plugin, () -> {
                sender.sendMessage("§8§m--------------------------------------------------");
                sender.sendMessage("§6§l🔍 VaultX Audit Log §7(" + target.getName() + ")");
                if (logs.isEmpty()) {
                    sender.sendMessage("§7No transaction logs found.");
                } else {
                    for (var log : logs) {
                        sender.sendMessage("§8["
                                + new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date(log.timestamp()))
                                + "] §7" + log.type() + " §a" + log.amount() + " " + log.currency() + " §7("
                                + log.callerPlugin() + ")");
                    }
                }
                sender.sendMessage("§8§m--------------------------------------------------");
            });
        });
    }

    private void handlePayOffline(CommandSender sender, String[] args) {
        if (!sender.hasPermission("vault.admin.payoffline")) {
            sender.sendMessage("§cYou do not have permission!");
            return;
        }
        if (args.length < 4) {
            sender.sendMessage("§cUsage: /vaultx payoffline <player> <currency> <amount> [reason]");
            return;
        }
        OfflinePlayer target = resolvePlayerFast(args[1]);
        if (target == null || (target.getName() == null && !target.hasPlayedBefore())) {
            sender.sendMessage("§cPlayer not found: " + args[1]);
            return;
        }
        String currency = args[2];
        double amount;
        try {
            amount = parsePositiveDouble(args[3]);
        } catch (Exception e) {
            sender.sendMessage("§cInvalid amount! Must be a positive number.");
            return;
        }
        String reason = args.length > 4 ? String.join(" ", java.util.Arrays.copyOfRange(args, 4, args.length))
                : "Admin Payout";

        Vault.getMailboxAPI().sendOfflinePaymentAsync(target, currency, amount, reason).thenAccept(success -> {
            net.milkbowl.vault.util.FoliaScheduler.runSync(plugin, () -> {
                if (success) {
                    sender.sendMessage("§aSuccessfully sent offline payment of " + amount + " " + currency + " to "
                            + target.getName() + "!");
                } else {
                    sender.sendMessage("§cFailed to send offline payment.");
                }
            });
        });
    }

    private void handleCrypto(CommandSender sender, String[] args) {
        if (Vault.getCryptoManager() == null) {
            sender.sendMessage("§cCrypto feature is not initialized.");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /vaultx crypto <wallet|mine|transfer> ...");
            return;
        }
        String sub = args[1].toLowerCase();
        if (sub.equals("wallet")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cOnly players can view wallets.");
                return;
            }
            String crypto = args.length > 2 ? args[2] : "BTC";
            Vault.getCryptoManager().getWalletAsync(player, crypto, java.util.concurrent.ForkJoinPool.commonPool())
                    .thenAccept(wallet -> {
                        runSync(() -> sender.sendMessage("§aWallet Address: §f" + wallet.walletAddress()
                                + " §7| Balance: §e" + wallet.balance() + " " + wallet.cryptoName()));
                    });
        } else if (sub.equals("mine")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cOnly players can mine crypto.");
                return;
            }
            String crypto = args.length > 2 ? args[2] : "BTC";
            double amount = args.length > 3 ? Double.parseDouble(args[3]) : 1.0;
            Vault.getCryptoManager()
                    .mineTokensAsync(player, crypto, amount, java.util.concurrent.ForkJoinPool.commonPool())
                    .thenAccept(res -> {
                        runSync(() -> sender.sendMessage(
                                res.transactionSuccess() ? "§a" + res.errorMessage : "§c" + res.errorMessage));
                    });
        } else if (sub.equals("transfer")) {
            if (args.length < 5) {
                sender.sendMessage("§cUsage: /vaultx crypto transfer <fromAddress> <toAddress> <crypto> <amount>");
                return;
            }
            String from = args[2];
            String to = args[3];
            String crypto = args[4];
            double amount = Double.parseDouble(args[5]);
            Vault.getCryptoManager()
                    .transferCryptoAsync(from, to, crypto, amount, java.util.concurrent.ForkJoinPool.commonPool())
                    .thenAccept(success -> {
                        runSync(() -> sender.sendMessage(success ? "§aCrypto transfer successful!"
                                : "§cCrypto transfer failed. Check address or balance."));
                    });
        }
    }

    private void handleCredit(CommandSender sender, String[] args) {
        if (Vault.getCreditManager() == null) {
            sender.sendMessage("§cCredit feature is not initialized.");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /vaultx credit <score|limit> ...");
            return;
        }
        String sub = args[1].toLowerCase();
        if (sub.equals("score")) {
            OfflinePlayer target = (args.length > 2) ? resolvePlayerFast(args[2])
                    : (sender instanceof Player ? (Player) sender : null);
            if (target == null) {
                sender.sendMessage("§cPlayer not found.");
                return;
            }
            Vault.getCreditManager().updateCreditScoreAsync(target, p -> {
                Economy econ = getEconomy();
                return econ != null ? econ.getBalance(p) : 0.0;
            }, java.util.concurrent.ForkJoinPool.commonPool()).thenAccept(score -> {
                runSync(() -> sender.sendMessage("§aCredit Score for " + target.getName() + ": §e" + score));
            });
        } else if (sub.equals("limit")) {
            if (!sender.hasPermission("vault.admin")) {
                sender.sendMessage("§cYou do not have permission.");
                return;
            }
            if (args.length < 5) {
                sender.sendMessage("§cUsage: /vaultx credit limit <player> <currency> <limit>");
                return;
            }
            OfflinePlayer target = resolvePlayerFast(args[2]);
            String curr = args[3];
            double limit = Double.parseDouble(args[4]);
            Vault.getCreditManager()
                    .setOverdraftLimitAsync(target, curr, limit, java.util.concurrent.ForkJoinPool.commonPool())
                    .thenAccept(res -> {
                        runSync(() -> sender.sendMessage(
                                res.transactionSuccess() ? "§a" + res.errorMessage : "§c" + res.errorMessage));
                    });
        }
    }

    private void handleStaking(CommandSender sender, Economy econ, String[] args) {
        if (Vault.getStakingManager() == null) {
            sender.sendMessage("§cStaking feature is not initialized.");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /vaultx staking <create|claim|list> ...");
            return;
        }
        String sub = args[1].toLowerCase();
        if (sub.equals("create")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cOnly players can create stakes.");
                return;
            }
            if (args.length < 5) {
                sender.sendMessage("§cUsage: /vaultx staking create <currency> <amount> <durationDays>");
                return;
            }
            String curr = args[2];
            double amount = Double.parseDouble(args[3]);
            int days = Integer.parseInt(args[4]);
            Vault.getStakingManager().createStakeAsync(player, curr, amount, days,
                    (p, c) -> (econ instanceof MultiCurrencyEconomy m) ? m.getCurrencyBalance(p, c)
                            : (c.equalsIgnoreCase("default") ? econ.getBalance(p) : 0.0),
                    (p, c, a) -> (econ instanceof MultiCurrencyEconomy m) ? m.withdrawCurrencyPlayer(p, c, a)
                            : (c.equalsIgnoreCase("default") ? econ.withdrawPlayer(p, a)
                                    : new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Not supported")),
                    java.util.concurrent.ForkJoinPool.commonPool()).thenAccept(res -> {
                        runSync(() -> sender.sendMessage(
                                res.transactionSuccess() ? "§a" + res.errorMessage : "§c" + res.errorMessage));
                    });
        } else if (sub.equals("claim")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cOnly players can claim stakes.");
                return;
            }
            if (args.length < 3) {
                sender.sendMessage("§cUsage: /vaultx staking claim <depositId>");
                return;
            }
            String depositId = args[2];
            Vault.getStakingManager().claimStakeAsync(player, depositId,
                    (p, c, a) -> (econ instanceof MultiCurrencyEconomy m) ? m.depositCurrencyPlayer(p, c, a)
                            : (c.equalsIgnoreCase("default") ? econ.depositPlayer(p, a)
                                    : new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Not supported")),
                    java.util.concurrent.ForkJoinPool.commonPool()).thenAccept(res -> {
                        runSync(() -> sender
                                .sendMessage(res.transactionSuccess() ? "§aStake claimed!" : "§c" + res.errorMessage));
                    });
        } else if (sub.equals("list")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cOnly players can list stakes.");
                return;
            }
            Vault.getStakingManager().getActiveStakesAsync(player, java.util.concurrent.ForkJoinPool.commonPool())
                    .thenAccept(stakes -> {
                        runSync(() -> {
                            sender.sendMessage("§e=== Your Active Stakes ===");
                            for (var s : stakes) {
                                sender.sendMessage("§7" + s.depositId() + " §8| §a" + s.principal() + " " + s.currency()
                                        + " §8| Rate: " + s.interestRate());
                            }
                        });
                    });
        }
    }

    private void handleAuction(CommandSender sender, Economy econ, String[] args) {
        if (Vault.getAuctionManager() == null) {
            sender.sendMessage("§cAuction feature is not initialized.");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /vaultx auction <create|bid|cancel|list> ...");
            return;
        }
        String sub = args[1].toLowerCase();
        if (sub.equals("create")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cOnly players can create auctions.");
                return;
            }
            if (args.length < 4) {
                sender.sendMessage("§cUsage: /vaultx auction create <startingPrice> <durationMinutes> [currency]");
                return;
            }
            double price = Double.parseDouble(args[2]);
            long minutes = Long.parseLong(args[3]);
            String curr = args.length > 4 ? args[4] : "default";
            var item = player.getInventory().getItemInHand();
            if (item == null || item.getType() == org.bukkit.Material.AIR) {
                sender.sendMessage("§cHold an item in your main hand to list in auction.");
                return;
            }
            Vault.getAuctionManager().createAuctionAsync(player, item, curr, price, minutes,
                    java.util.concurrent.ForkJoinPool.commonPool()).thenAccept(listing -> {
                        runSync(() -> {
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
                return;
            }
            if (args.length < 4) {
                sender.sendMessage("§cUsage: /vaultx auction bid <auctionId> <amount>");
                return;
            }
            String auctionId = args[2];
            double amount = Double.parseDouble(args[3]);
            Vault.getAuctionManager().placeBidAsync(player, auctionId, amount,
                    (p, c) -> (econ instanceof MultiCurrencyEconomy m) ? m.getCurrencyBalance(p, c)
                            : (c.equalsIgnoreCase("default") ? econ.getBalance(p) : 0.0),
                    (p, c, a) -> (econ instanceof MultiCurrencyEconomy m) ? m.withdrawCurrencyPlayer(p, c, a)
                            : (c.equalsIgnoreCase("default") ? econ.withdrawPlayer(p, a)
                                    : new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Not supported")),
                    (p, c, a) -> (econ instanceof MultiCurrencyEconomy m) ? m.depositCurrencyPlayer(p, c, a)
                            : (c.equalsIgnoreCase("default") ? econ.depositPlayer(p, a)
                                    : new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Not supported")),
                    java.util.concurrent.ForkJoinPool.commonPool()).thenAccept(res -> {
                        runSync(() -> sender.sendMessage(
                                res.transactionSuccess() ? "§a" + res.errorMessage : "§c" + res.errorMessage));
                    });
        } else if (sub.equals("cancel")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cOnly players can cancel auctions.");
                return;
            }
            if (args.length < 3) {
                sender.sendMessage("§cUsage: /vaultx auction cancel <auctionId>");
                return;
            }
            String auctionId = args[2];
            Vault.getAuctionManager()
                    .cancelAuctionAsync(player, auctionId, java.util.concurrent.ForkJoinPool.commonPool())
                    .thenAccept(res -> {
                        runSync(() -> sender.sendMessage(
                                res.transactionSuccess() ? "§aAuction cancelled!" : "§c" + res.errorMessage));
                    });
        } else if (sub.equals("list")) {
            Vault.getAuctionManager().getActiveAuctionsAsync(java.util.concurrent.ForkJoinPool.commonPool())
                    .thenAccept(auctions -> {
                        runSync(() -> {
                            sender.sendMessage("§e=== Active Auctions ===");
                            for (var a : auctions) {
                                sender.sendMessage("§7[" + a.auctionId() + "] §f" + a.item().getType() + " §8| Bid: §a"
                                        + a.currentBid() + " " + a.currency());
                            }
                        });
                    });
        }
    }
}
