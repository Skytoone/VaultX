package net.milkbowl.vault.economy;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.OfflinePlayer;
import org.bukkit.Bukkit;
import net.milkbowl.vault.economy.events.VaultBlackMarketLaunderEvent;

import net.milkbowl.vault.util.StripedLock;

public class BlackMarketManager implements VaultBlackMarketAPI {

    private final Plugin plugin;
    private final SecureRandom random = new SecureRandom();
    private final Map<UUID, Double> dirtyBalances = new ConcurrentHashMap<>();
    private final StripedLock stripedLock = new StripedLock();

    private static final String DIRTY_LORE_MARKER = "§8[vaultx:dirty_money]";

    private java.util.concurrent.locks.ReentrantLock getPlayerLock(UUID uuid) {
        return stripedLock.getLock(uuid);
    }

    public static class LaunderingResult extends VaultBlackMarketAPI.LaunderingResult {
        public LaunderingResult(boolean success, boolean seized, double dirtyLaundered, double cleanReceived,
                double feePaid) {
            super(success, seized, dirtyLaundered, cleanReceived, feePaid);
        }
    }

    public BlackMarketManager(Plugin plugin) {
        this.plugin = plugin;
    }

    public String getMode() {
        return plugin.getConfig().getString("blackmarket.dirty-money-mode", "CURRENCY").toUpperCase();
    }

    public double getDirtyBalance(Player player) {
        if (player == null)
            return 0.0;
        if (getMode().equals("ITEM")) {
            return countDirtyItems(player);
        } else {
            return dirtyBalances.getOrDefault(player.getUniqueId(), 0.0);
        }
    }

    @Override
    public double getDirtyBalance(OfflinePlayer player) {
        if (player == null)
            return 0.0;
        if (player.isOnline() && player.getPlayer() != null) {
            return getDirtyBalance(player.getPlayer());
        }
        return dirtyBalances.getOrDefault(player.getUniqueId(), 0.0);
    }

    @Override
    public void setDirtyBalance(OfflinePlayer player, double amount) {
        if (player == null)
            return;
        dirtyBalances.put(player.getUniqueId(), Math.max(0.0, amount));
    }

    public void depositDirty(Player player, double amount) {
        if (player == null || amount <= 0)
            return;
        if (getMode().equals("ITEM")) {
            giveDirtyItem(player, amount);
        } else {
            dirtyBalances.merge(player.getUniqueId(), amount, Double::sum);
        }
    }

    public boolean withdrawDirty(Player player, double amount) {
        if (player == null || amount <= 0)
            return false;
        double current = getDirtyBalance(player);
        if (current < amount)
            return false;

        if (getMode().equals("ITEM")) {
            return removeDirtyItems(player, amount);
        } else {
            dirtyBalances.put(player.getUniqueId(), current - amount);
            return true;
        }
    }

    @Override
    public void addDirtyMoney(OfflinePlayer player, double amount) {
        if (player == null || amount <= 0)
            return;
        if (player.isOnline() && player.getPlayer() != null) {
            depositDirty(player.getPlayer(), amount);
        } else {
            dirtyBalances.merge(player.getUniqueId(), amount, Double::sum);
        }
    }

    @Override
    public LaunderingResult launder(Player player, double dirtyAmount) {
        return launder(player, dirtyAmount, net.milkbowl.vault.Vault.getWrappedEconomies().isEmpty() ? null
                : net.milkbowl.vault.Vault.getWrappedEconomies().get(0));
    }

    public LaunderingResult launder(Player player, double dirtyAmount, Economy econ) {
        if (player == null || dirtyAmount <= 0) {
            return new LaunderingResult(false, false, 0, 0, 0);
        }

        VaultBlackMarketLaunderEvent event = new VaultBlackMarketLaunderEvent(player, dirtyAmount);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return new LaunderingResult(false, false, 0, 0, 0);
        }
        dirtyAmount = event.getDirtyAmount();

        double maxAllowed = plugin.getConfig().getDouble("blackmarket.max-per-transaction", 500000.0);
        if (maxAllowed > 0 && dirtyAmount > maxAllowed) {
            return new LaunderingResult(false, false, 0, 0, 0);
        }

