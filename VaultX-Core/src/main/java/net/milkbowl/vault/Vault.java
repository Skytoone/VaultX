package net.milkbowl.vault;

import java.util.Collection;
import java.util.UUID;
import java.io.File;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.ChatColor;

import net.milkbowl.vault.chat.Chat;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.MultiCurrencyEconomy;
import net.milkbowl.vault.economy.OptimizedEconomy;
import net.milkbowl.vault.permission.Permission;
import net.milkbowl.vault.permission.Permission_SuperPerms;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServiceRegisterEvent;

import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.plugin.java.JavaPlugin;

public class Vault extends JavaPlugin {

    private static YamlConfiguration messages;
    private static YamlConfiguration gui;
    private Permission perms;
    private ServicesManager sm;
    private static net.milkbowl.vault.security.TransactionFirewall firewall;
    private static net.milkbowl.vault.redis.LocalFailoverManager failoverManager;
    private static net.milkbowl.vault.redis.EscrowManager escrowManager;
    private static net.milkbowl.vault.economy.ExchangeRateManager exchangeRateManager;
    private static net.milkbowl.vault.economy.CentralBankManager centralBankManager;
    private static net.milkbowl.vault.economy.MailboxManager mailboxManager;
    private static net.milkbowl.vault.util.VaultXGUI vaultXGUI;
    private static net.milkbowl.vault.economy.StockExchangeManager stockExchangeManager;
    private static net.milkbowl.vault.economy.LoanManager loanManager;
    private static net.milkbowl.vault.economy.PaydayManager paydayManager;
    private static net.milkbowl.vault.economy.SubscriptionManager subscriptionManager;
    private static net.milkbowl.vault.economy.DynamicPricingManager dynamicPricingManager;
    private static net.milkbowl.vault.discord.DiscordManager discordManager;
    private static net.milkbowl.vault.integration.TownyLandsFactionsHook townyLandsFactionsHook;
    private static net.milkbowl.vault.economy.BlackMarketManager blackMarketManager;
    private static net.milkbowl.vault.economy.BankBackupManager bankBackupManager;

    public static net.milkbowl.vault.economy.BankBackupManager getBankBackupManager() {
        return bankBackupManager;
    }

    public static net.milkbowl.vault.discord.DiscordManager getDiscordManager() {
        return discordManager;
    }

    public static net.milkbowl.vault.integration.TownyLandsFactionsHook getTownyLandsFactionsHook() {
        return townyLandsFactionsHook;
    }

    public static net.milkbowl.vault.economy.BlackMarketManager getBlackMarketManager() {
        return blackMarketManager;
    }

    public static net.milkbowl.vault.economy.DynamicPricingManager getDynamicPricingManager() {
        return dynamicPricingManager;
    }

    public static net.milkbowl.vault.economy.SubscriptionManager getSubscriptionManager() {
        return subscriptionManager;
    }

    public static net.milkbowl.vault.economy.PaydayManager getPaydayManager() {
        return paydayManager;
    }

    public static net.milkbowl.vault.security.TransactionFirewall getFirewall() {
        return firewall;
    }

    public static net.milkbowl.vault.redis.LocalFailoverManager getFailoverManager() {
        return failoverManager;
    }

    public static net.milkbowl.vault.redis.EscrowManager getEscrowManager() {
        return escrowManager;
    }

    public static net.milkbowl.vault.economy.CurrencyExchangeAPI getCurrencyExchangeAPI() {
        return exchangeRateManager;
    }

    public static net.milkbowl.vault.economy.ExchangeRateManager getExchangeRateManager() {
        return exchangeRateManager;
    }

    public static net.milkbowl.vault.economy.CentralBankManager getCentralBankManager() {
        return centralBankManager;
    }

    public static net.milkbowl.vault.economy.MailboxManager getMailboxManager() {
        return mailboxManager;
    }

    public static net.milkbowl.vault.util.VaultXGUI getVaultXGUI() {
        return vaultXGUI;
    }

    public static net.milkbowl.vault.economy.StockExchangeManager getStockExchangeManager() {
        return stockExchangeManager;
    }

    public static net.milkbowl.vault.economy.LoanManager getLoanManager() {
        return loanManager;
    }

    public static java.util.List<OptimizedEconomy> getWrappedEconomies() {
        return wrappedEconomies;
    }

    public static net.milkbowl.vault.economy.VaultLeaderboardAPI getLeaderboardAPI() {
        return !wrappedEconomies.isEmpty() ? wrappedEconomies.get(0) : null;
    }

    public static net.milkbowl.vault.economy.VaultBatchTransactionAPI getBatchTransactionAPI() {
        return !wrappedEconomies.isEmpty() ? wrappedEconomies.get(0) : null;
    }

    public static net.milkbowl.vault.economy.VaultFormatAPI getFormatAPI() {
        return !wrappedEconomies.isEmpty() ? wrappedEconomies.get(0) : null;
    }

