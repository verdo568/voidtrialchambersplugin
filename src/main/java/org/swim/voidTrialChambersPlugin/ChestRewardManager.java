package org.swim.voidTrialChambersPlugin;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * ChestRewardManager：根據玩家所選難度（HELL / JUDGMENT），
 * * 支援「固定數量分佈」與「範圍分佈（例如 5–8）」。
 */
public class ChestRewardManager {

    /**
     * 靜態：各難度的「機率獎勵」分佈表；Material → RewardDistribution
     */
    private static final Map<VoidTrialChambersPlugin.TrialDifficulty,
            Map<Material, RewardDistribution>> DISTRIBUTIONS;
    /**
     * 靜態：各難度的「固定獎勵」清單
     */
    private static final Map<VoidTrialChambersPlugin.TrialDifficulty, List<ItemStack>> FIXED_REWARDS;

    static {
        // 初始化機率獎勵分佈
        Map<VoidTrialChambersPlugin.TrialDifficulty, Map<Material, RewardDistribution>> dist =
                new EnumMap<>(VoidTrialChambersPlugin.TrialDifficulty.class);
        dist.put(VoidTrialChambersPlugin.TrialDifficulty.HELL, buildHellDistributions());
        dist.put(VoidTrialChambersPlugin.TrialDifficulty.JUDGMENT, buildJudgDistributions());
        DISTRIBUTIONS = Collections.unmodifiableMap(dist);

        // 初始化固定獎勵清單
        Map<VoidTrialChambersPlugin.TrialDifficulty, List<ItemStack>> fixed =
                new EnumMap<>(VoidTrialChambersPlugin.TrialDifficulty.class);
        fixed.put(VoidTrialChambersPlugin.TrialDifficulty.HELL, List.of(
                new ItemStack(Material.DIAMOND, 3),
                new ItemStack(Material.EXPERIENCE_BOTTLE, 32),
                new ItemStack(Material.GOLD_INGOT, 5),
                new ItemStack(Material.TOTEM_OF_UNDYING, 1),
                new ItemStack(Material.EMERALD, 16)
        ));
        fixed.put(VoidTrialChambersPlugin.TrialDifficulty.JUDGMENT, List.of(
                new ItemStack(Material.DIAMOND, 5),
                new ItemStack(Material.EXPERIENCE_BOTTLE, 64),
                new ItemStack(Material.TOTEM_OF_UNDYING, 8),
                new ItemStack(Material.EMERALD, 64)
        ));
        FIXED_REWARDS = Collections.unmodifiableMap(fixed);
    }

    private final VoidTrialChambersPlugin plugin;

