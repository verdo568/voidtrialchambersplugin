package org.swim.voidTrialChambersPlugin;

import org.bukkit.Bukkit;
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

/**
 * 處理 /trailteam 指令的執行與參數補全，
 * 支援邀請玩家進入試煉世界並同意或拒絕邀請的功能。
 */
public class trailtp implements CommandExecutor, TabCompleter {
    // 用來儲存待處理的試煉邀請：鍵為被邀請者的 UUID，值為邀請者的 UUID
    private final Map<UUID, UUID> pendingInvites = new ConcurrentHashMap<>();

    /**
     * 構造函式，接收主插件實例（目前未使用）。
     *
     * @param plugin 主插件物件
     */
    public trailtp(JavaPlugin plugin) {
    }

    /**
     * 處理 /trailteam 指令，
     * 可使用子指令 invite、yes、no。
     */
    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("此指令僅限玩家使用");
            return true;
        }

        String worldName = player.getWorld().getName();
        // 若未輸入任何參數，顯示指令用法
        if (args.length == 0) {
            player.sendMessage("§e/trailteam invite <玩家> | /trailteam yes | /trailteam no");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "invite" -> {
                // 僅限在試煉世界使用邀請功能（世界名稱必須以 trial_ 開頭）
                if (!worldName.startsWith("trial_")) {
                    player.sendMessage("§c只能在試煉世界中邀請！");
                    return true;
                }
                // 檢查是否有指定玩家名稱
                if (args.length < 2) {
                    player.sendMessage("§e用法: /trailteam invite <玩家>");
                    return true;
                }
                // 取得目標玩家並檢查是否在線
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null || !target.isOnline()) {
                    player.sendMessage("§c玩家不在線上！");
                    return true;
                }
                // 無法邀請自己
                if (target == player) {
                    player.sendMessage("§c不能邀請自己！");
                    return true;
                }
                // 記錄邀請資料
                pendingInvites.put(target.getUniqueId(), player.getUniqueId());
                // 通知邀請者與被邀請者
                player.sendMessage("§a已邀請 " + target.getName() + " 加入你的試煉副本！");
                target.sendMessage("§b" + player.getName() + " 邀請你進入試煉副本，輸入 /trailteam yes 接受，/trailteam no 拒絕。");
            }
            case "yes" -> {
                // 接受邀請
                UUID inviteFrom = pendingInvites.remove(player.getUniqueId());
                if (inviteFrom == null) {
                    player.sendMessage("§e目前沒有待接受的邀請。");
                    return true;
                }
                // 取得並驗證邀請者
                Player inviter = Bukkit.getPlayer(inviteFrom);
                if (inviter == null || !inviter.isOnline()) {
                    player.sendMessage("§c邀請者已離線，邀請無效。");
                    return true;
                }
                String inviterWorld = inviter.getWorld().getName();
                // 確認邀請者仍在試煉世界
                if (!inviterWorld.startsWith("trial_")) {
                    player.sendMessage("§c邀請者已不在試煉世界！");
                    return true;
                }
                // 傳送玩家到邀請者所在位置
                player.teleport(inviter.getLocation());
                // 通知雙方
                player.sendMessage("§a你已接受邀請，被傳送進入試煉世界！");
                inviter.sendMessage("§b" + player.getName() + " 已接受你的邀請並進入你的試煉世界。");
            }
            case "no" -> {
                // 拒絕邀請
                UUID inviteFrom = pendingInvites.remove(player.getUniqueId());
                if (inviteFrom == null) {
                    player.sendMessage("§e目前沒有待拒絕的邀請。");
                    return true;
                }
                Player inviter = Bukkit.getPlayer(inviteFrom);
                // 告知玩家已拒絕
                player.sendMessage("§e你已拒絕邀請。");
                // 通知邀請者
                if (inviter != null && inviter.isOnline()) {
                    inviter.sendMessage("§c" + player.getName() + " 拒絕了你的邀請。");
                }
            }
            default -> player.sendMessage("§e/trailteam invite <玩家> | /trailteam yes | /trailteam no");
        }
        return true;
    }

    /**
     * 提供 /trailteam 指令的參數自動補全
     */
    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String @NotNull [] args) {
        if (args.length == 1) {
            return Arrays.asList("invite", "yes", "no");
        }
        // invite 子指令第二個參數：補全在線玩家名 (排除自己)
        if (args.length == 2 && "invite".equalsIgnoreCase(args[0])) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!p.getName().equalsIgnoreCase(sender.getName())) {
                    names.add(p.getName());
                }
            }
            return names;
        }
        // 其他情況不提供補全
        return Collections.emptyList();
    }
}