    public static net.milkbowl.vault.economy.VaultMailboxAPI getMailboxAPI() {
        return !wrappedEconomies.isEmpty() ? wrappedEconomies.get(0) : null;
    }

    public static net.milkbowl.vault.economy.VaultEscrowAPI getEscrowAPI() {
        return escrowManager;
    }

    public static net.milkbowl.vault.economy.VaultFirewallAPI getFirewallAPI() {
        return firewall;
    }

    public static net.milkbowl.vault.economy.VaultBlackMarketAPI getBlackMarketAPI() {
        return blackMarketManager;
    }

    public static net.milkbowl.vault.economy.VaultStockAPI getStockAPI() {
        return stockExchangeManager;
    }

    public static net.milkbowl.vault.economy.VaultBoosterAPI getBoosterAPI() {
        return !wrappedEconomies.isEmpty() ? wrappedEconomies.get(0) : null;
    }

    public static net.milkbowl.vault.economy.VaultAnalyticsAPI getAnalyticsAPI() {
        return !wrappedEconomies.isEmpty() ? wrappedEconomies.get(0) : null;
    }

    public static net.milkbowl.vault.economy.VaultCurrencyRegistry getCurrencyRegistry() {
        return !wrappedEconomies.isEmpty() ? wrappedEconomies.get(0) : null;
    }

    public static net.milkbowl.vault.economy.VaultAuditAPI getAuditAPI() {
        return !wrappedEconomies.isEmpty() ? wrappedEconomies.get(0) : null;
    }

    @Override
    public void onDisable() {
        // Shutdown registered OptimizedEconomy thread pools and clear caches
        for (OptimizedEconomy econ : wrappedEconomies) {
            try {
                econ.shutdown();
            } catch (Exception e) {
                getLogger().warning("[Vault] Exception shutting down OptimizedEconomy: " + e.getMessage());
            }
        }
        wrappedEconomies.clear();

        // Close GUI listener & maps
        if (vaultXGUI != null) {
            try {
                vaultXGUI.close();
            } catch (Exception e) {
                getLogger().warning("[Vault] Exception closing VaultXGUI: " + e.getMessage());
            }
        }

        // Clean up static UUIDCache
        net.milkbowl.vault.util.UUIDCache.cleanup();

        // Unregister all plugin event listeners
        org.bukkit.event.HandlerList.unregisterAll(this);

        // Remove all Service Registrations
        getServer().getServicesManager().unregisterAll(this);
        net.milkbowl.vault.util.FoliaScheduler.cancelTasks(this);

        // Close Redis Manager
        if (net.milkbowl.vault.redis.VaultRedisManager.getInstance() != null) {
            net.milkbowl.vault.redis.VaultRedisManager.getInstance().close();
        }

        // Close Escrow Manager
        if (escrowManager != null) {
            escrowManager.close();
        }

        // Close Exchange Rate Manager
        if (exchangeRateManager != null) {
            exchangeRateManager.close();
        }

        // Close Subscription Manager
        if (subscriptionManager != null) {
            subscriptionManager.close();
        }

        // Close Dynamic Pricing Manager
        if (dynamicPricingManager != null) {
            dynamicPricingManager.close();
        }

        // Close Discord Manager
        if (discordManager != null) {
            discordManager.close();
        }

        // Close Central Bank Manager
        if (centralBankManager != null) {
            centralBankManager.close();
        }

        // Close Payday Manager
        if (paydayManager != null) {
            paydayManager.close();
        }

        // Close Black Market Manager
        if (blackMarketManager != null) {
            blackMarketManager.close();
        }

        // Close Stock Exchange Manager
        if (stockExchangeManager != null) {
            stockExchangeManager.close();
        }

        // Close Loan Manager
        if (loanManager != null) {
            loanManager.close();
        }

        // Close Bank Backup Manager
        if (bankBackupManager != null) {
            bankBackupManager.close();
        }

        // Close Local Failover SQLite Manager
        if (failoverManager != null) {
            failoverManager.close();
        }

        // Close Transaction Firewall
        if (firewall != null) {
            try {
                firewall.close();
            } catch (Exception e) {
                getLogger().warning("[Vault] Exception closing TransactionFirewall: " + e.getMessage());
            }
        }

        // Shutdown and clear wrapped economies to prevent memory retention across plugin reloads
        for (OptimizedEconomy econ : wrappedEconomies) {
            if (econ != null) {
                try {
                    econ.shutdown();
                } catch (Exception ignored) {}
            }
        }
        wrappedEconomies.clear();

        // Clear physical economy listener static checks cache
        net.milkbowl.vault.listener.PhysicalEconomyListener.cleanup();
        net.milkbowl.vault.util.UUIDCache.cleanup();

        // Reset all static references to null to prevent memory retention or stale access on plugin disable
        vaultXGUI = null;
        firewall = null;
        failoverManager = null;
        escrowManager = null;
        exchangeRateManager = null;
        centralBankManager = null;
        mailboxManager = null;
        stockExchangeManager = null;
        loanManager = null;
        paydayManager = null;
        subscriptionManager = null;
        dynamicPricingManager = null;
        discordManager = null;
        townyLandsFactionsHook = null;
        blackMarketManager = null;
        bankBackupManager = null;
        messages = null;
        gui = null;
    }

