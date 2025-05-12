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
        // 每日最多 3 次領取
        if (!plugin.canClaim(playerId)) {
            player.sendMessage("§c您今天的獎勵次數已達上限 (3 次)，請明日再領取。");
            return;
        }
        // 取得玩家看向的水平方向向量
        Location eyeLoc = player.getEyeLocation();
        @NotNull Vector dir = eyeLoc.getDirection().setY(0).normalize();
        // 取垂直於方向向量的右手邊向量作為「身旁」位置
        double dx = dir.getZ();
        double dz = -dir.getX();
        // 算出寶箱位置：玩家腳底高度，加上偏移
        Location chestLoc = player.getLocation().clone().add(dx, 0, dz).subtract(0, 0, 0);
        Block block = chestLoc.getBlock();
        // 如果該位置不是空氣，先移除原方塊
        if (block.getType() != Material.AIR) {
            block.breakNaturally();  // 會掉落原方塊物品
        }
        // 如果該位置不是空氣，提示無法放置
        if (block.getType() != Material.AIR) {
            player.sendMessage("§c無法在身旁生成寶箱：該格被阻擋。");
            return;
        }

        // 放置寶箱方塊
        block.setType(Material.CHEST);
        if (!(block.getState() instanceof Chest chest)) {
            player.sendMessage("§c身旁放置寶箱失敗。");
            return;
        }

        // 填入獎勵物品
        Inventory inv = chest.getInventory();
        inv.addItem(
                new ItemStack(Material.DIAMOND, 3),
                new ItemStack(Material.GOLD_INGOT, 5),
                new ItemStack(Material.GOLDEN_APPLE, 2),
                new ItemStack(Material.EXPERIENCE_BOTTLE, 32),
                new ItemStack(Material.EMERALD, 16)
        );

        player.sendMessage("§a已在您腳旁生成寶箱獎勵，請盡快領取！");
        plugin.recordClaim(playerId);
        // 5 分鐘後自動移除該寶箱
        new BukkitRunnable() {
            @Override
            public void run() {
                if (block.getType() == Material.CHEST) {
                    block.setType(Material.AIR);
                }
            }
        }.runTaskLater(plugin, 5 * 60 * 20L);
    }
}