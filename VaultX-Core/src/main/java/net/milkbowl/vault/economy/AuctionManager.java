package net.milkbowl.vault.economy;

import net.milkbowl.vault.redis.LocalFailoverManager;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.BiFunction;

public class AuctionManager implements Listener {

    private final Plugin plugin;
    private final Map<String, VaultAuctionAPI.AuctionListing> activeAuctions = new ConcurrentHashMap<>();
    private final Map<UUID, List<ItemStack>> pendingClaimItems = new ConcurrentHashMap<>();

    public AuctionManager(Plugin plugin) {
        this.plugin = plugin;
        if (plugin != null) {
            Bukkit.getPluginManager().registerEvents(this, plugin);
        }
        loadAllFromDb();
    }

    public Plugin getPlugin() {
        return plugin;
    }

    public void loadAllFromDb() {
        LocalFailoverManager failover = LocalFailoverManager.getInstance();
        if (failover != null) {
            Map<String, VaultAuctionAPI.AuctionListing> loaded = failover.loadAllAuctions();
            activeAuctions.putAll(loaded);

            Map<UUID, List<ItemStack>> pendingLoaded = failover.loadAllPendingAuctionItems();
            if (pendingLoaded != null) {
                for (Map.Entry<UUID, List<ItemStack>> entry : pendingLoaded.entrySet()) {
                    pendingClaimItems.put(entry.getKey(), new java.util.concurrent.CopyOnWriteArrayList<>(entry.getValue()));
                }
            }
        }
    }

