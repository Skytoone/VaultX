package net.milkbowl.vault.economy;

import org.bukkit.entity.Player;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Stock Exchange & Commodities Market API.
 */
public interface VaultStockAPI {

    double getCommodityPrice(String commodity);

    double getPlayerShares(UUID playerUuid, String commodityOrBank);

    CompletableFuture<Boolean> buySharesAsync(Player player, String commodity, double shares);

    CompletableFuture<Boolean> sellSharesAsync(Player player, String commodity, double shares);
}
