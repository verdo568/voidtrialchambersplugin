package org.swim.voidTrialChambersPlugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Bed;
import org.bukkit.block.Block;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;


public class VoidTrialChambersPlugin extends JavaPlugin implements Listener {
    // ===== 常量 =====
    private static final int MAX_TRIAL_PLAYERS = 4; // 每個試煉世界允許的最大玩家人數

    // ===== 核心映射 =====
    final Map<UUID, World> playerTrialWorlds = new ConcurrentHashMap<>();// 每位玩家對應的試煉世界映射
    final Map<UUID, WorldMobSpawnerTask> spawnerTasks = new ConcurrentHashMap<>();// 玩家對應的刷怪任務映射
    final Map<UUID, Long> trialCooldowns = new ConcurrentHashMap<>();// 玩家試煉冷卻時間映射
    final Map<UUID, BossBar> cooldownBars = new ConcurrentHashMap<>();// 玩家冷卻進度條映射
    final Map<UUID, BukkitTask> cooldownTasks = new ConcurrentHashMap<>();// 玩家冷卻計時任務映射
    final Map<UUID, TrialDifficulty> playerDifficulties = new ConcurrentHashMap<>();// 玩家所選試煉難度映射
    final Map<UUID, Location> originalLocations = new ConcurrentHashMap<>();// 玩家原始位置備份映射
    final Map<String, Long> worldLastAccessed = new ConcurrentHashMap<>();// 世界最後訪問時間映射
    final Map<String, Integer> worldKillCounts = new ConcurrentHashMap<>();// 每個試煉世界的擊殺數映射
    final Map<String, Long> worldStartTimes = new ConcurrentHashMap<>();// 每個試煉世界的開始時間（毫秒）映射
    final Map<String, TrialSession> activeTrialSessions = new ConcurrentHashMap<>();// 當前所有活躍試煉會話映射
    final Map<UUID, UserDailyRecord> dailyRecords = new ConcurrentHashMap<>();// 玩家每日獎勵紀錄映射

    // ===== 其他集合 =====
    final Set<Location> playerPlacedBeds = new HashSet<>();// 玩家在試煉世界中放置的床位置集合
    List<String> excludedWorldNames;
    CleanUpManager cleanUpManager;
    LeaderboardManager leaderboardManager;
    private ChestRewardManager chestRewardManager;
    private File dailyRecordFile;
    private YamlConfiguration dailyRecordConfig;

    @Override
    public void onEnable() {
        // 註冊事件監聽
        Bukkit.getPluginManager().registerEvents(this, this);
        // 啟動怪物定期清理任務
        MonsterCleaner.startCleaningTask(this);
        // 設定指令與 Tab 補全
        trailtp trailTeamCmd = new trailtp(this);
        leaderboardManager = new LeaderboardManager(this);
        TrialChambersCommand trialCmd = new TrialChambersCommand(this);
        this.chestRewardManager = new ChestRewardManager(this);
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        // 載入每日紀錄檔
        dailyRecordFile = new File(getDataFolder(), "daily_records.yml");
        dailyRecordConfig = YamlConfiguration.loadConfiguration(dailyRecordFile);
        loadUserDailyRecords();
        LeaderboardCommand lbCmd = new LeaderboardCommand(leaderboardManager);
        getServer().getPluginManager().registerEvents(new WardenTargetFilter(), this);
        Bukkit.getPluginManager().registerEvents(new SignPlaceListener(), this);
        Bukkit.getPluginManager().registerEvents(new BedProtectionListener(), this);
        Bukkit.getPluginManager().registerEvents(new EntityDeathListener(this), this);
        excludedWorldNames = getConfig().getStringList("excluded_worlds");
        Objects.requireNonNull(getCommand("trialchambers")).setExecutor(new TrialChambersCommand(this));
        Objects.requireNonNull(getCommand("exittrial")).setExecutor(new ExitTrialCommand(this));
        Objects.requireNonNull(getCommand("trailteam")).setExecutor(trailTeamCmd);
        Objects.requireNonNull(getCommand("trailteam")).setTabCompleter(trailTeamCmd);
        Objects.requireNonNull(getCommand("trialleaderboard")).setExecutor(lbCmd);
        Objects.requireNonNull(getCommand("trialleaderboard")).setTabCompleter(lbCmd);
        // 初始化並排程定期清理任務（每 5 分鐘執行一次）
        this.cleanUpManager = new CleanUpManager(this);
        cleanUpManager.schedulePeriodicCleanup();
        getLogger().info("Void Trial Chambers Plugin 已啟用");
    }

