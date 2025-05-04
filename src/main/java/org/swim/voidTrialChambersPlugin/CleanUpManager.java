package org.swim.voidTrialChambersPlugin;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

public class CleanUpManager {

    private static final long WORLD_RETENTION_TIME = 15 * 60 * 1000L;

    private final VoidTrialChambersPlugin plugin;
    private final Map<String, Long> worldLastAccessed;
    private final Map<UUID, VoidTrialChambersPlugin.WorldMobSpawnerTask> spawnerTasks;
    private final Map<UUID, World> playerTrialWorlds;
    private final Map<UUID, Long> trialCooldowns;
    private final Map<UUID, BossBar> cooldownBars;
    private final Map<UUID, BukkitTask> cooldownTasks;
    private final Set<Location> playerPlacedBeds;

    public CleanUpManager(VoidTrialChambersPlugin plugin) {
        this.plugin = plugin;
        this.worldLastAccessed = plugin.worldLastAccessed;
        this.spawnerTasks = plugin.spawnerTasks;
        this.playerTrialWorlds = plugin.playerTrialWorlds;
        this.trialCooldowns = plugin.trialCooldowns;
        this.cooldownBars = plugin.cooldownBars;
        this.cooldownTasks = plugin.cooldownTasks;
        this.playerPlacedBeds = plugin.playerPlacedBeds;
    }

    /**
     * 清空指定世界的 entities/poi/region 資料夾內容（保留空資料夾），並卸載世界。
     */
    public void clearEntityAndPoiFolders(World world) {
        if (world == null) return;
        File worldFolder = world.getWorldFolder();
        Bukkit.unloadWorld(world, false);

        Path[] toClean = {
                Paths.get(worldFolder.getPath(), "entities"),
                Paths.get(worldFolder.getPath(), "poi"),
                Paths.get(worldFolder.getPath(), "region")
        };
        for (Path dir : toClean) {
            if (!Files.exists(dir)) continue;
            try {
                Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                    @Override
                    public @NotNull FileVisitResult visitFile(@NotNull Path file, @NotNull BasicFileAttributes attrs) throws IOException {
                        Files.delete(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public @NotNull FileVisitResult postVisitDirectory(@NotNull Path d, IOException exc) throws IOException {
                        if (!d.equals(dir)) Files.delete(d);
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                plugin.getLogger().warning("清理資料夾出錯: " + dir + " -> " + e.getMessage());
            }
        }
    }

    /**
     * 定期清理：超時世界 + 離線玩家 + 床記錄。
     */
    public void cleanupUnusedResources() {
        long now = System.currentTimeMillis();
        List<String> toRemove = new ArrayList<>();
        for (var entry : worldLastAccessed.entrySet()) {
            if (now - entry.getValue() > WORLD_RETENTION_TIME) {
                World w = Bukkit.getWorld(entry.getKey());
                if (w != null && w.getPlayers().isEmpty()) toRemove.add(entry.getKey());
            }
        }
        for (String name : toRemove) {
            World w = Bukkit.getWorld(name);
            UUID owner = plugin.getOwnerUUIDByWorldName(name);
            if (owner != null) stopAndCleanPlayerResources(owner);
            plugin.getLogger().info("正在清理未使用世界: " + name);
            clearEntityAndPoiFolders(w);
            worldLastAccessed.remove(name);
        }
        cleanupOfflinePlayerResources();
    }

    /**
     * 清理所有離線玩家的資源：冷卻、任務、世界、床記錄。
     */
    public void cleanupOfflinePlayerResources() {
        List<UUID> offline = new ArrayList<>();
        for (UUID uid : new HashSet<>(trialCooldowns.keySet())) {
            Player p = Bukkit.getPlayer(uid);
            if (p == null || !p.isOnline()) offline.add(uid);
        }
        for (UUID uid : offline) {
            stopAndCleanPlayerResources(uid);
        }
        // 同時清理床記錄
        playerPlacedBeds.removeIf(loc -> {
            World w = loc.getWorld();
            return w == null || !w.getName().startsWith("trial_") || !worldLastAccessed.containsKey(w.getName());
        });
    }

    /**
     * 停止並清理單玩家所有試煉相關資源（任務、世界、冷卻、BossBar）。
     */
    public void stopAndCleanPlayerResources(UUID playerUUID) {
        var task = spawnerTasks.remove(playerUUID);
        if (task != null) task.stop();

        playerTrialWorlds.remove(playerUUID);

        var ct = cooldownTasks.remove(playerUUID);
        if (ct != null) ct.cancel();

        var bar = cooldownBars.remove(playerUUID);
        if (bar != null) bar.removeAll();

        trialCooldowns.remove(playerUUID);
    }

    /**
     * 更新世界最後訪問時間。
     */
    public void updateWorldAccess(World world) {
        if (world != null && world.getName().startsWith("trial_")) {
            worldLastAccessed.put(world.getName(), System.currentTimeMillis());
        }
    }

    /**
     * 啟動每5分鐘一次的清理排程。
     */
    public void schedulePeriodicCleanup() {
        new BukkitRunnable() {
            @Override
            public void run() {
                cleanupUnusedResources();
            }
        }.runTaskTimer(plugin, 6000L, 6000L);
    }
}