    @Override
    public void onEnable() {
        // Migrate data folder from VaultX to Vault if necessary
        File vaultFolder = getDataFolder();
        if (!vaultFolder.exists()) {
            File vaultXFolder = new File(vaultFolder.getParentFile(), "VaultX");
            if (vaultXFolder.exists()) {
                getLogger().info("Found existing VaultX configuration folder. Migrating to Vault...");
                if (vaultXFolder.renameTo(vaultFolder)) {
                    getLogger().info("VaultX configuration folder successfully migrated to Vault.");
                } else {
                    getLogger().warning("Failed to migrate VaultX configuration folder to Vault.");
                }
            }
        }

        loadMessagesConfig();
        loadGuiConfig();
        sm = getServer().getServicesManager();

        // Auto-update config file preserving comments
        File configFile = new File(getDataFolder(), "config.yml");
        if (configFile.exists()) {
            try {
                net.milkbowl.vault.util.ConfigUpdater.updateConfig(configFile, getResource("config.yml"));
            } catch (Exception e) {
                getLogger().warning("Could not auto-update config.yml: " + e.getMessage());
            }
            reloadConfig();
        } else {
            saveDefaultConfig();
        }

        // Update Checker
        if (getConfig().getBoolean("settings.check-updates", true)) {
            new net.milkbowl.vault.util.UpdateChecker(this, 135253).getVersion(version -> {
                if (isNewerVersion(getDescription().getVersion(), version)) {
                    getLogger().warning("A new update is available (" + version
                            + ")! Download it here: https://www.spigotmc.org/resources/vaultx.135253/");
                } else {
                    getLogger().info("The plugin is up to date.");
                }
            });
        }

        // Initialize Failover Manager (SQLite)
        failoverManager = new net.milkbowl.vault.redis.LocalFailoverManager(this);
        escrowManager = new net.milkbowl.vault.redis.EscrowManager(this);
        exchangeRateManager = new net.milkbowl.vault.economy.ExchangeRateManager(this);
        if (sm != null && exchangeRateManager != null) {
            sm.register(net.milkbowl.vault.economy.CurrencyExchangeAPI.class, exchangeRateManager, this, ServicePriority.Normal);
        }
        if (sm != null && wrappedEconomies != null && !wrappedEconomies.isEmpty()) {
            OptimizedEconomy primaryEcon = wrappedEconomies.get(0);
            sm.register(net.milkbowl.vault.economy.VaultLeaderboardAPI.class, primaryEcon, this, ServicePriority.Normal);
            sm.register(net.milkbowl.vault.economy.VaultBatchTransactionAPI.class, primaryEcon, this, ServicePriority.Normal);
            sm.register(net.milkbowl.vault.economy.VaultFormatAPI.class, primaryEcon, this, ServicePriority.Normal);
            sm.register(net.milkbowl.vault.economy.VaultMailboxAPI.class, primaryEcon, this, ServicePriority.Normal);
            sm.register(net.milkbowl.vault.economy.VaultBoosterAPI.class, primaryEcon, this, ServicePriority.Normal);
            sm.register(net.milkbowl.vault.economy.VaultLockAPI.class, primaryEcon, this, ServicePriority.Normal);
            sm.register(net.milkbowl.vault.economy.VaultSubscriptionAPI.class, primaryEcon, this, ServicePriority.Normal);
            sm.register(net.milkbowl.vault.economy.VaultAnalyticsAPI.class, primaryEcon, this, ServicePriority.Normal);
            sm.register(net.milkbowl.vault.economy.VaultCurrencyRegistry.class, primaryEcon, this, ServicePriority.Normal);
            sm.register(net.milkbowl.vault.economy.VaultAuditAPI.class, primaryEcon, this, ServicePriority.Normal);
            sm.register(net.milkbowl.vault.economy.VaultCheckAPI.class, primaryEcon, this, ServicePriority.Normal);
            sm.register(net.milkbowl.vault.economy.VaultLoanAPI.class, primaryEcon, this, ServicePriority.Normal);
            sm.register(net.milkbowl.vault.economy.VaultInflationAPI.class, primaryEcon, this, ServicePriority.Normal);
            sm.register(net.milkbowl.vault.economy.VaultMilestoneAPI.class, primaryEcon, this, ServicePriority.Normal);
            sm.register(net.milkbowl.vault.economy.VaultCryptoAPI.class, primaryEcon, this, ServicePriority.Normal);
        }

        // Register PlaceholderAPI expansion if present
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try {
                new net.milkbowl.vault.integration.VaultXExpansion(this).register();
                getLogger().info("[VaultX] Successfully registered PlaceholderAPI expansion.");
            } catch (Throwable e) {
                getLogger().warning("[VaultX] Could not register PlaceholderAPI expansion: " + e.getMessage());
            }
        }
        centralBankManager = new net.milkbowl.vault.economy.CentralBankManager(this, exchangeRateManager);
        mailboxManager = new net.milkbowl.vault.economy.MailboxManager(this);
        vaultXGUI = new net.milkbowl.vault.util.VaultXGUI(this);
        if (getConfig().getBoolean("stocks.enabled", true)) {
            stockExchangeManager = new net.milkbowl.vault.economy.StockExchangeManager(this);
        }
        loanManager = new net.milkbowl.vault.economy.LoanManager(this);
        subscriptionManager = new net.milkbowl.vault.economy.SubscriptionManager(this);
        dynamicPricingManager = new net.milkbowl.vault.economy.DynamicPricingManager(this);
        discordManager = new net.milkbowl.vault.discord.DiscordManager(this);
        townyLandsFactionsHook = new net.milkbowl.vault.integration.TownyLandsFactionsHook(this);
        blackMarketManager = new net.milkbowl.vault.economy.BlackMarketManager(this);


