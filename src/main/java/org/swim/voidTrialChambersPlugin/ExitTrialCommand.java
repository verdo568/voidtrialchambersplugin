package org.swim.voidTrialChambersPlugin;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * 退出試煉指令執行器
 * 當玩家執行退出試煉指令時，移除狀態效果、停止任務並清理試煉世界
 */
public class ExitTrialCommand implements CommandExecutor {
    // 主插件實例，用於存取全域資料結構與管理器
    private final VoidTrialChambersPlugin plugin;

    /**
     * 建構子：注入主插件實例
     * @param plugin 主插件物件，用於存取設定與任務管理
     */
    public ExitTrialCommand(VoidTrialChambersPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 指令呼叫處理器
     * @param sender 指令發送者
     * @param cmd    指令物件
     * @param label  指令別名
     * @param args   指令參數
     * @return boolean 回傳 true 表示指令已被處理，false 則交給其他處理器
     */
    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player p)) {
            return true;
        }

        // 取得玩家所在世界名稱
        String currentWorld = p.getWorld().getName();
        // 若世界在排除名單中，則不處理退出動作
        if (plugin.excludedWorldNames.contains(currentWorld)) {
            return true;
        }

        // 取得主世界 (world) 實體
        World main = Bukkit.getWorld("world");
        if (main != null) {
            // 傳送玩家到主世界的重生點
            p.teleport(main.getSpawnLocation());
            // 發送退出提示訊息
            p.sendMessage("§6你已退出試煉並傳送至主世界");
        }

        // 取得玩家唯一識別碼
        UUID uid = p.getUniqueId();
        // 停止並移除玩家對應的生怪器任務
        VoidTrialChambersPlugin.WorldMobSpawnerTask spawner = plugin.spawnerTasks.remove(uid);
        if (spawner != null) {
            spawner.stop();
        }

        // 移除玩家試煉世界並清理該世界的生物與 POI 資料夾
        World w = plugin.playerTrialWorlds.remove(uid);
        if (w != null) {
            plugin.cleanUpManager.clearEntityAndPoiFolders(w);
        }

        // 指令處理完成
        return true;
    }
}