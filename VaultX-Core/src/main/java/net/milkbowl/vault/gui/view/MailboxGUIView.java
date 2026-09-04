package net.milkbowl.vault.gui.view;

import java.text.SimpleDateFormat;
import java.util.*;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;

import net.milkbowl.vault.Vault;
import net.milkbowl.vault.economy.MailboxManager;
import net.milkbowl.vault.redis.LocalFailoverManager.MailRecord;
import net.milkbowl.vault.util.FoliaScheduler;
import net.milkbowl.vault.util.VaultXGUI;
import net.milkbowl.vault.util.VaultXGUI.GUIMailboxHolder;

import static net.milkbowl.vault.gui.view.GUIHelper.*;

public class MailboxGUIView {

    private final Plugin plugin;

    public MailboxGUIView(Plugin plugin) {
        this.plugin = plugin;
    }

    public void openMailbox(Player player) {
        String title = getGuiTitle("mailbox", "§dEconomic Mailbox");
        Inventory inv = Bukkit.createInventory(new GUIMailboxHolder(), 36, title);

        MailboxManager mailboxManager = Vault.getMailboxManager();
        if (mailboxManager == null)
            return;

        FoliaScheduler.runAsync(plugin, () -> {
            List<MailRecord> mails = mailboxManager.getPendingMail(player.getUniqueId());

            FoliaScheduler.runSync(plugin, () -> {
                int slot = 10;
                for (MailRecord mail : mails) {
                    if (slot > 16 && slot < 19)
                        slot = 19;
                    if (slot > 25)
                        break;

                    Date date = new Date(mail.timestamp);
                    SimpleDateFormat sdf = new SimpleDateFormat("dd/MM HH:mm");

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

    public void handleClick(Player player, int slot, VaultXGUI gui) {
        if (slot == getSlot("back-item", 31)) {
            gui.openDashboard(player);
            return;
        }

        MailboxManager mailboxManager = Vault.getMailboxManager();
        if (mailboxManager == null)
            return;

        if (slot == getSlot("mailbox.items.claim-all", 32)) {
            mailboxManager.claimAllMail(player);
            FoliaScheduler.runLater(plugin, () -> openMailbox(player), 10L);
            return;
        }

        FoliaScheduler.runAsync(plugin, () -> {
            List<MailRecord> mails = mailboxManager.getPendingMail(player.getUniqueId());
            FoliaScheduler.runSync(plugin, () -> {
                int listSlot = 10;
                for (MailRecord mail : mails) {
                    if (listSlot > 16 && listSlot < 19)
                        listSlot = 19;
                    if (listSlot > 25)
                        break;

                    if (listSlot == slot) {
                        mailboxManager.claimMailRecord(player, mail);
                        FoliaScheduler.runLater(plugin, () -> openMailbox(player), 5L);
                        break;
                    }
                    listSlot++;
                }
            });
        });
    }
}
