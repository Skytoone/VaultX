package net.milkbowl.vault.security;

import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import net.milkbowl.vault.redis.LocalFailoverManager;

public class DiscordWebhookNotifier {

    private final Plugin plugin;
    private boolean enabled;
    private String webhookUrl;

    public DiscordWebhookNotifier(Plugin plugin) {
        this.plugin = plugin;
        loadConfig();
        startRetryScheduler();
    }

    public void loadConfig() {
        this.enabled = plugin.getConfig().getBoolean("security.firewall.discord-webhook.enabled", false);
        this.webhookUrl = plugin.getConfig().getString("security.firewall.discord-webhook.url", "");
    }

    public void sendAlertAsync(String action, OfflinePlayer player, String details, int color) {
        if (!enabled || webhookUrl == null || webhookUrl.isEmpty() || webhookUrl.startsWith("YOUR_")) {
            return;
        }

        String name = player != null ? player.getName() : "System";
        String uuidStr = player != null ? player.getUniqueId().toString() : "N/A";

        net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, new Runnable() {
            @Override
            public void run() {
                String payload = null;
                try {
                    JSONObject json = new JSONObject();
                    json.put("username", "VaultX Security");
                    json.put("avatar_url",
                            "https://image.noelshack.com/fichiers/2026/19/2/1778002404-universal-banner-edited-final-177800224786112.png");

                    JSONObject embed = new JSONObject();
                    embed.put("title", "🛡️ VaultX Security Alert");
                    embed.put("color", color);
                    embed.put("timestamp", Instant.now().toString());

                    JSONArray fields = new JSONArray();
                    fields.put(new JSONObject().put("name", "Action / Alert").put("value", action).put("inline", true));
                    fields.put(new JSONObject().put("name", "Player").put("value", name).put("inline", true));
                    fields.put(new JSONObject().put("name", "UUID").put("value", uuidStr).put("inline", false));
                    fields.put(new JSONObject().put("name", "Details").put("value", details).put("inline", false));

                    embed.put("fields", fields);
                    json.put("embeds", new JSONArray().put(embed));
                    payload = json.toString();

                    boolean success = sendWebhookPayloadDirect(payload);
                    if (!success) {
                        net.milkbowl.vault.Vault.getFailoverManager().savePendingWebhook(payload, 1, System.currentTimeMillis() + 60000L);
                    }
                } catch (RateLimitException e) {
                    plugin.getLogger().warning("[VaultX Security] Discord Webhook rate-limited. Retrying after " + e.getRetryAfterMs() + "ms.");
                    if (payload != null) {
                        net.milkbowl.vault.Vault.getFailoverManager().savePendingWebhook(payload, 1, System.currentTimeMillis() + e.getRetryAfterMs());
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("[VaultX Security] Failed to send Discord Webhook: " + e.getMessage());
                    if (payload != null) {
                        try {
                            net.milkbowl.vault.Vault.getFailoverManager().savePendingWebhook(payload, 1, System.currentTimeMillis() + 60000L);
                        } catch (Exception ignored) {}
                    }
                }
            }
        });
    }

    private boolean sendWebhookPayloadDirect(String payload) throws RateLimitException, Exception {
        URL url = java.net.URI.create(webhookUrl).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.3");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = payload.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int code = conn.getResponseCode();
            if (code == 429) {
                String retryAfterStr = conn.getHeaderField("Retry-After");
                long delayMs = 10000;
                if (retryAfterStr != null && !retryAfterStr.isEmpty()) {
                    try {
                        delayMs = Long.parseLong(retryAfterStr) * 1000L;
                    } catch (NumberFormatException e) {}
                }
                throw new RateLimitException(delayMs);
            }

            try (java.io.InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream()) {
                if (is != null) {
                    byte[] buf = new byte[512];
                    while (is.read(buf) > 0) {}
                }
            } catch (Exception ignored) {}

            if (code >= 200 && code < 300) {
                return true;
            }
            plugin.getLogger().warning("[VaultX Security] Failed to send Discord Webhook. HTTP Response Code: " + code);
            return false;
        } finally {
            try {
                conn.disconnect();
            } catch (Exception ignored) {}
        }
    }

    private void startRetryScheduler() {
        net.milkbowl.vault.util.FoliaScheduler.runTimerAsync(plugin, new Runnable() {
            @Override
            public void run() {
                if (!enabled || webhookUrl == null || webhookUrl.isEmpty() || webhookUrl.startsWith("YOUR_")) {
                    return;
                }
                LocalFailoverManager failoverManager = net.milkbowl.vault.Vault.getFailoverManager();
                if (failoverManager == null) return;

                long now = System.currentTimeMillis();
                java.util.List<LocalFailoverManager.PendingWebhookRecord> pending = failoverManager.getPendingWebhooks(now);
                if (pending == null || pending.isEmpty()) return;

                for (LocalFailoverManager.PendingWebhookRecord record : pending) {
                    try {
                        boolean success = sendWebhookPayloadDirect(record.payload);
                        if (success) {
                            failoverManager.deletePendingWebhook(record.id);
                        } else {
                            int nextAttempts = record.attempts + 1;
                            if (nextAttempts > 10) {
                                plugin.getLogger().severe("[VaultX Security] Webhook retry limit exceeded. Discarding message.");
                                failoverManager.deletePendingWebhook(record.id);
                            } else {
                                long backoffMs = (long) (15000L * Math.pow(2, nextAttempts));
                                failoverManager.updatePendingWebhook(record.id, nextAttempts, System.currentTimeMillis() + backoffMs);
                            }
                        }
                    } catch (RateLimitException e) {
                        int nextAttempts = record.attempts + 1;
                        if (nextAttempts > 10) {
                            plugin.getLogger().severe("[VaultX Security] Webhook retry limit exceeded due to rate limiting. Discarding message.");
                            failoverManager.deletePendingWebhook(record.id);
                        } else {
                            failoverManager.updatePendingWebhook(record.id, nextAttempts, System.currentTimeMillis() + e.getRetryAfterMs());
                        }
                        break;
                    } catch (Exception e) {
                        int nextAttempts = record.attempts + 1;
                        if (nextAttempts > 10) {
                            plugin.getLogger().severe("[VaultX Security] Webhook retry limit exceeded on network error. Discarding message.");
                            failoverManager.deletePendingWebhook(record.id);
                        } else {
                            long backoffMs = (long) (15000L * Math.pow(2, nextAttempts));
                            failoverManager.updatePendingWebhook(record.id, nextAttempts, System.currentTimeMillis() + backoffMs);
                        }
                    }
                }
            }
        }, 200L, 200L);
    }

    private static class RateLimitException extends Exception {
        private final long retryAfterMs;
        public RateLimitException(long retryAfterMs) {
            this.retryAfterMs = retryAfterMs;
        }
        public long getRetryAfterMs() {
            return retryAfterMs;
        }
    }
}

