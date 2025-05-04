package org.swim.voidTrialChambersPlugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Bed;
import org.bukkit.block.Block;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;


public class VoidTrialChambersPlugin extends JavaPlugin implements Listener {

    // 每個試煉世界的最大玩家數量
    private static final int MAX_TRIAL_PLAYERS = 4;
    // 每位玩家的試煉世界映射
    final Map<UUID, World> playerTrialWorlds = new ConcurrentHashMap<>();
    // MobSpawner 任務映射
    final Map<UUID, WorldMobSpawnerTask> spawnerTasks = new ConcurrentHashMap<>();
    // 試煉世界冷卻時間
    final Map<UUID, Long> trialCooldowns = new ConcurrentHashMap<>();
    // 冷卻時間的 BossBar
    final Map<UUID, BossBar> cooldownBars = new ConcurrentHashMap<>();
    // 冷卻時間的任務
    final Map<UUID, BukkitTask> cooldownTasks = new ConcurrentHashMap<>();
    // 玩家放置的床
    final Set<Location> playerPlacedBeds = new HashSet<>();
    // 新增：世界最後訪問時間
    final Map<String, Long> worldLastAccessed = new ConcurrentHashMap<>();
    // 原始位置備份
    final Map<UUID, Location> originalLocations = new ConcurrentHashMap<>();
    // 玩家難度映射
    final Map<UUID, TrialDifficulty> playerDifficulties = new ConcurrentHashMap<>();
    //每個試煉世界當前的擊殺數
    final Map<String, Integer> worldKillCounts = new ConcurrentHashMap<>();
    // 每個試煉世界的開始時間（毫秒）
    private final Map<String, Long> worldStartTimes = new ConcurrentHashMap<>();
    // 目前正在進行的試煉會話
    private final Map<String, TrialSession> activeTrialSessions = new ConcurrentHashMap<>();
    // 排除處理的世界名單
    List<String> excludedWorldNames;
    CleanUpManager cleanUpManager;
    private LeaderboardManager leaderboardManager;

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        MonsterCleaner.startCleaningTask(this);
        trailtp trailTeamCmd = new trailtp(this);
        saveDefaultConfig();
        reloadConfig();
        leaderboardManager = new LeaderboardManager(this);
        TrialChambersCommand trialCmd = new TrialChambersCommand(this);
        LeaderboardCommand lbCmd = new LeaderboardCommand(leaderboardManager);
        getServer().getPluginManager().registerEvents(new WardenTargetFilter(), this);
        Bukkit.getPluginManager().registerEvents(new SignPlaceListener(), this);
        Bukkit.getPluginManager().registerEvents(new BedProtectionListener(), this);
        excludedWorldNames = getConfig().getStringList("excluded_worlds");
        Objects.requireNonNull(getCommand("trialchambers")).setExecutor(new TrialChambersCommand(this));
        Objects.requireNonNull(getCommand("exittrial")).setExecutor(new ExitTrialCommand(this));
        Objects.requireNonNull(getCommand("trailteam")).setExecutor(trailTeamCmd);
        Objects.requireNonNull(getCommand("trailteam")).setTabCompleter(trailTeamCmd);
        Objects.requireNonNull(getCommand("trialleaderboard")).setExecutor(lbCmd);
        Objects.requireNonNull(getCommand("trialleaderboard")).setTabCompleter(lbCmd);
        // 初始化并调度定期清理任务（每 5 分钟一次）
        this.cleanUpManager = new CleanUpManager(this);
        cleanUpManager.schedulePeriodicCleanup();
        getLogger().info("記憶體清理任務已啟用");
        getLogger().info("Void Trial Chambers Plugin 已啟用");
    }

    @Override
    public void onDisable() {
        // 1. 停止所有刷怪任務
        spawnerTasks.values().forEach(WorldMobSpawnerTask::stop);

        // 2. 踢出並清理所有試煉世界
        for (World world : new ArrayList<>(playerTrialWorlds.values())) {
            // 踢出玩家
            for (Player p : world.getPlayers()) {
                Component msg = Component.text("§c伺服器/插件正在關閉，您已被踢出試煉世界");
                p.kick(msg);
            }
            // 卸載世界並清空 entities/poi/region 資料夾
            cleanUpManager.clearEntityAndPoiFolders(world);
        }

        // 3. 清空所有運行時記錄
        playerPlacedBeds.clear();
        worldLastAccessed.clear();
        originalLocations.clear();
        playerDifficulties.clear();
        trialCooldowns.clear();

        // 4. 取消並移除所有冷卻進度條與任務
        cooldownBars.values().forEach(BossBar::removeAll);
        cooldownBars.clear();
        cooldownTasks.values().forEach(BukkitTask::cancel);
        cooldownTasks.clear();

        getLogger().info("Void Trial Chambers Plugin 已停用");
    }

    // 建立個人試煉世界並確保 data 資料夾及空檔案存在
    World createPersonalTrialWorld(UUID uuid) {
        String worldName = "trial_" + uuid;
        World existingWorld = Bukkit.getWorld(worldName);
        if (existingWorld != null) {
            cleanUpManager.clearEntityAndPoiFolders(existingWorld);
        }

        // 使用 CompletableFuture 非同步創建世界
        WorldCreator creator = new WorldCreator(worldName)
                .environment(World.Environment.NORMAL)
                .generator(new VoidChunkGenerator());

        // 創建世界
        World world = creator.createWorld();

        if (world != null) {
            // 預先建立必要的資料夾及檔案
            prepareWorldDataFolders(world);
            cleanUpManager.updateWorldAccess(world);
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
        cleanUpManager.updateWorldAccess(to);

        // 如果離開的是試煉世界，檢查是否還有其他玩家
        if (from.getName().startsWith("trial_") && from.getPlayers().isEmpty()) {
            // 標記最後訪問時間
            cleanUpManager.updateWorldAccess(from);

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
                cleanUpManager.stopAndCleanPlayerResources(ownerUUID);
            }

            // 清理和卸載世界
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
     * 根據世界名稱取得對應的玩家 UUID
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
        // 只在 trial_ 开头的世界里记录
        if (!world.getName().startsWith("trial_")) return;

        Block block = event.getBlockPlaced();
        if (block.getState() instanceof Bed) {
            playerPlacedBeds.add(block.getLocation());
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

        UUID owner = findWorldOwner(name);
        if (owner == null) return;
        TrialDifficulty diff = playerDifficulties.get(owner);
        if (diff != TrialDifficulty.HELL && diff != TrialDifficulty.JUDGMENT) return;

        String diffNameZh = diff == TrialDifficulty.HELL ? "地獄" : "吞夢噬念";

        EntityDamageEvent last = evt.getEntity().getLastDamageCause();
        if (!(last instanceof EntityDamageByEntityEvent dbe)) return;
        Entity damager = dbe.getDamager();
        if (!(damager instanceof Player player)) return;

        // 累計擊殺
        int count = worldKillCounts.getOrDefault(name, 0) + 1;
        worldKillCounts.put(name, count);

        // 更新試煉 session
        TrialSession session = activeTrialSessions.computeIfAbsent(name, k -> new TrialSession(name));
        session.addParticipant(player);
        session.recordKill(player.getUniqueId());

        // 1. 動態設定提醒上限：Judgment 到 450，Hell 到 250
        int reminderMax = diff == TrialDifficulty.JUDGMENT ? 450 : 250;
        if (count % 50 == 0 && count <= reminderMax) {
            String msg = "§e本試煉（" + diffNameZh + "難度）已擊殺 " + count + " 隻怪物！";
            for (Player p : world.getPlayers()) {
                p.sendMessage(Component.text(msg));
            }
        }

        // 第一次擊殺，記錄開始時間
        if (count == 1) {
            worldStartTimes.put(name, System.currentTimeMillis());
        }

        // 2. 動態設定結束目標：Judgment 要 500，Hell 要 300
        int finishCount = diff == TrialDifficulty.JUDGMENT ? 500 : 300;
        if (count >= finishCount) {
            long start = worldStartTimes.getOrDefault(name, System.currentTimeMillis());
            long elapsedMs = System.currentTimeMillis() - start;
            long totalSeconds = elapsedMs / 1000;
            long minutes = totalSeconds / 60;
            long seconds = totalSeconds % 60;
            String timeStr = (minutes > 0
                    ? minutes + " 分 " + seconds + " 秒"
                    : seconds + " 秒");

            // 停止刷怪，清場
            WorldMobSpawnerTask spawner = spawnerTasks.remove(owner);
            if (spawner != null) spawner.stop();
            world.getEntities().stream()
                    .filter(e -> e instanceof Monster)
                    .forEach(Entity::remove);

            // 更新並存檔排行榜
            TrialSession currentSession = activeTrialSessions.get(name);
            if (currentSession != null) {
                currentSession.markCompleted();
                boolean solo = currentSession.isSolo();

                // 1. 組時間榜條目並更新
                LeaderboardManager.TimeLeaderboardEntry timeEntry =
                        new LeaderboardManager.TimeLeaderboardEntry(
                                name, currentSession.getParticipantNames(), elapsedMs
                        );
                leaderboardManager.updateTimeLeaderboard(diff.name(), timeEntry, solo);

                // 2. 組擊殺榜條目並更新
                LeaderboardManager.KillsLeaderboardEntry killsEntry =
                        new LeaderboardManager.KillsLeaderboardEntry(
                                name, currentSession.getPlayerKillRecords()
                        );
                leaderboardManager.updateKillsLeaderboard(diff.name(), killsEntry);

                // 3. 最後呼叫一次儲存
                leaderboardManager.saveLeaderboards();
            }

            // 廣播完成訊息（動態顯示 finishCount）
            String finishMsg = String.format(
                    "§6【試煉完成】恭喜，在%s難度的試煉副本已擊殺 %d 隻怪物！ 耗時：%s\n" +
                            "§a排行榜已更新，可使用 /trialleaderboard solo/team/kills 查看",
                    diffNameZh, finishCount, timeStr
            );
            world.getPlayers().forEach(p -> p.sendMessage(finishMsg));

            // 重置狀態
            worldKillCounts.remove(name);
            worldStartTimes.remove(name);
            activeTrialSessions.remove(name);
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
                case JUDGMENT ->
                        List.of(EntityType.ZOMBIE, EntityType.ENDERMAN, EntityType.WITCH, EntityType.BOGGED, EntityType.STRAY, EntityType.CAVE_SPIDER, EntityType.ILLUSIONER, EntityType.PIGLIN_BRUTE, EntityType.BREEZE);
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

    public static class TrialSession {
        private final Map<UUID, String> participants;
        private final Map<UUID, Integer> playerKills;
        private boolean completed;

        public TrialSession(String worldName) {
            long startTime = System.currentTimeMillis();
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

        public boolean isSolo() {
            return participants.size() == 1;
        }

        public List<String> getParticipantNames() {
            return new ArrayList<>(participants.values());
        }

        // ── 回傳 LeaderboardManager.PlayerKillRecord 列表 ──
        public List<LeaderboardManager.PlayerKillRecord> getPlayerKillRecords() {
            List<LeaderboardManager.PlayerKillRecord> records = new ArrayList<>();
            for (Map.Entry<UUID, Integer> entry : playerKills.entrySet()) {
                String playerName = participants.getOrDefault(entry.getKey(), "Unknown");
                records.add(new LeaderboardManager.PlayerKillRecord(playerName, entry.getValue()));
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

    public class WorldMobSpawnerTask {
        private static final int MAX_ACTIVE_MOBS = 300;
        private final World world;
        private final TrialDifficulty diff;
        private final Random rnd = new Random();
        private BukkitTask task;

        // Counter for waves, used for JUDGMENT difficulty effects
        private int waveCount = 0;

        public WorldMobSpawnerTask(World world, TrialDifficulty diff) {
            this.world = world;
            this.diff = diff;
        }

        public void start() {
            // Execute immediately, then every [3~5] seconds
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
                // Easy difficulty: no mobs
                return;
            }

            // Track wave count for JUDGMENT difficulty
            waveCount++;
            if (diff == TrialDifficulty.JUDGMENT && waveCount % 20 == 0) {
                applyNegativeEffectsToSurvival();
                spawnJudgmentBoss();
            }

            // Count current mobs of configured types
            long current = world.getEntities().stream()
                    .filter(e -> e instanceof LivingEntity)
                    .filter(e -> types.contains(e.getType()))
                    .count();
            if (current >= MAX_ACTIVE_MOBS) return;

            // Determine how many to spawn this wave
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

        private void applyNegativeEffectsToSurvival() {
            // Duration in ticks (e.g., 200 ticks = 10 seconds)
            int duration = 200;
            // Amplifier levels (0 = level I)
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

        /**
         * Spawns a Warden near every survival-mode player on Judgment difficulty every 10th wave.
         */
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

        private void spawnAroundPlayer(Player p, int count) {
            List<EntityType> types = diff.getMobs();
            if (types.isEmpty()) return;  // Easy difficulty: skip
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
                // JUDGMENT 难度下附加：力量 III 与 抗性 II
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
                    //最大生命值提升 +10 颗心（+20 HP）
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

        public void stop() {
            if (task != null) task.cancel();
        }
    }
}