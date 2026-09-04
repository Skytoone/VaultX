package net.milkbowl.vault.util;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.Plugin;

import net.milkbowl.vault.gui.view.AdminStatsGUIView;
import net.milkbowl.vault.gui.view.BlackMarketGUIView;
import net.milkbowl.vault.gui.view.DashboardGUIView;
import net.milkbowl.vault.gui.view.EscrowGUIView;
import net.milkbowl.vault.gui.view.ExchangeGUIView;
import net.milkbowl.vault.gui.view.HistoryGUIView;
import net.milkbowl.vault.gui.view.LoansGUIView;
import net.milkbowl.vault.gui.view.MailboxGUIView;
import net.milkbowl.vault.gui.view.StocksGUIView;

/**
 * Facade entrypoint and Bukkit Listener for all VaultX GUI View interfaces.
 */
public class VaultXGUI implements Listener {

    private final Plugin plugin;

    // View components
    private final DashboardGUIView dashboardView;
    private final BlackMarketGUIView blackMarketView;
    private final StocksGUIView stocksView;
    private final LoansGUIView loansView;
    private final AdminStatsGUIView adminStatsView;
    private final HistoryGUIView historyView;
    private final ExchangeGUIView exchangeView;
    private final EscrowGUIView escrowView;
    private final MailboxGUIView mailboxView;

    public VaultXGUI(Plugin plugin) {
        this.plugin = plugin;
        this.dashboardView = new DashboardGUIView(plugin);
        this.blackMarketView = new BlackMarketGUIView(plugin);
        this.stocksView = new StocksGUIView(plugin);
        this.loansView = new LoansGUIView(plugin);
        this.adminStatsView = new AdminStatsGUIView(plugin);
        this.historyView = new HistoryGUIView(plugin);
        this.exchangeView = new ExchangeGUIView(plugin);
        this.escrowView = new EscrowGUIView(plugin);
        this.mailboxView = new MailboxGUIView(plugin);

        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void close() {
        exchangeView.clearAll();
        org.bukkit.event.HandlerList.unregisterAll(this);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (event.getPlayer() != null) {
            UUID uuid = event.getPlayer().getUniqueId();
            exchangeView.clearPlayer(uuid);
        }
    }

    // --- CUSTOM HOLDERS FOR INVENTORY SECURITY ---
    public static class GUIDashboardHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    public static class GUIHistoryHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    public static class GUIExchangeHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    public static class GUIEscrowHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    public static class GUIMailboxHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    public static class GUIStocksHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    public static class GUILoansHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    public static class GUIAdminStatsHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    public static class GUIBlackMarketHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    // --- DELEGATED OPEN METHODS ---
    public void openDashboard(Player player) {
        dashboardView.openDashboard(player);
    }

    public void openBlackMarket(Player player) {
        blackMarketView.openBlackMarket(player);
    }

    public void openStocks(Player player) {
        stocksView.openStocks(player);
    }

    public void openLoans(Player player) {
        loansView.openLoans(player);
    }

    public void openAdminStats(Player player) {
        adminStatsView.openAdminStats(player);
    }

    public void openHistory(Player player) {
        historyView.openHistory(player);
    }

    public void openExchange(Player player) {
        exchangeView.openExchange(player);
    }

    public void openEscrow(Player player) {
        escrowView.openEscrow(player);
    }

    public void openMailbox(Player player) {
        mailboxView.openMailbox(player);
    }

    // --- INVENTORY DRAG EVENT HANDLER ---
    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder == null) return;

        boolean matches = (holder instanceof GUIDashboardHolder ||
                holder instanceof GUIHistoryHolder ||
                holder instanceof GUIExchangeHolder ||
                holder instanceof GUIEscrowHolder ||
                holder instanceof GUIMailboxHolder ||
                holder instanceof GUIStocksHolder ||
                holder instanceof GUILoansHolder ||
                holder instanceof GUIAdminStatsHolder ||
                holder instanceof GUIBlackMarketHolder);

        if (matches) {
            event.setCancelled(true);
        }
    }

    // --- INVENTORY CLICK EVENT HANDLER ---
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder == null)
            return;

        boolean matches = (holder instanceof GUIDashboardHolder ||
                holder instanceof GUIHistoryHolder ||
                holder instanceof GUIExchangeHolder ||
                holder instanceof GUIEscrowHolder ||
                holder instanceof GUIMailboxHolder ||
                holder instanceof GUIStocksHolder ||
                holder instanceof GUILoansHolder ||
                holder instanceof GUIAdminStatsHolder ||
                holder instanceof GUIBlackMarketHolder);

        if (!matches)
            return;

        event.setCancelled(true); // Complete exploit protection!

        if (!(event.getWhoClicked() instanceof Player))
            return;
        Player player = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();

        if (event.getCurrentItem() == null || event.getCurrentItem().getType() == Material.AIR)
            return;

        // Sound feedback
        try {
            Sound clickSound = null;
            try {
                clickSound = Sound.valueOf("BLOCK_WOODEN_BUTTON_CLICK_ON");
            } catch (Exception e1) {
                try {
                    clickSound = Sound.valueOf("WOOD_CLICK");
                } catch (Exception e2) {
                }
            }
            if (clickSound != null) {
                player.playSound(player.getLocation(), clickSound, 0.5f, 1.0f);
            }
        } catch (Exception ignored) {
        }

        if (holder instanceof GUIAdminStatsHolder) {
            adminStatsView.handleClick(player, slot, this);
        } else if (holder instanceof GUIDashboardHolder) {
            dashboardView.handleClick(player, slot, this);
        } else if (holder instanceof GUIBlackMarketHolder) {
            blackMarketView.handleClick(player, slot, this);
        } else if (holder instanceof GUIHistoryHolder) {
            historyView.handleClick(player, slot, this);
        } else if (holder instanceof GUIExchangeHolder) {
            exchangeView.handleClick(player, slot, event, this);
        } else if (holder instanceof GUIEscrowHolder) {
            escrowView.handleClick(player, slot, event, this);
        } else if (holder instanceof GUIMailboxHolder) {
            mailboxView.handleClick(player, slot, this);
        } else if (holder instanceof GUIStocksHolder) {
            stocksView.handleClick(player, slot, event, this);
        } else if (holder instanceof GUILoansHolder) {
            loansView.handleClick(player, slot, event, this);
        }
    }
}
