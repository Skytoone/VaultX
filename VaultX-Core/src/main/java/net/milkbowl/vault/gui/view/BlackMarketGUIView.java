package net.milkbowl.vault.gui.view;

import java.util.HashMap;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;

import net.milkbowl.vault.Vault;
import net.milkbowl.vault.economy.BlackMarketManager;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.util.VaultXGUI;
import net.milkbowl.vault.util.VaultXGUI.GUIBlackMarketHolder;

import static net.milkbowl.vault.gui.view.GUIHelper.*;

public class BlackMarketGUIView {

    private final Plugin plugin;

    public BlackMarketGUIView(Plugin plugin) {
        this.plugin = plugin;
    }

    public void openBlackMarket(Player player) {
        if (Vault.getBlackMarketManager() == null) return;
        BlackMarketManager bm = Vault.getBlackMarketManager();
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

    public void handleClick(Player player, int slot, VaultXGUI gui) {
        if (Vault.getBlackMarketManager() != null) {
            BlackMarketManager bm = Vault.getBlackMarketManager();
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
                Economy econ = getEconomy();
                BlackMarketManager.LaunderingResult res = bm.launder(player, amountToLaunder, econ);
                if (!res.isSuccess()) {
                    player.sendMessage(Vault.getMessage("blackmarket.insufficient-dirty", "&cYou do not have enough dirty money to launder that amount!"));
                } else if (res.isSeized()) {
                    player.sendMessage(Vault.getMessage("blackmarket.launder-seized", "&c&l🚨 [POLICE RAID] &cPolice intercepted your transaction! &c%seized% &cof dirty money was confiscated!")
                            .replace("%seized%", econ != null ? econ.format(res.getDirtyLaundered()) : String.valueOf(res.getDirtyLaundered())));
                } else {
                    player.sendMessage(Vault.getMessage("blackmarket.launder-success", "&a&l✔ [Black Market] &aSuccessfully laundered &c%dirty% &aof dirty money! Deposited &a%clean% &aclean cash into your account (Fee: &e%fee%&a).")
                            .replace("%dirty%", econ != null ? econ.format(res.getDirtyLaundered()) : String.valueOf(res.getDirtyLaundered()))
                            .replace("%clean%", econ != null ? econ.format(res.getCleanReceived()) : String.valueOf(res.getCleanReceived()))
                            .replace("%fee%", econ != null ? econ.format(res.getFeePaid()) : String.valueOf(res.getFeePaid())));
                }
                openBlackMarket(player);
            }
        }
    }
}
