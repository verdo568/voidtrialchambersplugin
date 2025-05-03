package org.swim.voidTrialChambersPlugin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 負責排行榜資料的載入/儲存與更新
 */
public class LeaderboardManager {
    private final File soloTimeFile;
    private final File teamTimeFile;
    private final File killsFile;

    private final Map<String, List<TimeLeaderboardEntry>> timeLeaderboardSolo = new ConcurrentHashMap<>();
    private final Map<String, List<TimeLeaderboardEntry>> timeLeaderboardTeam = new ConcurrentHashMap<>();
    private final Map<String, List<KillsLeaderboardEntry>> killsLeaderboard = new ConcurrentHashMap<>();

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public LeaderboardManager(JavaPlugin plugin) {
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            plugin.getLogger().warning("無法創建資料夾: " + dataFolder.getAbsolutePath());
        }

        soloTimeFile = new File(dataFolder, "solo_time_leaderboard.json");
        teamTimeFile = new File(dataFolder, "team_time_leaderboard.json");
        killsFile    = new File(dataFolder, "kills_leaderboard.json");

        try {
            // 嘗試建立檔案，並檢查 createNewFile 回傳值
            if (!soloTimeFile.exists()) {
                boolean created = soloTimeFile.createNewFile();
                if (!created) {
                    plugin.getLogger().warning("創建文件失敗: " + soloTimeFile.getName());
                }
            }
            if (!teamTimeFile.exists()) {
                boolean created = teamTimeFile.createNewFile();
                if (!created) {
                    plugin.getLogger().warning("創建文件失敗: " + teamTimeFile.getName());
                }
            }
            if (!killsFile.exists()) {
                boolean created = killsFile.createNewFile();
                if (!created) {
                    plugin.getLogger().warning("創建文件失敗: " + killsFile.getName());
                }
            }
        } catch (IOException e) {
            plugin.getLogger().warning("無法創建排行榜文件: " + e.getMessage());
        }

        loadLeaderboards();
    }

    private void loadLeaderboards() {
        load(soloTimeFile, new TypeToken<Map<String, List<TimeLeaderboardEntry>>>(){}.getType(), timeLeaderboardSolo);
        load(teamTimeFile, new TypeToken<Map<String, List<TimeLeaderboardEntry>>>(){}.getType(), timeLeaderboardTeam);
        load(killsFile,    new TypeToken<Map<String, List<KillsLeaderboardEntry>>>(){}.getType(), killsLeaderboard);
    }

    private <T> void load(File file, Type type, Map<String, List<T>> target) {
        try (FileReader reader = new FileReader(file)) {
            Map<String, List<T>> loaded = gson.fromJson(reader, type);
            if (loaded != null) target.putAll(loaded);
        } catch (IOException e) {
            // silent
        }
    }

    public void saveLeaderboards() {
        save(soloTimeFile, timeLeaderboardSolo);
        save(teamTimeFile, timeLeaderboardTeam);
        save(killsFile,    killsLeaderboard);
    }

    private <T> void save(File file, Map<String, List<T>> data) {
        try (FileWriter writer = new FileWriter(file)) {
            gson.toJson(data, writer);
        } catch (IOException e) {
            // silent
        }
    }

    public void updateTimeLeaderboard(String difficulty, TimeLeaderboardEntry entry, boolean isSolo) {
        Map<String, List<TimeLeaderboardEntry>> board = isSolo ? timeLeaderboardSolo : timeLeaderboardTeam;
        List<TimeLeaderboardEntry> list = board.computeIfAbsent(difficulty, k -> new ArrayList<>());
        list.add(entry);
        list.sort(Comparator.comparingLong(TimeLeaderboardEntry::getCompletionTimeMs));
        if (list.size() > 10) board.put(difficulty, list.subList(0, 10));
    }

    public void updateKillsLeaderboard(String difficulty, KillsLeaderboardEntry entry) {
        List<KillsLeaderboardEntry> list = killsLeaderboard.computeIfAbsent(difficulty, k -> new ArrayList<>());
        list.add(entry);
        list.sort((a, b) -> Integer.compare(b.getTotalKills(), a.getTotalKills()));
        if (list.size() > 10) killsLeaderboard.put(difficulty, list.subList(0, 10));
    }

    public List<TimeLeaderboardEntry> getSoloTimeEntries(String diff) {
        return timeLeaderboardSolo.getOrDefault(diff, Collections.emptyList());
    }
    public List<TimeLeaderboardEntry> getTeamTimeEntries(String diff) {
        return timeLeaderboardTeam.getOrDefault(diff, Collections.emptyList());
    }
    public List<KillsLeaderboardEntry> getKillsEntries(String diff) {
        return killsLeaderboard.getOrDefault(diff, Collections.emptyList());
    }

    // ---- Entry 類 ----

    public static class TimeLeaderboardEntry {
        private final List<String> playerNames;
        private final long completionTimeMs;
        private final String formattedTime;

        public TimeLeaderboardEntry(String worldName, List<String> playerNames, long completionTimeMs) {
            this.playerNames = playerNames;
            this.completionTimeMs = completionTimeMs;
            this.formattedTime = formatTime(completionTimeMs);
            long timestamp = System.currentTimeMillis();
        }

        private static String formatTime(long ms) {
            long totalSeconds = ms / 1000;
            long minutes = totalSeconds / 60;
            long seconds = totalSeconds % 60;
            return (minutes > 0 ? minutes + " 分 " : "") + seconds + " 秒";
        }

        public List<String> getPlayerNames() { return playerNames; }
        public long getCompletionTimeMs() { return completionTimeMs; }
        public String getFormattedTime() { return formattedTime; }
    }

    public static class KillsLeaderboardEntry {
        private final List<PlayerKillRecord> playerRecords;
        private final int totalKills;

        public KillsLeaderboardEntry(String worldName, List<PlayerKillRecord> playerRecords) {
            this.playerRecords = playerRecords;
            this.totalKills = playerRecords.stream().mapToInt(PlayerKillRecord::kills).sum();
            long timestamp = System.currentTimeMillis();
        }

        public int getTotalKills() { return totalKills; }
        public List<PlayerKillRecord> getPlayerRecords() { return playerRecords; }
    }

    public record PlayerKillRecord(String playerName, int kills) {}
}