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
import org.jetbrains.annotations.NotNull;

import java.util.*;

public class VoidTrialChambersPlugin extends JavaPlugin implements Listener {

    private World trialWorld;
    private final Map<UUID, Location> originalLocations = new HashMap<>();
    private final Map<UUID, Location> deathLocations = new HashMap<>();

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        saveDefaultConfig();
        reloadConfig();
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
    public void onPlayerRespawn(PlayerRespawnEvent evt) {
        Player p = evt.getPlayer();
        UUID id = p.getUniqueId();
        if (deathLocations.containsKey(id)) {
            Location orig = originalLocations.remove(id);
            deathLocations.remove(id);
            if (orig != null) {
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    p.teleport(orig);
                    p.sendMessage("§6你已返回原本世界位置！");
                }, 5L);
            }
        }
    }
    /**
     * 免疫虛空傷害：當玩家擁有失明效果（試煉倒數期間）時，取消來自虛空的傷害
     */
    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        Player p = (Player) event.getEntity();
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
        @Override public void generateNoise(@NotNull WorldInfo info, @NotNull Random rand, int chunkX, int chunkZ, @NotNull ChunkData data) {}
        @Override public void generateSurface(@NotNull WorldInfo info, @NotNull Random rand, int chunkX, int chunkZ, @NotNull ChunkData data) {}
        @Override public void generateCaves(@NotNull WorldInfo info, @NotNull Random rand, int chunkX, int chunkZ, @NotNull ChunkData data) {}
    }
    /**
     * 傳送玩家到試煉結構的指定座標附近
     */
    private void teleportNearStructure(Player p, int x, int y, int z) {
        // 0.5 讓玩家對齊方塊中心，+1 則是站在結構上方
        Location target = new Location(trialWorld, x + 0.5, y + 1.0, z + 0.5);
        p.teleport(target);
    }

    private class TrialChambersCommand implements CommandExecutor {
        @Override
        public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd,
                                 @NotNull String label, @NotNull String[] args) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§c只有玩家可以使用此指令");
                return true;
            }
            Player player = (Player) sender;
            originalLocations.put(player.getUniqueId(), player.getLocation());
            player.sendMessage("§6即將傳送至生成點並隱藏視野...");

            int range = getConfig().getInt("trial_range", 500);
            Random rnd = new Random();
            int x = rnd.nextInt(range * 2 + 1) - range;
            int z = rnd.nextInt(range * 2 + 1) - range;
            int y = getConfig().getInt("trial_chamber.y", 50);

            getConfig().set("trial_chamber.x", x);
            getConfig().set("trial_chamber.y", y);
            getConfig().set("trial_chamber.z", z);
            saveConfig();

            Location center = new Location(trialWorld, x + 0.5, y + 5, z + 0.5);
            player.teleport(center);// 先傳送到試煉中心
            player.addPotionEffect(new org.bukkit.potion.PotionEffect(
                    org.bukkit.potion.PotionEffectType.BLINDNESS, 400, 99, false, false, false));// 隱藏視野
            player.addPotionEffect(new org.bukkit.potion.PotionEffect(
                    org.bukkit.potion.PotionEffectType.RESISTANCE, 300, 99, false, false, false));// 無敵
            player.addPotionEffect(new org.bukkit.potion.PotionEffect(
                    org.bukkit.potion.PotionEffectType.SLOW_FALLING, 300, 2, false, false, false));// 減速墜落

            Bukkit.getScheduler().runTaskLater(VoidTrialChambersPlugin.this, () -> placeStructure(x, y, z), 200L);// 延遲生成試煉結構
            Bukkit.getScheduler().runTaskLater(VoidTrialChambersPlugin.this, () -> {
                player.removePotionEffect(org.bukkit.potion.PotionEffectType.BLINDNESS);
                player.removePotionEffect(org.bukkit.potion.PotionEffectType.SLOW_FALLING);
                teleportNearStructure(player, x, y, z);
                teleportToNearestBed(player);
            }, 240L);

            return true;
        }

        private void placeStructure(int x, int y, int z) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    String.format("execute in %s run place structure minecraft:trial_chambers %d %d %d",
                            trialWorld.getName(), x, y, z));
            getLogger().info("試煉之間已生成 at " + x + "," + y + "," + z);
        }

        /**
         * 在玩家附近 100 格範圍內搜尋最近的床鋪區塊，若找到則傳送至床上方，否則使用原有邏輯
         */
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
                // fallback to original or world spawn
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