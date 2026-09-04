package net.milkbowl.vault.persistence.repository;

import net.milkbowl.vault.economy.VaultAuctionAPI;
import net.milkbowl.vault.persistence.DatabaseConnectionManager;
import net.milkbowl.vault.util.ItemSerializer;
import org.bukkit.inventory.ItemStack;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class AuctionRepository {

    private final DatabaseConnectionManager dbManager;

    public AuctionRepository(DatabaseConnectionManager dbManager) {
        this.dbManager = dbManager;
    }

    public void saveAuction(VaultAuctionAPI.AuctionListing listing) {
        if (listing == null) return;
        String sql = dbManager.isPostgreSQL() ?
                "INSERT INTO local_auctions (auction_id, seller_uuid, item_data, currency, starting_price, current_bid, highest_bidder_uuid, duration_ms, expires_at_ms, is_closed) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (auction_id) DO UPDATE SET current_bid = EXCLUDED.current_bid, highest_bidder_uuid = EXCLUDED.highest_bidder_uuid, is_closed = EXCLUDED.is_closed" :
                (dbManager.isMySQL() ? "INSERT INTO local_auctions (auction_id, seller_uuid, item_data, currency, starting_price, current_bid, highest_bidder_uuid, duration_ms, expires_at_ms, is_closed) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE current_bid = VALUES(current_bid), highest_bidder_uuid = VALUES(highest_bidder_uuid), is_closed = VALUES(is_closed)" :
                        "REPLACE INTO local_auctions (auction_id, seller_uuid, item_data, currency, starting_price, current_bid, highest_bidder_uuid, duration_ms, expires_at_ms, is_closed) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
        dbManager.executeDatabaseOperation(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, listing.auctionId());
                ps.setString(2, listing.sellerUuid().toString());
                ps.setString(3, ItemSerializer.serializeItem(listing.item()));
                ps.setString(4, listing.currency());
                ps.setDouble(5, listing.startingPrice());
                ps.setDouble(6, listing.currentBid());
                ps.setString(7, listing.highestBidderUuid() != null ? listing.highestBidderUuid().toString() : null);
                ps.setLong(8, listing.durationMs());
                ps.setLong(9, listing.expiresAtMs());
                ps.setInt(10, listing.isClosed() ? 1 : 0);
                ps.executeUpdate();
            }
        }, "Failed to save auction listing");
    }

    public void deleteAuction(String auctionId) {
        String sql = "DELETE FROM local_auctions WHERE auction_id = ?";
        dbManager.executeDatabaseOperation(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, auctionId);
                ps.executeUpdate();
            }
        }, "Failed to delete auction");
    }

    public Map<String, VaultAuctionAPI.AuctionListing> loadAllAuctions() {
        String query = "SELECT auction_id, seller_uuid, item_data, currency, starting_price, current_bid, highest_bidder_uuid, duration_ms, expires_at_ms, is_closed FROM local_auctions WHERE is_closed = 0";
        return dbManager.executeDatabaseQuery(conn -> {
            Map<String, VaultAuctionAPI.AuctionListing> result = new HashMap<>();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(query)) {
                while (rs.next()) {
                    try {
                        String id = rs.getString("auction_id");
                        UUID seller = UUID.fromString(rs.getString("seller_uuid"));
                        ItemStack item = ItemSerializer.deserializeItem(rs.getString("item_data"));
                        String curr = rs.getString("currency");
                        double startPrice = rs.getDouble("starting_price");
                        double currentBid = rs.getDouble("current_bid");
                        String hbStr = rs.getString("highest_bidder_uuid");
                        UUID highestBidder = (hbStr != null && !hbStr.isEmpty()) ? UUID.fromString(hbStr) : null;
                        long duration = rs.getLong("duration_ms");
                        long expiresAt = rs.getLong("expires_at_ms");
                        boolean isClosed = rs.getInt("is_closed") == 1;

                        if (item != null) {
                            result.put(id, new VaultAuctionAPI.AuctionListing(
                                    id, seller, item, curr, startPrice, currentBid, highestBidder, duration, expiresAt, isClosed
                            ));
                        }
                    } catch (Exception ignored) {}
                }
            }
            return result;
        }, new HashMap<>(), "Failed to load active auctions");
    }

    public void savePendingAuctionItem(UUID uuid, ItemStack item) {
        if (uuid == null || item == null) return;
        String sql = "INSERT INTO local_pending_auction_items (uuid, item_data) VALUES (?, ?)";
        dbManager.executeDatabaseOperation(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, ItemSerializer.serializeItem(item));
                ps.executeUpdate();
            }
        }, "Failed to save pending auction item");
    }

    public Map<UUID, List<ItemStack>> loadAllPendingAuctionItems() {
        String query = "SELECT uuid, item_data FROM local_pending_auction_items";
        return dbManager.executeDatabaseQuery(conn -> {
            Map<UUID, List<ItemStack>> result = new HashMap<>();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(query)) {
                while (rs.next()) {
                    try {
                        UUID uuid = UUID.fromString(rs.getString("uuid"));
                        ItemStack item = ItemSerializer.deserializeItem(rs.getString("item_data"));
                        if (item != null) {
                            result.computeIfAbsent(uuid, k -> new ArrayList<>()).add(item);
                        }
                    } catch (Exception ignored) {}
                }
            }
            return result;
        }, new HashMap<>(), "Failed to load pending auction items");
    }

    public void deletePendingAuctionItems(UUID uuid) {
        if (uuid == null) return;
        String sql = "DELETE FROM local_pending_auction_items WHERE uuid = ?";
        dbManager.executeDatabaseOperation(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            }
        }, "Failed to delete pending auction items");
    }
}
