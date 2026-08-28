package net.milkbowl.vault.util;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class UUIDCache implements Listener {

    private static final Map<String, UUID> nameToUuid = new ConcurrentHashMap<>();
    private static final Map<UUID, String> uuidToName = new ConcurrentHashMap<>();
    private static final Map<String, Long> negativeCache = new ConcurrentHashMap<>();
    private static final java.util.Set<String> accessOrderNameQueue = new java.util.LinkedHashSet<>();
    private static final java.util.Set<UUID> accessOrderUuidQueue = new java.util.LinkedHashSet<>();
    private static final long NEGATIVE_CACHE_EXPIRATION_MS = 300000; // 5 minutes
    private static final int MAX_NEGATIVE_CACHE_SIZE = 2000;
    private static boolean enabled = false;

    private static Plugin pluginInstance;
    private static UUIDCache registeredListenerInstance = null;

    private static int maxCacheSize = 10000;
    private static org.bukkit.scheduler.BukkitTask cleanupTask = null;

    private static synchronized void putCache(String name, UUID uuid) {
        if (name == null || uuid == null) return;
        String lowerName = name.toLowerCase();
        UUID oldUuid = nameToUuid.get(lowerName);
        if (oldUuid != null && !oldUuid.equals(uuid)) {
            uuidToName.remove(oldUuid);
            accessOrderUuidQueue.remove(oldUuid);
        }
        
        accessOrderNameQueue.remove(lowerName);
        accessOrderNameQueue.add(lowerName);
        accessOrderUuidQueue.remove(uuid);
        accessOrderUuidQueue.add(uuid);

        if (nameToUuid.size() >= maxCacheSize && !nameToUuid.containsKey(lowerName)) {
            java.util.Iterator<String> it = accessOrderNameQueue.iterator();
            if (it.hasNext()) {
                String oldestName = it.next();
                it.remove();
                if (oldestName != null) {
                    UUID removedUuid = nameToUuid.remove(oldestName);
                    if (removedUuid != null) {
                        uuidToName.remove(removedUuid);
                        accessOrderUuidQueue.remove(removedUuid);
                    }
                }
            }
        }
        if (uuidToName.size() >= maxCacheSize && !uuidToName.containsKey(uuid)) {
            java.util.Iterator<UUID> it = accessOrderUuidQueue.iterator();
            if (it.hasNext()) {
                UUID oldestUuid = it.next();
                it.remove();
                if (oldestUuid != null) {
                    String removedName = uuidToName.remove(oldestUuid);
                    if (removedName != null) {
                        nameToUuid.remove(removedName.toLowerCase());
                        accessOrderNameQueue.remove(removedName.toLowerCase());
                    }
                }
            }
        }
        nameToUuid.put(lowerName, uuid);
        uuidToName.put(uuid, name);
    }

    private static final java.lang.invoke.MethodHandle GET_OFFLINE_PLAYER_IF_CACHED_MH;
    static {
        java.lang.invoke.MethodHandle mh = null;
        try {
            java.lang.reflect.Method method = Bukkit.class.getMethod("getOfflinePlayerIfCached", String.class);
            mh = java.lang.invoke.MethodHandles.lookup().unreflect(method);
        } catch (Throwable ignored) {}
        GET_OFFLINE_PLAYER_IF_CACHED_MH = mh;
    }

    public static void init(Plugin plugin, boolean isEnabled) {
        cleanup();
        pluginInstance = plugin;
        enabled = isEnabled;
        if (plugin != null) {
            maxCacheSize = plugin.getConfig().getInt("advanced.uuid-cache-max-size", 10000);
        }
        if (enabled) {
            registeredListenerInstance = new UUIDCache();
            Bukkit.getPluginManager().registerEvents(registeredListenerInstance, plugin);
            preload(plugin);
            // Schedule periodic cleanup for expired negativeCache entries (every 5 minutes = 6000 ticks)
            cleanupTask = net.milkbowl.vault.util.FoliaScheduler.runTimerAsync(plugin, () -> {
                long now = System.currentTimeMillis();
                negativeCache.values().removeIf(expiry -> expiry != null && now > expiry);
            }, 6000L, 6000L);
        }
    }

    public static synchronized void cleanup() {
        nameToUuid.clear();
        uuidToName.clear();
        negativeCache.clear();
        accessOrderNameQueue.clear();
        accessOrderUuidQueue.clear();
        if (cleanupTask != null) {
            try {
                cleanupTask.cancel();
            } catch (Exception ignored) {}
            cleanupTask = null;
        }
        if (registeredListenerInstance != null) {
            org.bukkit.event.HandlerList.unregisterAll(registeredListenerInstance);
            registeredListenerInstance = null;
        }
        pluginInstance = null;
        enabled = false;
    }

    public static void preload(Plugin plugin) {
        if (!enabled) return;
        boolean preloadEnabled = plugin.getConfig().getBoolean("advanced.preload-offline-players", true);
        if (!preloadEnabled) return;

        net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, new Runnable() {
            @Override
            public void run() {
                plugin.getLogger().info("[UUIDCache] Starting async pre-loading of offline players...");
                long start = System.currentTimeMillis();
                int count = 0;
                boolean loadedFromUserCache = false;
                
                java.io.File userCacheFile = new java.io.File("usercache.json");
                if (userCacheFile.exists()) {
                    try {
                        byte[] bytes = java.nio.file.Files.readAllBytes(userCacheFile.toPath());
                        String content = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                        org.json.JSONArray array = new org.json.JSONArray(content);
                        for (int i = 0; i < array.length(); i++) {
                            org.json.JSONObject obj = array.getJSONObject(i);
                            if (obj.has("name") && obj.has("uuid")) {
                                String name = obj.getString("name");
                                String uuidStr = obj.getString("uuid");
                                try {
                                    UUID uuid = UUID.fromString(uuidStr);
                                    putCache(name, uuid);
                                    count++;
                                } catch (IllegalArgumentException e) {}
                            }
                        }
                        loadedFromUserCache = true;
                        long end = System.currentTimeMillis();
                        plugin.getLogger().info("[UUIDCache] Fast pre-loaded " + count + " offline players from usercache.json in " + (end - start) + "ms.");
                    } catch (Exception e) {
                        plugin.getLogger().warning("[UUIDCache] Failed to parse usercache.json, falling back to Bukkit: " + e.getMessage());
                    }
                }

                if (!loadedFromUserCache) {
                    try {
                        OfflinePlayer[] offlinePlayers = Bukkit.getOfflinePlayers();
                        if (offlinePlayers != null) {
                            for (OfflinePlayer op : offlinePlayers) {
                                if (op != null) {
                                    String name = op.getName();
                                    UUID uuid = op.getUniqueId();
                                    if (name != null && uuid != null) {
                                        putCache(name, uuid);
                                        count++;
                                    }
                                }
                            }
                        }
                        long end = System.currentTimeMillis();
                        plugin.getLogger().info("[UUIDCache] Pre-loaded " + count + " offline players in " + (end - start) + "ms.");
                    } catch (Exception e) {
                        plugin.getLogger().warning("[UUIDCache] Error pre-loading offline players: " + e.getMessage());
                    }
                }
            }
        });
    }

    public static boolean isEnabled() {
        return enabled;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!enabled) return;
        UUID uuid = event.getPlayer().getUniqueId();
        String name = event.getPlayer().getName();
        if (name != null) {
            negativeCache.remove(name.toLowerCase());
            putCache(name, uuid);
            net.milkbowl.vault.redis.LocalFailoverManager fm = net.milkbowl.vault.Vault.getFailoverManager();
            if (fm != null) {
                fm.saveUuidCache(name, uuid);
            }
        }
    }

    public static String getName(java.util.UUID uuid) {
        if (uuid == null) return null;
        if (enabled) {
            String name = uuidToName.get(uuid);
            if (name != null) return name;
        }

        Player online = Bukkit.getPlayer(uuid);
        if (online != null && online.getName() != null) {
            if (enabled) {
                putCache(online.getName(), uuid);
            }
            return online.getName();
        }
        
        if (Bukkit.isPrimaryThread()) {
            Plugin plugin = pluginInstance != null ? pluginInstance : Bukkit.getPluginManager().getPlugin("Vault");
            if (plugin != null) {
                net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
                    try {
                        String dbName = null;
                        if (enabled) {
                            net.milkbowl.vault.redis.LocalFailoverManager fm = net.milkbowl.vault.Vault.getFailoverManager();
                            if (fm != null) {
                                dbName = fm.getNameFromCache(uuid);
                            }
                            if (dbName == null) {
                                net.milkbowl.vault.redis.VaultRedisManager redis = net.milkbowl.vault.redis.VaultRedisManager.getInstance();
                                if (redis != null && redis.isOnline()) {
                                    dbName = redis.getNameFromRedis(uuid);
                                }
                            }
                        }
                        if (dbName != null) {
                            putCache(dbName, uuid);
                        }
                    } catch (Exception ignored) {}
                });
            }
            return null;
        } else {
            if (enabled) {
                net.milkbowl.vault.redis.LocalFailoverManager fm = net.milkbowl.vault.Vault.getFailoverManager();
                if (fm != null) {
                    String dbName = fm.getNameFromCache(uuid);
                    if (dbName != null) {
                        putCache(dbName, uuid);
                        return dbName;
                    }
                }
                net.milkbowl.vault.redis.VaultRedisManager redis = net.milkbowl.vault.redis.VaultRedisManager.getInstance();
                if (redis != null && redis.isOnline()) {
                    String dbName = redis.getNameFromRedis(uuid);
                    if (dbName != null) {
                        putCache(dbName, uuid);
                        net.milkbowl.vault.redis.LocalFailoverManager fm2 = net.milkbowl.vault.Vault.getFailoverManager();
                        if (fm2 != null) {
                            fm2.saveUuidCache(dbName, uuid);
                        }
                        return dbName;
                    }
                }
            }
            OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
            if (op != null && op.getName() != null) {
                if (enabled) {
                    putCache(op.getName(), uuid);
                    net.milkbowl.vault.redis.LocalFailoverManager fm = net.milkbowl.vault.Vault.getFailoverManager();
                    if (fm != null) {
                        fm.saveUuidCache(op.getName(), uuid);
                    }
                }
                return op.getName();
            }
        }
        return null;
    }

    private static void putNegativeCache(String name) {
        if (name == null) return;
        if (negativeCache.size() >= MAX_NEGATIVE_CACHE_SIZE) {
            long now = System.currentTimeMillis();
            negativeCache.values().removeIf(expiry -> expiry != null && now > expiry);
            if (negativeCache.size() >= MAX_NEGATIVE_CACHE_SIZE) {
                negativeCache.clear();
            }
        }
        negativeCache.put(name.toLowerCase(), System.currentTimeMillis() + NEGATIVE_CACHE_EXPIRATION_MS);
    }

    public static OfflinePlayer getOfflinePlayerIfCached(String name) {
        if (GET_OFFLINE_PLAYER_IF_CACHED_MH != null) {
            try {
                return (OfflinePlayer) GET_OFFLINE_PLAYER_IF_CACHED_MH.invoke(name);
            } catch (Throwable e) {
                return null;
            }
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    public static OfflinePlayer getOfflinePlayerFast(String name) {
        if (name == null || name.isEmpty()) return null;
        if (enabled) {
            Long expire = negativeCache.get(name.toLowerCase());
            if (expire != null) {
                if (System.currentTimeMillis() < expire) {
                    return null;
                } else {
                    negativeCache.remove(name.toLowerCase());
                }
            }
        }
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            if (enabled) {
                putCache(online.getName(), online.getUniqueId());
            }
            return online;
        }
        if (enabled) {
            UUID cached = nameToUuid.get(name.toLowerCase());
            if (cached != null) {
                return Bukkit.getOfflinePlayer(cached);
            }
            
            // Try resolving via Bukkit's user cache without blocking
            OfflinePlayer cachedPlayer = getOfflinePlayerIfCached(name);
            if (cachedPlayer != null && cachedPlayer.getUniqueId() != null && cachedPlayer.getName() != null) {
                putCache(cachedPlayer.getName(), cachedPlayer.getUniqueId());
                return cachedPlayer;
            }
        }
        
        if (Bukkit.isPrimaryThread()) {
            Plugin plugin = pluginInstance != null ? pluginInstance : Bukkit.getPluginManager().getPlugin("Vault");
            if (plugin != null) {
                net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
                    try {
                        UUID dbUuid = null;
                        net.milkbowl.vault.redis.LocalFailoverManager fm = net.milkbowl.vault.Vault.getFailoverManager();
                        if (enabled && fm != null) {
                            dbUuid = fm.getUuidFromCache(name);
                        }
                        if (enabled && dbUuid == null) {
                            net.milkbowl.vault.redis.VaultRedisManager redis = net.milkbowl.vault.redis.VaultRedisManager.getInstance();
                            if (redis != null && redis.isOnline()) {
                                dbUuid = redis.getUuidFromRedis(name);
                            }
                        }
                        if (dbUuid != null) {
                            if (enabled) {
                                putCache(name, dbUuid);
                            }
                        } else {
                            OfflinePlayer op = getOfflinePlayerIfCached(name);
                            if (op != null && op.getUniqueId() != null && op.getName() != null && (op.hasPlayedBefore() || op.isOnline())) {
                                if (enabled) {
                                    putCache(op.getName(), op.getUniqueId());
                                    if (fm != null) {
                                        fm.saveUuidCache(op.getName(), op.getUniqueId());
                                    }
                                }
                            } else {
                                if (enabled) {
                                    putNegativeCache(name);
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                });
            }
            return null; // Return null to prevent main thread lag for cold fetch
        } else {
            if (enabled) {
                net.milkbowl.vault.redis.LocalFailoverManager fm = net.milkbowl.vault.Vault.getFailoverManager();
                if (fm != null) {
                    UUID dbUuid = fm.getUuidFromCache(name);
                    if (dbUuid != null) {
                        putCache(name, dbUuid);
                        return Bukkit.getOfflinePlayer(dbUuid);
                    }
                }
                net.milkbowl.vault.redis.VaultRedisManager redis = net.milkbowl.vault.redis.VaultRedisManager.getInstance();
                if (redis != null && redis.isOnline()) {
                    UUID dbUuid = redis.getUuidFromRedis(name);
                    if (dbUuid != null) {
                        putCache(name, dbUuid);
                        net.milkbowl.vault.redis.LocalFailoverManager fm2 = net.milkbowl.vault.Vault.getFailoverManager();
                        if (fm2 != null) {
                            fm2.saveUuidCache(name, dbUuid);
                        }
                        return Bukkit.getOfflinePlayer(dbUuid);
                    }
                }
            }
            OfflinePlayer op = Bukkit.getOfflinePlayer(name);
            if (op != null && op.getUniqueId() != null && op.getName() != null && (op.hasPlayedBefore() || op.isOnline())) {
                if (enabled) {
                    putCache(op.getName(), op.getUniqueId());
                    net.milkbowl.vault.redis.LocalFailoverManager fm = net.milkbowl.vault.Vault.getFailoverManager();
                    if (fm != null) {
                        fm.saveUuidCache(op.getName(), op.getUniqueId());
                    }
                }
                return op;
            } else {
                if (enabled) {
                    putNegativeCache(name);
                }
            }
        }
        return Bukkit.isPrimaryThread() ? null : Bukkit.getOfflinePlayer(name);
    }

    public static boolean hasPlayedBeforeFast(OfflinePlayer player) {
        if (player == null) return false;
        if (player.isOnline()) return true;
        return player.hasPlayedBefore();
    }
}
