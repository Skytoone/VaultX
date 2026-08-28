package net.milkbowl.vault.economy;

import net.milkbowl.vault.Vault;
import net.milkbowl.vault.redis.LocalFailoverManager.MailRecord;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.List;
import java.util.UUID;

public class MailboxManager implements Listener {

    private final Plugin plugin;

    public MailboxManager(Plugin plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    /**
     * Send an offline payment to a player's mailbox.
     */
    public void sendOfflineMail(UUID receiverUuid, String senderName, String message, double amount, String currency) {
        net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
            Vault.getFailoverManager().addMail(receiverUuid, senderName, message, amount, currency);
            if (Vault.getDiscordManager() != null) {
                String receiverName = net.milkbowl.vault.util.UUIDCache.getName(receiverUuid);
                String webhookTemplate = Vault.getMessage("discord.webhook-mailbox", "📥 **Offline Payment Received**\nUser **%receiver%** received **%amount%** from **%sender%**!");
                String content = webhookTemplate
                        .replace("%receiver%", receiverName != null ? receiverName : "Player")
                        .replace("%sender%", senderName)
                        .replace("%amount%", String.format("%.2f %s", amount, currency));
                Vault.getDiscordManager().sendWebhook("mailbox", content);
            }
        });
    }

    /**
     * Get all pending mail for a player.
     * Must be called asynchronously or handles internally.
     */
    public List<MailRecord> getPendingMail(UUID uuid) {
        return Vault.getFailoverManager().getPendingMail(uuid);
    }

    /**
     * Claim a specific mail record for a player.
     */
    public boolean claimMailRecord(Player player, MailRecord record) {
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp == null)
            return false;
        Economy econ = rsp.getProvider();

        String currency = record.currency.toLowerCase();
        double amount = record.amount;
        EconomyResponse response;

        if (currency.equals("default")) {
            response = econ.depositPlayer(player, amount);
        } else if (econ instanceof MultiCurrencyEconomy) {
            response = ((MultiCurrencyEconomy) econ).depositCurrencyPlayer(player, currency, amount);
        } else {
            player.sendMessage(Vault.getMessage("commands.mailbox.no-multicurrency", "&c&l[Mailbox] &cCannot claim this currency as multi-currency is not supported."));
            return false;
        }

        if (response.transactionSuccess()) {
            // Update status in DB
            net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
                Vault.getFailoverManager().claimMail(record.id);
            });
            player.sendMessage(Vault.getMessage("commands.mailbox.claimed-one", "&a&l[Mailbox] &aYou claimed &e%amount% &7(%currency%) &asent by &f%sender%&a.")
                    .replace("%amount%", econ.format(amount))
                    .replace("%currency%", currency.toUpperCase())
                    .replace("%sender%", record.senderName));
            playSoundSafe(player, 1.0f, 1.0f, "ENTITY_EXPERIENCE_ORB_PICKUP", "ORB_PICKUP");
            return true;
        } else {
            player.sendMessage(Vault.getMessage("commands.mailbox.claim-failed", "&c&l[Mailbox] &cClaim deposit failed: %error%")
                    .replace("%error%", response.errorMessage));
            return false;
        }
    }

    /**
     * Claim all pending mail for a player.
     */
    public void claimAllMail(Player player) {
        net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
            List<MailRecord> pending = getPendingMail(player.getUniqueId());
            if (pending.isEmpty()) {
                player.sendMessage(Vault.getMessage("commands.mailbox.empty-box", "&c&l[Mailbox] &cYou have no pending messages."));
                return;
            }

            net.milkbowl.vault.util.FoliaScheduler.runSync(plugin, () -> {
                int count = 0;
                for (MailRecord record : pending) {
                    if (claimMailRecord(player, record)) {
                        count++;
                    }
                }
                if (count > 0) {
                    player.sendMessage(Vault.getMessage("commands.mailbox.claimed-all", "&a&l[Mailbox] &aSuccessfully claimed &e%count% &atransaction(s)!")
                            .replace("%count%", String.valueOf(count)));
                }
            });
        });
    }

    @EventHandler
    @SuppressWarnings("deprecation")
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // Check for pending mail asynchronously
        net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
            List<MailRecord> pending = getPendingMail(uuid);
            if (!pending.isEmpty()) {
                // Schedule reminder 2 seconds after join
                net.milkbowl.vault.util.FoliaScheduler.runLater(plugin, () -> {
                    if (player.isOnline()) {
                        // Title Notification
                        try {
                            try {
                                java.lang.reflect.Method sendTitleMethod = player.getClass().getMethod("sendTitle",
                                        String.class, String.class, int.class, int.class, int.class);
                                sendTitleMethod.invoke(player,
                                        Vault.getMessage("commands.mailbox.title-mailbox", "&d&lMailbox"),
                                        Vault.getMessage("commands.mailbox.title-subtitle", "&fYou have &e%count% &fpending payment(s)!").replace("%count%", String.valueOf(pending.size())),
                                        10, 40, 10);
                            } catch (NoSuchMethodException e) {
                                player.sendTitle(
                                        Vault.getMessage("commands.mailbox.title-mailbox", "&d&lMailbox"),
                                        Vault.getMessage("commands.mailbox.title-subtitle", "&fYou have &e%count% &fpending payment(s)!").replace("%count%", String.valueOf(pending.size())));
                            }
                            playSoundSafe(player, 1.0f, 1.5f, "BLOCK_NOTE_BLOCK_PLING", "NOTE_PLING", "NOTE_PIANO");
                        } catch (Throwable t) {
                            // Backward compatibility support for older Spigot versions
                            player.sendMessage(Vault.getMessage("commands.mailbox.chat-pending", "§d§l[Mailbox] §fYou have §e%count% §funclaimed transaction(s).")
                                    .replace("%count%", String.valueOf(pending.size())));
                        }

                        // Chat Notification
                        player.sendMessage("");
                        player.sendMessage(Vault.getMessage("commands.mailbox.chat-header", "§d§l📬 Economic Mailbox 📬"));
                        player.sendMessage(Vault.getMessage("commands.mailbox.chat-offline", "§7You were offline during some transactions."));
                        player.sendMessage(Vault.getMessage("commands.mailbox.chat-pending", "§fYou have §e%count% §funclaimed transaction(s).").replace("%count%", String.valueOf(pending.size())));
                        player.sendMessage(Vault.getMessage("commands.mailbox.chat-info", "§fType §e/vx mailbox §fto open your mailbox and claim your funds!"));
                        player.sendMessage("");
                    }
                }, 40L);
            }
        });
    }

    private void playSoundSafe(Player player, float volume, float pitch, String... names) {
        if (player == null)
            return;
        for (String name : names) {
            try {
                Sound sound = Sound.valueOf(name);
                player.playSound(player.getLocation(), sound, volume, pitch);
                return;
            } catch (IllegalArgumentException ignored) {
            }
        }
    }
}

