package org.swim.voidTrialChambersPlugin;

import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;

import java.util.UUID;

public class EntityDeathListener implements Listener {

    private final VoidTrialChambersPlugin plugin;
    private final ChestRewardManager chestRewardManager;

    public EntityDeathListener(VoidTrialChambersPlugin plugin) {
        this.plugin = plugin;
        this.chestRewardManager = plugin.getChestRewardManager();
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent evt) {
        World world = evt.getEntity().getWorld();
        String name = world.getName();
        if (!name.startsWith("trial_")) return;
        if (!(evt.getEntity() instanceof Monster)) return;

        UUID owner = plugin.findWorldOwner(name);
        if (owner == null) return;
        VoidTrialChambersPlugin.TrialDifficulty diff = plugin.playerDifficulties.get(owner);
        if (diff != VoidTrialChambersPlugin.TrialDifficulty.HELL
                && diff != VoidTrialChambersPlugin.TrialDifficulty.JUDGMENT) return;

        String diffNameZh = diff == VoidTrialChambersPlugin.TrialDifficulty.HELL
                ? "地獄" : "吞夢噬念";

        EntityDamageEvent last = evt.getEntity().getLastDamageCause();
        if (!(last instanceof EntityDamageByEntityEvent dbe)) return;
        if (!(dbe.getDamager() instanceof Player player)) return;

        // 將此試煉世界的擊殺數量加一
        int count = plugin.worldKillCounts.getOrDefault(name, 0) + 1;
        plugin.worldKillCounts.put(name, count);

        // 更新或建立該世界的試煉會話資訊，並紀錄玩家參與與擊殺
        VoidTrialChambersPlugin.TrialSession session = plugin.activeTrialSessions
                .computeIfAbsent(name, k -> new VoidTrialChambersPlugin.TrialSession(name));
        session.addParticipant(player);
        session.recordKill(player.getUniqueId());

        // 根據難度在特定擊殺數量時進行動態提醒
        int reminderMax = diff == VoidTrialChambersPlugin.TrialDifficulty.JUDGMENT
                ? 450 : 250;
        if (count % 50 == 0 && count <= reminderMax) {
            String msg = "§e本試煉（" + diffNameZh + "難度）已擊殺 " + count + " 隻怪物！";
            for (Player p : world.getPlayers()) {
                p.sendMessage(Component.text(msg));
            }
        }

        // 若為第一次擊殺，記錄試煉開始的時間點
        if (count == 1) {
            plugin.worldStartTimes.put(name, System.currentTimeMillis());
        }

        // 檢查是否達到完成條件 500/300
        int finishCount = diff == VoidTrialChambersPlugin.TrialDifficulty.JUDGMENT
                ? 500 : 300;
        if (count >= finishCount) {
            long start = plugin.worldStartTimes.getOrDefault(name, System.currentTimeMillis());
            long elapsedMs = System.currentTimeMillis() - start;
            long totalSeconds = elapsedMs / 1000;
            long minutes = totalSeconds / 60;
            long seconds = totalSeconds % 60;
            String timeStr = (minutes > 0
                    ? minutes + " 分 " + seconds + " 秒"
                    : seconds + " 秒");

            // 停止刷怪任務並移除所有殘餘怪物
            VoidTrialChambersPlugin.WorldMobSpawnerTask spawner = plugin.spawnerTasks.remove(owner);
            if (spawner != null) spawner.stop();
            world.getEntities().stream()
                    .filter(e -> e instanceof Monster)
                    .forEach(Entity::remove);

            // 更新排行榜資料
            VoidTrialChambersPlugin.TrialSession current = plugin.activeTrialSessions.get(name);
            if (current != null) {
                current.markCompleted();
                boolean solo = current.isSolo();

                // 更新通關時間排行榜
                LeaderboardManager.TimeLeaderboardEntry timeEntry =
                        new LeaderboardManager.TimeLeaderboardEntry(
                                name, current.getParticipantNames(), elapsedMs);
                plugin.leaderboardManager.updateTimeLeaderboard(
                        diff.name(), timeEntry, solo);

                // 更新擊殺數排行榜
                LeaderboardManager.KillsLeaderboardEntry killsEntry =
                        new LeaderboardManager.KillsLeaderboardEntry(
                                name, current.getPlayerKillRecords());
                plugin.leaderboardManager.updateKillsLeaderboard(
                        diff.name(), killsEntry);

                plugin.leaderboardManager.saveLeaderboards();
            }

            // 廣播試煉完成訊息給所有玩家
            String finishMsg = String.format(
                    "§6【試煉完成】恭喜，在%s難度的試煉副本已擊殺 %d 隻怪物！ 耗時：%s\n" +
                            "§a排行榜已更新，可使用 /trialleaderboard solo/team/kills 查看",
                    diffNameZh, finishCount, timeStr);
            world.getPlayers().forEach(p -> p.sendMessage(finishMsg));
            // 試煉完成，給予參與者獎勵
            for (Player p : session.getParticipants()) {
                if (p.getGameMode() == GameMode.SURVIVAL) {
                    // 傳入當前難度，分別下發不同獎勵
                    chestRewardManager.giveRewardChestBeside(p, diff);
                }
            }
            // 重置該試煉世界的相關數據
            plugin.worldKillCounts.remove(name);
            plugin.worldStartTimes.remove(name);
            plugin.activeTrialSessions.remove(name);
        }
    }
}