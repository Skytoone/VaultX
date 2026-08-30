package net.milkbowl.vault.economy;

import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Modern VaultX API for live item and currency auctions with automatic escrow protection.
 */
public interface VaultAuctionAPI {

    record AuctionListing(
            String auctionId,
            UUID sellerUuid,
            ItemStack item,
            String currency,
            double startingPrice,
            double currentBid,
            UUID highestBidderUuid,
            long durationMs,
            long expiresAtMs,
            boolean isClosed
    ) {}

    /**
     * Creates a new auction listing for an item.
     *
     * @param seller Target seller
     * @param item Item stack to auction
     * @param currency Currency ID for bids
     * @param startingPrice Minimum starting bid
     * @param durationMinutes Duration in minutes
     * @return Future containing the created AuctionListing
     */
    CompletableFuture<AuctionListing> createAuctionAsync(OfflinePlayer seller, ItemStack item, String currency, double startingPrice, long durationMinutes);

    /**
     * Places a bid on an active auction.
     *
     * @param bidder Target bidder
     * @param auctionId Auction unique identifier
     * @param bidAmount Bid amount
     * @return Future containing EconomyResponse
     */
    CompletableFuture<EconomyResponse> placeBidAsync(OfflinePlayer bidder, String auctionId, double bidAmount);

    /**
     * Cancels an active auction if seller matches and no bids have been placed.
     *
     * @param seller Target seller
     * @param auctionId Auction unique identifier
     * @return Future containing EconomyResponse
     */
    CompletableFuture<EconomyResponse> cancelAuctionAsync(OfflinePlayer seller, String auctionId);

    /**
     * Retrieves all active auction listings.
     *
     * @return Future containing list of active listings
     */
    CompletableFuture<List<AuctionListing>> getActiveAuctionsAsync();
}