    @Override
    public void onDisable() {
        // 停止所有刷怪任務
        spawnerTasks.values().forEach(WorldMobSpawnerTask::stop);
        // 將試煉世界中的玩家踢出並清理世界
        for (World world : new ArrayList<>(playerTrialWorlds.values())) {
            for (Player p : world.getPlayers()) {
                Component msg = Component.text("§c伺服器/插件正在關閉，您已被踢出試煉世界");
                p.kick(msg);
            }
            // 卸載並清除世界的 entities、poi、region 資料夾
            cleanUpManager.clearEntityAndPoiFolders(world);
        }
        saveUserDailyRecords();
        // 清除所有運行時資料
        playerPlacedBeds.clear();
        worldLastAccessed.clear();
        originalLocations.clear();
        playerDifficulties.clear();
        trialCooldowns.clear();

        // 移除並取消所有冷卻進度條及相關任務
        cooldownBars.values().forEach(BossBar::removeAll);
        cooldownBars.clear();
        cooldownTasks.values().forEach(BukkitTask::cancel);
        cooldownTasks.clear();

        getLogger().info("Void Trial Chambers Plugin 已停用");
    }

    /**
     * 檢查玩家今天是否還能領取。
     */
    public boolean canClaim(UUID playerId) {
        LocalDate today = LocalDate.now(ZoneOffset.ofHours(8));
        UserDailyRecord rec = dailyRecords.get(playerId);
        if (rec == null || !today.equals(rec.getDate())) {
            return true;
        }
        return rec.getCount() < 5;// 每日最多 5 次領取
    }

    /**
     * 記錄一次領取，並立即儲存到檔案。
     */
    public void recordClaim(UUID playerId) {
        LocalDate today = LocalDate.now(ZoneOffset.ofHours(8));
        UserDailyRecord rec = dailyRecords.compute(playerId, (k, v) -> {
            if (v == null || !today.equals(v.getDate())) {
                return new UserDailyRecord(today, 1);
            } else {
                v.setCount(v.getCount() + 1);
                return v;
            }
        });
        // 立刻儲存
        saveUserDailyRecords();
    }

    private void loadUserDailyRecords() {
        if (dailyRecordConfig.contains("records")) {
            for (String key : Objects.requireNonNull(dailyRecordConfig.getConfigurationSection("records")).getKeys(false)) {
                String path = "records." + key;
                String dateStr = dailyRecordConfig.getString(path + ".date");
                int count = dailyRecordConfig.getInt(path + ".count", 0);
                try {
                    LocalDate date = null;
                    if (dateStr != null) {
                        date = LocalDate.parse(dateStr);
                    }
                    dailyRecords.put(UUID.fromString(key), new UserDailyRecord(date, count));
                } catch (Exception e) {
                    getLogger().warning("無法解析每日領取記錄: " + key + " => " + e.getMessage());
                }
            }
        }
    }

    private void saveUserDailyRecords() {
        dailyRecordConfig.set("records", null); // 清空
        for (Map.Entry<UUID, UserDailyRecord> entry : dailyRecords.entrySet()) {
            String key = entry.getKey().toString();
            UserDailyRecord rec = entry.getValue();
            String base = "records." + key;
            dailyRecordConfig.set(base + ".date", rec.getDate().toString());
            dailyRecordConfig.set(base + ".count", rec.getCount());
        }
        try {
            dailyRecordConfig.save(dailyRecordFile);
        } catch (IOException e) {
            getLogger().severe("無法儲存每日領取記錄: " + e.getMessage());
        }
    }

