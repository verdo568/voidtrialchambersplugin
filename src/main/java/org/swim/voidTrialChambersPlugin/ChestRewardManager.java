package org.swim.voidTrialChambersPlugin;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * 根據難度（HELL / JUDGMENT），在玩家腳旁放置一次性寶箱獎勵。
 * 採用靜態預先計算的機率分佈，以減少執行時的物件配置與運算，提升效能。
 */
public class ChestRewardManager {
    /**
     * 靜態常量：為每種難度、每項道具，建立機率分佈清單(含累積權重)。
     */
    private static final Map<VoidTrialChambersPlugin.TrialDifficulty,
            Map<Material, List<ChanceEntry>>> DISTRIBUTIONS = new EnumMap<>(VoidTrialChambersPlugin.TrialDifficulty.class);

    static {
        // ================================================
        // 1. 初始化「地獄難度」的機率分佈表
        // ================================================
        Map<Material, List<ChanceEntry>> hellMap = new EnumMap<>(Material.class);
        // —— 為「附魔金蘋果」定義三種數量 [1、2、4]，對應機率分別為 [95%、4%、1%]
        hellMap.put(Material.ENCHANTED_GOLDEN_APPLE,
                buildDistribution(new int[]{1, 2, 4}, new double[]{95, 4, 1}));
        // —— 為「下界合金」定義一種數量 [2]，對應機率為 1%
        hellMap.put(Material.NETHERITE_INGOT,
                buildDistribution(new int[]{2, 0}, new double[]{1, 99}));
        // —— 為「鐵錠」定義一種數量 [9]，對應機率為 20%
        hellMap.put(Material.IRON_INGOT,
                buildDistribution(new int[]{9, 0}, new double[]{20, 80}));
        // —— 為「地獄石英」定義一種數量 [9]，對應機率為 20%
        hellMap.put(Material.QUARTZ,
                buildDistribution(new int[]{9, 0}, new double[]{20, 80}));
        // —— 為「鞍」定義一種數量 [1]，對應機率為 67%
        hellMap.put(Material.SADDLE,
                buildDistribution(new int[]{1, 0}, new double[]{67, 33}));
        // —— 為「腐肉」定義一種數量 [8]，對應機率為 80%
        hellMap.put(Material.ROTTEN_FLESH,
                buildDistribution(new int[]{8, 0}, new double[]{80, 20}));
        // —— 為「火藥」定義一種數量 [5]，對應機率為 57%
        hellMap.put(Material.GUNPOWDER,
                buildDistribution(new int[]{5, 0}, new double[]{57, 43}));
        // —— 為「骨頭」定義一種數量 [3]，對應機率為 50%
        hellMap.put(Material.BONE,
                buildDistribution(new int[]{3, 0}, new double[]{50, 50}));
        // —— 為「泥土」定義一種數量 [7]，對應機率為 67%
        hellMap.put(Material.DIRT,
                buildDistribution(new int[]{7, 0}, new double[]{67, 33}));
        // —— 為「烈焰桿」定義一種數量 [3]，對應機率為 50%
        hellMap.put(Material.BLAZE_ROD,
                buildDistribution(new int[]{3, 0}, new double[]{50, 50}));
        // … 若要擴充地獄難度的其他道具分佈，可繼續新增 put()
        // 將地獄難度配置放入總分佈表中
        DISTRIBUTIONS.put(VoidTrialChambersPlugin.TrialDifficulty.HELL, hellMap);

        // ================================================
        // 2. 初始化「吞夢噬念（JUDGMENT）難度」的機率分佈表
        // ================================================
        Map<Material, List<ChanceEntry>> judgMap = new EnumMap<>(Material.class);
        // —— 為「附魔金蘋果」定義三種數量 [1、2、4]，對應機率分別為 [10%、40%、50%]
        judgMap.put(Material.ENCHANTED_GOLDEN_APPLE,
                buildDistribution(new int[]{1, 2, 4}, new double[]{10, 40, 50}));
        // —— 為「下界合金」定義三種數量 [1、2、3]，對應機率分別為 [30%、50%、20%]
        judgMap.put(Material.NETHERITE_INGOT,
                buildDistribution(new int[]{1, 2, 3}, new double[]{30, 50, 20}));
        // —— 為「鐵錠」定義一種數量 [9]，對應機率為 20%
        judgMap.put(Material.IRON_INGOT,
                buildDistribution(new int[]{9, 0}, new double[]{20, 80}));
        // —— 為「地獄石英」定義一種數量 [9]，對應機率為 20%
        judgMap.put(Material.QUARTZ,
                buildDistribution(new int[]{9, 0}, new double[]{20, 80}));
        // —— 為「鞍」定義一種數量 [1]，對應機率為 5%
        judgMap.put(Material.SADDLE,
                buildDistribution(new int[]{1, 0}, new double[]{5, 95}));
        // … 若要擴充吞夢噬念難度的其他道具分佈，可繼續新增 put()
        // 將吞夢噬念難度配置放入總分佈表中
        DISTRIBUTIONS.put(VoidTrialChambersPlugin.TrialDifficulty.JUDGMENT, judgMap);
    }

