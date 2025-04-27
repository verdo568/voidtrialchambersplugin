package org.swim.voidTrialChambersPlugin;

import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.World.Environment;
import org.bukkit.block.Bed;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.geysermc.floodgate.api.FloodgateApi;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

public class VoidTrialChambersPlugin extends JavaPlugin implements Listener {

    // 每位玩家的試煉世界映射
    private final Map<UUID, World> playerTrialWorlds = new HashMap<>();
    // 原始位置備份
    private final Map<UUID, Location> originalLocations = new HashMap<>();
    // 玩家難度映射
    private final Map<UUID, TrialDifficulty> playerDifficulties = new HashMap<>();
    // MobSpawner 任務映射
    private final Map<UUID, MobSpawnerTask> spawnerTasks = new HashMap<>();
    // 排除處理的世界名單
    private List<String> excludedWorldNames;
    // 試煉世界冷卻時間
    private final Map<UUID, Long> trialCooldowns = new HashMap<>();
    // 冷卻時間的 BossBar
    private final Map<UUID, BossBar> cooldownBars = new HashMap<>();
    // 冷卻時間的任務
    private final Map<UUID, BukkitTask> cooldownTasks = new HashMap<>();
    // 玩家放置的床
    private final Set<Location> playerPlacedBeds = new HashSet<>();
    // 新增：世界最後訪問時間
    private final Map<String, Long> worldLastAccessed = new HashMap<>();
    // 世界保留時間（毫秒）
    private static final long WORLD_RETENTION_TIME = 15 * 60 * 1000; // 15分鐘

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        saveDefaultConfig();
        reloadConfig();
        MonsterCleaner.startCleaningTask(this);
        excludedWorldNames = getConfig().getStringList("excluded_worlds");
        TrialChambersCommand trialCmd = new TrialChambersCommand();
        Objects.requireNonNull(getCommand("trialchambers")).setExecutor(new TrialChambersCommand());
        Objects.requireNonNull(getCommand("exittrial")).setExecutor(new ExitTrialCommand());
        // 註冊定期清理任務，每5分鐘執行一次
        new BukkitRunnable() {
            @Override
            public void run() {
                cleanupUnusedResources();
            }
        }.runTaskTimer(this, 6000L, 6000L); // 初次延遲5分鐘，之後每5分鐘執行一次
        getLogger().info("記憶體清理任務已啟用");
        getLogger().info("Void Trial Chambers Plugin 已啟用");
    }

    public static class MonsterCleaner {

        public static void startCleaningTask(VoidTrialChambersPlugin plugin) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    for (World world : Bukkit.getWorlds()) {
                        if (world.getName().startsWith("trial_")) {
                            for (Entity entity : world.getEntities()) {
                                if (entity instanceof Monster) {
                                    entity.remove();
                                }
                            }
                        }
                    }
                }
            }.runTaskTimer(plugin, 0L, 900L); // 每45秒執行一次
        }
    }

    @Override
    public void onDisable() {
        // 停止所有 Spawner 任務
        spawnerTasks.values().forEach(MobSpawnerTask::stop);
        // 先複製一份要處理的世界列表，避免迭代時並發修改
        for (World w : new ArrayList<>(playerTrialWorlds.values())) {
            // 如果世界裡有玩家，就逐一踢出
            for (Player p : w.getPlayers()) {
                // 使用 Component API 組成訊息
                Component msg = Component.text("§c伺服器/插件正在關閉，您已被踢出試煉世界");
                // 直接呼叫 kick(Component) 取代已棄用的 kickPlayer(String)
                p.kick(msg);
            }
            // 然後再清空 entities、poi、region 等資料夾
            clearEntityAndPoiFolders(w);
        }
        // 清理所有記錄的資料結構
        playerPlacedBeds.clear();
        worldLastAccessed.clear();
        trialCooldowns.clear();
        originalLocations.clear();
        playerDifficulties.clear();
        // 取消所有進度條和任務
        for (BossBar bar : cooldownBars.values()) {
            bar.removeAll();
        }
        cooldownBars.clear();

        for (BukkitTask task : cooldownTasks.values()) {
            task.cancel();
        }
        cooldownTasks.clear();

        getLogger().info("Void Trial Chambers Plugin 已停用");
    }
    /**
     * Difficulty enum 定義刷怪列表
     */
    public enum TrialDifficulty {
        EASY("簡單"),
        NORMAL("普通"),
        HARD("困難");

        private final String display;
        TrialDifficulty(String display) { this.display = display; }
        /** 返回中文显示名 */
        public String getDisplay() {
            return display;
        }

        public static TrialDifficulty from(String s) {
            for (TrialDifficulty d : values()) {
                if (d.name().equalsIgnoreCase(s) || d.display.equalsIgnoreCase(s)) return d;
            }
            return null;
        }

        public List<EntityType> getMobs() {
            return switch (this) {
                case NORMAL -> List.of(EntityType.ZOMBIE, EntityType.SKELETON, EntityType.SPIDER);
                case HARD   -> List.of(EntityType.ZOMBIE, EntityType.SKELETON, EntityType.CREEPER, EntityType.SPIDER, EntityType.ENDERMAN, EntityType.WITCH, EntityType.BOGGED, EntityType.STRAY, EntityType.WITHER_SKELETON);
                default     -> List.of();
            };
        }
    }

    /**
     * MobSpawnerTask: 定時隨機在玩家後方及周圍生成怪物
     */
    private class MobSpawnerTask {
        private final Player player;
        private final TrialDifficulty diff;
        private BukkitTask task;
        private final Random rnd = new Random();

        public MobSpawnerTask(UUID uid, World world, Player player, TrialDifficulty diff) {
            this.player = player;
            this.diff = diff;
        }

        public void start() {
            scheduleNext();
        }

        private void scheduleNext() {
            int delaySec;
            if (diff == TrialDifficulty.HARD) {
                delaySec = rnd.nextInt(3) + 3; // 3~5 秒
            } else {
                delaySec = rnd.nextInt(6) + 5; // 5~10 秒
            }
            task = new BukkitRunnable() {
                @Override
                public void run() {
                    spawnWave();
                    scheduleNext();
                }
            }.runTaskLater(VoidTrialChambersPlugin.this, delaySec * 20L);
        }

        /**
         * 檢查生成位置安全性：
         * - 底下必須有實心方塊，避免掉落
         * - 本體及頭頂及周圍 1 格範圍內都必須是空氣，避免窒息
         */
        private boolean isSafeSpawnLocation(Location loc) {
            // 檢查底下方塊
            Block below = loc.clone().add(0, -1, 0).getBlock();
            if (below.getType() == Material.AIR || !below.getType().isSolid()) {
                return false;
            }
            // 檢查本體、頭頂及周圍 1 格範圍的方塊都是空氣
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dy = 0; dy <= 1; dy++) {
                        Block check = loc.clone().add(dx, dy, dz).getBlock();
                        if (check.getType() != Material.AIR) {
                            return false;
                        }
                    }
                }
            }
            return true;
        }

        // 類別最上方定義全局活躍怪物上限
        private static final int MAX_ACTIVE_MOBS = 100;

        private void spawnWave() {
            World w = player.getWorld();
            // 先計算目前世界中，指定要生成的怪物類型，還有玩家放置的傳送點等不算玩家、載具...
            long currentCount = w.getEntities().stream()
                    // 只算 LivingEntity，且排除玩家與動物等必要分類，你可根據需要再加過濾
                    .filter(e -> e instanceof LivingEntity)
                    .filter(e -> {
                        EntityType t = e.getType();
                        // 只計算我們會 spawn 的怪物類型
                        return diff.getMobs().contains(t);
                    })
                    .count();

            // 如果已經達到上限，就不 spawn 任何新的
            if (currentCount >= MAX_ACTIVE_MOBS) {
                return;
            }

            // 原先依照難度計算每波應生成的數量
            int desired = switch (diff) {
                case NORMAL -> rnd.nextInt(3) + 4; // 4–6
                case HARD   -> rnd.nextInt(4) + 5; // 5–8
                default     -> 0;
            };

            // 計算還能生成幾隻：上限減去當前數量
            int availableSlots = (int)(MAX_ACTIVE_MOBS - currentCount);
            // 最終的生成目標數量不能超過 availableSlots
            int count = Math.min(desired, availableSlots);

            Location base = player.getLocation();
            List<EntityType> types = diff.getMobs();

            int spawned = 0;
            int attempts = 0;
            int maxAttempts = count * 5;

            while (spawned < count && attempts < maxAttempts) {
                attempts++;
                double angle = Math.toRadians(rnd.nextDouble() * 360);
                double distance = rnd.nextDouble() * 2 + 3;
                Location spawnLoc = base.clone().add(
                        Math.cos(angle) * distance,
                        0,
                        Math.sin(angle) * distance
                );

                // —— 保留你原本「跳過靠近原生床」的邏輯 ——
                boolean nearVanillaBed = false;
                for (int dx = -8; dx <= 8 && !nearVanillaBed; dx++) {
                    for (int dz = -8; dz <= 8; dz++) {
                        if (dx*dx + dz*dz > 64) continue;
                        Block b = w.getBlockAt(
                                spawnLoc.getBlockX() + dx,
                                spawnLoc.getBlockY(),
                                spawnLoc.getBlockZ() + dz
                        );
                        if (b.getState() instanceof Bed
                                && !playerPlacedBeds.contains(b.getLocation())) {
                            nearVanillaBed = true;
                            break;
                        }
                    }
                }
                if (nearVanillaBed) {
                    continue;
                }
                // —— 結束原生床檢查 ——

                // 安全位置微調
                int upTries = 0;
                while (!isSafeSpawnLocation(spawnLoc) && upTries < 5) {
                    spawnLoc.add(0, 1, 0);
                    upTries++;
                }
                if (isSafeSpawnLocation(spawnLoc)) {
                    EntityType type = types.get(rnd.nextInt(types.size()));
                    LivingEntity e = (LivingEntity) w.spawnEntity(spawnLoc, type);
                    e.setCustomNameVisible(false);
                    spawned++;
                }
            }
        }


        public void stop() {
            if (task != null) task.cancel();
        }
    }

    // 建立個人試煉世界並確保 data 資料夾及空檔案存在
    private World createPersonalTrialWorld(UUID uuid) {
        String worldName = "trial_" + uuid;
        World existingWorld = Bukkit.getWorld(worldName);
        if (existingWorld != null) {
            clearEntityAndPoiFolders(existingWorld);
        }

        // 使用 CompletableFuture 非同步創建世界
        WorldCreator creator = new WorldCreator(worldName)
                .environment(Environment.NORMAL)
                .generator(new VoidChunkGenerator());

        // 創建世界
        World world = creator.createWorld();

        if (world != null) {
            // 預先建立必要的資料夾及檔案
            prepareWorldDataFolders(world);
            updateWorldAccess(world);
            // 設置世界規則
            world.setGameRule(GameRule.KEEP_INVENTORY, true);
            world.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false); // 避免進度通知
            world.setDifficulty(Difficulty.HARD);
            world.setSpawnLocation(0, 64, 0);

            // 創建出生平台
            createSpawnPlatform(world);
            getLogger().info("Created personal trial world: " + worldName);
        }
        return world;
    }
    // 新增玩家進入/離開世界事件處理
    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        World from = event.getFrom();
        World to = event.getPlayer().getWorld();

        // 更新目標世界的訪問時間
        updateWorldAccess(to);

        // 如果離開的是試煉世界，檢查是否還有其他玩家
        if (from.getName().startsWith("trial_") && from.getPlayers().isEmpty()) {
            // 標記最後訪問時間，但暫不清理
            updateWorldAccess(from);
        }
    }

    // 新增的輔助方法，處理世界資料夾準備
    private void prepareWorldDataFolders(World world) {
        File worldFolder = world.getWorldFolder();
        // 建立必要的資料夾
        String[] foldersToCreate = {"data", "entities", "poi", "region"};

        for (String folder : foldersToCreate) {
            File dir = new File(worldFolder, folder);
            if (!dir.exists() && !dir.mkdirs()) {
                getLogger().warning("無法創建資料夾: " + dir.getAbsolutePath());
            }
        }

        // 創建必要的空檔案
        File raidsFile = new File(new File(worldFolder, "data"), "raids.dat");
        if (!raidsFile.exists()) {
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(raidsFile)) {
                fos.write(new byte[0]);
            } catch (IOException e) {
                getLogger().warning("無法創建 raids.dat 文件: " + e.getMessage());
            }
        }
    }
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // 如果玩家在試煉世界中登出
        World world = player.getWorld();
        if (world.getName().startsWith("trial_")) {
            // 標記世界最後訪問時間
            updateWorldAccess(world);

            // 可選：立即清理資源
            // 或者讓定期任務處理
        }

        // 清理冷卻進度條
        BossBar bar = cooldownBars.remove(uuid);
        if (bar != null) {
            bar.removeAll();
        }

        // 取消相關任務
        BukkitTask task = cooldownTasks.remove(uuid);
        if (task != null) {
            task.cancel();
        }
    }
    private void createSpawnPlatform(World world) {
        Location spawn = world.getSpawnLocation();
        int baseY = spawn.getBlockY() - 1;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                world.getBlockAt(spawn.getBlockX() + dx, baseY, spawn.getBlockZ() + dz)
                        .setType(Material.BEDROCK);
            }
        }
    }

    /**
     * 清空指定世界的 entities 和 poi 資料夾內容
     * 使用 Java NIO Files API 清空指定世界的資料夾內容，但保留資料夾本身
     */
    private void clearEntityAndPoiFolders(World world) {
        if (world == null) return;
        File worldFolder = world.getWorldFolder();

        // 卸載世界，但不刪除檔案
        Bukkit.unloadWorld(world, false);

        // 定義要清理的目錄
        Path[] pathsToClean = {
                Paths.get(worldFolder.getPath(), "entities"),
                Paths.get(worldFolder.getPath(), "poi"),
                Paths.get(worldFolder.getPath(), "region")
        };

        for (Path directory : pathsToClean) {
            if (Files.exists(directory)) {
                try {
                    // 使用 walkFileTree 遍歷目錄樹並只刪除內容
                    Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                        @Override
                        public @NotNull FileVisitResult visitFile(@NotNull Path file, @NotNull BasicFileAttributes attrs) throws IOException {
                            Files.delete(file);
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public @NotNull FileVisitResult postVisitDirectory(@NotNull Path dir, IOException exc) throws IOException {
                            // 如果不是要清理的根目錄，則刪除此子目錄
                            if (!dir.equals(directory)) {
                                Files.delete(dir);
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });
                } catch (IOException e) {
                    getLogger().warning("清理資料夾內容時出錯 " + directory + ": " + e.getMessage());
                }
            }
        }
    }
    /**
     * 清理未使用的資源
     */
    private void cleanupUnusedResources() {
        long now = System.currentTimeMillis();
        int cleaned = 0;

        // 1. 清理長時間未訪問的世界
        List<String> worldsToRemove = new ArrayList<>();
        for (Map.Entry<String, Long> entry : worldLastAccessed.entrySet()) {
            if (now - entry.getValue() > WORLD_RETENTION_TIME) {
                String worldName = entry.getKey();
                World world = Bukkit.getWorld(worldName);

                // 確保世界中沒有玩家
                if (world != null && world.getPlayers().isEmpty()) {
                    // 尋找對應的玩家UUID
                    UUID ownerUUID = null;
                    for (Map.Entry<UUID, World> worldEntry : playerTrialWorlds.entrySet()) {
                        if (worldEntry.getValue().getName().equals(worldName)) {
                            ownerUUID = worldEntry.getKey();
                            break;
                        }
                    }

                    if (ownerUUID != null) {
                        // 停止相關的任務
                        MobSpawnerTask task = spawnerTasks.remove(ownerUUID);
                        if (task != null) {
                            task.stop();
                        }

                        // 從映射中移除
                        playerTrialWorlds.remove(ownerUUID);
                        playerDifficulties.remove(ownerUUID);
                    }

                    // 清理世界資料
                    clearEntityAndPoiFolders(world);
                    worldsToRemove.add(worldName);
                    cleaned++;
                }
            }
        }

        // 移除處理過的世界記錄
        for (String worldName : worldsToRemove) {
            worldLastAccessed.remove(worldName);
        }

        // 2. 清理離線玩家的床位置記錄
        // 複製一份集合以避免並發修改異常
        Set<Location> bedLocations = new HashSet<>(playerPlacedBeds);
        for (Location bedLoc : bedLocations) {
            World world = bedLoc.getWorld();
            if (world == null || !world.getName().startsWith("trial_") ||
                    !worldLastAccessed.containsKey(world.getName())) {
                playerPlacedBeds.remove(bedLoc);
                cleaned++;
            }
        }

        // 3. 清理離線玩家的冷卻記錄
        List<UUID> offlinePlayers = new ArrayList<>();
        for (UUID uuid : trialCooldowns.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                offlinePlayers.add(uuid);
            }
        }

        for (UUID uuid : offlinePlayers) {
            trialCooldowns.remove(uuid);

            // 同時取消相關的冷卻任務和進度條
            BukkitTask task = cooldownTasks.remove(uuid);
            if (task != null) {
                task.cancel();
            }

            BossBar bar = cooldownBars.remove(uuid);
            if (bar != null) {
                bar.removeAll();
            }

            cleaned++;
        }

        if (cleaned > 0) {
            getLogger().info("記憶體清理完成: 釋放了 " + cleaned + " 個資源");
        }
    }

    /**
     * 更新世界訪問時間戳記
     */
    public void updateWorldAccess(World world) {
        if (world != null && world.getName().startsWith("trial_")) {
            worldLastAccessed.put(world.getName(), System.currentTimeMillis());
        }
    }
    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        World world = event.getBlockPlaced().getWorld();
        // 只在 trial_ 开头的世界里记录
        if (!world.getName().startsWith("trial_")) return;

        Block block = event.getBlockPlaced();
        if (block.getState() instanceof Bed) {
            playerPlacedBeds.add(block.getLocation());
        }
    }
    @EventHandler
    public void onSignPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        String worldName = block.getWorld().getName();

        // 只在試煉世界中攔截
        if (!worldName.startsWith("trial_")) return;

        Material type = block.getType();
        boolean isSign = switch (type) {
            // 直立 & 牆面告示牌
            case OAK_SIGN, SPRUCE_SIGN, BIRCH_SIGN, JUNGLE_SIGN,
                 ACACIA_SIGN, DARK_OAK_SIGN,
                 OAK_WALL_SIGN, SPRUCE_WALL_SIGN, BIRCH_WALL_SIGN,
                 JUNGLE_WALL_SIGN, ACACIA_WALL_SIGN, DARK_OAK_WALL_SIGN -> true;

            // 懸掛告示牌
            case OAK_HANGING_SIGN, SPRUCE_HANGING_SIGN, BIRCH_HANGING_SIGN,
                 JUNGLE_HANGING_SIGN, ACACIA_HANGING_SIGN, CHERRY_HANGING_SIGN,
                 DARK_OAK_HANGING_SIGN, PALE_OAK_HANGING_SIGN, MANGROVE_HANGING_SIGN,
                 BAMBOO_HANGING_SIGN, CRIMSON_HANGING_SIGN, WARPED_HANGING_SIGN -> true;

            // 牆面懸掛告示牌
            case OAK_WALL_HANGING_SIGN, SPRUCE_WALL_HANGING_SIGN,
                 BIRCH_WALL_HANGING_SIGN, JUNGLE_WALL_HANGING_SIGN,
                 ACACIA_WALL_HANGING_SIGN, CHERRY_WALL_HANGING_SIGN,
                 DARK_OAK_WALL_HANGING_SIGN, MANGROVE_WALL_HANGING_SIGN,
                 CRIMSON_WALL_HANGING_SIGN, WARPED_WALL_HANGING_SIGN,
                 BAMBOO_WALL_HANGING_SIGN -> true;

            default -> false;
        };

        if (isSign) {
            event.setCancelled(true);
            // 使用 Adventure Component API 傳送提示
            event.getPlayer().sendMessage(
                    Component.text("§c此地禁止放置任何告示牌！")
            );
        }
    }
    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        World world = event.getBlock().getWorld();
        // 只在 trial_ 开头的世界里移除
        if (!world.getName().startsWith("trial_")) return;

        Block block = event.getBlock();
        if (block.getState() instanceof Bed) {
            playerPlacedBeds.remove(block.getLocation());
        }
    }
    // 攔截任何形式的傳送門生成（保險起見）
    @EventHandler
    public void onPortalCreate(PortalCreateEvent event) {
        World world = event.getWorld();
        if (!world.getName().startsWith("trial_")) return;

        // 取消所有傳送門結構生成
        event.setCancelled(true);
    }

    /**
     * /trialchambers 指令處理，重複執行前先刪除舊世界
     */
    private class TrialChambersCommand implements CommandExecutor, TabCompleter {
        private static final long COOLDOWN_MS = 150_000L; // 2.5 分鐘

        @Override
        public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd,
                                 @NotNull String label, @NotNull String @NotNull [] args) {
            if (!(sender instanceof Player player)) {
                return true;
            }
            UUID uid = player.getUniqueId();
            long now = System.currentTimeMillis();
            long last = trialCooldowns.getOrDefault(uid, 0L);
            long elapsed = now - last;
            boolean isBedrock = FloodgateApi.getInstance().isFloodgatePlayer(uid);

            if (elapsed < COOLDOWN_MS) {
                long remaining = COOLDOWN_MS - elapsed;
                long minutesLeft = remaining / 60000;
                long secondsLeft = (remaining % 60000 + 999) / 1000;

                String timeLeft = (minutesLeft > 0 ? minutesLeft + " 分 " : "") + secondsLeft + " 秒";
                player.sendMessage("§e指令冷卻中，請稍等 " + timeLeft + "後再試");

                // **仅在 Java 客户端显示 BossBar**
                if (!isBedrock) {
                    BossBar bar = cooldownBars.computeIfAbsent(uid, id -> {
                        BossBar b = Bukkit.createBossBar("§e指令冷卻中... " + timeLeft,
                                BarColor.BLUE, BarStyle.SOLID);
                        b.addPlayer(player);
                        b.setVisible(true);
                        return b;
                    });

                    if (!cooldownTasks.containsKey(uid)) {
                        BukkitTask task = new BukkitRunnable() {
                            final long startTime = System.currentTimeMillis();
                            final long total = remaining;
                            @Override
                            public void run() {
                                long now = System.currentTimeMillis();
                                long passed = now - startTime;
                                long left = total - passed;
                                if (left <= 0) {
                                    bar.removeAll();
                                    cooldownBars.remove(uid);
                                    cooldownTasks.remove(uid);
                                    cancel();
                                    return;
                                }
                                long min = left / 60000;
                                long sec = (left % 60000 + 999) / 1000;
                                bar.setTitle("§e指令冷卻中... " +
                                        (min>0?min+" 分 ":"") + sec + " 秒");
                                bar.setProgress((double) left / total);
                            }
                        }.runTaskTimer(VoidTrialChambersPlugin.this, 0L, 20L);
                        cooldownTasks.put(uid, task);
                    } else {
                        bar.setTitle("§e指令冷卻中... " + timeLeft);
                    }
                }
                return true;
            }

            String input = args.length>0? args[0] : "普通";
            TrialDifficulty diff = TrialDifficulty.from(input);
            if (diff == null) { player.sendMessage("§c無效難度，請輸入 簡單/普通/困難"); return true; }

            // 刪除舊試煉世界
            World oldWorld = playerTrialWorlds.remove(uid);
            if (oldWorld != null) {
                clearEntityAndPoiFolders(oldWorld);
            }

            trialCooldowns.put(uid, now);
            TrialDifficulty ignored = playerDifficulties.get(uid);
            player.sendMessage(Component.text("§6正在進入「" + diff.getDisplay() + "」難度的試煉世界，請稍候..."));
            originalLocations.put(uid, player.getLocation());

            World personal = createPersonalTrialWorld(uid);
            if (personal == null) {
                player.sendMessage("§c無法建立試煉世界，請稍後再試");
                return true;
            }
            playerTrialWorlds.put(uid, personal);
            // 記錄難度並啟動刷怪任務
            playerDifficulties.put(uid, diff);
            MobSpawnerTask spawner = new MobSpawnerTask(uid, personal, player, diff);
            spawnerTasks.put(uid, spawner);
            spawner.start();

            int y = getConfig().getInt("trial_chamber.y", 50);
            int range = getConfig().getInt("trial_range", 500);
            Random rnd = new Random();
            int x = rnd.nextInt(range * 2 + 1) - range;
            int z = rnd.nextInt(range * 2 + 1) - range;
            Location center = new Location(personal, x + 0.5, y + 5, z + 0.5);

            player.teleport(center);
            applyTrialEffects(player);

            // 顯示「試煉準備中...」進度條
            BossBar trialBar = Bukkit.createBossBar("§6試煉準備中...", BarColor.YELLOW, BarStyle.SOLID);
            trialBar.addPlayer(player);
            trialBar.setVisible(true);

            new BukkitRunnable() {
                final long total = 13_000L;
                final long start = System.currentTimeMillis();

                @Override
                public void run() {
                    long now = System.currentTimeMillis();
                    long elapsed = now - start;

                    if (elapsed >= total) {
                        trialBar.removePlayer(player);
                        cancel();
                        return;
                    }

                    double progress = (double) elapsed / total;
                    trialBar.setProgress(progress);
                }
            }.runTaskTimer(VoidTrialChambersPlugin.this, 0L, 10L); // 每 0.5 秒更新一次

            new BukkitRunnable() {
                @Override public void run() {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                            String.format("execute in %s run place structure minecraft:trial_chambers %d %d %d",
                                    personal.getName(), x, y, z));
                    getLogger().info("Structure placed at " + x + "," + y + "," + z);
                }
            }.runTaskLater(VoidTrialChambersPlugin.this, 200L);

            new BukkitRunnable() {
                @Override public void run() {
                    removeTrialEffects(player);
                    teleportToNearestBedOrOrigin(player, personal);
                }
            }.runTaskLater(VoidTrialChambersPlugin.this, 240L);

            return true;
        }
        @Override
        public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
            if (args.length == 1) {
                List<String> options = Arrays.asList("困難", "普通", "簡單");
                String input = args[0];
                List<String> completions = new ArrayList<>();
                for (String option : options) {
                    if (option.startsWith(input)) {
                        completions.add(option);
                    }
                }
                return completions;
            }
            return Collections.emptyList();
        }
        private void applyTrialEffects(Player p) {
            p.addPotionEffect(new org.bukkit.potion.PotionEffect(PotionEffectType.BLINDNESS, 400, 99));
            p.addPotionEffect(new org.bukkit.potion.PotionEffect(PotionEffectType.RESISTANCE, 380, 99));
            p.addPotionEffect(new org.bukkit.potion.PotionEffect(PotionEffectType.SLOW_FALLING, 300, 2));
            p.addPotionEffect(new org.bukkit.potion.PotionEffect(PotionEffectType.SLOWNESS, 300, 99));
            p.addPotionEffect(new org.bukkit.potion.PotionEffect(PotionEffectType.MINING_FATIGUE, 300, 99));
        }

        private void removeTrialEffects(Player p) {
            for (PotionEffectType type : Arrays.asList(
                    PotionEffectType.BLINDNESS, PotionEffectType.SLOW_FALLING,
                    PotionEffectType.SLOWNESS, PotionEffectType.MINING_FATIGUE)) {
                p.removePotionEffect(type);
            }
            p.addPotionEffect(new org.bukkit.potion.PotionEffect(PotionEffectType.NIGHT_VISION, 100, 0));
        }

        private void teleportToNearestBedOrOrigin(Player p, World world) {
            Location loc = p.getLocation();
            Location bedLoc = findNearestBed(loc, world);
            if (bedLoc != null) {
                p.teleport(bedLoc);
                p.sendMessage("§6已進入試煉之間副本");
            } else {
                Location orig = originalLocations.remove(p.getUniqueId());
                if (orig != null) {
                    p.teleport(orig);
                    p.sendMessage("§6未找到床，已傳送回原始位置");
                }
            }
        }

        private Location findNearestBed(Location center, World world) {
            double best = Double.MAX_VALUE;
            Location bestLoc = null;
            int cx = center.getBlockX(), cy = center.getBlockY(), cz = center.getBlockZ();
            for (int x = cx - 100; x <= cx + 100; x++) {
                for (int z = cz - 100; z <= cz + 100; z++) {
                    for (int y = world.getMinHeight(); y <= Math.min(world.getMaxHeight(), cy + 100); y++) {
                        Block b = world.getBlockAt(x, y, z);
                        if (b.getState() instanceof Bed) {
                            double dist = b.getLocation().distanceSquared(center);
                            if (dist < best) {
                                best = dist;
                                bestLoc = b.getLocation().add(0.5, 1, 0.5);
                            }
                        }
                    }
                }
            }
            return bestLoc;
        }
    }

    private class ExitTrialCommand implements CommandExecutor {
        @Override
        public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd,
                                 @NotNull String label, @NotNull String @NotNull [] args) {
            if (!(sender instanceof Player p)) {
                return true;
            }
            String currentWorld = p.getWorld().getName();
            if (excludedWorldNames.contains(currentWorld)) {
                return true;
            }
            World main = Bukkit.getWorld("world");
            if (main != null) {
                for (PotionEffectType type : Arrays.asList(
                        PotionEffectType.BLINDNESS, PotionEffectType.SLOW_FALLING,
                        PotionEffectType.SLOWNESS, PotionEffectType.MINING_FATIGUE,
                        PotionEffectType.RESISTANCE)) {
                    p.removePotionEffect(type);
                }
                p.teleport(main.getSpawnLocation());
                p.sendMessage("§6你已退出試煉並傳送至主世界");
            }
            UUID uid = p.getUniqueId();
            // —— 修复：先取出再判空 ——
            MobSpawnerTask spawner = spawnerTasks.remove(uid);
            if (spawner != null) {
                spawner.stop();
            }

            // —— 同理，对世界清理也判空 ——
            World w = playerTrialWorlds.remove(uid);
            if (w != null) {
                clearEntityAndPoiFolders(w);
            }
            return true;
        }
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent evt) {
        Player p = evt.getPlayer();
        String worldName = p.getWorld().getName();
        if (excludedWorldNames.contains(worldName)) {
            return;
        }
        World main = Bukkit.getWorld("world");
        if (main != null) {
            evt.setRespawnLocation(main.getSpawnLocation());
            p.sendMessage("§6你已重生於主世界重生點！");
            UUID uid = p.getUniqueId();
            World w = playerTrialWorlds.remove(uid);
            clearEntityAndPoiFolders(w);
        }
    }

    @Override
    public ChunkGenerator getDefaultWorldGenerator(@NotNull String worldName, String id) {
        return new VoidChunkGenerator();
    }
    public static class VoidChunkGenerator extends ChunkGenerator {
        @Override
        public void generateNoise(@NotNull WorldInfo info, @NotNull Random rand, int x, int z, @NotNull ChunkData data) {
            // 在 y=0 處生成一層基岩，防止玩家掉落虛空
            if (x == 0 && z == 0) {
                data.setBlock(0, 0, 0, Material.BEDROCK);
            }
        }

        @Override
        public void generateSurface(@NotNull WorldInfo info, @NotNull Random rand, int x, int z, @NotNull ChunkData data) {}

        @Override
        public void generateCaves(@NotNull WorldInfo info, @NotNull Random rand, int x, int z, @NotNull ChunkData data) {}

        // 優化：預先生成重要區塊以減少動態生成延遲
        @Override
        public boolean shouldGenerateCaves() {
            return false;
        }

        @Override
        public boolean shouldGenerateDecorations() {
            return false;
        }

        @Override
        public boolean shouldGenerateMobs() {
            return false;
        }

        @Override
        public boolean shouldGenerateStructures() {
            return false;
        }
    }
}