package net.milkbowl.vault.command.subcommands.bank;

import net.milkbowl.vault.Vault;
import net.milkbowl.vault.command.VaultXCommand;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;

public class BankMemberHandler {

    private final VaultXCommand parent;

    public BankMemberHandler(VaultXCommand parent) {
        this.parent = parent;
    }

    public boolean handleInvites(CommandSender sender, Player player, net.milkbowl.vault.redis.LocalFailoverManager fm) {
        parent.runAsync(() -> {
            Map<String, String> pending = fm.getPendingInvitesForPlayer(player.getUniqueId());
            parent.runSync(() -> {
                if (pending.isEmpty()) {
                    sender.sendMessage(parent.getMsg("bank.invites-empty", "§cYou have no pending bank invitations."));
                    return;
                }
                sender.sendMessage(parent.getMsg("bank.invites-header", "§d§l=== Pending Bank Invitations ==="));
                for (Map.Entry<String, String> entry : pending.entrySet()) {
                    sender.sendMessage(parent.getMsg("bank.invites-entry", "  §7- §e%name% §7| Proposed Role: §f%role%")
                            .replace("%name%", entry.getKey())
                            .replace("%role%", entry.getValue()));
                    sender.sendMessage(parent.getMsg("bank.invites-actions",
                            "    §7Accept: §a/vx bank accept %name% §7| Deny: §c/vx bank deny %name%")
                            .replace("%name%", entry.getKey()));
                }
                sender.sendMessage(parent.getMsg("bank.invites-footer", "§d§l======================================="));
            });
        });
        return true;
    }

    public void handleAddMember(CommandSender sender, Player player, String bankName, String userRole, String[] args, net.milkbowl.vault.redis.LocalFailoverManager fm) {
        if (!userRole.equals("OWNER") && !userRole.equals("MANAGER")) {
            sender.sendMessage(parent.getMsg("bank.no-permission-action",
                    "§cOnly the Owner (OWNER) and Managers (MANAGER) can add members."));
            return;
        }
        if (args.length < 5) {
            sender.sendMessage(parent.getMsg("bank.usage", "§cUsage: /vaultx bank addmember <name> <player> <role>"));
            return;
        }
        OfflinePlayer target = parent.resolvePlayerFast(args[3]);
        if (target == null) {
            sender.sendMessage(parent.getMsg("general.player-not-found", "§cPlayer not found."));
            return;
        }
        String targetRole = args[4].toUpperCase();
        if (!targetRole.equals("OWNER") && !targetRole.equals("MANAGER") && !targetRole.equals("MEMBER")
                && !targetRole.equals("VIEWER")) {
            sender.sendMessage(parent.getMsg("commands.admin.bank-role-invalid", "§cInvalid role: OWNER, MANAGER, MEMBER, VIEWER."));
            return;
        }
        if (targetRole.equals("OWNER") && !userRole.equals("OWNER")) {
            sender.sendMessage(parent.getMsg("bank.no-permission-action", "§cOnly the current Owner can designate another Owner (OWNER)."));
            return;
        }

        parent.runAsync(() -> {
            int maxMembers = parent.getPlugin().getConfig().getInt("banks.max-members", 20);
            Map<UUID, String> currentMembers = fm.getBankMembers(bankName);
            long realCount = currentMembers.values().stream().filter(r -> !r.startsWith("INVITED_")).count();
            if (realCount >= maxMembers) {
                parent.runSync(() -> sender.sendMessage(parent.getMsg("bank.member-limit-reached",
                        "§c§l❌ §cThis bank has reached its maximum member limit (§e%max%§c)."
                                .replace("%max%", String.valueOf(maxMembers)))));
                return;
            }
            fm.addBankMember(bankName, target.getUniqueId(), targetRole);
            parent.runSync(() -> {
                sender.sendMessage(parent.getMsg("bank.member-added",
                        "§a§l✔ §aPlayer §e%player% §aadded to account §e%name% §awith role §e%role%&a.")
                        .replace("%player%", parent.getPlayerNameSafe(target, args[3]))
                        .replace("%name%", bankName)
                        .replace("%role%", targetRole));
                if (target.isOnline() && target.getPlayer() != null) {
                    target.getPlayer().sendMessage(parent.getMsg("bank.member-added-notify",
                            "§a§l✔ §aYou have been added to the shared bank account §e%name% §awith role §e%role%&a.")
                            .replace("%name%", bankName)
                            .replace("%role%", targetRole));
                }
            });
        });
    }

