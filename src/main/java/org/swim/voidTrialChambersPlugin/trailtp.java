package org.swim.voidTrialChambersPlugin;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class trailtp implements CommandExecutor, TabCompleter {
    // 存放邀請資料 (被邀請者UUID, 邀請者UUID)
    private final Map<UUID, UUID> pendingInvites = new ConcurrentHashMap<>();

    public trailtp(JavaPlugin plugin) {
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("此指令僅限玩家使用");
            return true;
        }

        String worldName = player.getWorld().getName();
        if (args.length == 0) {
            player.sendMessage("§e/trailteam invite <玩家> | /trailteam yes | /trailteam no");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "invite" -> {
                // 僅允許在 trial_ 世界
                if (!worldName.startsWith("trial_")) {
                    player.sendMessage("§c只能在試煉世界中邀請！");
                    return true;
                }
                if (args.length < 2) {
                    player.sendMessage("§e用法: /trailteam invite <玩家>");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null || !target.isOnline()) {
                    player.sendMessage("§c玩家不在線上！");
                    return true;
                }
                if (target == player) {
                    player.sendMessage("§c不能邀請自己！");
                    return true;
                }
                pendingInvites.put(target.getUniqueId(), player.getUniqueId());
                player.sendMessage("§a已邀請 " + target.getName() + " 加入你的試煉副本！");
                target.sendMessage("§b" + player.getName() + " 邀請你進入試煉副本，輸入 /trailteam yes 接受，/trailteam no 拒絕。");
            }
            case "yes" -> {
                UUID inviteFrom = pendingInvites.remove(player.getUniqueId());
                if (inviteFrom == null) {
                    player.sendMessage("§e目前沒有待接受的邀請。");
                    return true;
                }
                Player inviter = Bukkit.getPlayer(inviteFrom);
                if (inviter == null || !inviter.isOnline()) {
                    player.sendMessage("§c邀請者已離線，邀請無效。");
                    return true;
                }
                String inviterWorld = inviter.getWorld().getName();
                if (!inviterWorld.startsWith("trial_")) {
                    player.sendMessage("§c邀請者已不在試煉世界！");
                    return true;
                }
                World trialWorld = inviter.getWorld();
                player.teleport(inviter.getLocation());
                player.sendMessage("§a你已接受邀請，被傳送進入試煉世界！");
                inviter.sendMessage("§b" + player.getName() + " 已接受你的邀請並進入你的試煉世界。");
            }
            case "no" -> {
                UUID inviteFrom = pendingInvites.remove(player.getUniqueId());
                if (inviteFrom == null) {
                    player.sendMessage("§e目前沒有待拒絕的邀請。");
                    return true;
                }
                Player inviter = Bukkit.getPlayer(inviteFrom);
                player.sendMessage("§e你已拒絕邀請。");
                if (inviter != null && inviter.isOnline()) {
                    inviter.sendMessage("§c" + player.getName() + " 拒絕了你的邀請。");
                }
            }
            default -> player.sendMessage("§e/trailteam invite <玩家> | /trailteam yes | /trailteam no");
        }
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String @NotNull [] args) {
        if (args.length == 1) {
            return Arrays.asList("invite", "yes", "no");
        }
        if (args.length == 2 && "invite".equalsIgnoreCase(args[0])) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!p.getName().equalsIgnoreCase(sender.getName())) {
                    names.add(p.getName());
                }
            }
            return names;
        }
        return Collections.emptyList();
    }
}