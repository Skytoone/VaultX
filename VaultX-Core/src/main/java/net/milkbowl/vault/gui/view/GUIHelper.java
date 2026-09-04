package net.milkbowl.vault.gui.view;

import java.util.*;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;

import net.milkbowl.vault.Vault;
import net.milkbowl.vault.economy.Economy;

/**
 * Shared utility methods and material compatibility resolvers for VaultX GUI Views.
 */
public class GUIHelper {

    public static final Material GLASS_PANE = getMaterial("GRAY_STAINED_GLASS_PANE", "STAINED_GLASS_PANE");
    public static final Material CHEST = getMaterial("CHEST");
    public static final Material PAPER = getMaterial("PAPER");
    public static final Material GOLD_INGOT = getMaterial("GOLD_INGOT");
    public static final Material IRON_INGOT = getMaterial("IRON_INGOT");
    public static final Material EMERALD = getMaterial("EMERALD");
    public static final Material DIAMOND = getMaterial("DIAMOND");
    public static final Material GOLD_NUGGET = getMaterial("GOLD_NUGGET");
    public static final Material BOOK = getMaterial("BOOK");
    public static final Material BARRIER = getMaterial("BARRIER", "REDSTONE");
    public static final Material GOLD_BLOCK = getMaterial("GOLD_BLOCK");
    public static final Material REDSTONE = getMaterial("REDSTONE");

    public static Material getMaterial(String... names) {
        for (String name : names) {
            try {
                return Material.valueOf(name);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return Material.STONE;
    }

    public static Material RED_WOOL() {
        return getMaterial("RED_WOOL", "WOOL");
    }

    public static Material GREEN_WOOL() {
        return getMaterial("GREEN_WOOL", "WOOL");
    }

    public static Economy getEconomy() {
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        return rsp != null ? rsp.getProvider() : null;
    }

    public static ItemStack createGuiItem(Material mat, int amount, short data, String name, String... lore) {
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

    public static String getGuiTitle(String configPath, String defaultTitle) {
        YamlConfiguration guiCfg = Vault.getGuiConfig();
        if (guiCfg != null) {
            String title = guiCfg.getString(configPath + ".title");
            if (title != null) {
                return ChatColor.translateAlternateColorCodes('&', title);
            }
        }
        return defaultTitle;
    }

    public static int getSlot(String configPath, int defaultSlot) {
        YamlConfiguration guiCfg = Vault.getGuiConfig();
        if (guiCfg != null && guiCfg.contains(configPath + ".slot")) {
            return guiCfg.getInt(configPath + ".slot");
        }
        return defaultSlot;
    }

    public static ItemStack loadItem(String configPath, Material defaultMat, int defaultData, String defaultName, String[] defaultLore, Map<String, String> replacements) {
        YamlConfiguration guiCfg = Vault.getGuiConfig();
        Material mat = defaultMat;
        short data = (short) defaultData;
        String name = defaultName;
        String[] lore = defaultLore != null ? defaultLore : new String[0];

        if (guiCfg != null) {
            String matStr = guiCfg.getString(configPath + ".material");
            if (matStr != null) {
                try {
                    mat = Material.valueOf(matStr.toUpperCase());
                } catch (Exception ignored) {}
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

    private static ItemStack cachedFiller;

    public static ItemStack getFillerItem() {
        if (cachedFiller == null) {
            cachedFiller = loadItem("filler-item", GLASS_PANE, 15, "§7", new String[0], null);
        }
        return cachedFiller.clone();
    }

    public static void fillBorder(Inventory inv) {
        ItemStack filler = getFillerItem();
        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) == null) {
                inv.setItem(i, filler);
            }
        }
    }
}