    public void handleRemoveMember(CommandSender sender, Player player, String bankName, String userRole, String[] args, net.milkbowl.vault.redis.LocalFailoverManager fm) {
        if (!userRole.equals("OWNER") && !userRole.equals("MANAGER")) {
            sender.sendMessage(parent.getMsg("bank.no-permission-action",
                    "§cOnly the Owner (OWNER) and Managers (MANAGER) can remove members."));
            return;
        }
        if (args.length < 4) {
            sender.sendMessage(parent.getMsg("bank.usage", "§cUsage: /vaultx bank removemember <name> <player>"));
            return;
        }
        OfflinePlayer target = parent.resolvePlayerFast(args[3]);
        if (target == null) {
            sender.sendMessage(parent.getMsg("general.player-not-found", "§cPlayer not found."));
            return;
        }

        if (player.getUniqueId().equals(target.getUniqueId())) {
            sender.sendMessage(parent.getMsg("bank.member-cannot-remove-self", "§cYou cannot remove yourself with this command."));
            return;
        }

        parent.runAsync(() -> {
            String targetRole = fm.getBankRole(bankName, target.getUniqueId());
            if (targetRole == null) {
                parent.runSync(() -> sender.sendMessage(parent.getMsg("bank.member-not-found", "§cThe player is not a member of this bank account.")));
                return;
            }

            if (targetRole.equals("OWNER")) {
                parent.runSync(() -> sender.sendMessage(parent.getMsg("bank.member-cannot-remove-owner", "§cThe Owner (OWNER) cannot be removed from the account.")));
                return;
            }
            if (targetRole.equals("MANAGER") && !userRole.equals("OWNER")) {
                parent.runSync(() -> sender.sendMessage(parent.getMsg("bank.member-cannot-remove-manager", "§cOnly the Owner (OWNER) can remove a Manager (MANAGER).")));
                return;
            }

            fm.removeBankMember(bankName, target.getUniqueId());
            parent.runSync(() -> {
                sender.sendMessage(parent.getMsg("bank.member-removed",
                        "§a§l✔ §aPlayer §e%player% §aremoved from account §e%name%&a.")
                        .replace("%player%", parent.getPlayerNameSafe(target, args[3]))
                        .replace("%name%", bankName));
                if (target.isOnline() && target.getPlayer() != null) {
                    target.getPlayer().sendMessage(parent.getMsg("bank.member-removed-notify",
                            "§c§lℹ §cYou have been removed from the shared bank account §e%name%&c.")
                            .replace("%name%", bankName));
                }
            });
        });
    }

