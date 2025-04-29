package org.swim.voidTrialChambersPlugin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
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
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
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
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class VoidTrialChambersPlugin extends JavaPlugin implements Listener {

    // 世界保留時間（毫秒）
    private static final long WORLD_RETENTION_TIME = 15 * 60 * 1000; // 15分鐘
    // 每個試煉世界的最大玩家數量
    private static final int MAX_TRIAL_PLAYERS = 4;
    // 每位玩家的試煉世界映射
    private final Map<UUID, World> playerTrialWorlds = new HashMap<>();
    // 原始位置備份
    private final Map<UUID, Location> originalLocations = new HashMap<>();
    // 玩家難度映射
    private final Map<UUID, TrialDifficulty> playerDifficulties = new HashMap<>();
    // MobSpawner 任務映射
    private final Map<UUID, WorldMobSpawnerTask> spawnerTasks = new HashMap<>();
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
    //每個試煉世界當前的擊殺數
    private final Map<String, Integer> worldKillCounts = new HashMap<>();
    // 每個試煉世界的開始時間（毫秒）
    private final Map<String, Long> worldStartTimes = new HashMap<>();
    // 單人時間排行榜
    private final Map<String, List<TimeLeaderboardEntry>> timeLeaderboardSolo = new HashMap<>();
    // 團隊時間排行榜
    private final Map<String, List<TimeLeaderboardEntry>> timeLeaderboardTeam = new HashMap<>();
    // 击杀排行榜
    private final Map<String, List<KillsLeaderboardEntry>> killsLeaderboard = new HashMap<>();
    private final Map<String, TrialSession> activeTrialSessions = new ConcurrentHashMap<>();
    // 排除處理的世界名單
    private List<String> excludedWorldNames;
    //單獨通關排行榜
    private File soloTimeLeaderboardFile;
    // 團隊通關排行榜
    private File teamTimeLeaderboardFile;
    // 擊殺數排行榜
    private File killsLeaderboardFile;


    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        saveDefaultConfig();
        reloadConfig();
        setupLeaderboardFiles();
        loadLeaderboards();
        MonsterCleaner.startCleaningTask(this);
        excludedWorldNames = getConfig().getStringList("excluded_worlds");
        TrialChambersCommand trialCmd = new TrialChambersCommand();
        Objects.requireNonNull(getCommand("trialchambers")).setExecutor(new TrialChambersCommand());
        Objects.requireNonNull(getCommand("exittrial")).setExecutor(new ExitTrialCommand());
        Objects.requireNonNull(getCommand("trialleaderboard")).setExecutor(new LeaderboardCommand());
        Objects.requireNonNull(getCommand("trialleaderboard")).setTabCompleter(new LeaderboardCommand());
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

    @Override
    public void onDisable() {
        // 停止所有 Spawner 任務
        spawnerTasks.values().forEach(WorldMobSpawnerTask::stop);
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

    private void setupLeaderboardFiles() {

        File dataFolder = getDataFolder();
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }

        soloTimeLeaderboardFile = new File(dataFolder, "solo_time_leaderboard.json");
        teamTimeLeaderboardFile = new File(dataFolder, "team_time_leaderboard.json");
        killsLeaderboardFile = new File(dataFolder, "kills_leaderboard.json");

        try {
            if (!soloTimeLeaderboardFile.exists()) soloTimeLeaderboardFile.createNewFile();
            if (!teamTimeLeaderboardFile.exists()) teamTimeLeaderboardFile.createNewFile();
            if (!killsLeaderboardFile.exists()) killsLeaderboardFile.createNewFile();
        } catch (IOException e) {
            getLogger().warning("無法創建排行榜文件: " + e.getMessage());
        }
    }

    private void loadLeaderboards() {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();

        // Load solo time leaderboard
        try (FileReader reader = new FileReader(soloTimeLeaderboardFile)) {
            Type type = new TypeToken<Map<String, List<TimeLeaderboardEntry>>>() {
            }.getType();
            Map<String, List<TimeLeaderboardEntry>> loaded = gson.fromJson(reader, type);
            if (loaded != null) {
                timeLeaderboardSolo.putAll(loaded);
            }
        } catch (IOException e) {
            getLogger().warning("加載單人時間排行榜時發生錯誤: " + e.getMessage());
        }

        // Load team time leaderboard
        try (FileReader reader = new FileReader(teamTimeLeaderboardFile)) {
            Type type = new TypeToken<Map<String, List<TimeLeaderboardEntry>>>() {
            }.getType();
            Map<String, List<TimeLeaderboardEntry>> loaded = gson.fromJson(reader, type);
            if (loaded != null) {
                timeLeaderboardTeam.putAll(loaded);
            }
        } catch (IOException e) {
            getLogger().warning("加載團隊時間排行榜時發生錯誤: " + e.getMessage());
        }

        // Load kills leaderboard
        try (FileReader reader = new FileReader(killsLeaderboardFile)) {
            Type type = new TypeToken<Map<String, List<KillsLeaderboardEntry>>>() {
            }.getType();
            Map<String, List<KillsLeaderboardEntry>> loaded = gson.fromJson(reader, type);
            if (loaded != null) {
                killsLeaderboard.putAll(loaded);
            }
        } catch (IOException e) {
            getLogger().warning("加載擊殺排行榜時發生錯誤: " + e.getMessage());
        }
    }

    private void saveLeaderboards() {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();

        // Save solo time leaderboard
        try (FileWriter writer = new FileWriter(soloTimeLeaderboardFile)) {
            gson.toJson(timeLeaderboardSolo, writer);
        } catch (IOException e) {
            getLogger().warning("保存單人時間排行榜時發生錯誤: " + e.getMessage());
        }

        // Save team time leaderboard
        try (FileWriter writer = new FileWriter(teamTimeLeaderboardFile)) {
            gson.toJson(timeLeaderboardTeam, writer);
        } catch (IOException e) {
            getLogger().warning("保存團隊時間排行榜時發生錯誤: " + e.getMessage());
        }

        // Save kills leaderboard
        try (FileWriter writer = new FileWriter(killsLeaderboardFile)) {
            gson.toJson(killsLeaderboard, writer);
        } catch (IOException e) {
            getLogger().warning("保存擊殺排行榜時發生錯誤: " + e.getMessage());
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
        Player player = event.getPlayer();
        World to = player.getWorld();

        // 如果切换到 trial_* 世界，且玩家不是 OP
        if (to.getName().startsWith("trial_") && !player.isOp()) {
            // 计算当前世界中非 OP 玩家数
            long nonOpCount = to.getPlayers().stream()
                    .filter(p -> !p.isOp())
                    .count();
            if (nonOpCount > MAX_TRIAL_PLAYERS) {
                // 人数已满，将玩家传回主世界
                World main = Bukkit.getWorld("world");
                if (main != null) {
                    player.sendMessage("§c試煉世界已滿 (最多 " + MAX_TRIAL_PLAYERS + " 位玩家)，已傳送回主世界。");
                    player.teleport(main.getSpawnLocation());
                }
                // 不做后续清理逻辑
                return;
            }
        }

        // —— 新增：更新試煉會話參與者 ——
        if (to.getName().startsWith("trial_")) {
            TrialSession session = activeTrialSessions.computeIfAbsent(to.getName(), k -> new TrialSession(to.getName()));
            session.addParticipant(player);
        }

        World from = event.getFrom();

        // 更新目標世界的訪問時間
        updateWorldAccess(to);

        // 如果離開的是試煉世界，檢查是否還有其他玩家
        if (from.getName().startsWith("trial_") && from.getPlayers().isEmpty()) {
            // 標記最後訪問時間
            updateWorldAccess(from);

            // 找出這個世界的擁有者UUID
            UUID ownerUUID = null;
            for (Map.Entry<UUID, World> entry : new HashMap<>(playerTrialWorlds).entrySet()) {
                if (entry.getValue().getName().equals(from.getName())) {
                    ownerUUID = entry.getKey();
                    break;
                }
            }

            // 停止相關任務並清理資源
            if (ownerUUID != null) {
                stopAndCleanPlayerResources(ownerUUID);
            }

            // 清理和卸載世界
            clearEntityAndPoiFolders(from);
            worldLastAccessed.remove(from.getName());
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

        // 識別需要清理的世界
        List<String> worldsToClean = worldLastAccessed.entrySet().stream()
                .filter(entry -> now - entry.getValue() > WORLD_RETENTION_TIME)
                .map(Map.Entry::getKey)
                .filter(worldName -> {
                    World world = Bukkit.getWorld(worldName);
                    return world != null && world.getPlayers().isEmpty();
                })
                .toList();

        // 執行清理
        for (String worldName : worldsToClean) {
            World world = Bukkit.getWorld(worldName);
            if (world == null) continue;

            UUID ownerUUID = getOwnerUUIDByWorldName(worldName);

            if (ownerUUID != null) {
                stopAndCleanPlayerResources(ownerUUID);
            }

            getLogger().info("正在清理未使用的試煉世界: " + worldName);
            clearEntityAndPoiFolders(world);
            worldLastAccessed.remove(worldName);
            cleaned++;
        }

        // 清理離線玩家的資源
        cleanupOfflinePlayerResources();

        if (cleaned > 0) {
            getLogger().info("記憶體清理完成: 釋放了 " + cleaned + " 個世界資源");
        }
    }

    /**
     * 根據世界名稱取得對應的玩家 UUID
     */
    private UUID getOwnerUUIDByWorldName(String worldName) {
        for (Map.Entry<UUID, World> entry : playerTrialWorlds.entrySet()) {
            if (entry.getValue().getName().equals(worldName)) {
                return entry.getKey();
            }
        }
        return null;
    }

    // 新增輔助方法，處理停止和清理單個玩家的所有資源
    private void stopAndCleanPlayerResources(UUID playerUUID) {
        // 停止刷怪任務
        WorldMobSpawnerTask task = spawnerTasks.remove(playerUUID);
        if (task != null) {
            task.stop();
        }

        // 移除世界映射
        playerTrialWorlds.remove(playerUUID);
        playerDifficulties.remove(playerUUID);

        // 清理冷卻相關資源
        BukkitTask cooldownTask = cooldownTasks.remove(playerUUID);
        if (cooldownTask != null) {
            cooldownTask.cancel();
        }

        BossBar bar = cooldownBars.remove(playerUUID);
        if (bar != null) {
            bar.removeAll();
        }
    }

    // 清理離線玩家的資源
    private void cleanupOfflinePlayerResources() {
        int cleaned = 0;
        List<UUID> offlinePlayers = new ArrayList<>();

        // 識別離線玩家
        for (UUID uuid : new HashSet<>(trialCooldowns.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                offlinePlayers.add(uuid);
            }
        }

        // 清理資源
        for (UUID uuid : offlinePlayers) {
            trialCooldowns.remove(uuid);
            stopAndCleanPlayerResources(uuid);
            cleaned++;
        }

        // 清理玩家放置的床記錄
        Iterator<Location> bedIterator = playerPlacedBeds.iterator();
        while (bedIterator.hasNext()) {
            Location bedLoc = bedIterator.next();
            World world = bedLoc.getWorld();
            if (world == null || !world.getName().startsWith("trial_") ||
                    !worldLastAccessed.containsKey(world.getName())) {
                bedIterator.remove();  // 正確的移除方式
                cleaned++;
            }
        }

        if (cleaned > 0) {
            getLogger().info("已清理 " + cleaned + " 個離線玩家資源");
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

    @EventHandler
    public void onEntityDeath(EntityDeathEvent evt) {
        World world = evt.getEntity().getWorld();
        String name = world.getName();
        if (!name.startsWith("trial_")) return;
        if (!(evt.getEntity() instanceof Monster)) return;

        // 1. 取得最後一次傷害事件
        EntityDamageEvent last = evt.getEntity().getLastDamageCause();
        if (!(last instanceof EntityDamageByEntityEvent dbe)) {
            // 非實體直接攻擊（如爆炸）— 不計數
            return;
        }
        // 2. 強轉為 EntityDamageByEntityEvent
        Entity damager = dbe.getDamager();
        // 3. 只計算玩家直接攻擊
        if (!(damager instanceof Player player)) {
            return;
        }

        UUID owner = findWorldOwner(name);
        if (owner == null) return;
        TrialDifficulty diff = playerDifficulties.get(owner);
        if (diff != TrialDifficulty.HELL) return;

        // 增加計數
        int count = worldKillCounts.getOrDefault(name, 0) + 1;
        worldKillCounts.put(name, count);

        // 更新或建立試煉場景
        TrialSession session = activeTrialSessions.computeIfAbsent(name, k -> new TrialSession(name));
        session.addParticipant(player);
        session.recordKill(player.getUniqueId());

        // 如果是第一次擊殺，記錄開始時間
        if (count == 1) {
            worldStartTimes.put(name, System.currentTimeMillis());
        }

        // 達 300 就結束試煉
        if (count >= 300) {
            // 計算耗時
            long start = worldStartTimes.getOrDefault(name, System.currentTimeMillis());
            long elapsedMs = System.currentTimeMillis() - start;
            long totalSeconds = elapsedMs / 1000;
            long minutes = totalSeconds / 60;
            long seconds = totalSeconds % 60;

            // 格式化時間字串
            String timeStr = (minutes > 0
                    ? minutes + " 分 " + seconds + " 秒"
                    : seconds + " 秒");

            // 停止刷怪任務
            WorldMobSpawnerTask spawner = spawnerTasks.remove(owner);
            if (spawner != null) spawner.stop();

            // 清光所有怪物
            world.getEntities().stream()
                    .filter(e -> e instanceof Monster)
                    .forEach(Entity::remove);

            // 標記試煉完成並更新排行榜
            TrialSession currentSession = activeTrialSessions.get(name);
            if (currentSession != null) {
                currentSession.markCompleted();

                // 根據情況更新不同的排行榜
                if (currentSession.isSolo()) {
                    // 單人排行榜
                    TimeLeaderboardEntry entry = new TimeLeaderboardEntry(
                            name,
                            currentSession.getParticipantNames(),
                            elapsedMs
                    );
                    updateTimeLeaderboard(diff.name(), entry, true);
                } else {
                    // 多人排行榜
                    TimeLeaderboardEntry entry = new TimeLeaderboardEntry(
                            name,
                            currentSession.getParticipantNames(),
                            elapsedMs
                    );
                    updateTimeLeaderboard(diff.name(), entry, false);
                }

                // 擊殺排行榜
                KillsLeaderboardEntry killsEntry = new KillsLeaderboardEntry(
                        name,
                        currentSession.getPlayerKillRecords()
                );
                updateKillsLeaderboard(diff.name(), killsEntry);

                // 保存更新的排行榜
                saveLeaderboards();
            }

            // 廣播完成訊息，並顯示耗時
            world.getPlayers().forEach(p ->
                    p.sendMessage("§6【試煉完成】恭喜，在地獄難度的試煉副本已擊殺 300 隻怪物！" +
                            " 耗時：" + timeStr + "\n" +
                            "§a排行榜已更新，可使用 /trialleaderboard solo/team/kills 查看")
            );

            // 重置計數與開始時間，以便下次重新開始
            worldKillCounts.remove(name);
            worldStartTimes.remove(name);
            activeTrialSessions.remove(name);
        }
    }

    private void updateKillsLeaderboard(String difficulty, KillsLeaderboardEntry entry) {
        List<KillsLeaderboardEntry> entries = killsLeaderboard.computeIfAbsent(difficulty, k -> new ArrayList<>());

        // 添加新記錄
        entries.add(entry);

        // 按擊殺數排序（降序）
        entries.sort((e1, e2) -> Integer.compare(e2.getTotalKills(), e1.getTotalKills()));

        // 只保留前 10 名
        if (entries.size() > 10) {
            entries = entries.subList(0, 10);
            killsLeaderboard.put(difficulty, entries);
        }
    }

    private void updateTimeLeaderboard(String difficulty, TimeLeaderboardEntry entry, boolean isSolo) {
        Map<String, List<TimeLeaderboardEntry>> leaderboard = isSolo ? timeLeaderboardSolo : timeLeaderboardTeam;
        List<TimeLeaderboardEntry> entries = leaderboard.computeIfAbsent(difficulty, k -> new ArrayList<>());

        // 添加新記錄
        entries.add(entry);

        // 按完成時間排序
        entries.sort(Comparator.comparingLong(TimeLeaderboardEntry::getCompletionTimeMs));

        // 只保留前 10 名
        if (entries.size() > 10) {
            entries = entries.subList(0, 10);
            leaderboard.put(difficulty, entries);
        }
    }

    /**
     * 輔助：給定 worldName 找到對應的玩家 UUID
     */
    private UUID findWorldOwner(String worldName) {
        for (var entry : playerTrialWorlds.entrySet()) {
            if (entry.getValue().getName().equals(worldName)) {
                return entry.getKey();
            }
        }
        return null;
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

    /**
     * Difficulty enum 定義刷怪列表
     */
    public enum TrialDifficulty {
        EASY("簡單"),
        NORMAL("普通"),
        HELL("地獄");

        private final String display;

        TrialDifficulty(String display) {
            this.display = display;
        }

        public static TrialDifficulty from(String s) {
            for (TrialDifficulty d : values()) {
                if (d.name().equalsIgnoreCase(s) || d.display.equalsIgnoreCase(s)) return d;
            }
            return null;
        }

        /**
         * 返回中文显示名
         */
        public String getDisplay() {
            return display;
        }

        public List<EntityType> getMobs() {
            return switch (this) {
                case NORMAL -> List.of(EntityType.ZOMBIE, EntityType.SKELETON, EntityType.SPIDER);
                case HELL ->
                        List.of(EntityType.ZOMBIE, EntityType.CREEPER, EntityType.ENDERMAN, EntityType.WITCH, EntityType.BOGGED, EntityType.STRAY, EntityType.PHANTOM, EntityType.CAVE_SPIDER, EntityType.CAVE_SPIDER, EntityType.ILLUSIONER, EntityType.PIGLIN_BRUTE);
                default -> List.of();
            };
        }
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
            }.runTaskTimer(plugin, 0L, 3600L);
        }
    }

    // Classes to represent leaderboard entries
    public static class TimeLeaderboardEntry {
        private final String worldName;
        private final List<String> playerNames;
        private final long completionTimeMs;
        private final String formattedTime;
        private final long timestamp;

        public TimeLeaderboardEntry(String worldName, List<String> playerNames, long completionTimeMs) {
            this.worldName = worldName;
            this.playerNames = playerNames;
            this.completionTimeMs = completionTimeMs;
            this.formattedTime = formatTime(completionTimeMs);
            this.timestamp = System.currentTimeMillis();
        }

        public String getWorldName() {
            return worldName;
        }

        public List<String> getPlayerNames() {
            return playerNames;
        }

        public long getCompletionTimeMs() {
            return completionTimeMs;
        }

        public String getFormattedTime() {
            return formattedTime;
        }

        public long getTimestamp() {
            return timestamp;
        }

        private String formatTime(long ms) {
            long totalSeconds = ms / 1000;
            long minutes = totalSeconds / 60;
            long seconds = totalSeconds % 60;
            return (minutes > 0 ? minutes + " 分 " : "") + seconds + " 秒";
        }
    }

    public static class KillsLeaderboardEntry {
        private final String worldName;
        private final List<PlayerKillRecord> playerRecords;
        private final int totalKills;
        private final long timestamp;

        public KillsLeaderboardEntry(String worldName, List<PlayerKillRecord> playerRecords) {
            this.worldName = worldName;
            this.playerRecords = playerRecords;
            this.totalKills = playerRecords.stream().mapToInt(PlayerKillRecord::kills).sum();
            this.timestamp = System.currentTimeMillis();
        }

        public String getWorldName() {
            return worldName;
        }

        public List<PlayerKillRecord> getPlayerRecords() {
            return playerRecords;
        }

        public int getTotalKills() {
            return totalKills;
        }

        public long getTimestamp() {
            return timestamp;
        }
    }

    public record PlayerKillRecord(String playerName, int kills) {
    }

    public static class TrialSession {
        private final long startTime;
        private final Map<UUID, String> participants;
        private final Map<UUID, Integer> playerKills;
        private boolean completed;

        public TrialSession(String worldName) {
            this.startTime = System.currentTimeMillis();
            this.participants = new HashMap<>();
            this.playerKills = new HashMap<>();
            this.completed = false;
        }

        public void addParticipant(Player player) {
            participants.put(player.getUniqueId(), player.getName());
        }

        public void recordKill(UUID playerId) {
            playerKills.put(playerId, playerKills.getOrDefault(playerId, 0) + 1);
        }

        public long getDuration() {
            return System.currentTimeMillis() - startTime;
        }

        public boolean isSolo() {
            return participants.size() == 1;
        }

        public List<String> getParticipantNames() {
            return new ArrayList<>(participants.values());
        }

        public List<PlayerKillRecord> getPlayerKillRecords() {
            List<PlayerKillRecord> records = new ArrayList<>();
            for (Map.Entry<UUID, Integer> entry : playerKills.entrySet()) {
                String playerName = participants.getOrDefault(entry.getKey(), "Unknown");
                records.add(new PlayerKillRecord(playerName, entry.getValue()));
            }
            return records;
        }

        public void markCompleted() {
            this.completed = true;
        }

        public boolean isCompleted() {
            return completed;
        }
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
        public void generateSurface(@NotNull WorldInfo info, @NotNull Random rand, int x, int z, @NotNull ChunkData data) {
        }

        @Override
        public void generateCaves(@NotNull WorldInfo info, @NotNull Random rand, int x, int z, @NotNull ChunkData data) {
        }

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

    private class LeaderboardCommand implements CommandExecutor, TabCompleter {
        @Override
        public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd,
                                 @NotNull String label, @NotNull String @NotNull [] args) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§c只有玩家可以使用此指令");
                return true;
            }

            // 預設顯示地獄難度的排行榜
            String type = args.length > 0 ? args[0].toLowerCase() : "solo";

            switch (type) {
                case "solo":
                    showSoloTimeLeaderboard(player);
                    break;
                case "team":
                    showTeamTimeLeaderboard(player);
                    break;
                case "kills":
                    showKillsLeaderboard(player);
                    break;
                default:
                    player.sendMessage("§c未知的排行榜類型。用法: /trialleaderboard solo/team/kills");
            }

            return true;
        }

        @Override
        public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                          @NotNull String alias, String[] args) {
            if (args.length == 1) {
                String input = args[0].toLowerCase();
                return Stream.of("solo", "team", "kills")
                        .filter(s -> s.startsWith(input))
                        .collect(Collectors.toList());
            }
            return Collections.emptyList();
        }

        private void showSoloTimeLeaderboard(Player player) {
            List<TimeLeaderboardEntry> entries = timeLeaderboardSolo.getOrDefault(TrialDifficulty.HELL.name(), Collections.emptyList());

            player.sendMessage("§6=========== §e單人時間排行榜§6===========");
            if (entries.isEmpty()) {
                player.sendMessage("§7暫無記錄");
                return;
            }

            for (int i = 0; i < entries.size(); i++) {
                TimeLeaderboardEntry entry = entries.get(i);
                player.sendMessage(String.format("§e#%d §f%s §7- §a%s",
                        i + 1,
                        entry.getPlayerNames().getFirst(),
                        entry.getFormattedTime()));
            }
        }

        private void showTeamTimeLeaderboard(Player player) {
            List<TimeLeaderboardEntry> entries = timeLeaderboardTeam.getOrDefault(TrialDifficulty.HELL.name(), Collections.emptyList());

            player.sendMessage("§6=========== §e團隊時間排行榜§6===========");
            if (entries.isEmpty()) {
                player.sendMessage("§7暫無記錄");
                return;
            }

            for (int i = 0; i < entries.size(); i++) {
                TimeLeaderboardEntry entry = entries.get(i);
                player.sendMessage(String.format("§e#%d §f%s §7- §a%s",
                        i + 1,
                        String.join(", ", entry.getPlayerNames()),
                        entry.getFormattedTime()));
            }
        }

        private void showKillsLeaderboard(Player player) {
            List<KillsLeaderboardEntry> entries = killsLeaderboard.getOrDefault(TrialDifficulty.HELL.name(), Collections.emptyList());

            player.sendMessage("§6=========== §e擊殺排行榜§6===========");
            if (entries.isEmpty()) {
                player.sendMessage("§7暫無記錄");
                return;
            }

            for (int i = 0; i < entries.size(); i++) {
                KillsLeaderboardEntry entry = entries.get(i);
                StringBuilder sb = new StringBuilder();
                sb.append(String.format("§e#%d §a總擊殺: %d §7- ", i + 1, entry.getTotalKills()));

                List<String> playerRecords = entry.getPlayerRecords().stream()
                        .map(record -> record.playerName() + ": " + record.kills())
                        .collect(Collectors.toList());

                sb.append("§f").append(String.join(", ", playerRecords));
                player.sendMessage(sb.toString());
            }
        }
    }

    private class WorldMobSpawnerTask {
        private static final int MAX_ACTIVE_MOBS = 300;
        private final World world;
        private final TrialDifficulty diff;
        private final Random rnd = new Random();
        private BukkitTask task;

        public WorldMobSpawnerTask(World world, TrialDifficulty diff) {
            this.world = world;
            this.diff = diff;
        }

        public void start() {
            // 立刻執行一次，之後每 [3~5] 秒重複
            task = new BukkitRunnable() {
                @Override
                public void run() {
                    spawnWave();
                }
            }.runTaskTimer(VoidTrialChambersPlugin.this, 0L, (rnd.nextInt(3) + 3) * 20L);
        }

        private void spawnWave() {
            if (!world.getName().startsWith("trial_")) return;

            List<EntityType> types = diff.getMobs();
            if (types.isEmpty()) {
                // 簡單難度不刷怪
                return;
            }
            // 計算世界中目前的目標怪物數量
            long current = world.getEntities().stream()
                    .filter(e -> e instanceof LivingEntity)
                    .filter(e -> diff.getMobs().contains(e.getType()))
                    .count();
            if (current >= MAX_ACTIVE_MOBS) return;

            // 計算本波要刷多少
            int desired = diff == TrialDifficulty.HELL
                    ? rnd.nextInt(5) + 8// 8–12
                    : rnd.nextInt(3) + 4;  // 4–6
            int toSpawn = Math.min(desired, MAX_ACTIVE_MOBS - (int) current);

            List<Player> players = world.getPlayers();
            if (players.isEmpty()) return;

            int perPlayer = Math.max(1, toSpawn / players.size());
            for (Player p : players) {
                spawnAroundPlayer(p, perPlayer);
            }
        }

        private void spawnAroundPlayer(Player p, int count) {
            List<EntityType> types = diff.getMobs();
            if (types.isEmpty()) return;  // 简单难度直接退出
            int spawned = 0, tries = 0, maxTries = count * 5;
            Location base = p.getLocation();

            while (spawned < count && tries++ < maxTries) {
                double angle = Math.toRadians(rnd.nextDouble() * 360);
                double distance = rnd.nextDouble() * 2 + 3;
                Location loc = base.clone().add(
                        Math.cos(angle) * distance,
                        0,
                        Math.sin(angle) * distance
                );

                if (!isSafeSpawnLocation(loc) || isNearVanillaBed(loc)) continue;

                EntityType type = types.get(rnd.nextInt(types.size()));
                world.spawnEntity(loc, type)
                        .setCustomNameVisible(false);
                spawned++;
            }
        }

        private boolean isSafeSpawnLocation(Location loc) {
            Block below = loc.clone().add(0, -1, 0).getBlock();
            if (!below.getType().isSolid()) return false;
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++)
                    for (int dy = 0; dy <= 1; dy++) {
                        if (loc.clone().add(dx, dy, dz).getBlock().getType() != Material.AIR)
                            return false;
                    }
            return true;
        }

        private boolean isNearVanillaBed(Location loc) {
            World w = loc.getWorld();
            for (int dx = -8; dx <= 8; dx++)
                for (int dz = -8; dz <= 8; dz++) {
                    if (dx * dx + dz * dz > 64) continue;
                    Block b = w.getBlockAt(
                            loc.getBlockX() + dx, loc.getBlockY(), loc.getBlockZ() + dz
                    );
                    if (b.getState() instanceof Bed && !playerPlacedBeds.contains(b.getLocation()))
                        return true;
                }
            return false;
        }

        public void stop() {
            if (task != null) task.cancel();
        }
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
                                        (min > 0 ? min + " 分 " : "") + sec + " 秒");
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

            String input = args.length > 0 ? args[0] : "普通";
            TrialDifficulty diff = TrialDifficulty.from(input);
            if (diff == null) {
                player.sendMessage("§c無效難度，請輸入 簡單/普通/地獄");
                return true;
            }

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
            // 設置玩家的試煉世界
            playerTrialWorlds.put(uid, personal);
            // 記錄難度並啟動刷怪任務
            playerDifficulties.put(uid, diff);
            WorldMobSpawnerTask spawner = new WorldMobSpawnerTask(personal, diff);
            spawnerTasks.put(uid, spawner);
            spawner.start();

            String name = personal.getName();
            worldKillCounts.put(name, 0);
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
                @Override
                public void run() {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                            String.format("execute in %s run place structure minecraft:trial_chambers %d %d %d",
                                    personal.getName(), x, y, z));
                    getLogger().info("Structure placed at " + x + "," + y + "," + z);
                }
            }.runTaskLater(VoidTrialChambersPlugin.this, 200L);

            new BukkitRunnable() {
                @Override
                public void run() {
                    removeTrialEffects(player);
                    teleportToNearestBedOrOrigin(player, personal);
                }
            }.runTaskLater(VoidTrialChambersPlugin.this, 240L);

            return true;
        }

        @Override
        public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
            if (args.length == 1) {
                List<String> options = Arrays.asList("地獄", "普通", "簡單");
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
            WorldMobSpawnerTask spawner = spawnerTasks.remove(uid);
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
}