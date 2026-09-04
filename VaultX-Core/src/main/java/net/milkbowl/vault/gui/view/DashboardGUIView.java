package net.milkbowl.vault.gui.view;

import java.util.*;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import net.milkbowl.vault.Vault;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.MultiCurrencyEconomy;
import net.milkbowl.vault.util.VaultXGUI;
import net.milkbowl.vault.util.VaultXGUI.GUIDashboardHolder;

import static net.milkbowl.vault.gui.view.GUIHelper.*;

public class DashboardGUIView {

    private final Plugin plugin;

    public DashboardGUIView(Plugin plugin) {
        this.plugin = plugin;
    }

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

    public void handleClick(Player player, int slot, VaultXGUI gui) {
        if (slot == getSlot("dashboard.items.exchange", 14)) {
            gui.openExchange(player);
        } else if (slot == getSlot("dashboard.items.history", 15)) {
            gui.openHistory(player);
        } else if (slot == getSlot("dashboard.items.mailbox", 16)) {
            gui.openMailbox(player);
        } else if (slot == getSlot("dashboard.items.stocks", 20)) {
            gui.openStocks(player);
        } else if (slot == getSlot("dashboard.items.escrow", 22)) {
            gui.openEscrow(player);
        } else if (slot == getSlot("dashboard.items.loans", 24)) {
            gui.openLoans(player);
        } else if (slot == getSlot("dashboard.items.blackmarket", 25)) {
            gui.openBlackMarket(player);
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
    }
}
