package org.swim.voidTrialChambersPlugin;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Bed;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.geysermc.floodgate.api.FloodgateApi;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public class TrialChambersCommand implements CommandExecutor, TabCompleter {
    private static final long COOLDOWN_MS = 150_000L; // 2.5 分鐘
    private final VoidTrialChambersPlugin plugin;

    public TrialChambersCommand(VoidTrialChambersPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command cmd,
                             @NotNull String label,
                             @NotNull String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            return true;
        }
        UUID uid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long last = plugin.trialCooldowns.getOrDefault(uid, 0L);
        long elapsed = now - last;
        List<String> argList = new ArrayList<>(Arrays.asList(args));
        boolean bypassCooldown = player.isOp() && argList.remove("skip");
        boolean isBedrock = FloodgateApi.getInstance().isFloodgatePlayer(uid);

        if (elapsed < COOLDOWN_MS && !bypassCooldown) {
            long remaining = COOLDOWN_MS - elapsed;
            long minutesLeft = remaining / 60000;
            long secondsLeft = (remaining % 60000 + 999) / 1000;
            String timeLeft = (minutesLeft > 0 ? minutesLeft + " 分 " : "") + secondsLeft + " 秒";
            player.sendMessage("§e指令冷卻中，請稍等 " + timeLeft + " 後再試");

            if (!isBedrock) {
                BossBar bar = plugin.cooldownBars.computeIfAbsent(uid, id -> {
                    BossBar b = Bukkit.createBossBar("§e指令冷卻中... " + timeLeft,
                            BarColor.BLUE, BarStyle.SOLID);
                    b.addPlayer(player);
                    b.setVisible(true);
                    return b;
                });
                if (!plugin.cooldownTasks.containsKey(uid)) {
                    BukkitTask task = new BukkitRunnable() {
                        final long startTime = System.currentTimeMillis();
                        final long total = remaining;

                        @Override
                        public void run() {
                            long passed = System.currentTimeMillis() - startTime;
                            long left = total - passed;
                            if (left <= 0) {
                                bar.removeAll();
                                plugin.cooldownBars.remove(uid);
                                plugin.cooldownTasks.remove(uid);
                                cancel();
                                return;
                            }
                            long min = left / 60000;
                            long sec = (left % 60000 + 999) / 1000;
                            bar.setTitle("§e指令冷卻中... " +
                                    (min > 0 ? min + " 分 " : "") + sec + " 秒");
                            bar.setProgress((double) left / total);
                        }
                    }.runTaskTimer(plugin, 0L, 20L);
                    plugin.cooldownTasks.put(uid, task);
                } else {
                    // update title only
                    plugin.cooldownBars.get(uid)
                            .setTitle("§e指令冷卻中... " + timeLeft);
                }
            }
            return true;
        }

        if (bypassCooldown) {
            player.sendMessage("§aOP 已跳過指令冷卻");
        }

        String input = args.length > 0 ? args[0] : "普通";
        VoidTrialChambersPlugin.TrialDifficulty diff =
                VoidTrialChambersPlugin.TrialDifficulty.from(input);
        if (diff == null) {
            player.sendMessage("§c無效難度，請輸入 簡單/普通/地獄/吞夢噬念");
            return true;
        }

        // 删除旧世界
        World old = plugin.playerTrialWorlds.remove(uid);
        if (old != null) {
            plugin.cleanUpManager.clearEntityAndPoiFolders(old);
        }

        plugin.trialCooldowns.put(uid, now);
        plugin.originalLocations.put(uid, player.getLocation());
        player.sendMessage(Component.text("§6正在進入「" + diff.getDisplay() + "」難度的試煉世界，請稍候..."));

        World personal = plugin.createPersonalTrialWorld(uid);
        if (personal == null) {
            player.sendMessage("§c無法建立試煉世界，請稍後再試");
            return true;
        }
        plugin.playerTrialWorlds.put(uid, personal);
        plugin.playerDifficulties.put(uid, diff);

        VoidTrialChambersPlugin.WorldMobSpawnerTask oldTask = plugin.spawnerTasks.remove(uid);
        if (oldTask != null) {
            oldTask.stop();
        }
        World oldWorld = plugin.playerTrialWorlds.get(uid);
        if (oldWorld != null) {
            plugin.worldKillCounts.remove(oldWorld.getName());
        }

        VoidTrialChambersPlugin.WorldMobSpawnerTask spawner =
                plugin.new WorldMobSpawnerTask(personal, diff);
        plugin.spawnerTasks.put(uid, spawner);
        spawner.start();
        String worldName = personal.getName();
        plugin.worldKillCounts.put(worldName, 0);

        int y = plugin.getConfig().getInt("trial_chamber.y", 50);
        int range = plugin.getConfig().getInt("trial_range", 500);
        Random rnd = new Random();
        int x = rnd.nextInt(range * 2 + 1) - range;
        int z = rnd.nextInt(range * 2 + 1) - range;
        Location center = new Location(personal, x + 0.5, y + 5, z + 0.5);

        player.teleport(center);
        applyTrialEffects(player);

        BossBar prepBar = Bukkit.createBossBar("§6試煉準備中...", BarColor.YELLOW, BarStyle.SOLID);
        prepBar.addPlayer(player);
        prepBar.setVisible(true);

        new BukkitRunnable() {
            final long start = System.currentTimeMillis();
            final long total = 13_000L;

            @Override
            public void run() {
                long elapsed = System.currentTimeMillis() - start;
                if (elapsed >= total) {
                    prepBar.removeAll();
                    cancel();
                    return;
                }
                prepBar.setProgress((double) elapsed / total);
            }
        }.runTaskTimer(plugin, 0L, 10L);

        new BukkitRunnable() {
            @Override
            public void run() {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                        String.format("execute in %s run place structure minecraft:trial_chambers %d %d %d",
                                worldName, x, y, z));
                plugin.getLogger().info("Structure placed at " + x + "," + y + "," + z);
            }
        }.runTaskLater(plugin, 200L);

        new BukkitRunnable() {
            @Override
            public void run() {
                removeTrialEffects(player);
                teleportToNearestBedOrOrigin(player, personal);
            }
        }.runTaskLater(plugin, 240L);

        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender,
                                      @NotNull Command command,
                                      @NotNull String alias,
                                      @NotNull String @NotNull [] args) {
        if (args.length == 1) {
            List<String> opts = Arrays.asList("簡單", "普通", "地獄", "吞夢噬念");
            List<String> res = new ArrayList<>();
            for (String o : opts) {
                if (o.startsWith(args[0])) res.add(o);
            }
            return res;
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
                PotionEffectType.BLINDNESS,
                PotionEffectType.SLOW_FALLING,
                PotionEffectType.SLOWNESS,
                PotionEffectType.MINING_FATIGUE)) {
            p.removePotionEffect(type);
        }
        p.addPotionEffect(new org.bukkit.potion.PotionEffect(PotionEffectType.NIGHT_VISION, 100, 0));
    }

    private void teleportToNearestBedOrOrigin(Player p, World world) {
        Location loc = p.getLocation();
        Location bed = findNearestBed(loc, world);
        if (bed != null) {
            p.teleport(bed);
            p.sendMessage("§6已進入試煉之間副本");
        } else {
            Location orig = plugin.originalLocations.remove(p.getUniqueId());
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