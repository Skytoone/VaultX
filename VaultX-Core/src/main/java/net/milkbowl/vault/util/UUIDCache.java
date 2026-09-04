package net.milkbowl.vault.util;

import net.milkbowl.vault.persistence.repository.UUIDCacheRepository;
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
    private static final java.util.Queue<String> accessOrderNameQueue = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static final java.util.Queue<UUID> accessOrderUuidQueue = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static final long NEGATIVE_CACHE_EXPIRATION_MS = 300000;
    private static final int MAX_NEGATIVE_CACHE_SIZE = 2000;
    private static boolean enabled = false;

    private static Plugin pluginInstance;
    private static UUIDCache registeredListenerInstance = null;

    private static int maxCacheSize = 10000;
    private static org.bukkit.scheduler.BukkitTask cleanupTask = null;

    private static synchronized void putCache(String name, UUID uuid) {
        if (name == null || uuid == null) return;
        String lowerName = name.toLowerCase();
        UUID oldUuid = nameToUuid.put(lowerName, uuid);
        if (oldUuid != null && !oldUuid.equals(uuid)) {
            uuidToName.remove(oldUuid);
        }
        uuidToName.put(uuid, name);

        accessOrderNameQueue.add(lowerName);
        accessOrderUuidQueue.add(uuid);

        while (nameToUuid.size() > maxCacheSize && !accessOrderNameQueue.isEmpty()) {
            String oldestName = accessOrderNameQueue.poll();
            if (oldestName != null && nameToUuid.containsKey(oldestName)) {
                UUID removedUuid = nameToUuid.remove(oldestName);
                if (removedUuid != null) {
                    uuidToName.remove(removedUuid);
                }
            }
        }
        while (uuidToName.size() > maxCacheSize && !accessOrderUuidQueue.isEmpty()) {
            UUID oldestUuid = accessOrderUuidQueue.poll();
            if (oldestUuid != null && uuidToName.containsKey(oldestUuid)) {
                String removedName = uuidToName.remove(oldestUuid);
                if (removedName != null) {
                    nameToUuid.remove(removedName.toLowerCase());
                }
            }
        }
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
        MojangResolver.preloadUserCache(plugin, UUIDCache::putCache);
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
            UUIDCacheRepository.saveUuidCache(name, uuid);
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
                            dbName = UUIDCacheRepository.getNameFromDbOrRedis(uuid);
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
                String dbName = UUIDCacheRepository.getNameFromDbOrRedis(uuid);
                if (dbName != null) {
                    putCache(dbName, uuid);
                    return dbName;
                }
            }
            OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
            if (op != null && op.getName() != null) {
                if (enabled) {
                    putCache(op.getName(), uuid);
                    UUIDCacheRepository.saveUuidCache(op.getName(), uuid);
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
                        if (enabled) {
                            dbUuid = UUIDCacheRepository.getUuidFromDbOrRedis(name);
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
                                    UUIDCacheRepository.saveUuidCache(op.getName(), op.getUniqueId());
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
            return null;
        } else {
            if (enabled) {
                UUID dbUuid = UUIDCacheRepository.getUuidFromDbOrRedis(name);
                if (dbUuid != null) {
                    putCache(name, dbUuid);
                    return Bukkit.getOfflinePlayer(dbUuid);
                }
            }
            OfflinePlayer op = Bukkit.getOfflinePlayer(name);
            if (op != null && op.getUniqueId() != null && op.getName() != null && (op.hasPlayedBefore() || op.isOnline())) {
                if (enabled) {
                    putCache(op.getName(), op.getUniqueId());
                    UUIDCacheRepository.saveUuidCache(op.getName(), op.getUniqueId());
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
