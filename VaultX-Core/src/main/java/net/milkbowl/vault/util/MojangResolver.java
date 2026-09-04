package net.milkbowl.vault.util;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.function.BiConsumer;

public class MojangResolver {

    public static void preloadUserCache(Plugin plugin, BiConsumer<String, UUID> cacheConsumer) {
        boolean preloadEnabled = plugin.getConfig().getBoolean("advanced.preload-offline-players", true);
        if (!preloadEnabled) return;

        FoliaScheduler.runAsync(plugin, () -> {
            plugin.getLogger().info("[UUIDCache] Starting async pre-loading of offline players...");
            long start = System.currentTimeMillis();
            int count = 0;
            boolean loadedFromUserCache = false;
            
            File userCacheFile = new File("usercache.json");
            if (userCacheFile.exists()) {
                try {
                    byte[] bytes = Files.readAllBytes(userCacheFile.toPath());
                    String content = new String(bytes, StandardCharsets.UTF_8);
                    org.json.JSONArray array = new org.json.JSONArray(content);
                    for (int i = 0; i < array.length(); i++) {
                        org.json.JSONObject obj = array.getJSONObject(i);
                        if (obj.has("name") && obj.has("uuid")) {
                            String name = obj.getString("name");
                            String uuidStr = obj.getString("uuid");
                            try {
                                UUID uuid = UUID.fromString(uuidStr);
                                cacheConsumer.accept(name, uuid);
                                count++;
                            } catch (IllegalArgumentException ignored) {}
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
                                    cacheConsumer.accept(name, uuid);
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
        });
    }
}
