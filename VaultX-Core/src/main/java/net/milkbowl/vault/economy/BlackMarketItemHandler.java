package net.milkbowl.vault.economy;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class BlackMarketItemHandler {

    private final Plugin plugin;
    public static final String DIRTY_LORE_MARKER = "§8[vaultx:dirty_money]";

    public BlackMarketItemHandler(Plugin plugin) {
        this.plugin = plugin;
    }

    public double countDirtyItems(Player player) {
        double count = 0;
        String matName = plugin.getConfig().getString("blackmarket.item.material", "PAPER");
        Material mat = Material.matchMaterial(matName);
        if (mat == null)
            mat = Material.PAPER;

        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == mat && item.hasItemMeta()) {
                ItemMeta meta = item.getItemMeta();
                if (meta != null && isDirtyMoneyItem(meta)) {
                    count += item.getAmount() * 100.0;
                }
            }
        }
        return count;
    }

    public void giveDirtyItem(Player player, double amount) {
        String matName = plugin.getConfig().getString("blackmarket.item.material", "PAPER");
        Material mat = Material.matchMaterial(matName);
        if (mat == null)
            mat = Material.PAPER;

        int itemCount = (int) Math.floor(amount / 100.0);
        if (itemCount <= 0) return;

        int maxStack = mat.getMaxStackSize() > 0 ? mat.getMaxStackSize() : 64;

        while (itemCount > 0) {
            int stackSize = Math.min(itemCount, maxStack);
            ItemStack item = new ItemStack(mat, stackSize);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                String title = plugin.getConfig().getString("blackmarket.item.name", "&c&lMarked Dirty Money");
                meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', title));
                List<String> rawLore = plugin.getConfig().getStringList("blackmarket.item.lore");
                List<String> lore = new ArrayList<>();
                for (String l : rawLore) {
                    lore.add(ChatColor.translateAlternateColorCodes('&', l));
                }
                lore.add(DIRTY_LORE_MARKER);
                meta.setLore(lore);
                int cmd = plugin.getConfig().getInt("blackmarket.item.custom-model-data", 1001);
                if (cmd > 0) {
                    try {
                        java.lang.reflect.Method m = meta.getClass().getMethod("setCustomModelData", Integer.class);
                        m.invoke(meta, cmd);
                    } catch (Throwable ignored) {}
                }
                item.setItemMeta(meta);
            }

            Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
            for (ItemStack left : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), left);
            }
            itemCount -= stackSize;
        }
    }

    public boolean removeDirtyItems(Player player, double amount) {
        String matName = plugin.getConfig().getString("blackmarket.item.material", "PAPER");
        Material mat = Material.matchMaterial(matName);
        if (mat == null)
            mat = Material.PAPER;

        int itemsToRemove = (int) Math.ceil(amount / 100.0);
        if (itemsToRemove <= 0) return false;

        if (countDirtyItems(player) < itemsToRemove * 100.0) {
            return false;
        }

        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item != null && item.getType() == mat && item.hasItemMeta()) {
                ItemMeta meta = item.getItemMeta();
                if (meta != null && isDirtyMoneyItem(meta)) {
                    int count = item.getAmount();
                    if (count <= itemsToRemove) {
                        itemsToRemove -= count;
                        player.getInventory().setItem(i, null);
                    } else {
                        item.setAmount(count - itemsToRemove);
                        itemsToRemove = 0;
                    }
                    if (itemsToRemove <= 0)
                        break;
                }
            }
        }
        return itemsToRemove <= 0;
    }

    public boolean isDirtyMoneyItem(ItemMeta meta) {
        if (!meta.hasLore())
            return false;
        List<String> lore = meta.getLore();
        return lore != null && lore.contains(DIRTY_LORE_MARKER);
    }
}
