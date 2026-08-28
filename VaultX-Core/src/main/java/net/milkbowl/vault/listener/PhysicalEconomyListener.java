package net.milkbowl.vault.listener;

import net.milkbowl.vault.Vault;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.MultiCurrencyEconomy;
import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.redis.LocalFailoverManager;
import net.milkbowl.vault.redis.LocalFailoverManager.LocalCheckRecord;
import net.milkbowl.vault.util.VaultXVisuals;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PhysicalEconomyListener implements Listener {

    private final Vault plugin;
    private static final Set<String> CLAIMING_CHECKS = ConcurrentHashMap.newKeySet();

    public PhysicalEconomyListener(Vault plugin) {
        this.plugin = plugin;
    }

    private Economy getEconomy() {
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        return rsp != null ? rsp.getProvider() : null;
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        String handName = getEventHandName(event);
        Player player = event.getPlayer();
        Action action = event.getAction();

        // 1. Check ATM Signs (Main-hand only to prevent double opening)
        if (action == Action.RIGHT_CLICK_BLOCK && !"OFF_HAND".equals(handName)) {
            if (!plugin.getConfig().getBoolean("atms.enabled", true)) {
                return;
            }
            Block block = event.getClickedBlock();
            if (block != null && block.getState() instanceof Sign) {
                Sign sign = (Sign) block.getState();
                if (isATMSign(sign.getLines())) {
                    event.setCancelled(true);
                    double fee = plugin.getConfig().getDouble("atms.transaction-fee", 0.0);
                    if (fee > 0) {
                        Economy econ = getEconomy();
                        if (econ != null && econ.getBalance(player) >= fee) {
                            econ.withdrawPlayer(player, fee);
                            player.sendMessage(Vault.getMessage("atms.fee-deducted", "§7An ATM transaction fee of §e%fee% §7was deducted.")
                                    .replace("%fee%", econ.format(fee)));
                        }
                    }
                    if (Vault.getVaultXGUI() != null) {
                        Vault.getVaultXGUI().openDashboard(player);
                    } else {
                        player.sendMessage(Vault.getMessage("commands.menu.gui-not-enabled", "§c§l[VaultX] §cThe GUI menu is not enabled."));
                    }
                    return;
                }
            }
        }

        // 2. Check Check Redemption
        if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
            ItemStack item = event.getItem();
            if (item != null && item.getType() == Material.PAPER) {
                ItemMeta meta = item.getItemMeta();
                if (meta != null && meta.hasDisplayName() && 
                        (meta.getDisplayName().equals("§6§lEconomic Check") || meta.getDisplayName().equals("§6§lChèque Économique"))) {
                    List<String> lore = meta.getLore();
                    if (lore != null) {
                        String checkId = null;
                        for (String line : lore) {
                            String stripped = ChatColor.stripColor(line);
                            if (stripped.startsWith("ID: ")) {
                                checkId = stripped.substring(4).trim();
                                break;
                            }
                        }

                        if (checkId == null || checkId.isEmpty()) {
                            return;
                        }

                        event.setCancelled(true);

                        if (!CLAIMING_CHECKS.add(checkId)) {
                            player.sendMessage(Vault.getMessage("checks.already-processing", "§cThis check is already being processed."));
                            return;
                        }

                        final String finalCheckId = checkId;
                        final ItemStack itemToRestore = item.clone();
                        itemToRestore.setAmount(1);

                        // Consume 1 item from hand immediately
                        removeOneHandItem(player, handName, item);

                        // Run DB queries asynchronously, but execute the deposit on the main thread
                        net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
                            try {
                                LocalFailoverManager fm = Vault.getFailoverManager();
                                Economy econ = getEconomy();
                                if (fm == null || econ == null) {
                                    net.milkbowl.vault.util.FoliaScheduler.runSync(plugin, () -> {
                                        CLAIMING_CHECKS.remove(finalCheckId);
                                        restoreCheckItem(player, itemToRestore);
                                        if (player.isOnline()) {
                                            player.sendMessage(Vault.getMessage("commands.check.database-error", "§cDatabase communication error."));
                                        }
                                    });
                                    return;
                                }

                                LocalCheckRecord check = fm.getCheck(finalCheckId);

                                if (check == null || !check.status.equalsIgnoreCase("ACTIVE")) {
                                    net.milkbowl.vault.util.FoliaScheduler.runSync(plugin, () -> {
                                        CLAIMING_CHECKS.remove(finalCheckId);
                                        restoreCheckItem(player, itemToRestore);
                                        if (player.isOnline()) {
                                            player.sendMessage(Vault.getMessage("checks.invalid-or-claimed", "§cThis check is no longer valid or has already been claimed."));
                                            VaultXVisuals.playFailureSound(player);
                                        }
                                    });
                                    return;
                                }

                                // Update check status in database first to prevent race conditions
                                fm.updateCheckStatus(finalCheckId, "CLAIMED");

                                // Perform deposit on the main thread
                                net.milkbowl.vault.util.FoliaScheduler.runSync(plugin, () -> {
                                    EconomyResponse depRes;
                                    if (check.currency.equalsIgnoreCase("default")) {
                                        depRes = econ.depositPlayer(player, check.amount);
                                    } else if (econ instanceof MultiCurrencyEconomy) {
                                        depRes = ((MultiCurrencyEconomy) econ).depositCurrencyPlayer(player, check.currency, check.amount);
                                    } else {
                                        depRes = new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Currency not supported");
                                    }

                                    if (depRes.transactionSuccess()) {
                                        final double finalAmount = check.amount;
                                        final String finalCurrency = check.currency;
                                        // Save transaction log asynchronously since it is a fast DB update
                                        net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
                                            UUID creatorUuid = check.creatorUuid;
                                            String creatorName = net.milkbowl.vault.util.UUIDCache.getName(creatorUuid);
                                            if (creatorName == null) creatorName = "Unknown";

                                            fm.savePlayerTransaction(player.getUniqueId(), "DEPOSIT_CHECK", finalCurrency, finalAmount, creatorName);
                                            fm.savePlayerTransaction(creatorUuid, "CLAIMED_CHECK", finalCurrency, finalAmount, player.getName());
                                        });

                                        CLAIMING_CHECKS.remove(finalCheckId);
                                        if (player.isOnline()) {
                                            String formatted = econ.format(finalAmount) + (finalCurrency.equalsIgnoreCase("default") ? "" : " (" + finalCurrency.toUpperCase() + ")");
                                            player.sendMessage(Vault.getMessage("checks.claim-success", "§a§l✔ §aYou claimed a check of §e%amount%§a!")
                                                    .replace("%amount%", formatted));
                                            VaultXVisuals.sendTransactionNotification(player, finalCurrency, finalAmount, true);
                                        }
                                    } else {
                                        // Revert check status if deposit failed
                                        final String errMsg = depRes.errorMessage;
                                        net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
                                            fm.updateCheckStatus(finalCheckId, "ACTIVE");
                                        });
                                        CLAIMING_CHECKS.remove(finalCheckId);
                                        restoreCheckItem(player, itemToRestore);
                                        if (player.isOnline()) {
                                            player.sendMessage(Vault.getMessage("commands.check.deposit-failed", "§cDeposit failed: %error%")
                                                    .replace("%error%", errMsg));
                                            VaultXVisuals.playFailureSound(player);
                                        }
                                    }
                                });
                            } catch (Exception e) {
                                net.milkbowl.vault.util.FoliaScheduler.runSync(plugin, () -> {
                                    CLAIMING_CHECKS.remove(finalCheckId);
                                    restoreCheckItem(player, itemToRestore);
                                    if (player.isOnline()) {
                                        player.sendMessage(Vault.getMessage("commands.check.database-error", "§cDatabase communication error."));
                                    }
                                });
                            }
                        });
                    }
                }
            }
        }
    }

    @EventHandler
    public void onSignChange(SignChangeEvent event) {
        Player player = event.getPlayer();
        String[] lines = event.getLines();

        if (isATMSign(lines)) {
            if (!player.hasPermission("vault.admin")) {
                player.sendMessage(Vault.getMessage("general.no-permission", "§cYou do not have permission to create an ATM."));
                event.setCancelled(true);
                return;
            }

            // Nicely format the sign lines
            event.setLine(0, "§6§l[VaultX ATM]");
            event.setLine(1, "§7Right-click");
            event.setLine(2, "§7to open");
            event.setLine(3, "§7the menu");
            player.sendMessage(Vault.getMessage("atm.created", "§a§l✔ §aAutomatic Teller Machine (ATM) successfully created."));
        }
    }

    private boolean isATMSign(String[] lines) {
        if (lines == null) return false;
        String configuredHeader = plugin.getConfig().getString("atms.sign-header", "[ATM]").trim().toLowerCase();
        for (String line : lines) {
            if (line != null) {
                String stripped = ChatColor.stripColor(line).trim().toLowerCase();
                if (stripped.equals(configuredHeader) || stripped.equals("[vaultx atm]") || stripped.equals("[vx atm]") || stripped.equals("[atm]")) {
                    return true;
                }
            }
        }
        return false;
    }

    private void restoreCheckItem(Player player, ItemStack itemToRestore) {
        if (player == null || !player.isOnline()) {
            return;
        }
        java.util.HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(itemToRestore);
        if (leftover != null && !leftover.isEmpty()) {
            for (ItemStack leftoverItem : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftoverItem);
            }
        }
    }

    private String getEventHandName(PlayerInteractEvent event) {
        try {
            java.lang.reflect.Method m = event.getClass().getMethod("getHand");
            Object res = m.invoke(event);
            return res != null ? res.toString() : null;
        } catch (Throwable e) {
            return null;
        }
    }

    private void removeOneHandItem(Player player, String handName, ItemStack item) {
        boolean isOffhand = "OFF_HAND".equals(handName);
        int amount = item.getAmount();
        if (amount > 1) {
            item.setAmount(amount - 1);
            if (isOffhand) {
                try {
                    Object inv = player.getInventory();
                    java.lang.reflect.Method m = inv.getClass().getMethod("setItemInOffHand", ItemStack.class);
                    m.invoke(inv, item);
                    return;
                } catch (Throwable ignored) {}
            } else {
                try {
                    Object inv = player.getInventory();
                    java.lang.reflect.Method m = inv.getClass().getMethod("setItemInMainHand", ItemStack.class);
                    m.invoke(inv, item);
                } catch (Throwable ignored) {}
            }
        } else {
            if (isOffhand) {
                try {
                    Object inv = player.getInventory();
                    java.lang.reflect.Method m = inv.getClass().getMethod("setItemInOffHand", ItemStack.class);
                    m.invoke(inv, (Object) null);
                    return;
                } catch (Throwable ignored) {}
            }
            try {
                Object inv = player.getInventory();
                java.lang.reflect.Method m = inv.getClass().getMethod("setItemInMainHand", ItemStack.class);
                m.invoke(inv, (Object) null);
            } catch (Throwable e) {
                player.setItemInHand(null);
            }
        }
    }
}

