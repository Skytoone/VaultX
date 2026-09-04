package net.milkbowl.vault.gui.view;

import java.text.SimpleDateFormat;
import java.util.*;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;

import net.milkbowl.vault.Vault;
import net.milkbowl.vault.redis.LocalFailoverManager.PlayerTransactionRecord;
import net.milkbowl.vault.util.FoliaScheduler;
import net.milkbowl.vault.util.VaultXGUI;
import net.milkbowl.vault.util.VaultXGUI.GUIHistoryHolder;

import static net.milkbowl.vault.gui.view.GUIHelper.*;

public class HistoryGUIView {

    private final Plugin plugin;

    public HistoryGUIView(Plugin plugin) {
        this.plugin = plugin;
    }

    public void openHistory(Player player) {
        String title = getGuiTitle("history", "§8Transaction History");
        Inventory inv = Bukkit.createInventory(new GUIHistoryHolder(), 36, title);

        FoliaScheduler.runAsync(plugin, () -> {
            List<PlayerTransactionRecord> txs = Vault.getFailoverManager().getPlayerTransactions(player.getUniqueId(),
                    1, 10);

            FoliaScheduler.runSync(plugin, () -> {
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
                    SimpleDateFormat sdf = new SimpleDateFormat("dd/MM HH:mm");

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
                            } catch (Exception ignored) {}
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

    public void handleClick(Player player, int slot, VaultXGUI gui) {
        if (slot == getSlot("back-item", 31)) {
            gui.openDashboard(player);
        }
    }
}
