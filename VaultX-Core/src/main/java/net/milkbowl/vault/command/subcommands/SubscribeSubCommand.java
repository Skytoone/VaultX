package net.milkbowl.vault.command.subcommands;

import net.milkbowl.vault.Vault;
import net.milkbowl.vault.command.SubCommand;
import net.milkbowl.vault.command.VaultXCommand;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class SubscribeSubCommand implements SubCommand {

    private final VaultXCommand parent;

    public SubscribeSubCommand(VaultXCommand parent) {
        this.parent = parent;
    }

    @Override
    public String getName() {
        return "subscribe";
    }

    @Override
    public List<String> getAliases() {
        return Collections.singletonList("subscription");
    }

    @Override
    public String getPermission() {
        return "vault.command.subscribe";
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(parent.getMsg("general.only-players", "§cOnly players can execute this command."));
            return true;
        }

        Economy econ = parent.getEconomy();
        if (econ == null) {
            sender.sendMessage(parent.getMsg("general.economy-not-found", "§cNo economy provider registered!"));
            return true;
        }

        boolean subEnabled = parent.getPlugin().getConfig().getBoolean("subscriptions.enabled", true);
        if (!subEnabled) {
            player.sendMessage(parent.getMsg("commands.subscribe.disabled",
                    "§cThe recurring subscription system is disabled on this server."));
            return true;
        }

        if (args.length < 2) {
            sendSubscribeHelp(player);
            return true;
        }

        String action = args[1].toLowerCase();
        net.milkbowl.vault.redis.LocalFailoverManager fm = Vault.getFailoverManager();
        if (fm == null) {
            player.sendMessage(parent.getMsg("commands.subscribe.db-error", "§cDatabase communication error."));
            return true;
        }

        if (action.equals("create")) {
            if (args.length < 5) {
                player.sendMessage(parent.getMsg("commands.subscribe.create-usage",
                        "§cUsage: /subscribe create <player/bank> <amount> <hours> [currency]"));
                return true;
            }

            String targetInput = args[2];
            double amount;
            try {
                amount = parent.parsePositiveDouble(args[3]);
            } catch (NumberFormatException e) {
                player.sendMessage(parent.getMsg("general.invalid-amount", "§cInvalid amount. It must be a positive number."));
                return true;
            }

            int hours;
            try {
                hours = Integer.parseInt(args[4]);
                if (hours < 1) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                player.sendMessage(parent.getMsg("commands.subscribe.invalid-hours", "§cInvalid interval. It must be at least 1 hour."));
                return true;
            }

            String currency = args.length >= 6 ? args[5].toLowerCase() : "default";
            if (!parent.getCurrencies(econ).contains(currency)) {
                player.sendMessage(parent.getMsg("general.currency-not-supported", "§cThis currency is not supported."));
                return true;
            }

            String targetType;
            String targetVal;
            String targetName;

            if (fm.getBankAccountOwner(targetInput) != null) {
                targetType = "BANK";
                targetVal = targetInput.toLowerCase();
                targetName = targetInput.toUpperCase() + " (Bank)";
            } else {
                OfflinePlayer targetPlayer = parent.resolvePlayerFast(targetInput);
                if (targetPlayer == null || (!net.milkbowl.vault.util.UUIDCache.hasPlayedBeforeFast(targetPlayer)
                        && !targetPlayer.isOnline())) {
                    player.sendMessage(parent.getMsg("general.player-not-found", "§cPlayer or bank not found."));
                    return true;
                }
                if (player.getUniqueId().equals(targetPlayer.getUniqueId())) {
                    player.sendMessage(parent.getMsg("commands.subscribe.self-sub", "§cYou cannot subscribe to yourself."));
                    return true;
                }
                targetType = "PLAYER";
                targetVal = targetPlayer.getUniqueId().toString();
                targetName = targetPlayer.getName() != null ? targetPlayer.getName() : "Unknown";
            }

            List<net.milkbowl.vault.redis.LocalFailoverManager.SubscriptionRecord> existing = fm
                    .getSubscriptionsForSubscriber(player.getUniqueId());
            int limit = parent.getPlugin().getConfig().getInt("subscriptions.max-active-per-player", 10);
            if (limit > 0 && existing.stream().filter(s -> s.status.equalsIgnoreCase("ACTIVE")).count() >= limit) {
                player.sendMessage(parent.getMsg("commands.subscribe.limit-exceeded",
                        "§cYou have reached the limit of %limit% active subscriptions.")
                        .replace("%limit%", String.valueOf(limit)));
                return true;
            }

            String subId = UUID.randomUUID().toString().substring(0, 8);
            net.milkbowl.vault.redis.LocalFailoverManager.SubscriptionRecord sub = new net.milkbowl.vault.redis.LocalFailoverManager.SubscriptionRecord(
                    subId, player.getUniqueId(), targetType, targetVal, amount, currency, hours, 0,
                    System.currentTimeMillis(), "ACTIVE", System.currentTimeMillis());

            parent.runAsync(() -> {
                net.milkbowl.vault.economy.SubscriptionManager sm = Vault.getSubscriptionManager();
                if (sm != null) {
                    fm.saveSubscription(sub);
                    sm.processSingleSubscription(econ, sub, System.currentTimeMillis());

                    net.milkbowl.vault.redis.LocalFailoverManager.SubscriptionRecord updated = fm.getSubscription(subId);
                    parent.runSync(() -> {
                        if (updated != null && updated.status.equalsIgnoreCase("SUSPENDED")) {
                            player.sendMessage(parent.getMsg("commands.subscribe.created-suspended",
                                    "§e§l[Subscription] §cSubscription #%id% created to %target% but immediately suspended due to insufficient balance.")
                                    .replace("%id%", subId)
                                    .replace("%target%", targetName));
                        } else {
                            player.sendMessage(parent.getMsg("commands.subscribe.created-success",
                                    "§a§l✔ §aSubscription #%id% successfully created to %target% for %amount% every %hours% hour(s).")
                                    .replace("%id%", subId)
                                    .replace("%target%", targetName)
                                    .replace("%amount%", currency.equalsIgnoreCase("default") ? econ.format(amount)
                                            : String.format("%.2f %s", amount, parent.getCurrencyDisplayName(currency)))
                                    .replace("%hours%", String.valueOf(hours)));
                        }
                    });
                }
            });

        } else if (action.equals("list")) {
            parent.runAsync(() -> {
                List<net.milkbowl.vault.redis.LocalFailoverManager.SubscriptionRecord> subs = fm
                        .getSubscriptionsForSubscriber(player.getUniqueId());
                parent.runSync(() -> {
                    if (subs.isEmpty()) {
                        player.sendMessage(parent.getMsg("commands.subscribe.list-empty",
                                "§e§l[Subscription] §7You have no active or suspended subscriptions."));
                        return;
                    }
                    player.sendMessage(parent.getMsg("commands.subscribe.list-header", "&d&l📬 Your Recurring Subscriptions 📬"));
                    for (net.milkbowl.vault.redis.LocalFailoverManager.SubscriptionRecord s : subs) {
                        String tName = s.target;
                        if (s.targetType.equalsIgnoreCase("PLAYER")) {
                            OfflinePlayer pTarget = Bukkit.getOfflinePlayer(UUID.fromString(s.target));
                            tName = pTarget.getName() != null ? pTarget.getName() : "Unknown";
                        } else {
                            tName = s.target.toUpperCase() + " (Bank)";
                        }

                        String statusColor = s.status.equalsIgnoreCase("ACTIVE") ? "§a" : "§c";
                        String formattedAmt = s.currency.equalsIgnoreCase("default") ? econ.format(s.amount)
                                : String.format("%.2f %s", s.amount, parent.getCurrencyDisplayName(s.currency));
                        player.sendMessage(parent.getMsg("commands.subscribe.list-entry",
                                "&8- &fID: &e%id% &8| &fTarget: &b%target% &8| &fAmount: &7%amount% &8| &fFreq: &7%hours%h &8| Status: %color%%status%")
                                .replace("%id%", s.id)
                                .replace("%target%", tName)
                                .replace("%amount%", formattedAmt)
                                .replace("%hours%", String.valueOf(s.intervalHours))
                                .replace("%color%", statusColor)
                                .replace("%status%", s.status));
                    }
                });
            });

        } else if (action.equals("cancel")) {
            if (args.length < 3) {
                player.sendMessage(parent.getMsg("commands.subscribe.cancel-usage", "§cUsage: /subscribe cancel <id>"));
                return true;
            }
            String subId = args[2];
            parent.runAsync(() -> {
                net.milkbowl.vault.redis.LocalFailoverManager.SubscriptionRecord s = fm.getSubscription(subId);
                if (s == null || !s.subscriber.equals(player.getUniqueId())) {
                    player.sendMessage(parent.getMsg("commands.subscribe.not-found", "§cSubscription not found."));
                    return;
                }
                fm.deleteSubscription(subId);
                String tName = s.target;
                if (s.targetType.equalsIgnoreCase("PLAYER")) {
                    OfflinePlayer pTarget = Bukkit.getOfflinePlayer(UUID.fromString(s.target));
                    tName = pTarget.getName() != null ? pTarget.getName() : "Unknown";
                } else {
                    tName = s.target.toUpperCase() + " (Bank)";
                }
                final String finalTarget = tName;
                parent.runSync(() -> {
                    player.sendMessage(parent.getMsg("commands.subscribe.cancel-success",
                            "§a§l✔ §aSubscription #%id% to %target% has been cancelled.")
                            .replace("%id%", subId)
                            .replace("%target%", finalTarget));
                });
            });

        } else if (action.equals("resume")) {
            if (args.length < 3) {
                player.sendMessage(parent.getMsg("commands.subscribe.resume-usage", "§cUsage: /subscribe resume <id>"));
                return true;
            }
            String subId = args[2];
            parent.runAsync(() -> {
                net.milkbowl.vault.redis.LocalFailoverManager.SubscriptionRecord s = fm.getSubscription(subId);
                if (s == null || !s.subscriber.equals(player.getUniqueId())) {
                    player.sendMessage(parent.getMsg("commands.subscribe.not-found", "§cSubscription not found."));
                    return;
                }
                if (s.status.equalsIgnoreCase("ACTIVE")) {
                    player.sendMessage(parent.getMsg("commands.subscribe.already-active", "§cThis subscription is already active."));
                    return;
                }

                fm.updateSubscriptionBilling(subId, s.lastBilling, System.currentTimeMillis(), "ACTIVE");
                net.milkbowl.vault.economy.SubscriptionManager sm = Vault.getSubscriptionManager();
                if (sm != null) {
                    net.milkbowl.vault.redis.LocalFailoverManager.SubscriptionRecord reloaded = fm.getSubscription(subId);
                    sm.processSingleSubscription(econ, reloaded, System.currentTimeMillis());

                    net.milkbowl.vault.redis.LocalFailoverManager.SubscriptionRecord updated = fm.getSubscription(subId);
                    parent.runSync(() -> {
                        if (updated != null && updated.status.equalsIgnoreCase("SUSPENDED")) {
                            player.sendMessage(parent.getMsg("commands.subscribe.resume-failed",
                                    "§c§l[Subscription] §cCould not reactivate subscription #%id% because your balance is still insufficient.")
                                    .replace("%id%", subId));
                        } else {
                            player.sendMessage(parent.getMsg("commands.subscribe.resume-success",
                                    "§a§l✔ §aSubscription #%id% has been successfully reactivated!")
                                    .replace("%id%", subId));
                        }
                    });
                }
            });

        } else {
            sendSubscribeHelp(player);
        }
        return true;
    }

    private void sendSubscribeHelp(Player player) {
        player.sendMessage(parent.getMsg("commands.subscribe.help-header", "&d&l=== VaultX Subscription Help ==="));
        player.sendMessage(parent.getMsg("commands.subscribe.help-create",
                "&e/subscribe create <player/bank> <amount> <hours> [currency] &7- Create a subscription"));
        player.sendMessage(parent.getMsg("commands.subscribe.help-list",
                "&e/subscribe list &7- View your active and suspended subscriptions"));
        player.sendMessage(parent.getMsg("commands.subscribe.help-cancel", "&e/subscribe cancel <id> &7- Cancel a subscription"));
        player.sendMessage(parent.getMsg("commands.subscribe.help-resume",
                "&e/subscribe resume <id> &7- Reactivate a suspended subscription"));
        player.sendMessage(parent.getMsg("commands.subscribe.help-footer", "&d&l================================"));
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String label, String[] args) {
        return Collections.emptyList();
    }
}
