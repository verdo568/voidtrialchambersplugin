package org.swim.voidTrialChambersPlugin;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * ChestRewardManager
 * 提供給玩家一個包含預設物品的寶箱獎勵，可以選擇放在腳下、面前或左右兩側。
 */
public class ChestRewardManager {
    private final VoidTrialChambersPlugin plugin;

    public ChestRewardManager(VoidTrialChambersPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 在玩家面前（身旁）放置寶箱並填入獎勵。
     * 寶箱位置會比玩家脚底水平偏移一格，保持與玩家同一高度。
     *
     * @param player 要給予獎勵的玩家
     */
    public void giveRewardChestBeside(Player player) {
        UUID playerId = player.getUniqueId();
        // 每日最多 5 次領取
        if (!plugin.canClaim(playerId)) {
            player.sendMessage("§c您今天的獎勵次數已達上限 (5 次)，請明日再領取。");
            return;
        }

        // 取得玩家看向的水平方向向量
        Location eyeLoc = player.getEyeLocation();
        @NotNull Vector dir = eyeLoc.getDirection().setY(0).normalize();
        // 取垂直於方向向量的右手邊向量作為「身旁」位置
        double dx = dir.getZ();
        double dz = -dir.getX();
        // 算出寶箱位置：玩家腳底高度，加上偏移
        final Location chestLoc = player.getLocation().clone().add(dx, 0, dz);

        // 先移除原方塊（若存在）
        Block initialBlock = chestLoc.getBlock();
        if (initialBlock.getType() != Material.AIR) {
            initialBlock.breakNaturally();
        }
        // 再次確認空格是否已經清乾淨
        if (initialBlock.getType() != Material.AIR) {
            player.sendMessage("§c無法在身旁生成寶箱：該格被阻擋。");
            return;
        }

        // 放置寶箱
        initialBlock.setType(Material.CHEST);
        if (!(initialBlock.getState() instanceof Chest chest)) {
            player.sendMessage("§c身旁放置寶箱失敗。");
            return;
        }

        // 填入獎勵物品
        Inventory inv = chest.getInventory();
        inv.addItem(
                new ItemStack(Material.DIAMOND, 3),
                new ItemStack(Material.GOLD_INGOT, 5),
                new ItemStack(Material.ENCHANTED_GOLDEN_APPLE, 1),
                new ItemStack(Material.TOTEM_OF_UNDYING, 1),
                new ItemStack(Material.EXPERIENCE_BOTTLE, 32),
                new ItemStack(Material.EMERALD, 16)
        );

        player.sendMessage("§a已在您腳旁生成寶箱獎勵，請盡快領取！");
        plugin.recordClaim(playerId);

        // 5 分鐘後自動移除該寶箱
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    // 先確認 chunk 依然載入，避免在世界關閉時強制載入而拋例外
                    int cx = chestLoc.getBlockX() >> 4;
                    int cz = chestLoc.getBlockZ() >> 4;
                    if (!chestLoc.getWorld().isChunkLoaded(cx, cz)) {
                        return;
                    }
                    Block b = chestLoc.getBlock();
                    if (b.getType() == Material.CHEST) {
                        b.setType(Material.AIR);
                    }
                } catch (IllegalStateException ex) {
                    // chunk system 已關閉，直接跳過
                    plugin.getLogger().warning("移除延遲寶箱時跳過，chunk 系統已關閉: " + ex.getMessage());
                }
            }
        }.runTaskLater(plugin, 5 * 60 * 20L);
    }
}