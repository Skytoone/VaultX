package net.milkbowl.vault.economy.events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Asynchronous event dispatched when a stock market ticker updates price.
 */
public class VaultStockPriceChangeEvent extends Event {

    private static final HandlerList handlers = new HandlerList();

    private final String stockSymbol;
    private final double oldPrice;
    private final double newPrice;
    private final double percentageChange;

    public VaultStockPriceChangeEvent(String stockSymbol, double oldPrice, double newPrice) {
        super(false);
        this.stockSymbol = stockSymbol;
        this.oldPrice = oldPrice;
        this.newPrice = newPrice;
        this.percentageChange = oldPrice > 0 ? ((newPrice - oldPrice) / oldPrice) * 100.0 : 0.0;
    }

    public String getStockSymbol() {
        return stockSymbol;
    }

    public double getOldPrice() {
        return oldPrice;
    }

    public double getNewPrice() {
        return newPrice;
    }

    public double getPercentageChange() {
        return percentageChange;
    }

    @Override
    public HandlerList getHandlers() {
        return handlers;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }
}