        // Schedule Hourly Analytics Aggregation Task (runs every hour = 72000 ticks)
        net.milkbowl.vault.util.FoliaScheduler.runTimerAsync(this, () -> {
            if (failoverManager != null) {
                failoverManager.runAnalyticsAggregation();
            }
        }, 1200L, 72000L); // 1 minute delay, 1 hour interval

        // Register Backup Perms (SuperPerms)
        this.perms = new Permission_SuperPerms(this);
        sm.register(Permission.class, perms, this, ServicePriority.Lowest);

        // Set Command Executors
        if (getCommand("vault-info") != null)
            getCommand("vault-info").setExecutor(this);
        if (getCommand("vault-convert") != null)
            getCommand("vault-convert").setExecutor(this);
        if (getCommand("vaultx") != null) {
            net.milkbowl.vault.command.VaultXCommand vxCmd = new net.milkbowl.vault.command.VaultXCommand(this);
            getCommand("vaultx").setExecutor(vxCmd);
            getCommand("vaultx").setTabCompleter(vxCmd);

            // Dynamically register all other economy shortcut commands (renaming, aliases, permissions, enabling/disabling)
            net.milkbowl.vault.command.CommandRegistrationManager.registerCommands(this, vxCmd, vxCmd);
        }

        // Load up Plugin Metrics
        if (getConfig().getBoolean("metrics.enabled", true)) {
            int pluginId = 31404;
            Metrics metrics = new Metrics(this, pluginId);
            findCustomData(metrics);

            // Optional: Add custom charts
            metrics.addCustomChart(
                    new SimplePie("chart_id", () -> "My value"));
        }

        // Initialize Redis if enabled
        if (getConfig().getBoolean("redis.enabled", false)) {
            String host = getConfig().getString("redis.host", "127.0.0.1");
            int port = getConfig().getInt("redis.port", 6379);
            String password = getConfig().getString("redis.password", "");
            String serverId = getConfig().getString("redis.server-id", "server-1");
            String channel = getConfig().getString("redis.channel", "vaultx:economy:sync");

            new net.milkbowl.vault.redis.VaultRedisManager(this, host, port, password, serverId, channel);
        }

        // Initialize UUID Cache
        boolean uuidCacheEnabled = getConfig().getBoolean("advanced.global-uuid-cache", true);
        net.milkbowl.vault.util.UUIDCache.init(this, uuidCacheEnabled);

        // Initialize Bank Backup Manager
        boolean backupEnabled = getConfig().getBoolean("banks.backup.enabled", true);
        int backupInterval = getConfig().getInt("banks.backup.interval-minutes", 30);
        bankBackupManager = new net.milkbowl.vault.economy.BankBackupManager(this, backupEnabled, backupInterval);

        // Inject Modern Economy Interceptor Engine
        registerEconomyInterceptor();

        // Initialize Transaction Firewall
        firewall = new net.milkbowl.vault.security.TransactionFirewall(this);

        // Initialize Paydays
        if (getConfig().getBoolean("paydays.enabled", true)) {
            paydayManager = new net.milkbowl.vault.economy.PaydayManager(this);
        }

        // Register Physical Economy ATM & Check Listener
        getServer().getPluginManager().registerEvents(new net.milkbowl.vault.listener.PhysicalEconomyListener(this), this);

        // Register Modern VaultX APIs in Bukkit ServicesManager
        if (escrowManager != null) {
            getServer().getServicesManager().register(net.milkbowl.vault.economy.VaultEscrowAPI.class, escrowManager, this, org.bukkit.plugin.ServicePriority.Highest);
        }
        if (firewall != null) {
            getServer().getServicesManager().register(net.milkbowl.vault.economy.VaultFirewallAPI.class, firewall, this, org.bukkit.plugin.ServicePriority.Highest);
        }
        if (blackMarketManager != null) {
            getServer().getServicesManager().register(net.milkbowl.vault.economy.VaultBlackMarketAPI.class, blackMarketManager, this, org.bukkit.plugin.ServicePriority.Highest);
        }
        if (stockExchangeManager != null) {
            getServer().getServicesManager().register(net.milkbowl.vault.economy.VaultStockAPI.class, stockExchangeManager, this, org.bukkit.plugin.ServicePriority.Highest);
        }

