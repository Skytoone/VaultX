package net.milkbowl.vault.security;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class AnomalyDetector {

    private final Plugin plugin;
    private final Map<UUID, List<TransactionRecord>> transactionHistory = new ConcurrentHashMap<>();
    private final Queue<TransferEdge> activeTransfers = new ConcurrentLinkedQueue<>();

    public static class TransactionRecord {
        public final long timestamp;
        public final double amount;

        public TransactionRecord(long timestamp, double amount) {
            this.timestamp = timestamp;
            this.amount = amount;
        }
    }

    public static class TransferEdge {
        public final UUID from;
        public final UUID to;
        public final long timestamp;

        public TransferEdge(UUID from, UUID to, long timestamp) {
            this.from = from;
            this.to = to;
            this.timestamp = timestamp;
        }
    }

    public AnomalyDetector(Plugin plugin) {
        this.plugin = plugin;
    }

    public void clear() {
        transactionHistory.clear();
        activeTransfers.clear();
    }

    public void invalidateCache(OfflinePlayer player) {
        if (player != null) {
            transactionHistory.remove(player.getUniqueId());
        }
    }

    public void purgePlayer(UUID uuid) {
        if (uuid != null) {
            transactionHistory.remove(uuid);
        }
    }

    public boolean isSpikeDetected(OfflinePlayer player, double amount, String type, double currentBalance, int timeWindowSeconds, double minimumThreshold, double spikeFactor) {
        if (player == null || !player.isOnline()) return false;
        long now = System.currentTimeMillis();
        List<TransactionRecord> history = transactionHistory.computeIfAbsent(player.getUniqueId(), k -> new ArrayList<>());

        synchronized (history) {
            long cutoff = now - (timeWindowSeconds * 1000L);
            history.removeIf(record -> record.timestamp < cutoff);

            double sum = amount;
            for (TransactionRecord r : history) {
                sum += r.amount;
            }

            if (sum > minimumThreshold) {
                double checkBalance = Math.max(currentBalance, 100.0);
                if (sum > checkBalance * spikeFactor) {
                    return true;
                }
            }
        }
        return false;
    }

    public void recordTransaction(OfflinePlayer player, double amount, int timeWindowSeconds) {
        if (player == null || !player.isOnline()) return;
        long now = System.currentTimeMillis();
        List<TransactionRecord> history = transactionHistory.computeIfAbsent(player.getUniqueId(), k -> new ArrayList<>());
        synchronized (history) {
            long cutoff = now - (timeWindowSeconds * 1000L);
            history.removeIf(record -> record.timestamp < cutoff);
            history.add(new TransactionRecord(now, amount));
            transactionHistory.putIfAbsent(player.getUniqueId(), history);
        }
    }

    public List<UUID> checkCircularTransfer(OfflinePlayer sender, OfflinePlayer receiver, double amount, int timeWindowSeconds, double minimumThreshold) {
        if (sender == null || receiver == null) return null;
        UUID from = sender.getUniqueId();
        UUID to = receiver.getUniqueId();
        long now = System.currentTimeMillis();

        long cutoff = now - (timeWindowSeconds * 1000L);
        while (!activeTransfers.isEmpty()) {
            TransferEdge head = activeTransfers.peek();
            if (head == null || head.timestamp >= cutoff) {
                break;
            }
            activeTransfers.poll();
        }

        activeTransfers.add(new TransferEdge(from, to, now));

        if (amount >= minimumThreshold) {
            return findPath(to, from);
        }
        return null;
    }

    public List<UUID> findPath(UUID start, UUID target) {
        Map<UUID, List<UUID>> adjMap = new HashMap<>();
        for (TransferEdge edge : activeTransfers) {
            adjMap.computeIfAbsent(edge.from, k -> new ArrayList<>()).add(edge.to);
        }

        Queue<UUID> queue = new LinkedList<>();
        Map<UUID, UUID> parentMap = new HashMap<>();
        Map<UUID, Integer> depthMap = new HashMap<>();
        Set<UUID> visited = new HashSet<>();

        queue.add(start);
        visited.add(start);
        depthMap.put(start, 0);

        boolean found = false;
        while (!queue.isEmpty()) {
            UUID curr = queue.poll();
            if (curr.equals(target)) {
                found = true;
                break;
            }
            int currentDepth = depthMap.getOrDefault(curr, 0);
            if (currentDepth >= 5) {
                continue;
            }

            List<UUID> neighbors = adjMap.get(curr);
            if (neighbors != null) {
                for (UUID neighbor : neighbors) {
                    if (!visited.contains(neighbor)) {
                        visited.add(neighbor);
                        parentMap.put(neighbor, curr);
                        depthMap.put(neighbor, currentDepth + 1);
                        queue.add(neighbor);
                    }
                }
            }
        }

        if (found) {
            List<UUID> path = new ArrayList<>();
            UUID curr = target;
            while (curr != null) {
                path.add(0, curr);
                curr = parentMap.get(curr);
            }
            return path;
        }
        return null;
    }

    public void pruneExpiredData(int timeWindowSeconds) {
        long cutoffTime = System.currentTimeMillis() - (timeWindowSeconds * 1000L);
        for (Map.Entry<UUID, List<TransactionRecord>> entry : transactionHistory.entrySet()) {
            UUID playerUuid = entry.getKey();
            List<TransactionRecord> history = entry.getValue();
            if (history != null) {
                synchronized (history) {
                    history.removeIf(record -> record.timestamp < cutoffTime);
                    if (history.isEmpty() && Bukkit.getPlayer(playerUuid) == null) {
                        transactionHistory.remove(playerUuid, history);
                    }
                }
            }
        }

        long transferCutoff = System.currentTimeMillis() - (timeWindowSeconds * 1000L);
        while (!activeTransfers.isEmpty()) {
            TransferEdge head = activeTransfers.peek();
            if (head == null || head.timestamp >= transferCutoff) {
                break;
            }
            activeTransfers.poll();
        }
    }
}
