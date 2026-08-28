package net.milkbowl.vault.discord;

import org.bukkit.plugin.Plugin;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class DiscordManager {

    private final Plugin plugin;
    private final Map<String, LinkCode> activeLinkCodes = new ConcurrentHashMap<>();
    private final Map<UUID, String> linkedAccounts = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    // Rate limiting: track last code generation time per player
    private final Map<UUID, Long> lastCodeGenTime = new ConcurrentHashMap<>();
    private static final long RATE_LIMIT_MS = 60_000L; // 1 minute between code generations
    private static final int MAX_ACTIVE_CODES = 500; // Global cap to prevent memory DoS

    public static class LinkCode {
        private final UUID playerUuid;
        private final String code;
        private final long expiresAt;

        public LinkCode(UUID playerUuid, String code, long durationMinutes) {
            this.playerUuid = playerUuid;
            this.code = code;
            this.expiresAt = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(durationMinutes);
        }

        public UUID getPlayerUuid() {
            return playerUuid;
        }

        public String getCode() {
            return code;
        }

        public boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }

    public DiscordManager(Plugin plugin) {
        this.plugin = plugin;
        net.milkbowl.vault.redis.LocalFailoverManager fm = net.milkbowl.vault.Vault.getFailoverManager();
        if (fm != null) {
            net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
                try {
                    Map<UUID, String> savedLinks = fm.loadAllDiscordLinks();
                    if (savedLinks != null && !savedLinks.isEmpty()) {
                        linkedAccounts.putAll(savedLinks);
                    }
                } catch (Exception ignored) {}
            });
        }
    }

    /**
     * Generates a new link code for the given player.
     * Returns null if rate-limited (player generated a code too recently),
     * or if the server-wide active code cap has been reached.
     */
    public String generateLinkCode(UUID playerUuid) {
        long now = System.currentTimeMillis();

        // Clean up expired codes and rate limit entries
        activeLinkCodes.entrySet().removeIf(entry -> entry.getValue().isExpired());
        lastCodeGenTime.entrySet().removeIf(entry -> (now - entry.getValue()) > RATE_LIMIT_MS);

        // Rate limit: prevent spam / brute-force of other players' codes
        Long lastGen = lastCodeGenTime.get(playerUuid);
        if (lastGen != null && (now - lastGen) < RATE_LIMIT_MS) {
            return null; // caller should inform the player to wait
        }

        // Global cap to prevent memory exhaustion DoS
        if (activeLinkCodes.size() >= MAX_ACTIVE_CODES) {
            return null;
        }

        int num = random.nextInt(900000) + 100000;
        String code = String.valueOf(num);
        long duration = plugin.getConfig().getLong("discord.account-linking.link-code-expire-minutes", 10);

        LinkCode linkCode = new LinkCode(playerUuid, code, duration);
        activeLinkCodes.put(code, linkCode);
        lastCodeGenTime.put(playerUuid, now);
        return code;
    }

    public boolean linkAccount(String code, String discordTag) {
        LinkCode linkCode = activeLinkCodes.get(code);
        if (linkCode == null || linkCode.isExpired()) {
            return false;
        }
        UUID playerUuid = linkCode.getPlayerUuid();
        linkedAccounts.put(playerUuid, discordTag);
        activeLinkCodes.remove(code);
        
        net.milkbowl.vault.redis.LocalFailoverManager fm = net.milkbowl.vault.Vault.getFailoverManager();
        if (fm != null) {
            fm.saveDiscordLink(playerUuid, discordTag);
        }
        return true;
    }

    public void cleanupPlayer(UUID playerUuid) {
        if (playerUuid != null) {
            lastCodeGenTime.remove(playerUuid);
        }
    }

    public String getLinkedDiscordTag(UUID playerUuid) {
        return linkedAccounts.get(playerUuid);
    }

    public boolean isLinked(UUID playerUuid) {
        return linkedAccounts.containsKey(playerUuid);
    }

    public void sendWebhook(String webhookKey, String content) {
        if (!plugin.getConfig().getBoolean("discord.enabled", true)) {
            return;
        }

        boolean enabled = plugin.getConfig().getBoolean("discord.webhooks." + webhookKey + ".enabled", true);
        String urlStr = plugin.getConfig().getString("discord.webhooks." + webhookKey + ".url", "");

        if (!enabled || urlStr == null || urlStr.isEmpty() || urlStr.contains("YOUR_DISCORD_WEBHOOK_URL")) {
            return;
        }

        net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
            HttpURLConnection conn = null;
            try {
                URL url = URI.create(urlStr).toURL();
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; utf-8");
                conn.setRequestProperty("User-Agent", "VaultX-Minecraft-Plugin");
                conn.setDoOutput(true);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);

                // Escape quotes and backslashes for JSON payload
                String escapedContent = content.replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\n", "\\n");

                String jsonPayload = "{\"content\": \"" + escapedContent + "\"}";

                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = jsonPayload.getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }

                int responseCode = conn.getResponseCode();
                if (responseCode < 200 || responseCode >= 300) {
                    plugin.getLogger().warning(
                            "[DiscordManager] Webhook POST failed (" + webhookKey + ") HTTP response: " + responseCode);
                }
            } catch (Exception e) {
                plugin.getLogger().warning(
                        "[DiscordManager] Failed to send Discord webhook (" + webhookKey + "): " + e.getMessage());
            } finally {
                if (conn != null) {
                    try {
                        conn.disconnect();
                    } catch (Exception ignored) {}
                }
            }
        });
    }

    public void close() {
        activeLinkCodes.clear();
        linkedAccounts.clear();
        lastCodeGenTime.clear();
    }
}
