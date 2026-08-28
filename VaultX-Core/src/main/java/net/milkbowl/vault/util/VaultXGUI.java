package net.milkbowl.vault.util;

import net.milkbowl.vault.Vault;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.ExchangeRateManager;
import net.milkbowl.vault.economy.ExchangeRateManager.ConversionResult;
import net.milkbowl.vault.economy.MailboxManager;
import net.milkbowl.vault.economy.MultiCurrencyEconomy;
import net.milkbowl.vault.redis.EscrowManager;
import net.milkbowl.vault.redis.EscrowManager.EscrowDetails;
import net.milkbowl.vault.redis.LocalFailoverManager;
import net.milkbowl.vault.redis.LocalFailoverManager.MailRecord;
import net.milkbowl.vault.redis.LocalFailoverManager.PlayerTransactionRecord;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class VaultXGUI implements Listener {

    private final Plugin plugin;

    // Cyclical selectors state for Exchange GUI
    private final Map<UUID, String> selectedFrom = new ConcurrentHashMap<>();
    private final Map<UUID, String> selectedTo = new ConcurrentHashMap<>();

    // Material resolver for 1.8 - 1.20+ compatibility
    private static final Material GLASS_PANE = getMaterial("GRAY_STAINED_GLASS_PANE", "STAINED_GLASS_PANE");
    private static final Material CHEST = getMaterial("CHEST");
    private static final Material PAPER = getMaterial("PAPER");
    private static final Material GOLD_INGOT = getMaterial("GOLD_INGOT");
    private static final Material IRON_INGOT = getMaterial("IRON_INGOT");
    private static final Material EMERALD = getMaterial("EMERALD");
    private static final Material DIAMOND = getMaterial("DIAMOND");
    private static final Material GOLD_NUGGET = getMaterial("GOLD_NUGGET");
    private static final Material BOOK = getMaterial("BOOK");
    private static final Material BARRIER = getMaterial("BARRIER", "REDSTONE");
    private static final Material GOLD_BLOCK = getMaterial("GOLD_BLOCK");
    private static final Material REDSTONE = getMaterial("REDSTONE");

    private static Material getMaterial(String... names) {
        for (String name : names) {
            try {
                return Material.valueOf(name);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return Material.STONE;
    }

    public VaultXGUI(Plugin plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void close() {
        selectedFrom.clear();
        selectedTo.clear();
        org.bukkit.event.HandlerList.unregisterAll(this);
    }

    @EventHandler
    public void onPlayerQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        if (event.getPlayer() != null) {
            UUID uuid = event.getPlayer().getUniqueId();
            selectedFrom.remove(uuid);
            selectedTo.remove(uuid);
        }
    }

    private Economy getEconomy() {
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        return rsp != null ? rsp.getProvider() : null;
    }

    // --- CUSTOM HOLDERS FOR INVENTORY SECURITY ---
    public static class GUIDashboardHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    public static class GUIHistoryHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    public static class GUIExchangeHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    public static class GUIEscrowHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    public static class GUIMailboxHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    public static class GUIStocksHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    public static class GUILoansHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    public static class GUIAdminStatsHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    public static class GUIBlackMarketHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    // --- ITEM CREATION UTILITY ---
    private ItemStack createGuiItem(Material mat, int amount, short data, String name, String... lore) {
        ItemStack item = new ItemStack(mat, amount, data);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore != null && lore.length > 0) {
                meta.setLore(Arrays.asList(lore));
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private String getGuiTitle(String configPath, String defaultTitle) {
        YamlConfiguration guiCfg = Vault.getGuiConfig();
        if (guiCfg != null) {
            String title = guiCfg.getString(configPath + ".title");
            if (title != null) {
                return ChatColor.translateAlternateColorCodes('&', title);
            }
        }
        return defaultTitle;
    }

    private int getSlot(String configPath, int defaultSlot) {
        YamlConfiguration guiCfg = Vault.getGuiConfig();
        if (guiCfg != null && guiCfg.contains(configPath + ".slot")) {
            return guiCfg.getInt(configPath + ".slot");
        }
        return defaultSlot;
    }

    private ItemStack loadItem(String configPath, Material defaultMat, int defaultData, String defaultName, String[] defaultLore, Map<String, String> replacements) {
        YamlConfiguration guiCfg = Vault.getGuiConfig();
        Material mat = defaultMat;
        short data = (short) defaultData;
        String name = defaultName;
        String[] lore = defaultLore;

        if (guiCfg != null) {
            String matStr = guiCfg.getString(configPath + ".material");
            if (matStr != null) {
                try {
                    mat = Material.valueOf(matStr.toUpperCase());
                } catch (Exception e) {}
            }
            data = (short) guiCfg.getInt(configPath + ".data", defaultData);
            String nameFromConfig = guiCfg.getString(configPath + ".name");
            if (nameFromConfig != null) {
                name = ChatColor.translateAlternateColorCodes('&', nameFromConfig);
            }
            List<String> loreList = guiCfg.getStringList(configPath + ".lore");
            if (loreList != null && guiCfg.contains(configPath + ".lore")) {
                lore = new String[loreList.size()];
                for (int i = 0; i < loreList.size(); i++) {
                    lore[i] = ChatColor.translateAlternateColorCodes('&', loreList.get(i));
                }
            }
        }

        if (replacements != null) {
            for (Map.Entry<String, String> entry : replacements.entrySet()) {
                name = name.replace(entry.getKey(), entry.getValue());
                for (int i = 0; i < lore.length; i++) {
                    lore[i] = lore[i].replace(entry.getKey(), entry.getValue());
                }
            }
        }

        return createGuiItem(mat, 1, data, name, lore);
    }

    private ItemStack loadCommodityItem(String key, Material defMat, String defName, double price, double shares, Economy econ) {
        Map<String, String> reps = new HashMap<>();
        reps.put("%price%", econ != null ? econ.format(price) : String.valueOf(price));
        reps.put("%shares%", String.format("%.2f", shares));
        reps.put("%value%", econ != null ? econ.format(price * shares) : String.format("%.2f", price * shares));

        String[] defLore = new String[] {
            "§7Current price: §e" + (econ != null ? econ.format(price) : price),
            "§7Your shares: §f" + String.format("%.2f", shares),
            "§7Value: §a" + (econ != null ? econ.format(price * shares) : String.format("%.2f", price * shares)),
            "",
            "§e▶ Left Click: §fBuy 1 share",
            "§e▶ Right Click: §fSell 1 share",
            "§e▶ Shift + Left: §fBuy 10 shares",
            "§e▶ Shift + Right: §fSell 10 shares"
        };

        return loadItem("stocks.items." + key, defMat, 0, defName, defLore, reps);
    }

    private ItemStack loadBankSharesItem(String bank, double shares, double bal, double sharePrice, Economy econ) {
        Map<String, String> reps = new HashMap<>();
        reps.put("%bank%", bank.toUpperCase());
        reps.put("%balance%", econ != null ? econ.format(bal) : String.valueOf(bal));
        reps.put("%price%", econ != null ? econ.format(sharePrice) : String.valueOf(sharePrice));
        reps.put("%sell_price%", econ != null ? econ.format(sharePrice * 0.95) : String.valueOf(sharePrice * 0.95));
        reps.put("%owned%", String.format("%.2f", shares));
        reps.put("%value%", econ != null ? econ.format(sharePrice * shares) : String.format("%.2f", sharePrice * shares));

        String[] defLore = new String[] {
            "§7Bank balance: §a" + (econ != null ? econ.format(bal) : bal),
            "§7Price of a share (1%): §e" + (econ != null ? econ.format(sharePrice) : sharePrice),
            "§7Your shares: §f" + String.format("%.2f", shares) + " §7/ 100.0",
            "§7Value: §a" + (econ != null ? econ.format(sharePrice * shares) : String.format("%.2f", sharePrice * shares)),
            "",
            "§e▶ Left Click: §fBuy 1 share",
            "§e▶ Right Click: §fSell 1 share",
            "§e▶ Shift + Left: §fBuy 10 shares",
            "§e▶ Shift + Right: §fSell 10 shares"
        };

        return loadItem("stocks.items.bank-shares", CHEST, 0, "§d§lShares: " + bank.toUpperCase(), defLore, reps);
    }

    private ItemStack cachedFiller;

    private ItemStack getFillerItem() {
        if (cachedFiller == null) {
            cachedFiller = loadItem("filler-item", GLASS_PANE, 15, "§7", new String[0], null);
        }
        return cachedFiller.clone();
    }

    private void fillBorder(Inventory inv) {
        ItemStack filler = getFillerItem();
        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) == null) {
                inv.setItem(i, filler);
            }
        }
    }
    // --- 1. DASHBOARD ---
    public void openDashboard(Player player) {
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp == null)
            return;
        Economy econ = rsp.getProvider();

        String title = getGuiTitle("dashboard", "§d§lVaultX Dashboard");
        int size = 36;
        YamlConfiguration guiCfg = Vault.getGuiConfig();
        if (guiCfg != null) {
            size = guiCfg.getInt("dashboard.size", 36);
        }
        Inventory inv = Bukkit.createInventory(new GUIDashboardHolder(), size, title);

        double defaultBal = econ.getBalance(player);

        List<String> currenciesList = new ArrayList<>();
        currenciesList.add("default");
        if (econ instanceof MultiCurrencyEconomy) {
            for (String curr : ((MultiCurrencyEconomy) econ).getSupportedCurrencies()) {
                String normalized = curr.toLowerCase();
                if (!normalized.equals("default") && !currenciesList.contains(normalized)) {
                    currenciesList.add(normalized);
                }
            }
        }

        // Balances
        for (String curr : currenciesList) {
            int slot = -1;
            if (guiCfg != null) {
                slot = guiCfg.getInt("currencies." + curr + ".slot", -1);
            }
            if (slot == -1) {
                if (curr.equals("default")) slot = 10;
                else if (curr.equals("gems")) slot = 11;
                else if (curr.equals("tokens")) slot = 12;
            }

            if (slot < 0 || slot >= size) {
                continue;
            }

            double bal = 0;
            if (curr.equals("default")) {
                bal = defaultBal;
            } else if (econ instanceof MultiCurrencyEconomy) {
                bal = ((MultiCurrencyEconomy) econ).getCurrencyBalance(player, curr);
            }

            Map<String, String> reps = new HashMap<>();
            reps.put("%balance%", curr.equals("default") ? econ.format(bal) : String.format("%.2f", bal));

            Material defMat = GOLD_INGOT;
            String defName = curr.substring(0, 1).toUpperCase() + curr.substring(1).toLowerCase();
            String[] defLore = new String[]{"§fBalance: §7" + (curr.equals("default") ? econ.format(bal) : String.format("%.2f", bal))};

            if (curr.equals("gems")) {
                defMat = EMERALD;
                defName = "§a§lGems";
                defLore = new String[]{"§fBalance: §a" + String.format("%.2f", bal)};
            } else if (curr.equals("tokens")) {
                defMat = DIAMOND;
                defName = "§b§lTokens";
                defLore = new String[]{"§fBalance: §b" + String.format("%.2f", bal)};
            }

            String configPath = "currencies." + curr;
            if (guiCfg == null || !guiCfg.contains(configPath)) {
                if (curr.equals("default") || curr.equals("gems") || curr.equals("tokens")) {
                    configPath = "dashboard.items." + curr + "-currency";
                }
            }

            inv.setItem(slot, loadItem(configPath, defMat, 0, defName, defLore, reps));
        }

        // Navigation
        inv.setItem(getSlot("dashboard.items.exchange", 14), loadItem("dashboard.items.exchange", GOLD_NUGGET, 0, "§6§lCurrency Exchange",
                new String[]{"§7Click to exchange your currencies", "§7in real time (Forex)."}, null));
        inv.setItem(getSlot("dashboard.items.history", 15), loadItem("dashboard.items.history", PAPER, 0, "§f§lTransaction History",
                new String[]{"§7Click to view your", "§710 most recent transactions."}, null));
        inv.setItem(getSlot("dashboard.items.mailbox", 16), loadItem("dashboard.items.mailbox", CHEST, 0, "§d§lMailbox",
                new String[]{"§7Click to claim your", "§7received offline payments."}, null));

        // Extra dynamic sections
        inv.setItem(getSlot("dashboard.items.stocks", 20), loadItem("dashboard.items.stocks", GOLD_INGOT, 0, "§e§lStock Market & Shares",
                new String[]{"§7Buy and sell commodities", "§7and bank shares in real time."}, null));
        inv.setItem(getSlot("dashboard.items.escrow", 22), loadItem("dashboard.items.escrow", BOOK, 0, "§b§lEscrow Management",
                new String[]{"§7View your secure transactions", "§7pending release."}, null));
        inv.setItem(getSlot("dashboard.items.loans", 24), loadItem("dashboard.items.loans", PAPER, 0, "§c§lLoans & Guild Credit",
                new String[]{"§7Manage your active loans and repay", "§7your outstanding debts."}, null));
        inv.setItem(getSlot("dashboard.items.blackmarket", 25), loadItem("dashboard.items.blackmarket", BARRIER, 0, "§8§lBlack Market & Laundering",
                new String[]{"§7Launder dirty money", "§7into clean cash."}, null));
        inv.setItem(getSlot("dashboard.items.discord-link", 17), loadItem("dashboard.items.discord-link", BOOK, 0, "§9§lDiscord Integration",
                new String[]{"§7Link your account to Discord", "§7to manage balance & payouts."}, null));

        fillBorder(inv);
        player.openInventory(inv);
    }

    // --- BLACK MARKET GUI ---
    public void openBlackMarket(Player player) {
        if (Vault.getBlackMarketManager() == null) return;
        net.milkbowl.vault.economy.BlackMarketManager bm = Vault.getBlackMarketManager();
        Economy econ = getEconomy();

        String title = getGuiTitle("blackmarket", "§8§lMarché Noir - Blanchiment");
        int size = 27;
        YamlConfiguration guiCfg = Vault.getGuiConfig();
        if (guiCfg != null) {
            size = guiCfg.getInt("blackmarket.size", 27);
        }
        Inventory inv = Bukkit.createInventory(new GUIBlackMarketHolder(), size, title);

        double dirty = bm.getDirtyBalance(player);
        double clean = econ != null ? econ.getBalance(player) : 0;

        Map<String, String> reps = new HashMap<>();
        reps.put("%dirty%", econ != null ? econ.format(dirty) : String.format("%.2f", dirty));
        reps.put("%clean%", econ != null ? econ.format(clean) : String.format("%.2f", clean));

        double feePct = plugin.getConfig().getDouble("blackmarket.laundering-fee-percent", 20.0);
        double riskPct = plugin.getConfig().getDouble("blackmarket.seizure-risk-percent", 5.0);

        reps.put("%fee%", String.format("%.1f", feePct));
        reps.put("%risk%", String.format("%.1f", riskPct));

        Map<String, String> reps100 = new HashMap<>(reps);
        reps100.put("%fee_amount%", econ != null ? econ.format(100 * feePct / 100.0) : String.format("%.2f", 100 * feePct / 100.0));
        inv.setItem(getSlot("blackmarket.items.launder-100", 12), loadItem("blackmarket.items.launder-100", PAPER, 0, "§e§lLaunder 100$", new String[]{"§eClick to launder 100$"}, reps100));

        Map<String, String> reps1000 = new HashMap<>(reps);
        reps1000.put("%fee_amount%", econ != null ? econ.format(1000 * feePct / 100.0) : String.format("%.2f", 1000 * feePct / 100.0));
        inv.setItem(getSlot("blackmarket.items.launder-1000", 13), loadItem("blackmarket.items.launder-1000", PAPER, 0, "§e§lLaunder 1,000$", new String[]{"§eClick to launder 1,000$"}, reps1000));

        Map<String, String> repsAll = new HashMap<>(reps);
        repsAll.put("%fee_amount%", econ != null ? econ.format(dirty * feePct / 100.0) : String.format("%.2f", dirty * feePct / 100.0));
        inv.setItem(getSlot("blackmarket.items.launder-all", 14), loadItem("blackmarket.items.launder-all", GOLD_BLOCK, 0, "§a§lLaunder ALL Dirty Money", new String[]{"§eClick to launder all"}, repsAll));

        inv.setItem(getSlot("blackmarket.items.dirty-info", 11), loadItem("blackmarket.items.dirty-info", REDSTONE, 0, "§c§lDirty Money", new String[]{"§fDirty Balance: " + dirty}, reps));
        inv.setItem(getSlot("blackmarket.items.clean-info", 15), loadItem("blackmarket.items.clean-info", EMERALD, 0, "§a§lClean Cash", new String[]{"§fClean Balance: " + clean}, reps));

        fillBorder(inv);
        player.openInventory(inv);
    }

    public void openStocks(Player player) {
        String title = getGuiTitle("stocks", "§d§lStock & Bank Market");
        Inventory inv = Bukkit.createInventory(new GUIStocksHolder(), 36, title);
        LocalFailoverManager fm = Vault.getFailoverManager();
        if (fm == null)
            return;

        net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
            double goldPrice = fm.getCommodityPrice("gold");
            double ironPrice = fm.getCommodityPrice("iron");
            double emeraldPrice = fm.getCommodityPrice("emerald");
            double diamondPrice = fm.getCommodityPrice("diamond");

            double goldShares = fm.getPlayerStockShares(player.getUniqueId(), "gold");
            double ironShares = fm.getPlayerStockShares(player.getUniqueId(), "iron");
            double emeraldShares = fm.getPlayerStockShares(player.getUniqueId(), "emerald");
            double diamondShares = fm.getPlayerStockShares(player.getUniqueId(), "diamond");

            List<String> playerBanks = fm.getBanksForPlayer(player.getUniqueId());
            Map<String, Double> bankShares = new HashMap<>();
            Map<String, Double> bankBalances = new HashMap<>();
            Map<String, Double> bankSharePrices = new HashMap<>();

            Economy econ = getEconomy();

            for (String bank : playerBanks) {
                double shares = fm.getPlayerStockShares(player.getUniqueId(), "bank:" + bank.toLowerCase());
                bankShares.put(bank, shares);

                double bal = 0.0;
                if (econ != null) {
                    bal = econ.bankBalance(bank).balance;
                    if (bal == 0.0 && !econ.bankBalance(bank).transactionSuccess()) {
                        bal = fm.getBankBalance(bank);
                    }
                }
                bankBalances.put(bank, bal);
                bankSharePrices.put(bank, Math.max(10.0, bal / 100.0));
            }

            net.milkbowl.vault.util.FoliaScheduler.runSync(plugin, () -> {
                inv.setItem(10, loadCommodityItem("gold", GOLD_INGOT, "§e§lGold", goldPrice, goldShares, econ));
                inv.setItem(12, loadCommodityItem("iron", IRON_INGOT, "§f§lIron", ironPrice, ironShares, econ));
                inv.setItem(14, loadCommodityItem("emerald", EMERALD, "§a§lEmerald", emeraldPrice, emeraldShares, econ));
                inv.setItem(16, loadCommodityItem("diamond", DIAMOND, "§b§lDiamond", diamondPrice, diamondShares, econ));

                int slot = 28;
                for (String bank : playerBanks) {
                    if (slot > 34)
                        break;
                    double shares = bankShares.get(bank);
                    double bal = bankBalances.get(bank);
                    double sharePrice = bankSharePrices.get(bank);

                    inv.setItem(slot++, loadBankSharesItem(bank, shares, bal, sharePrice, econ));
                }

                inv.setItem(getSlot("back-item", 31), loadItem("back-item", BARRIER, 0, "§cBack", new String[]{"§7Return to the dashboard"}, null));
                fillBorder(inv);
                player.openInventory(inv);
            });
        });
    }

    public void openLoans(Player player) {
        String title = getGuiTitle("loans", "§c§lYour Loans & Credits");
        Inventory inv = Bukkit.createInventory(new GUILoansHolder(), 36, title);
        LocalFailoverManager fm = Vault.getFailoverManager();
        if (fm == null)
            return;

        net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
            List<LocalFailoverManager.LoanRecord> loans = fm.getLoansForPlayer(player.getUniqueId());
            double debt = fm.getPlayerDebt(player.getUniqueId());
            Economy econ = getEconomy();

            net.milkbowl.vault.util.FoliaScheduler.runSync(plugin, () -> {
                int slot = 10;
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("dd/MM HH:mm");

                for (LocalFailoverManager.LoanRecord loan : loans) {
                    if (slot > 16 && slot < 19)
                        slot = 19;
                    if (slot > 25)
                        break;

                    boolean active = loan.status.equalsIgnoreCase("ACTIVE");
                    String color = active ? "§a" : "§7";
                    String nextBillingStr = active ? sdf.format(new Date(loan.nextBilling)) : "Expired/Paid";

                    Map<String, String> reps = new HashMap<>();
                    reps.put("%id%", loan.id);
                    reps.put("%bank%", loan.bankName.toUpperCase());
                    reps.put("%principal%", econ != null ? econ.format(loan.principal) : String.valueOf(loan.principal));
                    reps.put("%remaining%", econ != null ? econ.format(loan.remaining) : String.valueOf(loan.remaining));
                    reps.put("%interest%", (loan.interestRate * 100.0) + "%");
                    reps.put("%next%", nextBillingStr);
                    reps.put("%status%", loan.status);

                    String[] defLore = new String[] {
                        "§7Lending bank: §f" + loan.bankName.toUpperCase(),
                        "§7Borrowed amount: §a" + (econ != null ? econ.format(loan.principal) : loan.principal),
                        "§7Remaining to pay (with interest): " + color + (econ != null ? econ.format(loan.remaining) : loan.remaining),
                        "§7Interest rate: §f" + (loan.interestRate * 100.0) + "%",
                        "§7Next installment: §f" + nextBillingStr,
                        "§7Status: §f" + loan.status,
                        "",
                        "§e▶ Left Click: §fRepay 1 installment",
                        "§e▶ Shift + Click: §fRepay in full"
                    };

                    inv.setItem(slot++, loadItem("loans.items.loan-entry", PAPER, 0, "§e§lLoan ID: " + loan.id, defLore, reps));
                }

                if (debt > 0.0) {
                    Map<String, String> reps = new HashMap<>();
                    reps.put("%debt%", econ != null ? econ.format(debt) : String.valueOf(debt));

                    String[] defLore = new String[] {
                        "§7Total pending debt: §e" + (econ != null ? econ.format(debt) : debt),
                        "§7This debt will be automatically deducted",
                        "§7from your next Payday salary."
                    };

                    inv.setItem(22, loadItem("loans.items.salary-garnishment", BARRIER, 0, "§c§lSalary Garnishments", defLore, reps));
                }

                inv.setItem(getSlot("back-item", 31), loadItem("back-item", BARRIER, 0, "§cBack", new String[]{"§7Return to the dashboard"}, null));
                fillBorder(inv);
                player.openInventory(inv);
            });
        });
    }

    public void openAdminStats(Player player) {
        String title = getGuiTitle("admin_stats", "§b§lMacroeconomic Stats");
        Inventory inv = Bukkit.createInventory(new GUIAdminStatsHolder(), 36, title);

        net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
            LocalFailoverManager fm = Vault.getFailoverManager();
            RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
            if (fm == null || rsp == null) return;
            Economy econ = rsp.getProvider();

            List<String> currenciesList = new ArrayList<>();
            currenciesList.add("default");
            if (econ instanceof MultiCurrencyEconomy) {
                for (String curr : ((MultiCurrencyEconomy) econ).getSupportedCurrencies()) {
                    String normalized = curr.toLowerCase();
                    if (!normalized.equals("default") && !currenciesList.contains(normalized)) {
                        currenciesList.add(normalized);
                    }
                }
            }

            java.util.Map<String, LocalFailoverManager.LocalCurrencyStats> currencyStats = new java.util.HashMap<>();
            net.milkbowl.vault.redis.VaultRedisManager redis = net.milkbowl.vault.redis.VaultRedisManager.getInstance();
            boolean redisOnline = redis != null && redis.isOnline();

            for (String curr : currenciesList) {
                if (redisOnline) {
                    net.milkbowl.vault.redis.VaultRedisManager.CurrencyStats stats = redis.getGlobalCurrencyStats(curr);
                    if (stats != null) {
                        currencyStats.put(curr, new LocalFailoverManager.LocalCurrencyStats(stats.totalMoney, stats.accountsCount, stats.averageBalance));
                    } else {
                        currencyStats.put(curr, fm.getLocalCurrencyStats(curr));
                    }
                } else {
                    currencyStats.put(curr, fm.getLocalCurrencyStats(curr));
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

            net.milkbowl.vault.util.FoliaScheduler.runSync(plugin, () -> {
                int currSlot = 10;
                for (String curr : currenciesList) {
                    if (currSlot > 12) break;
                    LocalFailoverManager.LocalCurrencyStats stats = currencyStats.get(curr);
                    double total = stats != null ? stats.totalMoney : 0.0;
                    long accs = stats != null ? stats.accountsCount : 0;
                    double avg = stats != null ? stats.averageBalance : 0.0;

                    String formatTotal = curr.equals("default") ? econ.format(total) : String.format("%.2f %s", total, curr.toUpperCase());
                    String formatAvg = curr.equals("default") ? econ.format(avg) : String.format("%.2f %s", avg, curr.toUpperCase());

                    Material mat = GOLD_INGOT;
                    String displayName = "§e§l" + curr.substring(0, 1).toUpperCase() + curr.substring(1).toLowerCase() + " Supply";
                    if (curr.equals("gems")) mat = EMERALD;
                    else if (curr.equals("tokens")) mat = DIAMOND;

                    String[] lore = new String[] {
                        "§fGlobal Supply: §a" + formatTotal,
                        "§fTotal Accounts: §e" + accs,
                        "§fAverage Balance: §b" + formatAvg
                    };

                    inv.setItem(currSlot, createGuiItem(mat, 1, (short) 0, displayName, lore));
                    currSlot++;
                }

                String formattedCbBal = econ.format(cbBalance);
                Map<String, String> cbReps = new HashMap<>();
                cbReps.put("%treasury%", treasuryAccount);
                cbReps.put("%balance%", formattedCbBal);
                inv.setItem(getSlot("admin_stats.items.central-bank", 14), loadItem("admin_stats.items.central-bank", GOLD_BLOCK, 0, "§6§lCentral Bank Reserve", new String[] {
                    "§fTreasury Account: §7" + treasuryAccount,
                    "§fReserve Balance: §e" + formattedCbBal
                }, cbReps));

                Map<String, String> debtReps = new HashMap<>();
                debtReps.put("%loans_debt%", econ.format(bankLoansDebt));
                debtReps.put("%player_debts%", econ.format(playerDebts));
                debtReps.put("%total_debt%", econ.format(totalDebt));
                inv.setItem(getSlot("admin_stats.items.debts", 15), loadItem("admin_stats.items.debts", REDSTONE, 0, "§c§lMacroeconomic Debts", new String[] {
                    "§fActive Bank Loans: §7" + econ.format(bankLoansDebt),
                    "§fGarnishment Debts: §7" + econ.format(playerDebts),
                    "§c§lTotal Debts: §e" + econ.format(totalDebt)
                }, debtReps));

                List<String> richLore = new java.util.ArrayList<>();
                richLore.add("§7Top 10 richest players (Default):");
                int rank = 1;
                for (net.milkbowl.vault.redis.VaultRedisManager.LeaderboardEntry entry : leaderboard) {
                    if (rank > 10) break;
                    String entryBal = econ.format(entry.balance);
                    richLore.add(String.format("§e#%d  §f%s  §8»  §a%s", rank, entry.name, entryBal));
                    rank++;
                }
                if (leaderboard.isEmpty()) {
                    richLore.add("§cNo data available");
                }
                inv.setItem(getSlot("admin_stats.items.leaderboard", 16), loadItem("admin_stats.items.leaderboard", Material.BOOK, 0, "§d§lWealth Leaderboard", richLore.toArray(new String[0]), null));

                inv.setItem(getSlot("back-item", 31), loadItem("back-item", BARRIER, 0, "§cBack", new String[]{"§7Return to the dashboard"}, null));

                fillBorder(inv);
                player.openInventory(inv);
            });
        });
    }

    // --- 2. TRANSACTION HISTORY ---
    public void openHistory(Player player) {
        String title = getGuiTitle("history", "§8Transaction History");
        Inventory inv = Bukkit.createInventory(new GUIHistoryHolder(), 36, title);

        net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
            List<PlayerTransactionRecord> txs = Vault.getFailoverManager().getPlayerTransactions(player.getUniqueId(),
                    1, 10);

            net.milkbowl.vault.util.FoliaScheduler.runSync(plugin, () -> {
                int slot = 10;
                for (PlayerTransactionRecord tx : txs) {
                    if (slot > 16 && slot < 19)
                        slot = 19;
                    if (slot > 25)
                        break;

                    String color = tx.type.startsWith("DEPOSIT") ? "§a" : "§c";
                    String prefix = tx.type.startsWith("DEPOSIT") ? "+" : "-";
                    String party = tx.otherParty != null ? tx.otherParty : "System";

                    Date date = new Date(tx.timestamp);
                    java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("dd/MM HH:mm");

                    Map<String, String> reps = new HashMap<>();
                    reps.put("%color%", color);
                    reps.put("%prefix%", prefix);
                    String cleanType = tx.type.replace("DEPOSIT_", "").replace("WITHDRAW_", "").toUpperCase();
                    reps.put("%type%", cleanType);
                    reps.put("%amount%", String.format("%.2f", tx.amount));
                    reps.put("%currency%", tx.currency.toUpperCase());
                    reps.put("%party%", party);
                    reps.put("%category%", tx.category);
                    reps.put("%date%", sdf.format(date));

                    String defaultName = color + "§l" + cleanType;
                    String[] defaultLore = new String[] {
                        "§fAmount: " + color + prefix + String.format("%.2f", tx.amount) + " " + tx.currency.toUpperCase(),
                        "§fParty: §7" + party,
                        "§fCategory: §7" + tx.category,
                        "§fDate: §7" + sdf.format(date)
                    };

                    Material defaultMat = tx.type.startsWith("DEPOSIT") ? EMERALD : PAPER;
                    Material mat = defaultMat;
                    YamlConfiguration guiCfg = Vault.getGuiConfig();
                    if (guiCfg != null) {
                        String configMatKey = tx.type.startsWith("DEPOSIT") ? "deposit-material" : "withdraw-material";
                        String matStr = guiCfg.getString("history.items.history-entry." + configMatKey);
                        if (matStr != null) {
                            try {
                                mat = Material.valueOf(matStr.toUpperCase());
                            } catch (Exception e) {}
                        }
                    }

                    inv.setItem(slot++, loadItem("history.items.history-entry", mat, 0, defaultName, defaultLore, reps));
                }

                inv.setItem(getSlot("back-item", 31), loadItem("back-item", BARRIER, 0, "§cBack", new String[]{"§7Return to the dashboard"}, null));
                fillBorder(inv);
                player.openInventory(inv);
            });
        });
    }

    // --- 3. CURRENCY EXCHANGE ---
    public void openExchange(Player player) {
        UUID uuid = player.getUniqueId();
        selectedFrom.putIfAbsent(uuid, "default");

        Economy econ = getEconomy();
        String secondCurr = "gems";
        if (econ instanceof MultiCurrencyEconomy) {
            List<String> supported = ((MultiCurrencyEconomy) econ).getSupportedCurrencies();
            if (!supported.contains(secondCurr)) {
                for (String curr : supported) {
                    if (!curr.equalsIgnoreCase("default")) {
                        secondCurr = curr.toLowerCase();
                        break;
                    }
                }
            }
        }
        selectedTo.putIfAbsent(uuid, secondCurr);

        String from = selectedFrom.get(uuid);
        String to = selectedTo.get(uuid);

        String title = getGuiTitle("exchange", "§6Dynamic Currency Exchange");
        Inventory inv = Bukkit.createInventory(new GUIExchangeHolder(), 27, title);

        // Currencies selectors items
        inv.setItem(11, getCurrencySelectorItem("FROM", from));
        inv.setItem(15, getCurrencySelectorItem("TO", to));

        // Center convert button
        ExchangeRateManager rateManager = Vault.getExchangeRateManager();
        double fromRate = rateManager.getRate(from);
        double toRate = rateManager.getRate(to);
        double unitRate = fromRate / toRate;

        double taxPct = plugin.getConfig().getDouble("central-bank.taxes.exchange-tax-percent", 1.0);
        boolean taxesEnabled = plugin.getConfig().getBoolean("central-bank.taxes.enabled", false);

        Map<String, String> reps = new HashMap<>();
        reps.put("%from%", from.toUpperCase());
        reps.put("%to%", to.toUpperCase());
        reps.put("%rate%", String.format("%.4f", unitRate));
        reps.put("%tax%", taxesEnabled ? taxPct + "%" : "0%");

        String[] defLore = new String[] {
            "§7Current rate: §f1 " + from.toUpperCase() + " = §e" + String.format("%.4f", unitRate) + " " + to.toUpperCase(),
            "§7Exchange fee: §f" + (taxesEnabled ? taxPct + "%" : "0%"),
            "",
            "§e▶ Left Click: §fConvert 10 " + from.toUpperCase(),
            "§e▶ Right Click: §fConvert 100 " + from.toUpperCase(),
            "§e▶ Shift + Left: §fConvert 1000 " + from.toUpperCase(),
            "§e▶ Shift + Right: §fConvert 1 " + from.toUpperCase()
        };

        inv.setItem(13, loadItem("exchange.items.convert", GOLD_NUGGET, 0, "§6§lCONVERT !", defLore, reps));

        inv.setItem(getSlot("back-item", 22), loadItem("back-item", BARRIER, 0, "§cBack", new String[]{"§7Return to the dashboard"}, null));

        fillBorder(inv);
        player.openInventory(inv);
    }

    private ItemStack getCurrencySelectorItem(String prefix, String currency) {
        Material mat = GOLD_INGOT;
        String name = currency.substring(0, 1).toUpperCase() + currency.substring(1).toLowerCase();

        if (currency.equalsIgnoreCase("gems")) {
            mat = EMERALD;
            name = "§a§lGems";
        } else if (currency.equalsIgnoreCase("tokens")) {
            mat = DIAMOND;
            name = "§b§lTokens";
        }

        YamlConfiguration guiCfg = Vault.getGuiConfig();
        if (guiCfg != null) {
            String cPath = "currencies." + currency.toLowerCase();
            String matStr = guiCfg.getString(cPath + ".material");
            if (matStr != null) {
                try {
                    mat = Material.valueOf(matStr.toUpperCase());
                } catch (Exception e) {}
            }
            String nameFromConfig = guiCfg.getString(cPath + ".name");
            if (nameFromConfig != null) {
                name = ChatColor.translateAlternateColorCodes('&', nameFromConfig);
            }
        }

        Map<String, String> reps = new HashMap<>();
        reps.put("%prefix%", prefix);
        reps.put("%name%", name);

        String defaultName = "§fConvert " + prefix + " : " + name;
        String[] defaultLore = new String[] { "§7Click to change currency" };

        return loadItem("exchange.items.selector", mat, 0, defaultName, defaultLore, reps);
    }

    private void cycleCurrency(UUID uuid, boolean isFrom) {
        Economy econ = getEconomy();
        List<String> list = new ArrayList<>();
        list.add("default");
        if (econ instanceof MultiCurrencyEconomy) {
            for (String curr : ((MultiCurrencyEconomy) econ).getSupportedCurrencies()) {
                String normalized = curr.toLowerCase();
                if (!normalized.equals("default") && !list.contains(normalized)) {
                    list.add(normalized);
                }
            }
        }
        if (list.isEmpty()) {
            list = Arrays.asList("default");
        }

        Map<UUID, String> map = isFrom ? selectedFrom : selectedTo;
        String current = map.getOrDefault(uuid, isFrom ? "default" : (list.size() > 1 ? list.get(1) : "default"));
        int index = list.indexOf(current.toLowerCase());
        if (index == -1) index = 0;
        int nextIndex = (index + 1) % list.size();
        map.put(uuid, list.get(nextIndex));
    }

    // --- 4. ESCROWS ---
    public void openEscrow(Player player) {
        String title = getGuiTitle("escrow", "§8Escrow Management");
        Inventory inv = Bukkit.createInventory(new GUIEscrowHolder(), 36, title);

        EscrowManager escrowManager = Vault.getEscrowManager();
        if (escrowManager == null)
            return;

        escrowManager.listEscrows(player).thenAccept(escrows -> {
            net.milkbowl.vault.util.FoliaScheduler.runSync(plugin, () -> {
                int slot = 10;
                for (EscrowDetails esc : escrows) {
                    if (slot > 16 && slot < 19)
                        slot = 19;
                    if (slot > 25)
                        break;

                    boolean isSender = esc.sender.equals(player.getUniqueId());
                    String senderName = UUIDCache.getName(esc.sender);
                    String receiverName = UUIDCache.getName(esc.receiver);
                    long remainingSec = Math.max(0, (esc.timeoutAt - System.currentTimeMillis()) / 1000);

                    Map<String, String> reps = new HashMap<>();
                    reps.put("%id%", esc.id);
                    reps.put("%short_id%", esc.id.substring(0, 8));
                    reps.put("%sender%", senderName != null ? senderName : "Unknown");
                    reps.put("%receiver%", receiverName != null ? receiverName : "Unknown");
                    reps.put("%amount%", String.format("%.2f", esc.amount));
                    reps.put("%currency%", esc.currency.toUpperCase());
                    reps.put("%status%", esc.status);
                    reps.put("%time%", String.valueOf(remainingSec));

                    Material defaultMat = isSender ? RED_WOOL() : GREEN_WOOL();
                    short defaultData = (short) 0;
                    if (defaultMat.name().equals("WOOL")) {
                        defaultData = (short) (isSender ? 14 : 13);
                    }

                    Material mat = defaultMat;
                    short data = defaultData;
                    YamlConfiguration guiCfg = Vault.getGuiConfig();
                    if (guiCfg != null) {
                        String key = isSender ? "sender-material" : "receiver-material";
                        String matStr = guiCfg.getString("escrow.items.escrow-entry." + key);
                        if (matStr != null) {
                            try {
                                mat = Material.valueOf(matStr.toUpperCase());
                                data = (short) guiCfg.getInt("escrow.items.escrow-entry." + (isSender ? "sender-data" : "receiver-data"), 0);
                            } catch (Exception e) {}
                        } else {
                            matStr = guiCfg.getString("escrow.items.escrow-entry.material");
                            if (matStr != null) {
                                try {
                                    mat = Material.valueOf(matStr.toUpperCase());
                                    data = (short) guiCfg.getInt("escrow.items.escrow-entry.data", 0);
                                } catch (Exception e) {}
                            }
                        }
                    }

                    String[] defaultLoreArray = new String[] {
                        "§fID: §7" + esc.id,
                        "§fSender: §7" + (senderName != null ? senderName : "Unknown"),
                        "§fReceiver: §7" + (receiverName != null ? receiverName : "Unknown"),
                        "§fAmount: §e" + String.format("%.2f", esc.amount) + " " + esc.currency.toUpperCase(),
                        "§fStatus: §7" + esc.status,
                        "§fTime remaining: §7" + remainingSec + "s"
                    };

                    ItemStack item = loadItem("escrow.items.escrow-entry", mat, data, "§6Escrow : " + esc.id.substring(0, 8), defaultLoreArray, reps);

                    // Add dynamic actions to lore
                    List<String> actionLines = new ArrayList<>();
                    if (esc.status.equalsIgnoreCase("PENDING")) {
                        if (isSender) {
                            if (guiCfg != null && guiCfg.contains("escrow.items.escrow-entry.lore-actions.sender-pending")) {
                                List<String> list = guiCfg.getStringList("escrow.items.escrow-entry.lore-actions.sender-pending");
                                for (String s : list) {
                                    actionLines.add(ChatColor.translateAlternateColorCodes('&', s));
                                }
                            } else {
                                actionLines.add("§e▶ Left click: §aRelease funds");
                                actionLines.add("§e▶ Right click: §cRefund (if expired)");
                            }
                        } else {
                            if (guiCfg != null && guiCfg.contains("escrow.items.escrow-entry.lore-actions.receiver-pending")) {
                                List<String> list = guiCfg.getStringList("escrow.items.escrow-entry.lore-actions.receiver-pending");
                                for (String s : list) {
                                    actionLines.add(ChatColor.translateAlternateColorCodes('&', s));
                                }
                            } else {
                                actionLines.add("§e▶ Left click: §cReturn to sender");
                            }
                        }
                    }

                    ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
                        List<String> currentLore = meta.getLore();
                        if (currentLore == null) {
                            currentLore = new ArrayList<>();
                        } else {
                            currentLore = new ArrayList<>(currentLore);
                        }
                        if (!actionLines.isEmpty()) {
                            currentLore.add("");
                            for (String line : actionLines) {
                                currentLore.add(line);
                            }
                        }
                        meta.setLore(currentLore);
                        item.setItemMeta(meta);
                    }

                    inv.setItem(slot++, item);
                }

                inv.setItem(getSlot("back-item", 31), loadItem("back-item", BARRIER, 0, "§cBack", new String[]{"§7Return to the dashboard"}, null));
                fillBorder(inv);
                player.openInventory(inv);
            });
        });
    }

    private Material RED_WOOL() {
        return getMaterial("RED_WOOL", "WOOL");
    }

    private Material GREEN_WOOL() {
        return getMaterial("GREEN_WOOL", "WOOL");
    }

    // --- 5. MAILBOX ---
    public void openMailbox(Player player) {
        String title = getGuiTitle("mailbox", "§dEconomic Mailbox");
        Inventory inv = Bukkit.createInventory(new GUIMailboxHolder(), 36, title);

        MailboxManager mailboxManager = Vault.getMailboxManager();
        if (mailboxManager == null)
            return;

        net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
            List<MailRecord> mails = mailboxManager.getPendingMail(player.getUniqueId());

            net.milkbowl.vault.util.FoliaScheduler.runSync(plugin, () -> {
                int slot = 10;
                for (MailRecord mail : mails) {
                    if (slot > 16 && slot < 19)
                        slot = 19;
                    if (slot > 25)
                        break;

                    Date date = new Date(mail.timestamp);
                    java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("dd/MM HH:mm");

                    Map<String, String> reps = new HashMap<>();
                    reps.put("%sender%", mail.senderName);
                    reps.put("%amount%", String.format("%.2f", mail.amount));
                    reps.put("%currency%", mail.currency.toUpperCase());
                    reps.put("%message%", mail.message);
                    reps.put("%date%", sdf.format(date));

                    String defaultName = "§dPayment from: §f" + mail.senderName;
                    String[] defaultLore = new String[] {
                        "§fAmount: §e" + String.format("%.2f", mail.amount) + " " + mail.currency.toUpperCase(),
                        "§fMessage: §7" + mail.message,
                        "§fReceived on: §7" + sdf.format(date),
                        "",
                        "§e▶ Click to claim these funds"
                    };

                    inv.setItem(slot++, loadItem("mailbox.items.mail-entry", CHEST, 0, defaultName, defaultLore, reps));
                }

                inv.setItem(getSlot("back-item", 31), loadItem("back-item", BARRIER, 0, "§cBack", new String[]{"§7Return to the dashboard"}, null));
                if (!mails.isEmpty()) {
                    inv.setItem(getSlot("mailbox.items.claim-all", 32), loadItem("mailbox.items.claim-all", GOLD_NUGGET, 0, "§a§lCLAIM ALL",
                            new String[]{"§7Claim all pending funds"}, null));
                }
                fillBorder(inv);
                player.openInventory(inv);
            });
        });
    }

    // --- INVENTORY DRAG EVENT HANDLER ---
    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder == null) return;

        boolean matches = (holder instanceof GUIDashboardHolder ||
                holder instanceof GUIHistoryHolder ||
                holder instanceof GUIExchangeHolder ||
                holder instanceof GUIEscrowHolder ||
                holder instanceof GUIMailboxHolder ||
                holder instanceof GUIStocksHolder ||
                holder instanceof GUILoansHolder ||
                holder instanceof GUIAdminStatsHolder ||
                holder instanceof GUIBlackMarketHolder);

        if (matches) {
            event.setCancelled(true);
        }
    }

    // --- INVENTORY CLICK EVENT HANDLER ---
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder == null)
            return;

        boolean matches = (holder instanceof GUIDashboardHolder ||
                holder instanceof GUIHistoryHolder ||
                holder instanceof GUIExchangeHolder ||
                holder instanceof GUIEscrowHolder ||
                holder instanceof GUIMailboxHolder ||
                holder instanceof GUIStocksHolder ||
                holder instanceof GUILoansHolder ||
                holder instanceof GUIAdminStatsHolder ||
                holder instanceof GUIBlackMarketHolder);

        if (!matches)
            return;

        event.setCancelled(true); // Complete exploit protection!

        if (!(event.getWhoClicked() instanceof Player))
            return;
        Player player = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();

        if (event.getCurrentItem() == null || event.getCurrentItem().getType() == Material.AIR)
            return;

        // Sound feedback
        try {
            Sound clickSound = null;
            try {
                clickSound = Sound.valueOf("BLOCK_WOODEN_BUTTON_CLICK_ON");
            } catch (Exception e1) {
                try {
                    clickSound = Sound.valueOf("WOOD_CLICK");
                } catch (Exception e2) {
                }
            }
            if (clickSound != null) {
                player.playSound(player.getLocation(), clickSound, 0.5f, 1.0f);
            }
        } catch (Exception ignored) {
        }

        // --- ADMIN STATS CLICK ---
        if (holder instanceof GUIAdminStatsHolder) {
            if (slot == getSlot("back-item", 31)) {
                openDashboard(player);
            }
            return;
        }

        // --- DASHBOARD CLICK ---
        if (holder instanceof GUIDashboardHolder) {
            if (slot == getSlot("dashboard.items.exchange", 14)) {
                openExchange(player);
            } else if (slot == getSlot("dashboard.items.history", 15)) {
                openHistory(player);
            } else if (slot == getSlot("dashboard.items.mailbox", 16)) {
                openMailbox(player);
            } else if (slot == getSlot("dashboard.items.stocks", 20)) {
                openStocks(player);
            } else if (slot == getSlot("dashboard.items.escrow", 22)) {
                openEscrow(player);
            } else if (slot == getSlot("dashboard.items.loans", 24)) {
                openLoans(player);
            } else if (slot == getSlot("dashboard.items.blackmarket", 25)) {
                openBlackMarket(player);
            } else if (slot == getSlot("dashboard.items.discord-link", 17)) {
                player.closeInventory();
                if (Vault.getDiscordManager() != null) {
                    String code = Vault.getDiscordManager().generateLinkCode(player.getUniqueId());
                    if (code == null) {
                        player.sendMessage(Vault.getMessage("discord.link-code-ratelimit", "&c[Discord] &fVeuillez attendre 1 minute avant de générer un nouveau code."));
                    } else {
                        long expire = plugin.getConfig().getLong("discord.account-linking.link-code-expire-minutes", 10);
                        player.sendMessage(Vault.getMessage("discord.link-code-generated", "&a&l[Discord] &fYour verification link code is: &e&l%code% &7(expires in %expire% minutes).")
                                .replace("%code%", code)
                                .replace("%expire%", String.valueOf(expire)));
                    }
                }
            }
            return;
        }

        // --- BLACK MARKET CLICK ---
        if (holder instanceof GUIBlackMarketHolder) {
            if (Vault.getBlackMarketManager() != null) {
                net.milkbowl.vault.economy.BlackMarketManager bm = Vault.getBlackMarketManager();
                double dirty = bm.getDirtyBalance(player);
                double amountToLaunder = 0;

                if (slot == getSlot("blackmarket.items.launder-100", 12)) {
                    amountToLaunder = 100.0;
                } else if (slot == getSlot("blackmarket.items.launder-1000", 13)) {
                    amountToLaunder = 1000.0;
                } else if (slot == getSlot("blackmarket.items.launder-all", 14)) {
                    amountToLaunder = dirty;
                }

                if (amountToLaunder > 0) {
                    net.milkbowl.vault.economy.BlackMarketManager.LaunderingResult res = bm.launder(player, amountToLaunder, getEconomy());
                    if (!res.isSuccess()) {
                        player.sendMessage(Vault.getMessage("blackmarket.insufficient-dirty", "&cYou do not have enough dirty money to launder that amount!"));
                    } else if (res.isSeized()) {
                        player.sendMessage(Vault.getMessage("blackmarket.launder-seized", "&c&l🚨 [POLICE RAID] &cPolice intercepted your transaction! &c%seized% &cof dirty money was confiscated!")
                                .replace("%seized%", getEconomy() != null ? getEconomy().format(res.getDirtyLaundered()) : String.valueOf(res.getDirtyLaundered())));
                    } else {
                        player.sendMessage(Vault.getMessage("blackmarket.launder-success", "&a&l✔ [Black Market] &aSuccessfully laundered &c%dirty% &aof dirty money! Deposited &a%clean% &aclean cash into your account (Fee: &e%fee%&a).")
                                .replace("%dirty%", getEconomy() != null ? getEconomy().format(res.getDirtyLaundered()) : String.valueOf(res.getDirtyLaundered()))
                                .replace("%clean%", getEconomy() != null ? getEconomy().format(res.getCleanReceived()) : String.valueOf(res.getCleanReceived()))
                                .replace("%fee%", getEconomy() != null ? getEconomy().format(res.getFeePaid()) : String.valueOf(res.getFeePaid())));
                    }
                    openBlackMarket(player);
                }
            }
            return;
        }

        // --- HISTORY CLICK ---
        if (holder instanceof GUIHistoryHolder) {
            if (slot == getSlot("back-item", 31)) {
                openDashboard(player);
            }
            return;
        }

        // --- EXCHANGE CLICK ---
        if (holder instanceof GUIExchangeHolder) {
            if (slot == getSlot("back-item", 22)) {
                openDashboard(player);
                return;
            }

            UUID uuid = player.getUniqueId();
            if (slot == 11) {
                cycleCurrency(uuid, true);
                if (selectedFrom.get(uuid).equals(selectedTo.get(uuid))) {
                    cycleCurrency(uuid, false); // Prevent same conversion
                }
                openExchange(player);
            } else if (slot == 15) {
                cycleCurrency(uuid, false);
                if (selectedFrom.get(uuid).equals(selectedTo.get(uuid))) {
                    cycleCurrency(uuid, true); // Prevent same conversion
                }
                openExchange(player);
            } else if (slot == 13) {
                // Determine conversion amount based on click type
                double amount = 10.0;
                if (event.isRightClick()) {
                    amount = event.isShiftClick() ? 1.0 : 100.0;
                } else {
                    amount = event.isShiftClick() ? 1000.0 : 10.0;
                }

                String from = selectedFrom.get(uuid);
                String to = selectedTo.get(uuid);

                double finalAmount = amount;
                ConversionResult res = Vault.getExchangeRateManager().convert(player, from, to, finalAmount);
                if (res.success) {
                    player.sendMessage(Vault.getMessage("commands.stocks.menu-confirm-success", "&a&l✔ &a%message%")
                            .replace("%message%", res.message));
                    VaultXVisuals.sendTransactionNotification(player, to, res.receivedAmount, true);
                    openExchange(player);
                } else {
                    player.sendMessage(Vault.getMessage("commands.stocks.menu-confirm-failure", "&c&l❌ &c%message%")
                            .replace("%message%", res.message));
                    VaultXVisuals.playFailureSound(player);
                }
            }
            return;
        }

        // --- ESCROW CLICK ---
        if (holder instanceof GUIEscrowHolder) {
            if (slot == getSlot("back-item", 31)) {
                openDashboard(player);
                return;
            }

            EscrowManager escrowManager = Vault.getEscrowManager();
            if (escrowManager == null)
                return;

            // Fetch escrows again to align with the slot
            escrowManager.listEscrows(player).thenAccept(escrows -> {
                net.milkbowl.vault.util.FoliaScheduler.runSync(plugin, () -> {
                    int listSlot = 10;
                    for (EscrowDetails esc : escrows) {
                        if (listSlot > 16 && listSlot < 19)
                            listSlot = 19;
                        if (listSlot > 25)
                            break;

                        if (listSlot == slot) {
                            boolean isSender = esc.sender.equals(player.getUniqueId());
                            if (esc.status.equalsIgnoreCase("PENDING")) {
                                if (isSender) {
                                    if (event.isLeftClick()) {
                                        // Release escrow
                                        escrowManager.releaseEscrow(esc.id, player).thenAccept(res -> {
                                            player.sendMessage(
                                                    res.success ? "§a§l✔ §a" + res.message : "§c§l❌ §c" + res.message);
                                            net.milkbowl.vault.util.FoliaScheduler.runSync(plugin, () -> openEscrow(player));
                                        });
                                    } else {
                                        // Refund escrow
                                        escrowManager.refundEscrow(esc.id, player).thenAccept(res -> {
                                            player.sendMessage(
                                                    res.success ? "§a§l✔ §a" + res.message : "§c§l❌ §c" + res.message);
                                            net.milkbowl.vault.util.FoliaScheduler.runSync(plugin, () -> openEscrow(player));
                                        });
                                    }
                                } else {
                                    if (event.isLeftClick()) {
                                        // Receiver refund request (return to sender)
                                        escrowManager.refundEscrow(esc.id, player).thenAccept(res -> {
                                            player.sendMessage(
                                                    res.success ? "§a§l✔ §a" + res.message : "§c§l❌ §c" + res.message);
                                            net.milkbowl.vault.util.FoliaScheduler.runSync(plugin, () -> openEscrow(player));
                                        });
                                    }
                                }
                            }
                            break;
                        }
                        listSlot++;
                    }
                });
            });
            return;
        }

        // --- MAILBOX CLICK ---
        if (holder instanceof GUIMailboxHolder) {
            if (slot == getSlot("back-item", 31)) {
                openDashboard(player);
                return;
            }

            MailboxManager mailboxManager = Vault.getMailboxManager();
            if (mailboxManager == null)
                return;

            if (slot == getSlot("mailbox.items.claim-all", 32)) {
                mailboxManager.claimAllMail(player);
                net.milkbowl.vault.util.FoliaScheduler.runLater(plugin, () -> openMailbox(player), 10L);
                return;
            }

            net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
                List<MailRecord> mails = mailboxManager.getPendingMail(player.getUniqueId());
                net.milkbowl.vault.util.FoliaScheduler.runSync(plugin, () -> {
                    int listSlot = 10;
                    for (MailRecord mail : mails) {
                        if (listSlot > 16 && listSlot < 19)
                            listSlot = 19;
                        if (listSlot > 25)
                            break;

                        if (listSlot == slot) {
                            mailboxManager.claimMailRecord(player, mail);
                            net.milkbowl.vault.util.FoliaScheduler.runLater(plugin, () -> openMailbox(player), 5L);
                            break;
                        }
                        listSlot++;
                    }
                });
            });
        }

        // --- STOCKS CLICK ---
        if (holder instanceof GUIStocksHolder) {
            if (slot == getSlot("back-item", 31)) {
                openDashboard(player);
                return;
            }

            LocalFailoverManager fm = Vault.getFailoverManager();
            RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
            Economy econ = rsp != null ? rsp.getProvider() : null;
            if (fm == null || econ == null)
                return;

            double amount = event.isShiftClick() ? 10.0 : 1.0;
            boolean buy = event.isLeftClick();

            if (slot == 10 || slot == 12 || slot == 14 || slot == 16) {
                String commodity = "gold";
                if (slot == 12)
                    commodity = "iron";
                else if (slot == 14)
                    commodity = "emerald";
                else if (slot == 16)
                    commodity = "diamond";

                final String finalComm = commodity;
                final double finalAmt = amount;

                net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
                    double price = fm.getCommodityPrice(finalComm);
                    if (price <= 0.0)
                        return;

                    double currentShares = fm.getPlayerStockShares(player.getUniqueId(), finalComm);

                    net.milkbowl.vault.util.FoliaScheduler.runSync(plugin, () -> {
                        if (buy) {
                            double cost = price * finalAmt;
                            double bal = econ.getBalance(player);
                            if (bal < cost) {
                                player.sendMessage(Vault.getMessage("commands.stocks.insufficient-funds", "§cYou do not have enough funds (Cost: %cost%).")
                                        .replace("%cost%", econ.format(cost)));
                                VaultXVisuals.playFailureSound(player);
                                return;
                            }

                            EconomyResponse wRes = econ.withdrawPlayer(player, cost);
                            if (wRes.transactionSuccess()) {
                                double newShares = currentShares + finalAmt;
                                double newPrice = price * (1.0 + 0.0005 * finalAmt);

                                net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
                                    fm.updatePlayerStockShares(player.getUniqueId(), finalComm, newShares);
                                    fm.updateCommodityPrice(finalComm, newPrice);
                                    fm.savePlayerTransaction(player.getUniqueId(), "WITHDRAW_STOCK_BUY", "default", cost,
                                            finalComm.toUpperCase());
                                });

                                player.sendMessage(Vault.getMessage("commands.stocks.buy-success", "§a§l✔ §aSuccessfully purchased %amount% shares of %commodity%!")
                                        .replace("%amount%", String.valueOf(finalAmt))
                                        .replace("%commodity%", finalComm.toUpperCase()));
                                openStocks(player);
                            } else {
                                player.sendMessage(Vault.getMessage("commands.stocks.tx-failed", "&cTransaction failed: %error%")
                                        .replace("%error%", wRes.errorMessage));
                                VaultXVisuals.playFailureSound(player);
                            }
                        } else { // sell
                            if (currentShares < finalAmt) {
                                player.sendMessage(Vault.getMessage("commands.stocks.insufficient-shares", "§cYou do not own enough shares (Available: %available%).")
                                        .replace("%available%", String.valueOf(currentShares)));
                                VaultXVisuals.playFailureSound(player);
                                return;
                            }

                            double payout = price * finalAmt;
                            EconomyResponse dRes = econ.depositPlayer(player, payout);
                            if (dRes.transactionSuccess()) {
                                double newShares = currentShares - finalAmt;
                                double newPrice = Math.max(1.0, price * (1.0 - 0.0005 * finalAmt));

                                net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
                                    fm.updatePlayerStockShares(player.getUniqueId(), finalComm, newShares);
                                    fm.updateCommodityPrice(finalComm, newPrice);
                                    fm.savePlayerTransaction(player.getUniqueId(), "DEPOSIT_STOCK_SELL", "default", payout,
                                            finalComm.toUpperCase());
                                });

                                player.sendMessage(Vault.getMessage("commands.stocks.sell-success", "§a§l✔ §aSuccessfully sold %amount% shares of %commodity%!")
                                        .replace("%amount%", String.valueOf(finalAmt))
                                        .replace("%commodity%", finalComm.toUpperCase()));
                                openStocks(player);
                            } else {
                                player.sendMessage(Vault.getMessage("commands.stocks.tx-failed", "&cTransaction failed: %error%")
                                        .replace("%error%", dRes.errorMessage));
                                VaultXVisuals.playFailureSound(player);
                            }
                        }
                    });
                });
            } else if (slot >= 28 && slot <= 34) {
                ItemStack clickedItem = event.getCurrentItem();
                if (clickedItem == null || clickedItem.getType() != Material.CHEST)
                    return;

                final double finalAmt = amount;

                net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
                    List<String> playerBanks = fm.getBanksForPlayer(player.getUniqueId());
                    int bankIdx = slot - 28;
                    String bankName = null;
                    if (bankIdx >= 0 && bankIdx < playerBanks.size()) {
                        bankName = playerBanks.get(bankIdx);
                    } else if (clickedItem.hasItemMeta() && clickedItem.getItemMeta().hasDisplayName()) {
                        String display = ChatColor.stripColor(clickedItem.getItemMeta().getDisplayName());
                        if (display.startsWith("Shares: ")) {
                            bankName = display.substring(8).toLowerCase();
                        }
                    }
                    if (bankName == null) return;
                    final String finalBank = bankName;

                    double dbBankBal = fm.getBankBalance(finalBank);
                    double outstandingShares = fm.getOutstandingBankShares(finalBank);
                    double currentShares = fm.getPlayerStockShares(player.getUniqueId(), "bank:" + finalBank);

                    net.milkbowl.vault.util.FoliaScheduler.runSync(plugin, () -> {
                        double bankBal = econ.bankBalance(finalBank).balance;
                        if (bankBal == 0.0 && !econ.bankBalance(finalBank).transactionSuccess()) {
                            bankBal = dbBankBal;
                        }
                        double sharePrice = Math.max(10.0, bankBal / 100.0);

                        if (buy) {
                            double cost = sharePrice * finalAmt;
                            double bal = econ.getBalance(player);
                            if (bal < cost) {
                                player.sendMessage(Vault.getMessage("commands.stocks.insufficient-funds", "§cYou do not have enough funds (Cost: %cost%).")
                                        .replace("%cost%", econ.format(cost)));
                                VaultXVisuals.playFailureSound(player);
                                return;
                            }

                            if (outstandingShares + finalAmt > 100.0) {
                                player.sendMessage(Vault.getMessage("commands.stocks.bank-outstanding-limit",
                                        "§cCannot buy: global limit of 100 bank shares reached."));
                                VaultXVisuals.playFailureSound(player);
                                return;
                            }

                            EconomyResponse wRes = econ.withdrawPlayer(player, cost);
                            if (wRes.transactionSuccess()) {
                                econ.bankDeposit(finalBank, cost);
                                double newBankBal = bankBal + cost;
                                double newShares = currentShares + finalAmt;

                                net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
                                    fm.saveBankBalance(finalBank, newBankBal);
                                    fm.updatePlayerStockShares(player.getUniqueId(), "bank:" + finalBank, newShares);
                                    fm.savePlayerTransaction(player.getUniqueId(), "WITHDRAW_BANK_SHARE_BUY", "default", cost,
                                            finalBank.toUpperCase());
                                });

                                player.sendMessage(Vault.getMessage("commands.stocks.bank-buy-success", "§a§l✔ §aSuccessfully purchased %amount% shares of bank %bank%!")
                                        .replace("%amount%", String.valueOf(finalAmt))
                                        .replace("%bank%", finalBank.toUpperCase()));
                                openStocks(player);
                            } else {
                                player.sendMessage(Vault.getMessage("commands.stocks.pay-failed", "&cPayment failed: %error%")
                                        .replace("%error%", wRes.errorMessage));
                                VaultXVisuals.playFailureSound(player);
                            }
                        } else { // sell
                            if (currentShares < finalAmt) {
                                player.sendMessage(Vault.getMessage("commands.stocks.insufficient-shares", "§cYou do not own enough shares (Available: %available%).")
                                        .replace("%available%", String.valueOf(currentShares)));
                                VaultXVisuals.playFailureSound(player);
                                return;
                            }

                            double payout = sharePrice * finalAmt;
                            if (bankBal < payout) {
                                player.sendMessage(Vault.getMessage("commands.stocks.bank-insufficient-liquidity",
                                        "§cThe bank does not have enough liquidity to buy back your shares."));
                                VaultXVisuals.playFailureSound(player);
                                return;
                            }

                            EconomyResponse wRes = econ.bankWithdraw(finalBank, payout);
                            if (wRes.transactionSuccess()) {
                                econ.depositPlayer(player, payout);
                                double newBankBal = Math.max(0.0, bankBal - payout);
                                double newShares = currentShares - finalAmt;

                                net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
                                    fm.saveBankBalance(finalBank, newBankBal);
                                    fm.updatePlayerStockShares(player.getUniqueId(), "bank:" + finalBank, newShares);
                                    fm.savePlayerTransaction(player.getUniqueId(), "DEPOSIT_BANK_SHARE_SELL", "default", payout,
                                            finalBank.toUpperCase());
                                });

                                player.sendMessage(Vault.getMessage("commands.stocks.bank-sell-success", "§a§l✔ §aSuccessfully sold %amount% shares of bank %bank%!")
                                        .replace("%amount%", String.valueOf(finalAmt))
                                        .replace("%bank%", finalBank.toUpperCase()));
                                openStocks(player);
                            } else {
                                player.sendMessage(Vault.getMessage("commands.stocks.buyback-failed", "&cBuyback failed: %error%")
                                        .replace("%error%", wRes.errorMessage));
                                VaultXVisuals.playFailureSound(player);
                            }
                        }
                    });
                });
            }
            return;
        }

        // --- LOANS CLICK ---
        if (holder instanceof GUILoansHolder) {
            if (slot == getSlot("back-item", 31)) {
                openDashboard(player);
                return;
            }

            LocalFailoverManager fm = Vault.getFailoverManager();
            RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
            Economy econ = rsp != null ? rsp.getProvider() : null;
            if (fm == null || econ == null)
                return;

            ItemStack clickedItem = event.getCurrentItem();
            if (clickedItem == null || clickedItem.getType() != Material.PAPER)
                return;

            final int clickedSlot = slot;
            String displayLoanId = null;
            if (clickedItem.hasItemMeta() && clickedItem.getItemMeta().hasDisplayName()) {
                String display = ChatColor.stripColor(clickedItem.getItemMeta().getDisplayName());
                if (display.startsWith("Loan ID: ")) {
                    displayLoanId = display.substring(9).trim();
                }
            }
            final String fallbackLoanId = displayLoanId;

            net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
                List<LocalFailoverManager.LoanRecord> loans = fm.getLoansForPlayer(player.getUniqueId());
                LocalFailoverManager.LoanRecord active = null;

                int listSlot = 10;
                int loanIndex = -1;
                for (int i = 0; i < loans.size(); i++) {
                    if (listSlot > 16 && listSlot < 19) listSlot = 19;
                    if (listSlot > 25) break;
                    if (listSlot == clickedSlot) {
                        loanIndex = i;
                        break;
                    }
                    listSlot++;
                }

                if (loanIndex >= 0 && loanIndex < loans.size()) {
                    LocalFailoverManager.LoanRecord lr = loans.get(loanIndex);
                    if (lr.status.equalsIgnoreCase("ACTIVE")) {
                        active = lr;
                    }
                } else if (fallbackLoanId != null) {
                    for (LocalFailoverManager.LoanRecord lr : loans) {
                        if (lr.id.equalsIgnoreCase(fallbackLoanId) && lr.status.equalsIgnoreCase("ACTIVE")) {
                            active = lr;
                            break;
                        }
                    }
                }

                if (active == null)
                    return;

                final LocalFailoverManager.LoanRecord finalActive = active;
                final double dbBankBal = fm.getBankBalance(active.bankName);

                net.milkbowl.vault.util.FoliaScheduler.runSync(plugin, () -> {
                    double repaymentAmount;
                    boolean payFull = event.isShiftClick();
                    double installment = (finalActive.principal * (1.0 + finalActive.interestRate)) / 10.0;
                    if (payFull) {
                        repaymentAmount = finalActive.remaining;
                    } else {
                        repaymentAmount = Math.min(finalActive.remaining, installment);
                    }

                    double bal = econ.getBalance(player);
                    if (bal < repaymentAmount) {
                        player.sendMessage(Vault.getMessage("commands.loan.repay-insufficient", "§cYou do not have enough funds for this repayment (Requires: %amount%).")
                                .replace("%amount%", econ.format(repaymentAmount)));
                        VaultXVisuals.playFailureSound(player);
                        return;
                    }

                    final double finalRepaymentAmount = repaymentAmount;
                    EconomyResponse wRes = econ.withdrawPlayer(player, repaymentAmount);
                    if (wRes.transactionSuccess()) {
                        econ.bankDeposit(finalActive.bankName, repaymentAmount);

                        double newBankBal = dbBankBal + repaymentAmount;
                        double newRemaining = Math.max(0.0, finalActive.remaining - repaymentAmount);
                        String newStatus = newRemaining <= 0.0 ? "PAID" : "ACTIVE";

                        net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
                            fm.saveBankBalance(finalActive.bankName, newBankBal);
                            fm.updateLoanRemaining(finalActive.id, newRemaining, newStatus);
                            fm.savePlayerTransaction(player.getUniqueId(), "WITHDRAW_LOAN_REPAY", "default", finalRepaymentAmount,
                                    finalActive.bankName.toUpperCase());
                        });

                        player.sendMessage(Vault.getMessage("commands.loan.repay-success", "§a§l✔ §aRepayment of %amount% successfully made for the loan!")
                                .replace("%amount%", econ.format(repaymentAmount)));
                        openLoans(player);
                    } else {
                        player.sendMessage(Vault.getMessage("commands.loan.repay-failed", "§cTransaction failed: %error%")
                                .replace("%error%", wRes.errorMessage));
                        VaultXVisuals.playFailureSound(player);
                    }
                });
            });
            return;
        }
    }
}

