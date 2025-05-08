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

/**
 * 管理試煉 (Void Trial) 世界與相關資源的清理機制，
 * 包含定期卸載過期世界、移除離線玩家資源、
 * 以及清理玩家放置的床鋪記錄等功能。
 */
public class CleanUpManager {

    // 世界保留時長：15 分鐘 (毫秒)
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
     * 清除指定世界的 entities、poi、region 資料夾中的所有檔案
     * （保留空資料夾結構），並將該世界卸載以釋放資源。
     *
     * @param world 要清理並卸載的世界物件
     */
    public void clearEntityAndPoiFolders(World world) {
        if (world == null) return;
        File worldFolder = world.getWorldFolder();
        // 卸載世界，但不刪除實際資料夾
        Bukkit.unloadWorld(world, false);

        // 指定要清理的三個資料夾路徑
        Path[] toClean = {
                Paths.get(worldFolder.getPath(), "entities"),
                Paths.get(worldFolder.getPath(), "poi"),
                Paths.get(worldFolder.getPath(), "region")
        };
        for (Path dir : toClean) {
            if (!Files.exists(dir)) continue;
            try {
                // 遍歷並刪除所有檔案及子目錄（保留根目錄）
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
     * 定期清理任務：
     * 1. 移除超過保留時長且無玩家的試煉世界
     * 2. 清理所有離線玩家相關資源
     * 3. 同步清除無效的床鋪記錄
     */
    public void cleanupUnusedResources() {
        long now = System.currentTimeMillis();
        List<String> toRemove = new ArrayList<>();
        // 找出已過保留時間且該世界內無玩家的世界
        for (var entry : worldLastAccessed.entrySet()) {
            if (now - entry.getValue() > WORLD_RETENTION_TIME) {
                World w = Bukkit.getWorld(entry.getKey());
                if (w != null && w.getPlayers().isEmpty()) toRemove.add(entry.getKey());
            }
        }
        // 依序停止並清理這些世界與其玩家資源
        for (String name : toRemove) {
            World w = Bukkit.getWorld(name);
            UUID owner = plugin.getOwnerUUIDByWorldName(name);
            if (owner != null) stopAndCleanPlayerResources(owner);
            plugin.getLogger().info("正在清理未使用世界: " + name);
            clearEntityAndPoiFolders(w);
            worldLastAccessed.remove(name);
        }
        // 再執行一次離線玩家資源清理
        cleanupOfflinePlayerResources();
    }

    /**
     * 清理所有離線玩家的試煉相關資源：
     * - 試煉冷卻 (cooldown)
     * - 生怪器排程任務
     * - 試煉世界紀錄
     * - 床鋪放置記錄
     */
    public void cleanupOfflinePlayerResources() {
        List<UUID> offline = new ArrayList<>();
        // 尋找目前不在線上的玩家
        for (UUID uid : new HashSet<>(trialCooldowns.keySet())) {
            Player p = Bukkit.getPlayer(uid);
            if (p == null || !p.isOnline()) offline.add(uid);
        }
        // 停止並清理這些離線玩家的資源
        for (UUID uid : offline) {
            stopAndCleanPlayerResources(uid);
        }
        // 同時移除不屬於有效試煉世界的床鋪記錄
        playerPlacedBeds.removeIf(loc -> {
            World w = loc.getWorld();
            return w == null || !w.getName().startsWith("trial_") || !worldLastAccessed.containsKey(w.getName());
        });
    }

    /**
     * 停止並移除指定玩家所有試煉相關資源，
     * 包含生怪器任務、世界紀錄、冷卻任務與 BossBar 顯示。
     *
     * @param playerUUID 要清理資源的玩家 UUID
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
     * 更新指定試煉世界的最後訪問時間，
     * 用以延長該世界的保留週期。
     *
     * @param world 要更新存取時間的世界物件
     */
    public void updateWorldAccess(World world) {
        if (world != null && world.getName().startsWith("trial_")) {
            worldLastAccessed.put(world.getName(), System.currentTimeMillis());
        }
    }

    /**
     * 啟動一個每 5 分鐘 (6000 tick) 執行一次的排程任務，
     * 用於自動呼叫 cleanupUnusedResources() 進行資源清理。
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