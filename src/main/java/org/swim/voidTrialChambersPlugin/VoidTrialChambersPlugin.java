package org.swim.voidTrialChambersPlugin;

import org.bukkit.*;
import org.bukkit.World.Environment;
import org.bukkit.block.Bed;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.*;

public class VoidTrialChambersPlugin extends JavaPlugin implements Listener {

    // 每位玩家的試煉世界映射
    private final Map<UUID, World> playerTrialWorlds = new HashMap<>();
    // 原始位置備份
    private final Map<UUID, Location> originalLocations = new HashMap<>();
    // 排除處理的世界名單
    private List<String> excludedWorldNames;

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        saveDefaultConfig();
        reloadConfig();
        // 讀取 excluded_worlds
        excludedWorldNames = getConfig().getStringList("excluded_worlds");

        if (getCommand("trialchambers") != null) {
            Objects.requireNonNull(getCommand("trialchambers")).setExecutor(new TrialChambersCommand());
        } else {
            getLogger().severe("無法註冊指令 'trialchambers'");
        }
        if (getCommand("exittrial") != null) {
            Objects.requireNonNull(getCommand("exittrial")).setExecutor(new ExitTrialCommand());
        } else {
            getLogger().severe("無法註冊指令 'exittrial'");
        }

        getLogger().info("Void Trial Chambers Plugin 已啟用");
    }

    @Override
    public void onDisable() {
        // 停用時刪除所有尚未刪除的試煉世界
        for (World w : new ArrayList<>(playerTrialWorlds.values())) {
            deleteWorld(w);
        }
        getLogger().info("Void Trial Chambers Plugin 已停用");
    }

    /**
     * 建立一個只屬於玩家的試煉世界
     */
    private World createPersonalTrialWorld(UUID uuid) {
        String worldName = "trial_" + uuid;
        WorldCreator wc = new WorldCreator(worldName);
        wc.environment(Environment.NORMAL)
                .generator(new VoidChunkGenerator());
        World world = wc.createWorld();
        if (world != null) {
            world.setGameRule(GameRule.KEEP_INVENTORY, true);
            world.setSpawnLocation(0, 64, 0);
            createSpawnPlatform(world);
            getLogger().info("Created personal trial world: " + worldName);
        }
        return world;
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
     * 刪除世界：卸載後延遲遞迴刪除檔案
     */
    private void deleteWorld(World world) {
        if (world == null) return;
        String name = world.getName();
        File folder = world.getWorldFolder();
        Bukkit.unloadWorld(world, false);
        new BukkitRunnable() {
            @Override
            public void run() {
                deleteFolderRecursively(folder);
                getLogger().info("Deleted trial world: " + name);
            }
        }.runTaskLater(this, 40L);
    }

    private void deleteFolderRecursively(File file) {
        if (file.isDirectory()) {
            for (File child : Objects.requireNonNull(file.listFiles())) {
                deleteFolderRecursively(child);
            }
        }
        file.delete();
    }

    /**
     * /trialchambers 指令處理
     */
    private class TrialChambersCommand implements CommandExecutor {
        @Override
        public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd,
                                 @NotNull String label, @NotNull String[] args) {
            if (!(sender instanceof Player player)) {
                return true;
            }
            player.sendMessage("§6正在進入試煉世界，請稍候...");
            UUID uid = player.getUniqueId();
            originalLocations.put(uid, player.getLocation());
            World personal = createPersonalTrialWorld(uid);
            if (personal == null) {
                player.sendMessage("§c無法建立試煉世界，請稍後再試");
                return true;
            }
            playerTrialWorlds.put(uid, personal);

            int y = getConfig().getInt("trial_chamber.y", 50);
            int range = getConfig().getInt("trial_range", 500);
            Random rnd = new Random();
            int x = rnd.nextInt(range * 2 + 1) - range;
            int z = rnd.nextInt(range * 2 + 1) - range;

            Location center = new Location(personal, x + 0.5, y + 5, z + 0.5);
            player.teleport(center);
            applyTrialEffects(player);

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
        }

        private void teleportToNearestBedOrOrigin(Player p, World world) {
            Location loc = p.getLocation();
            Location bedLoc = findNearestBed(loc, world, 150);
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

        private Location findNearestBed(Location center, World world, int radius) {
            double best = Double.MAX_VALUE;
            Location bestLoc = null;
            int cx = center.getBlockX(), cy = center.getBlockY(), cz = center.getBlockZ();
            for (int x = cx - radius; x <= cx + radius; x++) {
                for (int z = cz - radius; z <= cz + radius; z++) {
                    for (int y = world.getMinHeight(); y <= Math.min(world.getMaxHeight(), cy + radius); y++) {
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

    /**
     * 斷線事件：離開時刪除個人世界
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent evt) {
        UUID uid = evt.getPlayer().getUniqueId();
        World w = playerTrialWorlds.remove(uid);
        deleteWorld(w);
    }

    /**
     * /exittrial 指令
     */
    private class ExitTrialCommand implements CommandExecutor {
        @Override
        public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd,
                                 @NotNull String label, @NotNull String[] args) {
            if (!(sender instanceof Player p)) {
                return true;
            }
            // 排除世界不處理
            String currentWorld = p.getWorld().getName();
            if (excludedWorldNames.contains(currentWorld)) {
                return true;
            }
            // 清除效果與回到主世界
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
            World w = playerTrialWorlds.remove(uid);
            deleteWorld(w);
            return true;
        }
    }

    /**
     * 重生事件：若在排除世界，不進行任何處理
     */
    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent evt) {
        Player p = evt.getPlayer();
        String worldName = p.getWorld().getName();
        // 如果是在排除列表，跳過所有處理
        if (excludedWorldNames.contains(worldName)) {
            return;
        }
        // 否則送回主世界重生點
        World main = Bukkit.getWorld("world");
        if (main != null) {
            evt.setRespawnLocation(main.getSpawnLocation());
            p.sendMessage("§6你已重生於主世界重生點！");
            UUID uid = p.getUniqueId();
            World w = playerTrialWorlds.remove(uid);
            deleteWorld(w);
        }
    }

    @Override
    public ChunkGenerator getDefaultWorldGenerator(@NotNull String worldName, String id) {
        return new VoidChunkGenerator();
    }

    public static class VoidChunkGenerator extends ChunkGenerator {
        @Override public void generateNoise(@NotNull WorldInfo info, @NotNull Random rand, int x, int z, @NotNull ChunkData data) {}
        @Override public void generateSurface(@NotNull WorldInfo info, @NotNull Random rand, int x, int z, @NotNull ChunkData data) {}
        @Override public void generateCaves(@NotNull WorldInfo info, @NotNull Random rand, int x, int z, @NotNull ChunkData data) {}
    }
}