    /**
     * 建構子
     *
     * @param plugin 主插件實例，提供權限與記錄玩家領取次數功能
     */
    public ChestRewardManager(VoidTrialChambersPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 建構「HELL 地獄難度」的分佈表
     */
    private static Map<Material, RewardDistribution> buildHellDistributions() {
        return Map.ofEntries(
                // —— 為「附魔金蘋果」定義三種數量 [1、2、4]，對應機率分別為 [95%、4%、1%]
                distEntry(Material.ENCHANTED_GOLDEN_APPLE, new int[]{1, 2, 4}, new double[]{95, 4, 1}),
                // —— 為「下界合金」定義一種數量 [2]，對應機率為 1%
                distEntry(Material.NETHERITE_INGOT, new int[]{2, 0}, new double[]{1, 99}),
                // —— 為「鐵錠」定義一種數量 [9]，對應機率為 20%
                distEntry(Material.IRON_INGOT, new int[]{9, 0}, new double[]{20, 80}),
                // —— 為「鞍」定義一種數量 [1]，對應機率為 67%
                distEntry(Material.SADDLE, new int[]{1, 0}, new double[]{67, 33}),
                // —— 為「骨頭」定義一種數量 [3]，對應機率為 50%
                distEntry(Material.BONE, new int[]{3, 0}, new double[]{50, 50}),
                // —— 為「泥土」定義一種數量 [7]，對應機率為 67%
                distEntry(Material.DIRT, new int[]{7, 0}, new double[]{67, 33}),
                // —— 為「烈焰桿」定義一種數量 [3]，對應機率為 50%
                distEntry(Material.BLAZE_ROD, new int[]{3, 0}, new double[]{50, 50}),
                //「火藥 5–8」範圍分佈 (73% 給予 5–8)
                distRangeEntry(Material.GUNPOWDER, 5, 8),
                //「腐肉 6–8」範圍分佈 (73% 給予 6–8)
                distRangeEntry(Material.ROTTEN_FLESH, 6, 8),
                //「地獄石英 8–16」範圍分佈 (73% 給予 8–16)
                distRangeEntry(Material.QUARTZ, 8, 16)
        );
    }

    /**
     * 建構「JUDGMENT 吞夢噬念難度」的分佈表
     */
    private static Map<Material, RewardDistribution> buildJudgDistributions() {
        return Map.ofEntries(
                distEntry(Material.ENCHANTED_GOLDEN_APPLE, new int[]{1, 2, 4}, new double[]{10, 40, 50}),
                distEntry(Material.NETHERITE_INGOT, new int[]{1, 2, 3}, new double[]{30, 50, 20}),
                distEntry(Material.IRON_INGOT, new int[]{9, 0}, new double[]{20, 80}),
                distEntry(Material.QUARTZ, new int[]{9, 0}, new double[]{20, 80}),
                distEntry(Material.SADDLE, new int[]{1, 0}, new double[]{5, 95})
        );
    }

    // ----------------------------------
    // 以下為靜態方法：建構各難度的「機率分佈表」
    // ----------------------------------

    /**
     * 建立固定選項分佈的輔助方法
     *
     * @param m      道具材質
     * @param counts 各選項數量陣列
     * @param w      與 counts 對應的百分比權重陣列
     * @return Map 條目，key=Material, value=RewardDistribution
     */
    private static Map.Entry<Material, RewardDistribution> distEntry(
            Material m, int[] counts, double[] w) {
        return Map.entry(m, RewardDistribution.of(counts, w));
    }

    /**
     * 建立「範圍分佈」的輔助方法：
     * weights[0]：給予 range 的機率 (pctGive)
     * weights[1]：給予 0 的機率 (pctNone)
     *
     * @param m   道具材質
     * @param min 最小隨機數量
     * @param max 最大隨機數量
     * @return Map 條目
     */
    private static Map.Entry<Material, RewardDistribution> distRangeEntry(
            Material m, int min, int max) {
        return Map.entry(m, RewardDistribution.ofRange(min, max, new double[]{(double) 73, (double) 27}));
    }

    /**
     * 根據指定難度，在玩家腳邊放置寶箱並填入獎勵。
     * 放置後 5 分鐘自動移除寶箱（若區塊仍載入且方塊為 Chest）。
     *
     * @param player 執行領取的玩家
     * @param diff   玩家所選難度（HELL 或 JUDGMENT）
     */
    public void giveRewardChestBeside(Player player, VoidTrialChambersPlugin.TrialDifficulty diff) {
        UUID pid = player.getUniqueId();
        // 檢查當日領取次數
        if (!plugin.canClaim(pid)) {
            player.sendMessage("§c您今天的獎勵次數已達上限 (5 次)，請明日再領取。");
            return;
        }

        // 計算玩家面向側邊的放置位置
        Location loc = computeSideLocation(player);
        Block block = loc.getBlock();
        // 若目標方塊不是空氣，先嘗試破壞一次
        if (block.getType() != Material.AIR) {
            block.breakNaturally();
        }
        // 若仍被阻擋，回饋錯誤訊息
        if (block.getType() != Material.AIR) {
            player.sendMessage("§c無法在身旁生成寶箱：該格被阻擋。");
            return;
        }

        // 放置 Chest 方塊，並取得 Chest 實例
        block.setType(Material.CHEST);
        Chest chest = (Chest) block.getState();
        Inventory inv = chest.getInventory();

        // 填入「固定獎勵」
        FIXED_REWARDS.get(diff).forEach(inv::addItem);
        // 填入「機率獎勵」
        DISTRIBUTIONS.get(diff).forEach((mat, rd) -> {
            int cnt = rd.pickCount();
            if (cnt > 0) {
                inv.addItem(new ItemStack(mat, cnt));
            }
        });

        player.sendMessage("§a已在您腳旁生成【" +
                (diff == VoidTrialChambersPlugin.TrialDifficulty.HELL ? "地獄" : "吞夢噬念") +
                "難度】寶箱獎勵，請盡快領取！");
        // 記錄領取次數
        plugin.recordClaim(pid);
    }

    /**
     * 計算玩家正側邊方塊位置：根據玩家視線方向取垂直向量偏移。
     *
     * @param p 玩家物件
     * @return 玩家側邊方塊位置 (Location)
     */
    private Location computeSideLocation(Player p) {
        Vector d = p.getEyeLocation().getDirection().setY(0).normalize();
        // 水平面上，X/Z 互換並反向，得到側邊向量
        return p.getLocation().clone().add(d.getZ(), 0, -d.getX());
    }

    // ----------------------------------
    // 內部類別：封裝「固定分佈」與「範圍分佈」抽樣邏輯
    // ----------------------------------
    private static class RewardDistribution {
        private final int[] counts;        // 固定選項陣列
        private final double[] cumWeights; // 累積權重陣列
        private final boolean isRange;     // 是否為範圍分佈
        private final int rangeMin;        // 範圍最小值
        private final int rangeMax;        // 範圍最大值

        private RewardDistribution(int[] counts, double[] cumWeights,
                                   boolean isRange, int min, int max) {
            this.counts = counts;
            this.cumWeights = cumWeights;
            this.isRange = isRange;
            this.rangeMin = min;
            this.rangeMax = max;
        }

        /**
         * 建立「固定選項」分佈
         *
         * @param counts     各選項數量
         * @param weightsPct 與 counts 對應的百分比權重
         * @return RewardDistribution 實例
         */
        static RewardDistribution of(int[] counts, double[] weightsPct) {
            int n = counts.length;
            double cum = 0.0;
            double[] cw = new double[n];
            for (int i = 0; i < n; i++) {
                cum += weightsPct[i];
                cw[i] = cum;
            }
            return new RewardDistribution(counts, cw, false, 0, 0);
        }

        /**
         * 建立「範圍」分佈
         *
         * @param min     範圍最小值
         * @param max     範圍最大值
         * @param weights weights[0]=range% , weights[1]=none%
         * @return RewardDistribution 實例
         */
        static RewardDistribution ofRange(int min, int max, double[] weights) {
            // 用 counts[-] 表示範圍、counts[1]=0 表示不給予
            int[] cnts = new int[]{-1, 0};
            double cum = 0.0;
            double[] cw = new double[2];
            cum += weights[0];
            cw[0] = cum;
            cum += weights[1];
            cw[1] = cum;
            return new RewardDistribution(cnts, cw, true, min, max);
        }

        /**
         * 抽樣方法：以二分搜尋累積機率，
         * 若 isRange，第一段落中則回傳隨機[min,max]，否則回傳 0。
         *
         * @return 抽出的數量
         */
        int pickCount() {
            double total = cumWeights[cumWeights.length - 1];
            double r = ThreadLocalRandom.current().nextDouble() * total;
            int idx = Arrays.binarySearch(cumWeights, r);
            if (idx < 0) idx = -idx - 1;

            if (!isRange) {
                // 固定分佈：直接根據索引回傳 counts
                return idx < counts.length ? counts[idx] : 0;
            }
            // 範圍分佈：
            if (idx == 0) {
                // 首段命中 → 隨機回傳 [rangeMin, rangeMax]
                return ThreadLocalRandom.current().nextInt(rangeMin, rangeMax + 1);
            } else {
                // 次段命中 → 回傳 0
                return 0;
            }
        }
    }
}