        double feePercent = plugin.getConfig().getDouble("blackmarket.laundering-fee-percent", 20.0);
        double seizureRisk = plugin.getConfig().getDouble("blackmarket.seizure-risk-percent", 5.0);

        // Synchronized per-player to prevent race condition / double-spend exploit
        synchronized (getPlayerLock(player.getUniqueId())) {
            double currentDirty = getDirtyBalance(player);
            if (currentDirty < dirtyAmount) {
                return new LaunderingResult(false, false, 0, 0, 0);
            }

            if (!withdrawDirty(player, dirtyAmount)) {
                return new LaunderingResult(false, false, 0, 0, 0);
            }

            // Determine outcome AFTER the dirty money is already withdrawn
            boolean isSeized = (random.nextDouble() * 100.0) < seizureRisk;

            if (isSeized) {
                return new LaunderingResult(true, true, dirtyAmount, 0, 0);
            }

            double fee = dirtyAmount * (feePercent / 100.0);
            double cleanReceived = dirtyAmount - fee;

            if (econ != null) {
                econ.depositPlayer(player, cleanReceived);
            }

            return new LaunderingResult(true, false, dirtyAmount, cleanReceived, fee);
        }
    }

    private double countDirtyItems(Player player) {
        double count = 0;
        String matName = plugin.getConfig().getString("blackmarket.item.material", "PAPER");
        Material mat = Material.matchMaterial(matName);
        if (mat == null)
            mat = Material.PAPER;

        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == mat && item.hasItemMeta()) {
                ItemMeta meta = item.getItemMeta();
                // Use hidden lore marker to prevent display-name rename exploit
                if (meta != null && isDirtyMoneyItem(meta)) {
                    count += item.getAmount() * 100.0; // 1 item = 100$ dirty money value
                }
            }
        }
        return count;
    }

    private void giveDirtyItem(Player player, double amount) {
        String matName = plugin.getConfig().getString("blackmarket.item.material", "PAPER");
        Material mat = Material.matchMaterial(matName);
        if (mat == null)
            mat = Material.PAPER;

        int itemCount = Math.max(1, (int) Math.round(amount / 100.0));
        ItemStack item = new ItemStack(mat, itemCount);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            String title = plugin.getConfig().getString("blackmarket.item.name", "&c&lMarked Dirty Money");
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', title));
            List<String> rawLore = plugin.getConfig().getStringList("blackmarket.item.lore");
            List<String> lore = new ArrayList<>();
            for (String l : rawLore) {
                lore.add(ChatColor.translateAlternateColorCodes('&', l));
            }
            // Append hidden marker lore line to prevent rename exploits via anvil
            lore.add(DIRTY_LORE_MARKER);
            meta.setLore(lore);
            int cmd = plugin.getConfig().getInt("blackmarket.item.custom-model-data", 1001);
            if (cmd > 0) {
                try {
                    java.lang.reflect.Method m = meta.getClass().getMethod("setCustomModelData", Integer.class);
                    m.invoke(meta, cmd);
                } catch (Throwable ignored) {
                }
            }
            item.setItemMeta(meta);
        }

        Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
        for (ItemStack left : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), left);
        }
    }

    private boolean removeDirtyItems(Player player, double amount) {
        String matName = plugin.getConfig().getString("blackmarket.item.material", "PAPER");
        Material mat = Material.matchMaterial(matName);
        if (mat == null)
            mat = Material.PAPER;

        int itemsToRemove = Math.max(1, (int) Math.round(amount / 100.0));
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

    /**
     * Checks for the hidden lore marker to identify legitimate dirty money items.
     * Using the display name alone is exploitable via anvil renaming.
     */
    private boolean isDirtyMoneyItem(ItemMeta meta) {
        if (!meta.hasLore())
            return false;
        List<String> lore = meta.getLore();
        return lore != null && lore.contains(DIRTY_LORE_MARKER);
    }

    public void cleanupPlayer(UUID uuid) {
        if (uuid != null) {
            dirtyBalances.remove(uuid);
        }
    }

    public void close() {
        dirtyBalances.clear();
    }
}