    // ===== 世界創建與準備 =====
    World createPersonalTrialWorld(UUID uuid) {
        String worldName = "trial_" + uuid;
        World existingWorld = Bukkit.getWorld(worldName);
        if (existingWorld != null) {
            // 如果世界已存在，先清理原有資料夾
            cleanUpManager.clearEntityAndPoiFolders(existingWorld);
        }

        // 使用 VoidChunkGenerator 生成虛空地圖
        WorldCreator creator = new WorldCreator(worldName)
                .environment(World.Environment.NORMAL)
                .generator(new VoidChunkGenerator());

        // 建立新世界
        World world = creator.createWorld();

        if (world != null) {
            // 預先建立必要的資料夾與檔案
            prepareWorldDataFolders(world);
            // 更新世界最後訪問時間
            cleanUpManager.updateWorldAccess(world);
            // 設定世界規則
            world.setGameRule(GameRule.KEEP_INVENTORY, true);
            world.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false);
            world.setDifficulty(Difficulty.HARD);
            world.setSpawnLocation(0, 64, 0);
            getLogger().info("Created personal trial world: " + worldName);
        }
        return world;
    }

    /**
     * 提供給 Listener 或其他類別調用的獎勵寶箱管理器存取方法
     */
    public ChestRewardManager getChestRewardManager() {
        return chestRewardManager;
    }

    // 新增玩家進入/離開世界事件處理
    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        World to = player.getWorld();

        // 若進入 trial_ 世界且非 OP，檢查人數是否已達上限
        if (to.getName().startsWith("trial_") && !player.isOp()) {
            long nonOpCount = to.getPlayers().stream()
                    .filter(p -> !p.isOp())
                    .count();
            if (nonOpCount > MAX_TRIAL_PLAYERS) {
                // 人數已滿，將玩家傳回主世界並提示
                World main = Bukkit.getWorld("world");
                if (main != null) {
                    player.sendMessage("§c試煉世界已滿 (最多 " + MAX_TRIAL_PLAYERS + " 位玩家)，已傳送回主世界。");
                    player.teleport(main.getSpawnLocation());
                }
                return;
            }
        }

        // 若進入 trial_ 世界，將玩家加入試煉會話
        if (to.getName().startsWith("trial_")) {
            TrialSession session = activeTrialSessions.computeIfAbsent(to.getName(), k -> new TrialSession(to.getName()));
            session.addParticipant(player);
        }

        World from = event.getFrom();

        // 更新目標世界的最後訪問時間
        cleanUpManager.updateWorldAccess(to);

        // 若離開的是試煉世界且已無其他玩家，停止並清理資源
        if (from.getName().startsWith("trial_") && from.getPlayers().isEmpty()) {
            cleanUpManager.updateWorldAccess(from);
            UUID ownerUUID = null;
            for (Map.Entry<UUID, World> entry : new HashMap<>(playerTrialWorlds).entrySet()) {
                if (entry.getValue().getName().equals(from.getName())) {
                    ownerUUID = entry.getKey();
                    break;
                }
            }
            if (ownerUUID != null) {
                cleanUpManager.stopAndCleanPlayerResources(ownerUUID);
            }
            cleanUpManager.clearEntityAndPoiFolders(from);
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

    /**
     * 根據試煉世界名稱取得擁有者的玩家 UUID
     */
    public UUID getOwnerUUIDByWorldName(String worldName) {
        for (Map.Entry<UUID, World> entry : playerTrialWorlds.entrySet()) {
            if (entry.getValue().getName().equals(worldName)) {
                return entry.getKey();
            }
        }
        return null;
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        World world = event.getBlockPlaced().getWorld();
        // 只在試煉世界中記錄玩家放置的床
        if (!world.getName().startsWith("trial_")) return;
        Block block = event.getBlockPlaced();
        if (block.getState() instanceof Bed) {
            playerPlacedBeds.add(block.getLocation());
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        World world = event.getBlock().getWorld();
        // 只在試煉世界中移除被破壞的床記錄
        if (!world.getName().startsWith("trial_")) return;
        Block block = event.getBlock();
        if (block.getState() instanceof Bed) {
            playerPlacedBeds.remove(block.getLocation());
        }
    }

    @EventHandler
    public void onPortalCreate(PortalCreateEvent event) {
        World world = event.getWorld();
        // 阻止試煉世界內生成任何形式的傳送門
        if (!world.getName().startsWith("trial_")) return;
        event.setCancelled(true);
    }

    // 協助外部 Listener 取得試煉世界擁有者 UUID
    UUID findWorldOwner(String worldName) {
        for (var entry : playerTrialWorlds.entrySet()) {
            if (entry.getValue().getName().equals(worldName)) {
                return entry.getKey();
            }
        }
        return null;
    }

    @Override
    public ChunkGenerator getDefaultWorldGenerator(@NotNull String worldName, String id) {
        // 回傳自訂的 VoidChunkGenerator
        return new VoidChunkGenerator();
    }

    /**
     * 試煉難度列舉，對應顯示名稱與刷怪列表
     */
    public enum TrialDifficulty {
        EASY("簡單"),
        NORMAL("普通"),
        HELL("地獄"),
        JUDGMENT("吞夢噬念");

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
         * 取得中文顯示名稱
         */
        public String getDisplay() {
            return display;
        }

        public List<EntityType> getMobs() {
            return switch (this) {
                case NORMAL -> List.of(EntityType.ZOMBIE, EntityType.SKELETON, EntityType.SPIDER);
                case HELL ->
                        List.of(EntityType.ZOMBIE, EntityType.CREEPER, EntityType.ENDERMAN, EntityType.WITCH, EntityType.BOGGED, EntityType.STRAY, EntityType.PHANTOM, EntityType.CAVE_SPIDER, EntityType.CAVE_SPIDER, EntityType.ILLUSIONER, EntityType.PIGLIN_BRUTE);
                case JUDGMENT ->
                        List.of(EntityType.ZOMBIE, EntityType.ENDERMAN, EntityType.WITCH, EntityType.BOGGED, EntityType.STRAY, EntityType.CAVE_SPIDER, EntityType.ILLUSIONER, EntityType.PIGLIN_BRUTE, EntityType.BREEZE);
                default -> List.of();
            };
        }
    }

    public static class MonsterCleaner {
        // 啟動定期清理任務，每 180 秒移除所有試煉世界中的怪物
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

    public static class TrialSession {
        // 試煉會話：管理參與者與擊殺紀錄
        private final Map<UUID, String> participants;
        private final Map<UUID, Integer> playerKills;
        private boolean completed;

        public TrialSession(String worldName) {
            long startTime = System.currentTimeMillis();
            this.participants = new HashMap<>();
            this.playerKills = new HashMap<>();
            this.completed = false;
        }

        // 將玩家加入此試煉會話
        public void addParticipant(Player player) {
            participants.put(player.getUniqueId(), player.getName());
        }

        // 紀錄玩家擊殺數
        public void recordKill(UUID playerId) {
            playerKills.put(playerId, playerKills.getOrDefault(playerId, 0) + 1);
        }

        // 檢查是否為單人試煉
        public boolean isSolo() {
            return participants.size() == 1;
        }

        // 取得參與者名稱列表
        public List<String> getParticipantNames() {
            return new ArrayList<>(participants.values());
        }

        // 回傳玩家擊殺紀錄列表
        public List<LeaderboardManager.PlayerKillRecord> getPlayerKillRecords() {
            List<LeaderboardManager.PlayerKillRecord> records = new ArrayList<>();
            for (Map.Entry<UUID, Integer> entry : playerKills.entrySet()) {
                String playerName = participants.getOrDefault(entry.getKey(), "Unknown");
                records.add(new LeaderboardManager.PlayerKillRecord(playerName, entry.getValue()));
            }
            return records;
        }

        // 標記試煉已完成
        public void markCompleted() {
            this.completed = true;
        }

        // 檢查試煉是否已完成
        public boolean isCompleted() {
            return completed;
        }

        //把參與者 UUID 轉成 Player 物件，只回傳在線上的玩家
        public List<Player> getParticipants() {
            List<Player> list = new ArrayList<>();
            for (UUID id : participants.keySet()) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    list.add(p);
                }
            }
            return list;
        }
    }

    public static class VoidChunkGenerator extends ChunkGenerator {
        @Override
        public void generateSurface(@NotNull WorldInfo info, @NotNull Random rand, int x, int z, @NotNull ChunkData data) {
        }

        @Override
        public void generateCaves(@NotNull WorldInfo info, @NotNull Random rand, int x, int z, @NotNull ChunkData data) {
        }

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

    public class WorldMobSpawnerTask {
        // 世界刷怪任務，根據難度定期產生生物
        private static final int MAX_ACTIVE_MOBS = 300;
        private final World world;
        private final TrialDifficulty diff;
        private final Random rnd = new Random();
        private BukkitTask task;

        // 波次計數，用於 JUDGMENT 難度特效
        private int waveCount = 0;

        public WorldMobSpawnerTask(World world, TrialDifficulty diff) {
            this.world = world;
            this.diff = diff;
        }

        // 啟動刷怪任務：立即執行，之後每 3~5 秒重複
        public void start() {
            task = new BukkitRunnable() {
                @Override
                public void run() {
                    spawnWave();
                }
            }.runTaskTimer(VoidTrialChambersPlugin.this, 0L, (rnd.nextInt(3) + 3) * 20L);
        }

        // 產生一波怪物
        private void spawnWave() {
            if (!world.getName().startsWith("trial_")) return;

            List<EntityType> types = diff.getMobs();
            if (types.isEmpty()) {
                // 簡單難度不產生怪物
                return;
            }

            // 增加波次計數
            waveCount++;
            if (diff == TrialDifficulty.JUDGMENT && waveCount % 20 == 0) {
                // 每 20 波觸發負面特效並召喚 Boss
                applyNegativeEffectsToSurvival();
                spawnJudgmentBoss();
            }

            // 計算目前世界中指定怪物的數量
            long current = world.getEntities().stream()
                    .filter(e -> e instanceof LivingEntity)
                    .filter(e -> types.contains(e.getType()))
                    .count();
            if (current >= MAX_ACTIVE_MOBS) return;

            // 決定本波需產生多少怪物
            int desired;
            if (diff == TrialDifficulty.HELL) {
                desired = rnd.nextInt(5) + 8; // 8–12
            } else if (diff == TrialDifficulty.JUDGMENT) {
                desired = rnd.nextInt(10) + 15; // 15–24
            } else {
                desired = rnd.nextInt(3) + 4;  // 4–6
            }
            int toSpawn = Math.min(desired, MAX_ACTIVE_MOBS - (int) current);

            List<Player> players = world.getPlayers();
            if (players.isEmpty()) return;

            int perPlayer = Math.max(1, toSpawn / players.size());
            for (Player p : players) {
                if (p.getGameMode() != GameMode.SURVIVAL) continue;
                spawnAroundPlayer(p, perPlayer);
            }
        }

        // 給所有生存模式玩家添加負面特效
        private void applyNegativeEffectsToSurvival() {
            int duration = 200;// 持續時間（刻）約 10 秒
            List<PotionEffect> effects = Arrays.asList(
                    new PotionEffect(PotionEffectType.SLOWNESS, duration, 2),
                    new PotionEffect(PotionEffectType.WEAKNESS, duration, 3),
                    new PotionEffect(PotionEffectType.HUNGER, duration, 2),
                    new PotionEffect(PotionEffectType.DARKNESS, duration, 2),
                    new PotionEffect(PotionEffectType.WITHER, duration, 2),
                    new PotionEffect(PotionEffectType.UNLUCK, duration, 2),
                    new PotionEffect(PotionEffectType.POISON, duration, 2)
            );
            for (Player p : world.getPlayers()) {
                if (p.getGameMode() != GameMode.SURVIVAL) continue;
                if (isNearVanillaBed(p.getLocation())) continue;
                for (PotionEffect effect : effects) {
                    p.addPotionEffect(effect);
                }
            }
        }

        // 每 10 波在生存模式玩家附近召喚兩隻 伏守者 作為 Boss
        private void spawnJudgmentBoss() {
            // 收集所有生存模式的玩家
            List<Player> survivors = world.getPlayers().stream()
                    .filter(p -> p.getGameMode() == GameMode.SURVIVAL)
                    .filter(p -> !isNearVanillaBed(p.getLocation()))
                    .toList();
            if (survivors.isEmpty()) return;
            // 中二風格的 BOSS 登場公告
            String prefix = "§5[吞夢噬念] ";
            String line1 = "§c黑夜低語，夢魘裂縫中蜿蜒……";
            String line2 = "§c伏守者甦醒，將你之靈魂雕刻於深淵！";
            for (Player p : world.getPlayers()) {
                p.sendMessage(prefix + line1);
                p.sendMessage(prefix + line2);
            }

            // 定義兩隻 Warden 的名字
            List<Component> bossNames = List.of(
                    Component.text("吞夢者．噬念獄主").color(NamedTextColor.DARK_RED),
                    Component.text("深淵覺醒者．裂縫裁決").color(NamedTextColor.DARK_PURPLE)
            );

            // 在每個生存玩家上方生成兩隻 Warden，並賦予不同名字
            for (Player target : survivors) {
                Location base = target.getLocation().clone().add(0, 1, 0);
                for (int i = 0; i < bossNames.size(); i++) {
                    // 輕微偏移位置，避免重疊
                    Location spawnLoc = base.clone().add((i - 0.5) * 1.5, 0, 0);
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dy = -1; dy <= 1; dy++) {
                            for (int dz = -1; dz <= 1; dz++) {
                                // 保留腳下中心方塊 (dx=0, dy=-1, dz=0)
                                if (dx == 0 && dy == -1 && dz == 0) continue;
                                Block b = spawnLoc.clone().add(dx, dy, dz).getBlock();
                                if (!b.getType().isAir()) {
                                    b.setType(Material.AIR);
                                }
                            }
                        }
                    }
                    Warden warden = (Warden) world.spawnEntity(spawnLoc, EntityType.WARDEN);
                    warden.customName(bossNames.get(i));
                    warden.setCustomNameVisible(true);
                }
            }
        }

        // 在指定玩家周圍隨機位置生成怪物
        private void spawnAroundPlayer(Player p, int count) {
            List<EntityType> types = diff.getMobs();
            if (types.isEmpty()) return;  // 簡單難度略過
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
                LivingEntity mob = (LivingEntity) world.spawnEntity(loc, type);
                mob.setCustomNameVisible(false);
                // Hell 難度：添加抗性 I
                if (diff == TrialDifficulty.HELL) {
                    mob.addPotionEffect(new PotionEffect(
                            PotionEffectType.RESISTANCE,
                            Integer.MAX_VALUE,
                            0
                    ));
                    mob.addPotionEffect(new PotionEffect(
                            PotionEffectType.STRENGTH,
                            Integer.MAX_VALUE,
                            0
                    ));
                }
                // 吞夢噬念 難度下加入力量 III 與抗性 II，並提升最大生命值
                if (diff == TrialDifficulty.JUDGMENT) {
                    mob.addPotionEffect(new PotionEffect(
                            PotionEffectType.STRENGTH,
                            Integer.MAX_VALUE,
                            3
                    ));
                    mob.addPotionEffect(new PotionEffect(
                            PotionEffectType.RESISTANCE,
                            Integer.MAX_VALUE,
                            2
                    ));
                    //最大生命值提升 +10 颗心
                    AttributeInstance healthAttr = mob.getAttribute(Attribute.MAX_HEALTH);
                    if (healthAttr != null) {
                        double newMax = healthAttr.getBaseValue() + 20.0;
                        healthAttr.setBaseValue(newMax);
                        mob.setHealth(newMax);
                    }
                }
                spawned++;
            }
        }

        // 檢查生成點下方是否為實心方塊，且周圍空間足夠
        private boolean isSafeSpawnLocation(Location loc) {
            Block below = loc.clone().add(0, -1, 0).getBlock();
            if (!below.getType().isSolid()) return false;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dy = 0; dy <= 1; dy++) {
                        if (loc.clone().add(dx, dy, dz).getBlock().getType() != Material.AIR)
                            return false;
                    }
                }
            }
            return true;
        }

        // 檢查位置是否靠近非玩家放置的原版床
        private boolean isNearVanillaBed(Location loc) {
            for (int dx = -8; dx <= 8; dx++) {
                for (int dz = -8; dz <= 8; dz++) {
                    if (dx * dx + dz * dz > 64) continue;
                    Block b = world.getBlockAt(
                            loc.getBlockX() + dx,
                            loc.getBlockY(),
                            loc.getBlockZ() + dz
                    );
                    if (b.getState() instanceof org.bukkit.block.Bed &&
                            !playerPlacedBeds.contains(b.getLocation())) {
                        return true;
                    }
                }
            }
            return false;
        }

        // 停止此刷怪任務
        public void stop() {
            if (task != null) task.cancel();
        }
    }
}