        // Register PlaceholderAPI Expansion if available
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            net.milkbowl.vault.util.VaultPlaceholderExpansion expansion = new net.milkbowl.vault.util.VaultPlaceholderExpansion(
                    this);
            expansion.register();
            getServer().getPluginManager().registerEvents(expansion, this);
        }

        // Register Skript expressions if Skript is available
        if (Bukkit.getPluginManager().isPluginEnabled("Skript")) {
            try {
                net.milkbowl.vault.skript.SkriptVaultXHook.register();
                getLogger().info("Successfully registered custom Skript currency addon.");
            } catch (Throwable e) {
                getLogger().warning("Failed to register custom Skript currency addon: " + e.getMessage());
            }
        }

        Bukkit.getConsoleSender().sendMessage("§6───────────────────────────────────────────────────────────────────");
        Bukkit.getConsoleSender().sendMessage("§e⚡ If you have any suggestions or encounter any bugs");
        Bukkit.getConsoleSender().sendMessage("§e   related to this plugin, please join this Discord server:");
        Bukkit.getConsoleSender().sendMessage("§8   » §bhttps://discord.gg/3QzcDHC6 §8«");
        Bukkit.getConsoleSender().sendMessage("§7   Thank you for using this plugin — §6§lSkyNex§a. ");
        Bukkit.getConsoleSender().sendMessage("§6   ⭐ §eDon't forget to rate this plugin §6⭐ ");
        Bukkit.getConsoleSender().sendMessage("§6───────────────────────────────────────────────────────────────────");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String commandLabel, String[] args) {
        if (!sender.hasPermission("vault.admin")) {
            sender.sendMessage(getMessage("general.no-permission", "§cYou do not have permission to use that command!"));
            return true;
        }

        if (command.getName().equalsIgnoreCase("vault-info")) {
            infoCommand(sender);
            return true;
        } else if (command.getName().equalsIgnoreCase("vault-convert")) {
            convertCommand(sender, args);
            return true;
        }

        return false;
    }

    private void convertCommand(CommandSender sender, String[] args) {
        Collection<RegisteredServiceProvider<Economy>> econs = this.getServer().getServicesManager()
                .getRegistrations(Economy.class);
        if (econs == null || econs.size() < 2) {
            sender.sendMessage(getMessage("admin-core.convert.no-economies", "§cYou must have at least 2 economies loaded to convert."));
            return;
        } else if (args.length != 2) {
            sender.sendMessage(getMessage("admin-core.convert.usage", "§cUsage: /vault-convert [economy1] [economy2]"));
            return;
        }

        Economy econ1 = null;
        Economy econ2 = null;
        StringBuilder economies = new StringBuilder();
        for (RegisteredServiceProvider<Economy> econ : econs) {
            String econName = econ.getProvider().getName().replace(" ", "");
            if (econName.equalsIgnoreCase(args[0])) {
                econ1 = econ.getProvider();
            } else if (econName.equalsIgnoreCase(args[1])) {
                econ2 = econ.getProvider();
            }
            if (economies.length() > 0) {
                economies.append(", ");
            }
            economies.append(econName);
        }

        if (econ1 == null || econ2 == null) {
            sender.sendMessage(getMessage("admin-core.convert.not-found", "§cEconomy not found! Loaded economies: %economies%")
                    .replace("%economies%", economies.toString()));
            return;
        }

        sender.sendMessage(getMessage("admin-core.convert.starting", "§eStarting conversion process... (Running in safe non-blocking batches)"));

        final Economy finalEcon1 = econ1;
        final Economy finalEcon2 = econ2;

        net.milkbowl.vault.util.FoliaScheduler.runAsync(Vault.this, () -> {
            final OfflinePlayer[] players = Bukkit.getOfflinePlayers();
            final int total = players != null ? players.length : 0;
            final int batchSize = 50;
            final java.util.concurrent.atomic.AtomicInteger processedCount = new java.util.concurrent.atomic.AtomicInteger(0);

            class BatchProcessor implements Runnable {
                int index = 0;

                @Override
                public void run() {
                    int end = Math.min(index + batchSize, total);
                    for (int i = index; i < end; i++) {
                        OfflinePlayer op = players[i];
                        if (op != null) {
                            try {
                                if (finalEcon1.hasAccount(op)) {
                                    if (!finalEcon2.hasAccount(op)) {
                                        finalEcon2.createPlayerAccount(op);
                                    }

                                    boolean overallSuccess = true;
                                    if (finalEcon1 instanceof MultiCurrencyEconomy && finalEcon2 instanceof MultiCurrencyEconomy) {
                                        MultiCurrencyEconomy mc1 = (MultiCurrencyEconomy) finalEcon1;
                                        MultiCurrencyEconomy mc2 = (MultiCurrencyEconomy) finalEcon2;
                                        java.util.List<String> currencies = mc1.getSupportedCurrencies();
                                        if (currencies != null && !currencies.isEmpty()) {
                                            for (String cur : currencies) {
                                                double diff = mc1.getCurrencyBalance(op, cur) - mc2.getCurrencyBalance(op, cur);
                                                if (diff > 0) {
                                                    net.milkbowl.vault.economy.EconomyResponse res = mc2.depositCurrencyPlayer(op, cur, diff);
                                                    if (!res.transactionSuccess()) overallSuccess = false;
                                                } else if (diff < 0) {
                                                    net.milkbowl.vault.economy.EconomyResponse res = mc2.withdrawCurrencyPlayer(op, cur, -diff);
                                                    if (!res.transactionSuccess()) overallSuccess = false;
                                                }
                                            }
                                        } else {
                                            double diff = finalEcon1.getBalance(op) - finalEcon2.getBalance(op);
                                            if (diff > 0) {
                                                net.milkbowl.vault.economy.EconomyResponse res = finalEcon2.depositPlayer(op, diff);
                                                if (!res.transactionSuccess()) overallSuccess = false;
                                            } else if (diff < 0) {
                                                net.milkbowl.vault.economy.EconomyResponse res = finalEcon2.withdrawPlayer(op, -diff);
                                                if (!res.transactionSuccess()) overallSuccess = false;
                                            }
                                        }
                                    } else {
                                        double diff = finalEcon1.getBalance(op) - finalEcon2.getBalance(op);
                                        if (diff > 0) {
                                            net.milkbowl.vault.economy.EconomyResponse res = finalEcon2.depositPlayer(op, diff);
                                            if (!res.transactionSuccess()) overallSuccess = false;
                                        } else if (diff < 0) {
                                            net.milkbowl.vault.economy.EconomyResponse res = finalEcon2.withdrawPlayer(op, -diff);
                                            if (!res.transactionSuccess()) overallSuccess = false;
                                        }
                                    }

                                    if (overallSuccess) {
                                        processedCount.incrementAndGet();
                                    }
                                }
                            } catch (Exception e) {
                                getLogger().warning("[Vault Convert] Error converting player " + (op.getName() != null ? op.getName() : op.getUniqueId()) + ": " + e.getMessage());
                            }
                        }
                    }
                    index = end;
                    if (index < total) {
                        net.milkbowl.vault.util.FoliaScheduler.runLater(Vault.this, this, 1L);
                    } else {
                        sender.sendMessage(getMessage("admin-core.convert.success", "§a§l✔ §aConversion complete! Processed %processed% players.")
                                .replace("%processed%", String.valueOf(processedCount.get())));
                    }
                }
            }

            net.milkbowl.vault.util.FoliaScheduler.runSync(Vault.this, new BatchProcessor());
        });
    }

    private void infoCommand(CommandSender sender) {
        String registeredEcons = getServiceList(Economy.class);
        String registeredPerms = getServiceList(Permission.class);
        String registeredChats = getServiceList(Chat.class);

        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        Economy econ = rsp != null ? rsp.getProvider() : null;

        RegisteredServiceProvider<Permission> rspp = getServer().getServicesManager().getRegistration(Permission.class);
        Permission perm = rspp != null ? rspp.getProvider() : null;

        RegisteredServiceProvider<Chat> rspc = getServer().getServicesManager().getRegistration(Chat.class);
        Chat chat = rspc != null ? rspc.getProvider() : null;

        sender.sendMessage(getMessage("admin-core.info.header", "§b§l=== Vault Modern X Information ==="));
        sender.sendMessage(getMessage("admin-core.info.economy", "§fEconomy Provider: §e%provider% §7[%list%]")
                .replace("%provider%", econ == null ? "None" : econ.getName())
                .replace("%list%", registeredEcons));
        sender.sendMessage(getMessage("admin-core.info.permission", "§fPermission Provider: §e%provider% §7[%list%]")
                .replace("%provider%", perm == null ? "None" : perm.getName())
                .replace("%list%", registeredPerms));
        sender.sendMessage(getMessage("admin-core.info.chat", "§fChat Provider: §e%provider% §7[%list%]")
                .replace("%provider%", chat == null ? "None" : chat.getName())
                .replace("%list%", registeredChats));
        sender.sendMessage(getMessage("admin-core.info.footer", "§b§l==============================="));
    }

    private <T> String getServiceList(Class<T> serviceClass) {
        Collection<RegisteredServiceProvider<T>> registrations = getServer().getServicesManager()
                .getRegistrations(serviceClass);
        if (registrations.isEmpty())
            return "None";
        StringBuilder sb = new StringBuilder();
        for (RegisteredServiceProvider<T> reg : registrations) {
            if (sb.length() > 0)
                sb.append(", ");
            // Use actual provider's reported name via reflection fallback if standard
            // name() missing
            try {
                java.lang.reflect.Method getName = reg.getProvider().getClass().getMethod("getName");
                sb.append(getName.invoke(reg.getProvider()));
            } catch (Exception e) {
                sb.append(reg.getProvider().getClass().getSimpleName());
            }
        }
        return sb.toString();
    }

    private void findCustomData(Metrics metrics) {
        metrics.addCustomChart(new SimplePie("economy", () -> {
            RegisteredServiceProvider<Economy> rsp = Bukkit.getServer().getServicesManager()
                    .getRegistration(Economy.class);
            return rsp != null ? rsp.getProvider().getName() : "No Economy";
        }));

        metrics.addCustomChart(new SimplePie("permission", () -> {
            RegisteredServiceProvider<Permission> rsp = Bukkit.getServer().getServicesManager()
                    .getRegistration(Permission.class);
            return rsp != null ? rsp.getProvider().getName() : "No Permission";
        }));

        metrics.addCustomChart(new SimplePie("chat", () -> {
            RegisteredServiceProvider<Chat> rsp = Bukkit.getServer().getServicesManager().getRegistration(Chat.class);
            return rsp != null ? rsp.getProvider().getName() : "No Chat";
        }));
    }

    private void registerEconomyInterceptor() {
        // 1. Wrap any economy plugin already loaded at startup
        wrapExistingEconomies();

        // 2. Dynamically intercept future economy providers and clean cache on
        // disconnect
        getServer().getPluginManager().registerEvents(new Listener() {

            @EventHandler
            public void onServiceRegister(ServiceRegisterEvent event) {
                if (event.getProvider().getService().equals(Economy.class)) {
                    Object rawProvider = event.getProvider().getProvider();
                    if (!(rawProvider instanceof OptimizedEconomy)) {
                        wrapAndRegisterEconomy((Economy) rawProvider);
                    }
                }
            }

            @EventHandler
            public void onPlayerQuit(PlayerQuitEvent event) {
                RegisteredServiceProvider<Economy> rsp = sm.getRegistration(Economy.class);
                if (rsp != null && rsp.getProvider() instanceof OptimizedEconomy) {
                    ((OptimizedEconomy) rsp.getProvider()).invalidateCache(event.getPlayer());
                }
                if (firewall != null) {
                    firewall.invalidateCache(event.getPlayer());
                }
                if (blackMarketManager != null) {
                    blackMarketManager.cleanupPlayer(event.getPlayer().getUniqueId());
                }
                if (discordManager != null) {
                    discordManager.cleanupPlayer(event.getPlayer().getUniqueId());
                }
            }

            @EventHandler
            public void onPlayerPreLogin(org.bukkit.event.player.AsyncPlayerPreLoginEvent event) {
                final UUID uuid = event.getUniqueId();
                RegisteredServiceProvider<Economy> rsp = sm.getRegistration(Economy.class);
                if (rsp != null && rsp.getProvider() instanceof OptimizedEconomy) {
                    OptimizedEconomy econ = (OptimizedEconomy) rsp.getProvider();
                    econ.warmCache(uuid);
                }
            }

            @EventHandler
            public void onPlayerJoin(org.bukkit.event.player.PlayerJoinEvent event) {
                org.bukkit.entity.Player player = event.getPlayer();
                RegisteredServiceProvider<Economy> rsp = sm.getRegistration(Economy.class);
                if (rsp != null && rsp.getProvider() instanceof OptimizedEconomy) {
                    OptimizedEconomy econ = (OptimizedEconomy) rsp.getProvider();
                    econ.onPlayerJoin(player);
                }
            }

        }, this);

        // 3. Register native SQLite/Redis fallback economy at Lowest priority
        wrapAndRegisterEconomyFallback();
    }

    private static final java.util.List<OptimizedEconomy> wrappedEconomies = new java.util.concurrent.CopyOnWriteArrayList<>();

    private void wrapAndRegisterEconomyFallback() {
        boolean useCache = getConfig().getBoolean("economy.use-cache", true);
        boolean debugTransactions = getConfig().getBoolean("economy.debug-transactions", false);

        boolean rateLimiterEnabled = getConfig().getBoolean("security.rate-limiter.enabled", true);
        int maxTps = getConfig().getInt("security.rate-limiter.max-transactions-per-second", 20);
        int cooldown = getConfig().getInt("security.rate-limiter.cooldown-seconds", 5);

        boolean nativeBanks = getConfig().getBoolean("banks.native-redis.enabled", true);

        // Shutdown any previous fallback wrapper
        wrappedEconomies.removeIf(econ -> {
            if (econ.getDelegate() == null) {
                try {
                    econ.shutdown();
                } catch (Exception ignored) {}
                return true;
            }
            return false;
        });

        OptimizedEconomy wrapped = new OptimizedEconomy(this, null, useCache, debugTransactions, rateLimiterEnabled,
                maxTps, cooldown, nativeBanks);
        wrappedEconomies.add(wrapped);
        sm.register(Economy.class, wrapped, this, ServicePriority.Lowest);
        sm.register(MultiCurrencyEconomy.class, wrapped, this, ServicePriority.Lowest);
    }

    private void wrapExistingEconomies() {
        Collection<RegisteredServiceProvider<Economy>> registrations = sm.getRegistrations(Economy.class);
        for (RegisteredServiceProvider<Economy> reg : registrations) {
            if (!(reg.getProvider() instanceof OptimizedEconomy)) {
                wrapAndRegisterEconomy(reg.getProvider());
            }
        }
    }

    private void wrapAndRegisterEconomy(Economy original) {
        boolean useCache = getConfig().getBoolean("economy.use-cache", true);
        boolean debugTransactions = getConfig().getBoolean("economy.debug-transactions", false);

        boolean rateLimiterEnabled = getConfig().getBoolean("security.rate-limiter.enabled", true);
        int maxTps = getConfig().getInt("security.rate-limiter.max-transactions-per-second", 20);
        int cooldown = getConfig().getInt("security.rate-limiter.cooldown-seconds", 5);

        boolean nativeBanks = getConfig().getBoolean("banks.native-redis.enabled", true);

        // Shutdown any previous wrapper for this delegate
        wrappedEconomies.removeIf(econ -> {
            if (econ.getDelegate() == original) {
                try {
                    econ.shutdown();
                } catch (Exception ignored) {}
                return true;
            }
            return false;
        });

        OptimizedEconomy wrapped = new OptimizedEconomy(this, original, useCache, debugTransactions, rateLimiterEnabled,
                maxTps, cooldown, nativeBanks);
        wrappedEconomies.add(wrapped);
        // Force register with highest possible priority to secure the primary routing
        // spot
        sm.register(Economy.class, wrapped, this, ServicePriority.Highest);
        sm.register(MultiCurrencyEconomy.class, wrapped, this, ServicePriority.Highest);
    }

    private void loadMessagesConfig() {
        File file = new File(getDataFolder(), "messages.yml");
        if (file.exists()) {
            try {
                net.milkbowl.vault.util.ConfigUpdater.updateConfig(file, getResource("messages.yml"));
            } catch (Exception e) {
                getLogger().warning("Could not auto-update messages.yml: " + e.getMessage());
            }
        } else {
            saveResource("messages.yml", false);
        }
        messages = YamlConfiguration.loadConfiguration(file);
    }

    private void loadGuiConfig() {
        File file = new File(getDataFolder(), "gui.yml");
        if (file.exists()) {
            try {
                net.milkbowl.vault.util.ConfigUpdater.updateConfig(file, getResource("gui.yml"));
            } catch (Exception e) {
                getLogger().warning("Could not auto-update gui.yml: " + e.getMessage());
            }
        } else {
            saveResource("gui.yml", false);
        }
        gui = YamlConfiguration.loadConfiguration(file);
    }

    public static YamlConfiguration getGuiConfig() {
        return gui;
    }

    public static String getMessage(String path, String def) {
        if (messages == null) {
            return ChatColor.translateAlternateColorCodes('&', def);
        }
        String val = messages.getString(path);
        if (val == null) {
            return ChatColor.translateAlternateColorCodes('&', def);
        }
        return ChatColor.translateAlternateColorCodes('&', val);
    }

    public static double getDynamicTaxPercent(String taxKey, double fallback) {
        if (failoverManager == null) return fallback;
        String val = failoverManager.getSetting("tax_" + taxKey);
        if (val == null) return fallback;
        try {
            return Double.parseDouble(val);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static void setDynamicTaxPercent(String taxKey, double percent) {
        if (failoverManager != null) {
            failoverManager.saveSetting("tax_" + taxKey, String.valueOf(percent));
        }
    }

    private boolean isNewerVersion(String current, String online) {
        try {
            String cleanCurrent = getVaultXVersion(current);
            String[] currentParts = cleanCurrent.split("\\.");
            String[] onlineParts = online.split("\\.");
            int length = Math.max(currentParts.length, onlineParts.length);
            for (int i = 0; i < length; i++) {
                int c = (i < currentParts.length) ? Integer.parseInt(currentParts[i].replaceAll("[^0-9]", "")) : 0;
                int o = (i < onlineParts.length) ? Integer.parseInt(onlineParts[i].replaceAll("[^0-9]", "")) : 0;
                if (o > c)
                    return true;
                if (c > o)
                    return false;
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    private String getVaultXVersion(String fullVersion) {
        if (fullVersion.contains("-VaultX-")) {
            return fullVersion.substring(fullVersion.lastIndexOf("-VaultX-") + 8);
        }
        return fullVersion;
    }
}

