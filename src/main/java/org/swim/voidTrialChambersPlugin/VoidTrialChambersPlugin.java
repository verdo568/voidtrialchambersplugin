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
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BlockVector;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public class VoidTrialChambersPlugin extends JavaPlugin implements Listener {

    private World trialWorld;
    private final Map<UUID, Location> originalLocations = new HashMap<>();
    private final Map<UUID, Location> deathLocations = new HashMap<>();
    private final List<BlockVector> placedChambers = new ArrayList<>();
    private static final int MIN_DISTANCE = 300;
    private static final int MIN_DIST_SQ = MIN_DISTANCE * MIN_DISTANCE;

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        saveDefaultConfig();
        reloadConfig();

        // 讀取已放置結構的清單
        List<String> coords = getConfig().getStringList("trial_chambers.placed");
        for (String s : coords) {
            String[] parts = s.split(",");
            if (parts.length == 3) {
                try {
                    int x = Integer.parseInt(parts[0]);
                    int y = Integer.parseInt(parts[1]);
                    int z = Integer.parseInt(parts[2]);
                    placedChambers.add(new BlockVector(x, y, z));
                } catch (NumberFormatException ignored) {}
            }
        }

        if (getCommand("trialchambers") != null) {
            Objects.requireNonNull(getCommand("trialchambers")).setExecutor(new TrialChambersCommand());
        } else {
            getLogger().severe("無法註冊指令 'trialchambers'，請檢查 plugin.yml 設定！");
        }
        createVoidWorld();
        getLogger().info("Void Trial Chambers Plugin 已啟用");
    }

    @Override
    public void onDisable() {
        // 停用時將清單寫回 config
        List<String> coords = new ArrayList<>();
        for (BlockVector vec : placedChambers) {
            coords.add(vec.getBlockX() + "," + vec.getBlockY() + "," + vec.getBlockZ());
        }
        getConfig().set("trial_chambers.placed", coords);
        saveConfig();
        getLogger().info("Saved " + coords.size() + " trial chamber locations.");
        getLogger().info("Void Trial Chambers Plugin 已停用");
    }

    private void createVoidWorld() {
        String name = getConfig().getString("trial_world_name", "trial_chambers_void");
        trialWorld = Bukkit.getWorld(name);
        if (trialWorld == null) {
            WorldCreator wc = new WorldCreator(name);
            wc.environment(Environment.NORMAL)
                    .generator(new VoidChunkGenerator());
            trialWorld = wc.createWorld();
            if (trialWorld != null) {
                trialWorld.setSpawnLocation(0, 64, 0);
                getLogger().info("Void world created: " + name);
                createSpawnPlatform();
                // —— 在这里设置 keepInventory ——
                trialWorld.setGameRule(GameRule.KEEP_INVENTORY, true);
                getLogger().info("已為世界 “" + name + "” 啟用 keepInventory");
            } else {
                getLogger().severe("創建虛空世界失敗: " + name);
            }
        }
    }

    private void createSpawnPlatform() {
        Location spawn = trialWorld.getSpawnLocation();
        int baseY = spawn.getBlockY() - 1;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                trialWorld.getBlockAt(spawn.getBlockX() + dx, baseY, spawn.getBlockZ() + dz)
                        .setType(Material.BEDROCK);
            }
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent evt) {
        Player p = evt.getEntity();
        if (p.getWorld().equals(trialWorld)) {
            deathLocations.put(p.getUniqueId(), p.getLocation());
        }
    }

    @EventHandler
    public void onPlayerRespawnToMainSpawn(PlayerRespawnEvent evt) {
        // 取得預設主世界（名稱通常是 "world"，如有不同請改成你的主世界名稱）
        World mainWorld = Bukkit.getWorld("world");
        if (mainWorld == null) {
            // 如果找不到就 fallback 回玩家原本的重生點
            return;
        }
        // 設定重生位置
        Location mainSpawn = mainWorld.getSpawnLocation();
        evt.setRespawnLocation(mainSpawn);
        evt.getPlayer().sendMessage("§6你已重生於主世界重生點！");
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player p)) return;
        if (event.getCause() == EntityDamageEvent.DamageCause.VOID
                && p.hasPotionEffect(PotionEffectType.BLINDNESS)
                && p.getWorld().equals(trialWorld)) {
            event.setCancelled(true);
        }
    }

    @Override
    public ChunkGenerator getDefaultWorldGenerator(@NotNull String worldName, String id) {
        return new VoidChunkGenerator();
    }

    public static class VoidChunkGenerator extends ChunkGenerator {
        @Override public void generateNoise(@NotNull WorldInfo info,@NotNull Random rand,int chunkX,int chunkZ,@NotNull ChunkData data) {}
        @Override public void generateSurface(@NotNull WorldInfo info,@NotNull Random rand,int chunkX,int chunkZ,@NotNull ChunkData data) {}
        @Override public void generateCaves(@NotNull WorldInfo info,@NotNull Random rand,int chunkX,int chunkZ,@NotNull ChunkData data) {}
    }

    private void teleportNearStructure(Player p, int x, int y, int z) {
        Location target = new Location(trialWorld, x + 0.5, y + 1.0, z + 0.5);
        p.teleport(target);
    }

    private class TrialChambersCommand implements CommandExecutor {

        @Override
        public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd,
                                 @NotNull String label, @NotNull String @NotNull [] args) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§c只有玩家可以使用此指令");
                return true;
            }
            originalLocations.put(player.getUniqueId(), player.getLocation());
            player.sendMessage("§6即將傳送至生成點並隱藏視野...");

            int range = getConfig().getInt("trial_range", 500);
            Random rnd = new Random();
            int x, y, z;

            // Y 座標
            y = getConfig().getInt("trial_chamber.y", 50);

            // 保證與已放置結構至少 MIN_DISTANCE
            do {
                x = rnd.nextInt(range * 2 + 1) - range;
                z = rnd.nextInt(range * 2 + 1) - range;
            } while (!isFarFromExisting(x, y, z));

            // 記錄並持久化
            placedChambers.add(new BlockVector(x, y, z));
            savePlacedChambersConfig();

            // 更新 config 中的最新座標
            getConfig().set("trial_chamber.x", x);
            getConfig().set("trial_chamber.z", z);
            saveConfig();

            // 盲化與傳送
            Location center = new Location(trialWorld, x + 0.5, y + 5, z + 0.5);
            player.teleport(center);
            player.addPotionEffect(new org.bukkit.potion.PotionEffect(
                    PotionEffectType.BLINDNESS, 400, 99, false, false, false));
            player.addPotionEffect(new org.bukkit.potion.PotionEffect(
                    PotionEffectType.RESISTANCE, 380, 99, false, false, false));
            player.addPotionEffect(new org.bukkit.potion.PotionEffect(
                    PotionEffectType.SLOW_FALLING, 300, 2, false, false, false));
            player.addPotionEffect(new org.bukkit.potion.PotionEffect(
                    PotionEffectType.SLOWNESS, 300, 99, false, false, false));
            player.addPotionEffect(new org.bukkit.potion.PotionEffect(
                    PotionEffectType.MINING_FATIGUE, 300, 99, false, false, false));

            int finalZ = z;
            int finalX1 = x;
            Bukkit.getScheduler().runTaskLater(VoidTrialChambersPlugin.this, () -> placeStructure(finalX1, y, finalZ), 200L);
            int finalX = x;
            int finalZ1 = z;
            Bukkit.getScheduler().runTaskLater(VoidTrialChambersPlugin.this, () -> {
                player.removePotionEffect(PotionEffectType.BLINDNESS);
                player.removePotionEffect(PotionEffectType.SLOW_FALLING);
                player.removePotionEffect(PotionEffectType.SLOWNESS);
                player.removePotionEffect(PotionEffectType.MINING_FATIGUE);
                teleportNearStructure(player, finalX, y, finalZ1);
                teleportToNearestBed(player);
            }, 240L);

            return true;
        }

        private boolean isFarFromExisting(int x, int y, int z) {
            for (BlockVector vec : placedChambers) {
                long dx = vec.getBlockX() - x;
                long dz = vec.getBlockZ() - z;
                if (dx*dx + dz*dz < MIN_DIST_SQ) {
                    return false;
                }
            }
            return true;
        }

        private void savePlacedChambersConfig() {
            List<String> coords = new ArrayList<>();
            for (BlockVector vec : placedChambers) {
                coords.add(vec.getBlockX() + "," + vec.getBlockY() + "," + vec.getBlockZ());
            }
            getConfig().set("trial_chambers.placed", coords);
            saveConfig();
        }

        private void placeStructure(int x, int y, int z) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    String.format("execute in %s run place structure minecraft:trial_chambers %d %d %d",
                            trialWorld.getName(), x, y, z));
            getLogger().info("試煉之間已生成 at " + x + "," + y + "," + z);
        }

        private void teleportToNearestBed(Player p) {
            Location playerLoc = p.getLocation();
            World world = p.getWorld();
            int radius = 150;
            double nearestDistSq = Double.MAX_VALUE;
            Location nearestBedLoc = null;

            int px = playerLoc.getBlockX();
            int py = playerLoc.getBlockY();
            int pz = playerLoc.getBlockZ();
            int minX = px - radius, maxX = px + radius;
            int minY = world.getMinHeight();
            int maxY = Math.min(world.getMaxHeight(), py + radius);
            int minZ = pz - radius, maxZ = pz + radius;

            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    for (int y = minY; y <= maxY; y++) {
                        Block block = world.getBlockAt(x, y, z);
                        if (block.getState() instanceof Bed) {
                            double distSq = block.getLocation().distanceSquared(playerLoc);
                            if (distSq < nearestDistSq) {
                                nearestDistSq = distSq;
                                nearestBedLoc = block.getLocation().add(0.5, 1.0, 0.5);
                            }
                        }
                    }
                }
            }

            if (nearestBedLoc != null) {
                p.teleport(nearestBedLoc);
                p.sendMessage("§6已傳送至最近的床鋪");
            } else {
                Location orig = originalLocations.remove(p.getUniqueId());
                if (orig != null) {
                    p.teleport(orig);
                    p.sendMessage("§6附近未找到床鋪，已傳送回原始位置");
                } else {
                    Location spawn = p.getWorld().getSpawnLocation();
                    p.teleport(spawn);
                    p.sendMessage("§c附近未找到床鋪，且無原始位置，已傳送至世界重生點");
                }
            }
        }
    }
}
