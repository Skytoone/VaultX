package net.milkbowl.vault.gui.view;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;

import net.milkbowl.vault.Vault;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.ExchangeRateManager;
import net.milkbowl.vault.economy.ExchangeRateManager.ConversionResult;
import net.milkbowl.vault.economy.MultiCurrencyEconomy;
import net.milkbowl.vault.util.VaultXGUI;
import net.milkbowl.vault.util.VaultXGUI.GUIExchangeHolder;
import net.milkbowl.vault.util.VaultXVisuals;

import static net.milkbowl.vault.gui.view.GUIHelper.*;

public class ExchangeGUIView {

    private final org.bukkit.plugin.Plugin plugin;
    private final Map<UUID, String> selectedFrom = new ConcurrentHashMap<>();
    private final Map<UUID, String> selectedTo = new ConcurrentHashMap<>();

    public ExchangeGUIView(org.bukkit.plugin.Plugin plugin) {
        this.plugin = plugin;
    }

    public void clearPlayer(UUID uuid) {
        selectedFrom.remove(uuid);
        selectedTo.remove(uuid);
    }

    public void clearAll() {
        selectedFrom.clear();
        selectedTo.clear();
    }

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

    private org.bukkit.inventory.ItemStack getCurrencySelectorItem(String prefix, String currency) {
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
                } catch (Exception ignored) {}
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

    public void handleClick(Player player, int slot, InventoryClickEvent event, VaultXGUI gui) {
        if (slot == getSlot("back-item", 22)) {
            gui.openDashboard(player);
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
    }
}