    public void handleInvite(CommandSender sender, Player player, String bankName, String userRole, String[] args, net.milkbowl.vault.redis.LocalFailoverManager fm) {
        if (!userRole.equals("OWNER") && !userRole.equals("MANAGER")) {
            sender.sendMessage(parent.getMsg("bank.no-permission-action",
                    "§cOnly the Owner (OWNER) and Managers (MANAGER) can invite members."));
            return;
        }
        if (args.length < 5) {
            sender.sendMessage(parent.getMsg("bank.usage", "§cUsage: /vaultx bank invite <name> <player> <role>"));
            return;
        }
        OfflinePlayer target = parent.resolvePlayerFast(args[3]);
        if (target == null) {
            sender.sendMessage(parent.getMsg("general.player-not-found", "§cPlayer not found."));
            return;
        }
        String targetRole = args[4].toUpperCase();
        if (!targetRole.equals("MANAGER") && !targetRole.equals("MEMBER") && !targetRole.equals("VIEWER")) {
            sender.sendMessage(parent.getMsg("commands.admin.bank-role-invalid", "§cInvalid invitation role: MANAGER, MEMBER, VIEWER."));
            return;
        }
        if (targetRole.equals("MANAGER") && !userRole.equals("OWNER")) {
            sender.sendMessage(parent.getMsg("bank.no-permission-action", "§cOnly the Owner (OWNER) can invite a Manager (MANAGER)."));
            return;
        }

        parent.runAsync(() -> {
            String currentRole = fm.getBankRole(bankName, target.getUniqueId());
            if (currentRole != null) {
                parent.runSync(() -> {
                    if (currentRole.startsWith("INVITED_")) {
                        sender.sendMessage(parent.getMsg("bank.invite-already-pending", "§cThis player already has a pending invitation for this bank."));
                    } else {
                        sender.sendMessage(parent.getMsg("bank.invite-already-member", "§cThis player is already a member of this bank."));
                    }
                });
                return;
            }

            String inviteRole = "INVITED_" + targetRole;
            fm.addBankMember(bankName, target.getUniqueId(), inviteRole);

            net.milkbowl.vault.redis.VaultRedisManager redis = net.milkbowl.vault.redis.VaultRedisManager.getInstance();
            if (redis != null) {
                redis.publishBankMemberUpdate(bankName, target.getUniqueId(), inviteRole);
            }

            parent.runSync(() -> {
                sender.sendMessage(parent.getMsg("bank.invite-sent",
                        "§a§l✔ §aInvitation sent to §e%player% §ato join §e%name% §awith role §e%role%&a.")
                        .replace("%player%", parent.getPlayerNameSafe(target, args[3]))
                        .replace("%name%", bankName)
                        .replace("%role%", targetRole));
                if (target.isOnline() && target.getPlayer() != null) {
                    target.getPlayer().sendMessage(parent.getMsg("bank.invite-received",
                            "§a§l[Bank] §aYou have been invited to join bank §e%name% §awith role §e%role%&a. Accept with §e/vx bank accept %name%&a.")
                            .replace("%name%", bankName)
                            .replace("%role%", targetRole));
                }
            });
        });
    }

    public void handleAccept(CommandSender sender, Player player, String bankName, String userRole, net.milkbowl.vault.redis.LocalFailoverManager fm) {
        if (userRole == null || !userRole.startsWith("INVITED_")) {
            sender.sendMessage(parent.getMsg("bank.invites-empty", "§cYou do not have a pending invitation for this bank."));
            return;
        }
        final String targetRole = userRole.replace("INVITED_", "");
        parent.runAsync(() -> {
            int maxMembers = parent.getPlugin().getConfig().getInt("banks.max-members", 20);
            Map<UUID, String> currentMembers = fm.getBankMembers(bankName);
            long realCount = currentMembers.values().stream().filter(r -> !r.startsWith("INVITED_")).count();
            if (realCount >= maxMembers) {
                parent.runSync(() -> sender.sendMessage(parent.getMsg("bank.member-limit-reached",
                        "§c§l❌ §cThis bank is full and cannot accept more members (max §e%max%§c)."
                                .replace("%max%", String.valueOf(maxMembers)))));
                return;
            }
            fm.addBankMember(bankName, player.getUniqueId(), targetRole);
            net.milkbowl.vault.redis.VaultRedisManager redis = net.milkbowl.vault.redis.VaultRedisManager.getInstance();
            if (redis != null) {
                redis.publishBankMemberUpdate(bankName, player.getUniqueId(), targetRole);
            }
            parent.runSync(() -> {
                sender.sendMessage(parent.getMsg("bank.accept-success",
                        "§a§l✔ §aYou accepted the invitation to join §e%name% §aas §e%role%&a.")
                        .replace("%name%", bankName)
                        .replace("%role%", targetRole));
            });
        });
    }

    public void handleDeny(CommandSender sender, Player player, String bankName, String userRole, net.milkbowl.vault.redis.LocalFailoverManager fm) {
        if (userRole == null || !userRole.startsWith("INVITED_")) {
            sender.sendMessage(parent.getMsg("bank.invites-empty", "§cYou do not have a pending invitation for this bank."));
            return;
        }
        parent.runAsync(() -> {
            fm.removeBankMember(bankName, player.getUniqueId());
            net.milkbowl.vault.redis.VaultRedisManager redis = net.milkbowl.vault.redis.VaultRedisManager.getInstance();
            if (redis != null) {
                redis.publishBankMemberUpdate(bankName, player.getUniqueId(), "REMOVE");
            }
            parent.runSync(() -> {
                sender.sendMessage(parent.getMsg("bank.deny-success",
                        "§a§l✔ §aYou declined the invitation to join §e%name%&a.")
                        .replace("%name%", bankName));
            });
        });
    }
}