    private final VoidTrialChambersPlugin plugin;
    private final Random random = new Random();

    public ChestRewardManager(VoidTrialChambersPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 建立「數量 → 權重(%)」分佈的累積權重列表。
     *
     * @param counts     各選項的數量
     * @param weightsPct 權重百分比，長度須與 counts 相同
     * @return 排序後的累積清單
     */
    private static List<ChanceEntry> buildDistribution(int[] counts, double[] weightsPct) {
        int n = counts.length;
        List<ChanceEntry> list = new ArrayList<>(n);
        double cum = 0D;
        for (int i = 0; i < n; i++) {
            cum += weightsPct[i];
            list.add(new ChanceEntry(counts[i], cum));
        }
        return Collections.unmodifiableList(list);
    }

    /**
     * 放置寶箱並填入獎勵，5 分鐘後自動清除。
     */
    public void giveRewardChestBeside(Player player, VoidTrialChambersPlugin.TrialDifficulty diff) {
        UUID playerId = player.getUniqueId();
        if (!plugin.canClaim(playerId)) {
            player.sendMessage("§c您今天的獎勵次數已達上限 (5 次)，請明日再領取。");
            return;
        }

        // 算出玩家腳旁的位置 (跟玩家面向垂直)
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().setY(0).normalize();
        final Location chestLoc = player.getLocation().clone()
                .add(dir.getZ(), 0, -dir.getX());

        // 確保該格為空氣，否則破壞後再檢查
        Block block = chestLoc.getBlock();
        if (block.getType() != Material.AIR) {
            block.breakNaturally();
        }
        if (block.getType() != Material.AIR) {
            player.sendMessage("§c無法在身旁生成寶箱：該格被阻擋。");
            return;
        }

        // 放置箱子
        block.setType(Material.CHEST);
        if (!(block.getState() instanceof Chest chest)) {
            player.sendMessage("§c放置寶箱失敗。");
            return;
        }
        Inventory inv = chest.getInventory();

        // 固定獎勵：依難度選定數量
        if (diff == VoidTrialChambersPlugin.TrialDifficulty.HELL) {
            inv.addItem(
                    new ItemStack(Material.DIAMOND, 3),
                    new ItemStack(Material.EXPERIENCE_BOTTLE, 32),
                    new ItemStack(Material.GOLD_INGOT, 5),
                    new ItemStack(Material.TOTEM_OF_UNDYING, 1),
                    new ItemStack(Material.EMERALD, 16)

            );
        } else {
            inv.addItem(
                    new ItemStack(Material.DIAMOND, 5),
                    new ItemStack(Material.EXPERIENCE_BOTTLE, 64),
                    new ItemStack(Material.TOTEM_OF_UNDYING, 8),
                    new ItemStack(Material.EMERALD, 64)
            );
        }

        // 機率獎勵：使用靜態 DISTRIBUTIONS，依難度、依道具 抽取數量
        Map<Material, List<ChanceEntry>> distMap = DISTRIBUTIONS.get(diff);
        for (Map.Entry<Material, List<ChanceEntry>> entry : distMap.entrySet()) {
            int count = pickCount(entry.getValue());
            if (count > 0) {
                inv.addItem(new ItemStack(entry.getKey(), count));
            }
        }

        player.sendMessage("§a已在您腳旁生成【" +
                (diff == VoidTrialChambersPlugin.TrialDifficulty.HELL ? "地獄" : "吞夢噬念") +
                "難度】寶箱獎勵，請盡快領取！");
        plugin.recordClaim(playerId);

        // 5 分鐘後自動移除
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    World w = chestLoc.getWorld();
                    if (w == null) return;
                    // 若世界或區塊已卸載，跳過
                    if (Bukkit.getWorlds().stream().noneMatch(ww -> ww.getUID().equals(w.getUID())))
                        return;
                    int cx = chestLoc.getBlockX() >> 4, cz = chestLoc.getBlockZ() >> 4;
                    if (!w.isChunkLoaded(cx, cz)) return;

                    Block b = chestLoc.getBlock();
                    if (b.getType() == Material.CHEST) {
                        b.setType(Material.AIR);
                    }
                } catch (IllegalStateException ex) {
                    plugin.getLogger().warning("移除延遲寶箱時跳過，chunk 系統已關閉: " + ex.getMessage());
                }
            }
        }.runTaskLater(plugin, 5 * 60 * 20L);
    }

    /**
     * 根據單一道具的累積機率分佈清單抽出要給的數量。
     */
    private int pickCount(List<ChanceEntry> entries) {
        double total = entries.getLast().cumulativeWeight;
        double r = random.nextDouble() * total;
        // 二分搜尋或線性搜尋都可，列表通常很短，故用線性最快
        for (ChanceEntry e : entries) {
            if (r < e.cumulativeWeight) {
                return e.count;
            }
        }
        return 0;
    }

    /**
     * 單項機率分佈 Entry，保存給定 count 與其對應的累積權重。
     */
    private record ChanceEntry(int count, double cumulativeWeight) {
    }
}