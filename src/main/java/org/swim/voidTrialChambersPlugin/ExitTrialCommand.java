package org.swim.voidTrialChambersPlugin;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.UUID;

public class ExitTrialCommand implements CommandExecutor {
    private final VoidTrialChambersPlugin plugin;

    public ExitTrialCommand(VoidTrialChambersPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player p)) {
            return true;
        }
        String currentWorld = p.getWorld().getName();
        if (plugin.excludedWorldNames.contains(currentWorld)) {
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
        VoidTrialChambersPlugin.WorldMobSpawnerTask spawner = plugin.spawnerTasks.remove(uid);
        if (spawner != null) {
            spawner.stop();
        }
        World w = plugin.playerTrialWorlds.remove(uid);
        if (w != null) {
            plugin.cleanUpManager.clearEntityAndPoiFolders(w);
        }
        return true;
    }
}