package org.swim.voidTrialChambersPlugin;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.stream.Collectors;
import java.util.Locale;

/**
 * /trialleaderboard <solo|team|kills> <地獄|吞夢噬念>
 */
public class LeaderboardCommand implements CommandExecutor, TabCompleter {
    private static final Map<String, String> TYPE_KEY_MAP = Map.of(
            "solo", "solo",
            "team", "team",
            "kills", "kills"
    );
    private static final Map<String, String> DIFFICULTY_KEY_MAP = Map.of(
            "地獄", "HELL",
            "吞夢噬念", "JUDGMENT"
    );
    private static final Map<String, String> DIFF_DISPLAY = Map.of(
            "HELL", "地獄",
            "JUDGMENT", "吞夢噬念"
    );

    private final LeaderboardManager manager;

    public LeaderboardCommand(LeaderboardManager manager) {
        this.manager = manager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command cmd,
                             @NotNull String label,
                             @NotNull String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c只有玩家可以使用此指令");
            return true;
        }
        if (args.length < 2) {
            player.sendMessage("§c用法: /trialleaderboard <solo/team/kills> <地獄/吞夢噬念>");
            return true;
        }

        // 使用 Locale.ROOT 以避免默认区域设置带来的差异
        String typeKey = TYPE_KEY_MAP.get(args[0].toLowerCase(Locale.ROOT));
        String diffKey = DIFFICULTY_KEY_MAP.get(args[1]);
        if (typeKey == null || diffKey == null) {
            player.sendMessage("§c用法: /trialleaderboard <solo/team/kills> <地獄/吞夢噬念>");
            return true;
        }

        String display = DIFF_DISPLAY.get(diffKey);
        switch (typeKey) {
            case "solo"  -> showSolo(player, diffKey, display);
            case "team"  -> showTeam(player, diffKey, display);
            case "kills" -> showKills(player, diffKey, display);
        }
        return true;
    }

    private void showSolo(Player p, String diff, String display) {
        List<LeaderboardManager.TimeLeaderboardEntry> list = manager.getSoloTimeEntries(diff);
        p.sendMessage("§6=== §e單人時間排行榜（" + display + "）§6 ===");
        if (list.isEmpty()) {
            p.sendMessage("§7暫無記錄");
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            var e = list.get(i);
            p.sendMessage(String.format("§e#%d §f%s §7- §a%s",
                    i + 1,
                    e.getPlayerNames().getFirst(),
                    e.getFormattedTime()
            ));
        }
    }

    private void showTeam(Player p, String diff, String display) {
        List<LeaderboardManager.TimeLeaderboardEntry> list = manager.getTeamTimeEntries(diff);
        p.sendMessage("§6=== §e團隊時間排行榜（" + display + "）§6 ===");
        if (list.isEmpty()) {
            p.sendMessage("§7暫無記錄");
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            var e = list.get(i);
            p.sendMessage(String.format("§e#%d §f%s §7- §a%s",
                    i + 1,
                    String.join(", ", e.getPlayerNames()),
                    e.getFormattedTime()
            ));
        }
    }

    private void showKills(Player p, String diff, String display) {
        List<LeaderboardManager.KillsLeaderboardEntry> list = manager.getKillsEntries(diff);
        p.sendMessage("§6=== §e累計擊殺排行榜（" + display + "）§6 ===");
        if (list.isEmpty()) {
            p.sendMessage("§7暫無記錄");
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            var e = list.get(i);
            String recs = e.getPlayerRecords().stream()
                    .map(r -> r.playerName() + ": " + r.kills())
                    .collect(Collectors.joining(", "));
            p.sendMessage(String.format("§e#%d §a總擊殺: %d §7- §f%s",
                    i + 1,
                    e.getTotalKills(),
                    recs
            ));
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender,
                                      @NotNull Command cmd,
                                      @NotNull String alias,
                                      String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return TYPE_KEY_MAP.keySet().stream()
                    .filter(key -> key.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .toList();
        } else if (args.length == 2) {
            // 中文不受大小寫影響，此处无需 toLowerCase
            String prefix = args[1];
            return DIFFICULTY_KEY_MAP.keySet().stream()
                    .filter(key -> key.startsWith(prefix))
                    .toList();
        }
        return Collections.emptyList();
    }
}