    private void giveOrDropItem(UUID targetUuid, ItemStack item) {
        if (targetUuid == null || item == null) return;
        Player onlinePlayer = Bukkit.getPlayer(targetUuid);
        if (onlinePlayer != null && onlinePlayer.isOnline() && plugin != null) {
            net.milkbowl.vault.util.FoliaScheduler.runEntitySync(plugin, onlinePlayer, () -> {
                Map<Integer, ItemStack> leftover = onlinePlayer.getInventory().addItem(item.clone());
                for (ItemStack remaining : leftover.values()) {
                    onlinePlayer.getWorld().dropItemNaturally(onlinePlayer.getLocation(), remaining);
                }
            });
        } else {
            synchronized (pendingClaimItems) {
                pendingClaimItems.computeIfAbsent(targetUuid, k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(item.clone());
            }
            LocalFailoverManager failover = LocalFailoverManager.getInstance();
            if (failover != null) {
                net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
                    failover.savePendingAuctionItem(targetUuid, item);
                });
            }
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        List<ItemStack> items;
        synchronized (pendingClaimItems) {
            items = pendingClaimItems.remove(uuid);
        }
        if (items != null && !items.isEmpty()) {
            net.milkbowl.vault.util.FoliaScheduler.runEntitySync(plugin, player, () -> {
                for (ItemStack item : items) {
                    Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
                    for (ItemStack remaining : leftover.values()) {
                        player.getWorld().dropItemNaturally(player.getLocation(), remaining);
                    }
                }
                player.sendMessage("§a§l[Auction] §aYou received §e" + items.size() + " §aitem(s) from auction claims!");
            });
            LocalFailoverManager failover = LocalFailoverManager.getInstance();
            if (failover != null) {
                net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
                    failover.deletePendingAuctionItems(uuid);
                });
            }
        }
    }

    public CompletableFuture<VaultAuctionAPI.AuctionListing> createAuctionAsync(
            OfflinePlayer seller, ItemStack item, String currency, double startingPrice, long durationMinutes, ExecutorService executor) {
        java.util.function.Supplier<VaultAuctionAPI.AuctionListing> supplier = () -> {
            if (seller == null || item == null || Double.isNaN(startingPrice) || Double.isInfinite(startingPrice) || startingPrice <= 0) return null;
            String auctionId = "auc_" + UUID.randomUUID().toString().substring(0, 8);
            long now = System.currentTimeMillis();
            long expiresAt = now + (durationMinutes * 60L * 1000L);
            VaultAuctionAPI.AuctionListing listing = new VaultAuctionAPI.AuctionListing(
                    auctionId, seller.getUniqueId(), item, currency == null ? "default" : currency,
                    startingPrice, startingPrice, null, durationMinutes * 60L * 1000L, expiresAt, false
            );
            activeAuctions.put(auctionId, listing);

            LocalFailoverManager failover = LocalFailoverManager.getInstance();
            if (failover != null) {
                failover.saveAuction(listing);
            }
            return listing;
        };
        return executor != null ? CompletableFuture.supplyAsync(supplier, executor) : CompletableFuture.supplyAsync(supplier);
    }

    public CompletableFuture<EconomyResponse> placeBidAsync(
            OfflinePlayer bidder, String auctionId, double bidAmount,
            BiFunction<OfflinePlayer, String, Double> getBalFunc,
            TriFunction<OfflinePlayer, String, Double, EconomyResponse> withdrawFunc,
            TriFunction<OfflinePlayer, String, Double, EconomyResponse> depositFunc,
            ExecutorService executor) {
        java.util.function.Supplier<EconomyResponse> supplier = () -> {
            if (bidder == null || auctionId == null || Double.isNaN(bidAmount) || Double.isInfinite(bidAmount) || bidAmount <= 0) {
                return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Invalid bidder or auctionId");
            }
            synchronized (activeAuctions) {
                VaultAuctionAPI.AuctionListing listing = activeAuctions.get(auctionId);
                if (listing == null || listing.isClosed() || System.currentTimeMillis() > listing.expiresAtMs()) {
                    return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Auction closed or expired");
                }
                if (bidAmount <= listing.currentBid()) {
                    return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Bid amount must be higher than current bid");
                }
                EconomyResponse res = withdrawFunc.apply(bidder, listing.currency(), bidAmount);
                if (!res.transactionSuccess()) return res;

                if (listing.highestBidderUuid() != null) {
                    OfflinePlayer previousBidder = null;
                    String prevName = net.milkbowl.vault.util.UUIDCache.getName(listing.highestBidderUuid());
                    if (prevName != null) {
                        previousBidder = net.milkbowl.vault.util.UUIDCache.getOfflinePlayerFast(prevName);
                    }
                    if (previousBidder == null) {
                        previousBidder = org.bukkit.Bukkit.getOfflinePlayer(listing.highestBidderUuid());
                    }
                    if (depositFunc != null && previousBidder != null) {
                        depositFunc.apply(previousBidder, listing.currency(), listing.currentBid());
                    }
                }

                VaultAuctionAPI.AuctionListing updated = new VaultAuctionAPI.AuctionListing(
                        listing.auctionId(), listing.sellerUuid(), listing.item(), listing.currency(),
                        listing.startingPrice(), bidAmount, bidder.getUniqueId(), listing.durationMs(), listing.expiresAtMs(), false
                );
                activeAuctions.put(auctionId, updated);

                LocalFailoverManager failover = LocalFailoverManager.getInstance();
                if (failover != null) {
                    failover.saveAuction(updated);
                }
                return new EconomyResponse(bidAmount, getBalFunc.apply(bidder, listing.currency()), EconomyResponse.ResponseType.SUCCESS, "Bid placed successfully");
            }
        };
        return executor != null ? CompletableFuture.supplyAsync(supplier, executor) : CompletableFuture.supplyAsync(supplier);
    }

    public CompletableFuture<EconomyResponse> cancelAuctionAsync(OfflinePlayer seller, String auctionId, ExecutorService executor) {
        java.util.function.Supplier<EconomyResponse> supplier = () -> {
            if (seller == null || auctionId == null) {
                return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Invalid arguments");
            }
            VaultAuctionAPI.AuctionListing listing = activeAuctions.get(auctionId);
            if (listing == null || !listing.sellerUuid().equals(seller.getUniqueId())) {
                return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Auction not found or unauthorized");
            }
            if (listing.highestBidderUuid() != null) {
                return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Cannot cancel auction with active bids");
            }
            activeAuctions.remove(auctionId);

            LocalFailoverManager failover = LocalFailoverManager.getInstance();
            if (failover != null) {
                failover.deleteAuction(auctionId);
            }

            // Return item to seller
            giveOrDropItem(listing.sellerUuid(), listing.item());

            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.SUCCESS, "Auction cancelled and item returned");
        };
        return executor != null ? CompletableFuture.supplyAsync(supplier, executor) : CompletableFuture.supplyAsync(supplier);
    }

    public CompletableFuture<List<VaultAuctionAPI.AuctionListing>> getActiveAuctionsAsync(ExecutorService executor) {
        java.util.function.Supplier<List<VaultAuctionAPI.AuctionListing>> supplier = () -> {
            long now = System.currentTimeMillis();
            return activeAuctions.values().stream()
                    .filter(a -> !a.isClosed() && a.expiresAtMs() > now)
                    .toList();
        };
        return executor != null ? CompletableFuture.supplyAsync(supplier, executor) : CompletableFuture.supplyAsync(supplier);
    }

    public CompletableFuture<Boolean> settleAuctionAsync(
            String auctionId,
            TriFunction<OfflinePlayer, String, Double, EconomyResponse> depositFunc,
            ExecutorService executor) {
        java.util.function.Supplier<Boolean> supplier = () -> {
            synchronized (activeAuctions) {
                VaultAuctionAPI.AuctionListing listing = activeAuctions.get(auctionId);
                if (listing == null || listing.isClosed()) return false;

                long now = System.currentTimeMillis();
                if (now < listing.expiresAtMs()) return false;

                VaultAuctionAPI.AuctionListing closedListing = new VaultAuctionAPI.AuctionListing(
                        listing.auctionId(), listing.sellerUuid(), listing.item(), listing.currency(),
                        listing.startingPrice(), listing.currentBid(), listing.highestBidderUuid(), listing.durationMs(), listing.expiresAtMs(), true
                );
                activeAuctions.put(auctionId, closedListing);

                LocalFailoverManager failover = LocalFailoverManager.getInstance();
                if (failover != null) {
                    failover.saveAuction(closedListing);
                }

                if (listing.highestBidderUuid() != null) {
                    OfflinePlayer seller = net.milkbowl.vault.util.UUIDCache.getOfflinePlayerFast(
                            net.milkbowl.vault.util.UUIDCache.getName(listing.sellerUuid()));
                    if (seller == null) seller = org.bukkit.Bukkit.getOfflinePlayer(listing.sellerUuid());
                    if (depositFunc != null) {
                        depositFunc.apply(seller, listing.currency(), listing.currentBid());
                    }
                    if (net.milkbowl.vault.Vault.getMailboxManager() != null) {
                        net.milkbowl.vault.Vault.getMailboxManager().sendOfflineMail(
                                listing.sellerUuid(), "Auction House",
                                "Your auction for " + listing.item().getType() + " ended. Payout: " + listing.currentBid() + " " + listing.currency(),
                                0.0, listing.currency()
                        );
                    }
                    // Deliver item to winning bidder
                    giveOrDropItem(listing.highestBidderUuid(), listing.item());
                } else {
                    if (net.milkbowl.vault.Vault.getMailboxManager() != null) {
                        net.milkbowl.vault.Vault.getMailboxManager().sendOfflineMail(
                                listing.sellerUuid(), "Auction House",
                                "Your auction for " + listing.item().getType() + " expired with no bids. Item returned.",
                                0.0, listing.currency()
                        );
                    }
                    // Return item to seller if no bids
                    giveOrDropItem(listing.sellerUuid(), listing.item());
                }
                return true;
            }
        };
        return executor != null ? CompletableFuture.supplyAsync(supplier, executor) : CompletableFuture.supplyAsync(supplier);
    }

    public void saveAllAndClear() {
        LocalFailoverManager failover = LocalFailoverManager.getInstance();
        if (failover != null) {
            for (VaultAuctionAPI.AuctionListing listing : activeAuctions.values()) {
                failover.saveAuction(listing);
            }
        }
        activeAuctions.clear();
    }

    @FunctionalInterface
    public interface TriFunction<A, B, C, R> {
        R apply(A a, B b, C c);
    }
}

