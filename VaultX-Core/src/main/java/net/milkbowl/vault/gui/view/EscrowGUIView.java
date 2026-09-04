package net.milkbowl.vault.gui.view;

import java.util.*;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import net.milkbowl.vault.Vault;
import net.milkbowl.vault.redis.EscrowManager;
import net.milkbowl.vault.redis.EscrowManager.EscrowDetails;
import net.milkbowl.vault.util.FoliaScheduler;
import net.milkbowl.vault.util.UUIDCache;
import net.milkbowl.vault.util.VaultXGUI;
import net.milkbowl.vault.util.VaultXGUI.GUIEscrowHolder;

import static net.milkbowl.vault.gui.view.GUIHelper.*;

public class EscrowGUIView {

    private final Plugin plugin;

    public EscrowGUIView(Plugin plugin) {
        this.plugin = plugin;
    }

    public void openEscrow(Player player) {
        String title = getGuiTitle("escrow", "§8Escrow Management");
        Inventory inv = Bukkit.createInventory(new GUIEscrowHolder(), 36, title);

        EscrowManager escrowManager = Vault.getEscrowManager();
        if (escrowManager == null)
            return;

        escrowManager.listEscrows(player).thenAccept(escrows -> {
            FoliaScheduler.runSync(plugin, () -> {
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
                            } catch (Exception ignored) {}
                        } else {
                            matStr = guiCfg.getString("escrow.items.escrow-entry.material");
                            if (matStr != null) {
                                try {
                                    mat = Material.valueOf(matStr.toUpperCase());
                                    data = (short) guiCfg.getInt("escrow.items.escrow-entry.data", 0);
                                } catch (Exception ignored) {}
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

    public void handleClick(Player player, int slot, InventoryClickEvent event, VaultXGUI gui) {
        if (slot == getSlot("back-item", 31)) {
            gui.openDashboard(player);
            return;
        }

        EscrowManager escrowManager = Vault.getEscrowManager();
        if (escrowManager == null)
            return;

        // Fetch escrows again to align with the slot
        escrowManager.listEscrows(player).thenAccept(escrows -> {
            FoliaScheduler.runSync(plugin, () -> {
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
                                        FoliaScheduler.runSync(plugin, () -> openEscrow(player));
                                    });
                                } else {
                                    // Refund escrow
                                    escrowManager.refundEscrow(esc.id, player).thenAccept(res -> {
                                        player.sendMessage(
                                                res.success ? "§a§l✔ §a" + res.message : "§c§l❌ §c" + res.message);
                                        FoliaScheduler.runSync(plugin, () -> openEscrow(player));
                                    });
                                }
                            } else {
                                if (event.isLeftClick()) {
                                    // Receiver refund request (return to sender)
                                    escrowManager.refundEscrow(esc.id, player).thenAccept(res -> {
                                        player.sendMessage(
                                                res.success ? "§a§l✔ §a" + res.message : "§c§l❌ §c" + res.message);
                                        FoliaScheduler.runSync(plugin, () -> openEscrow(player));
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
    }
}
