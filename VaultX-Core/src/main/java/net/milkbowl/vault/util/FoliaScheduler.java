package net.milkbowl.vault.util;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class FoliaScheduler {
    private static final boolean IS_FOLIA;
    private static final Set<FoliaBukkitTask> ACTIVE_TASKS = ConcurrentHashMap.newKeySet();

    private static Method getAsyncSchedulerMethod;
    private static Method getGlobalRegionSchedulerMethod;
    private static Method runNowMethod;
    private static Method runGlobalMethod;
    private static Method runDelayedGlobalMethod;
    private static Method runDelayedAsyncMethod;
    private static Method runAtFixedRateAsyncMethod;
    private static Method scheduledTaskCancelMethod;

    static {
        boolean folia = false;
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            folia = true;

            getAsyncSchedulerMethod = Bukkit.class.getMethod("getAsyncScheduler");
            getGlobalRegionSchedulerMethod = Bukkit.class.getMethod("getGlobalRegionScheduler");

            Object asyncSched = getAsyncSchedulerMethod.invoke(null);
            Object globalSched = getGlobalRegionSchedulerMethod.invoke(null);

            runNowMethod = asyncSched.getClass().getMethod("runNow", Plugin.class, java.util.function.Consumer.class);
            runDelayedAsyncMethod = asyncSched.getClass().getMethod("runDelayed", Plugin.class, java.util.function.Consumer.class, long.class, TimeUnit.class);
            runAtFixedRateAsyncMethod = asyncSched.getClass().getMethod("runAtFixedRate", Plugin.class, java.util.function.Consumer.class, long.class, long.class, TimeUnit.class);

            runGlobalMethod = globalSched.getClass().getMethod("run", Plugin.class, java.util.function.Consumer.class);
            runDelayedGlobalMethod = globalSched.getClass().getMethod("runDelayed", Plugin.class, java.util.function.Consumer.class, long.class);

            Class<?> scheduledTaskClass = Class.forName("io.papermc.paper.threadedregions.scheduler.ScheduledTask");
            scheduledTaskCancelMethod = scheduledTaskClass.getMethod("cancel");
        } catch (Throwable e) {
            folia = false;
        }
        IS_FOLIA = folia;
    }

    public static boolean isFolia() {
        return IS_FOLIA;
    }

    private static org.bukkit.scheduler.BukkitTask trackTask(FoliaBukkitTask task) {
        ACTIVE_TASKS.add(task);
        return task;
    }

    private static void untrackTask(FoliaBukkitTask task) {
        if (task != null) {
            ACTIVE_TASKS.remove(task);
        }
    }

    public static org.bukkit.scheduler.BukkitTask runAsync(Plugin plugin, Runnable runnable) {
        FoliaBukkitTask bukkitTask = new FoliaBukkitTask(plugin);
        trackTask(bukkitTask);
        Runnable wrapped = () -> {
            try {
                runnable.run();
            } finally {
                untrackTask(bukkitTask);
            }
        };
        if (IS_FOLIA && runNowMethod != null) {
            try {
                Object scheduler = getAsyncSchedulerMethod.invoke(null);
                java.util.function.Consumer<Object> consumer = task -> wrapped.run();
                Object scheduledTask = runNowMethod.invoke(scheduler, plugin, consumer);
                bukkitTask.setFoliaTask(scheduledTask);
                return bukkitTask;
            } catch (Exception e) {
                plugin.getLogger().warning("Folia AsyncScheduler error, falling back: " + e.getMessage());
            }
        }
        bukkitTask.setSpigotTask(Bukkit.getScheduler().runTaskAsynchronously(plugin, wrapped));
        return bukkitTask;
    }

    public static org.bukkit.scheduler.BukkitTask runSync(Plugin plugin, Runnable runnable) {
        FoliaBukkitTask bukkitTask = new FoliaBukkitTask(plugin);
        trackTask(bukkitTask);
        Runnable wrapped = () -> {
            try {
                runnable.run();
            } finally {
                untrackTask(bukkitTask);
            }
        };
        if (IS_FOLIA && runGlobalMethod != null) {
            try {
                Object scheduler = getGlobalRegionSchedulerMethod.invoke(null);
                java.util.function.Consumer<Object> consumer = task -> wrapped.run();
                Object scheduledTask = runGlobalMethod.invoke(scheduler, plugin, consumer);
                bukkitTask.setFoliaTask(scheduledTask);
                return bukkitTask;
            } catch (Exception e) {
                plugin.getLogger().warning("Folia GlobalRegionScheduler error, falling back: " + e.getMessage());
            }
        }
        bukkitTask.setSpigotTask(Bukkit.getScheduler().runTask(plugin, wrapped));
        return bukkitTask;
    }

    public static org.bukkit.scheduler.BukkitTask runEntitySync(Plugin plugin, org.bukkit.entity.Entity entity, Runnable runnable) {
        if (entity == null) {
            return runSync(plugin, runnable);
        }
        FoliaBukkitTask bukkitTask = new FoliaBukkitTask(plugin);
        trackTask(bukkitTask);
        Runnable wrapped = () -> {
            try {
                runnable.run();
            } finally {
                untrackTask(bukkitTask);
            }
        };
        if (IS_FOLIA) {
            try {
                Method getSchedulerMethod = entity.getClass().getMethod("getScheduler");
                Object entityScheduler = getSchedulerMethod.invoke(entity);
                Method runMethod = entityScheduler.getClass().getMethod("run", Plugin.class, java.util.function.Consumer.class, Runnable.class);
                java.util.function.Consumer<Object> consumer = task -> wrapped.run();
                Object scheduledTask = runMethod.invoke(entityScheduler, plugin, consumer, null);
                bukkitTask.setFoliaTask(scheduledTask);
                return bukkitTask;
            } catch (Exception e) {
                // Fallback to Spigot / Paper scheduler if reflection fails
            }
        }
        bukkitTask.setSpigotTask(Bukkit.getScheduler().runTask(plugin, wrapped));
        return bukkitTask;
    }

    public static org.bukkit.scheduler.BukkitTask runLater(Plugin plugin, Runnable runnable, long ticks) {
        FoliaBukkitTask bukkitTask = new FoliaBukkitTask(plugin);
        trackTask(bukkitTask);
        Runnable wrapped = () -> {
            try {
                runnable.run();
            } finally {
                untrackTask(bukkitTask);
            }
        };
        if (IS_FOLIA && runDelayedGlobalMethod != null) {
            try {
                Object scheduler = getGlobalRegionSchedulerMethod.invoke(null);
                java.util.function.Consumer<Object> consumer = task -> wrapped.run();
                Object scheduledTask = runDelayedGlobalMethod.invoke(scheduler, plugin, consumer, ticks);
                bukkitTask.setFoliaTask(scheduledTask);
                return bukkitTask;
            } catch (Exception e) {
                plugin.getLogger().warning("Folia GlobalRegionScheduler error, falling back: " + e.getMessage());
            }
        }
        bukkitTask.setSpigotTask(Bukkit.getScheduler().runTaskLater(plugin, wrapped, ticks));
        return bukkitTask;
    }

    public static org.bukkit.scheduler.BukkitTask runLaterAsync(Plugin plugin, Runnable runnable, long ticks) {
        FoliaBukkitTask bukkitTask = new FoliaBukkitTask(plugin);
        trackTask(bukkitTask);
        Runnable wrapped = () -> {
            try {
                runnable.run();
            } finally {
                untrackTask(bukkitTask);
            }
        };
        if (IS_FOLIA && runDelayedAsyncMethod != null) {
            try {
                Object scheduler = getAsyncSchedulerMethod.invoke(null);
                java.util.function.Consumer<Object> consumer = task -> wrapped.run();
                Object scheduledTask = runDelayedAsyncMethod.invoke(scheduler, plugin, consumer, ticks * 50L, TimeUnit.MILLISECONDS);
                bukkitTask.setFoliaTask(scheduledTask);
                return bukkitTask;
            } catch (Exception e) {
                plugin.getLogger().warning("Folia AsyncScheduler error, falling back: " + e.getMessage());
            }
        }
        bukkitTask.setSpigotTask(Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, wrapped, ticks));
        return bukkitTask;
    }

    public static org.bukkit.scheduler.BukkitTask runTimerAsync(Plugin plugin, Runnable runnable, long delayTicks, long periodTicks) {
        if (IS_FOLIA && runAtFixedRateAsyncMethod != null) {
            try {
                Object scheduler = getAsyncSchedulerMethod.invoke(null);
                java.util.function.Consumer<Object> consumer = task -> runnable.run();
                Object scheduledTask = runAtFixedRateAsyncMethod.invoke(scheduler, plugin, consumer, delayTicks * 50L, periodTicks * 50L, TimeUnit.MILLISECONDS);
                return trackTask(new FoliaBukkitTask(scheduledTask, plugin));
            } catch (Exception e) {
                plugin.getLogger().warning("Folia AsyncScheduler error, falling back: " + e.getMessage());
            }
        }
        return trackTask(new FoliaBukkitTask(Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, runnable, delayTicks, periodTicks)));
    }

    public static void cancelTasks(Plugin plugin) {
        for (FoliaBukkitTask task : ACTIVE_TASKS) {
            if (task != null && task.getOwner() == plugin) {
                try {
                    task.cancel();
                } catch (Exception ignored) {}
            }
        }
        ACTIVE_TASKS.removeIf(task -> task != null && task.getOwner() == plugin);
        try {
            Bukkit.getScheduler().cancelTasks(plugin);
        } catch (Exception ignored) {}
    }

    public static class FoliaBukkitTask implements org.bukkit.scheduler.BukkitTask {
        private volatile Object foliaTask;
        private volatile org.bukkit.scheduler.BukkitTask spigotTask;
        private final Plugin plugin;

        public FoliaBukkitTask(Plugin plugin) {
            this.foliaTask = null;
            this.spigotTask = null;
            this.plugin = plugin;
        }

        public FoliaBukkitTask(Object foliaTask, Plugin plugin) {
            this.foliaTask = foliaTask;
            this.spigotTask = null;
            this.plugin = plugin;
        }

        public FoliaBukkitTask(org.bukkit.scheduler.BukkitTask spigotTask) {
            this.foliaTask = null;
            this.spigotTask = spigotTask;
            this.plugin = spigotTask != null ? spigotTask.getOwner() : null;
        }

        public void setFoliaTask(Object foliaTask) {
            this.foliaTask = foliaTask;
        }

        public void setSpigotTask(org.bukkit.scheduler.BukkitTask spigotTask) {
            this.spigotTask = spigotTask;
        }

        @Override
        public int getTaskId() {
            if (spigotTask != null) return spigotTask.getTaskId();
            return -1;
        }

        @Override
        public Plugin getOwner() {
            if (spigotTask != null) return spigotTask.getOwner();
            return plugin;
        }

        @Override
        public boolean isSync() {
            if (spigotTask != null) return spigotTask.isSync();
            return false;
        }

        @Override
        public void cancel() {
            ACTIVE_TASKS.remove(this);
            if (spigotTask != null) {
                spigotTask.cancel();
            } else if (foliaTask != null && scheduledTaskCancelMethod != null) {
                try {
                    scheduledTaskCancelMethod.invoke(foliaTask);
                } catch (Exception e) {
                    // Ignore cancel exception on shutdown
                }
            }
        }
    }
}
