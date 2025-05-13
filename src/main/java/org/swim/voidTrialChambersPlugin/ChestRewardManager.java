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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ChestRewardManager {
    private final VoidTrialChambersPlugin plugin;

    public ChestRewardManager(VoidTrialChambersPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 根據難度，在玩家身旁放置對應獎勵的寶箱。
     *
     * @param player 要給予獎勵的玩家
     * @param diff   試煉難度（HELL / JUDGMENT）
     */
    public void giveRewardChestBeside(Player player, VoidTrialChambersPlugin.TrialDifficulty diff) {
        UUID playerId = player.getUniqueId();
        // 每日最多 5 次領取
        if (!plugin.canClaim(playerId)) {
            player.sendMessage("§c您今天的獎勵次數已達上限 (5 次)，請明日再領取。");
            return;
        }

        // 計算放箱子位置（與原本相同）
        Location eyeLoc = player.getEyeLocation();
        Vector dir = eyeLoc.getDirection().setY(0).normalize();
        double dx = dir.getZ(), dz = -dir.getX();
        final Location chestLoc = player.getLocation().clone().add(dx, 0, dz);

        Block initialBlock = chestLoc.getBlock();
        if (initialBlock.getType() != Material.AIR) {
            initialBlock.breakNaturally();
        }
        if (initialBlock.getType() != Material.AIR) {
            player.sendMessage("§c無法在身旁生成寶箱：該格被阻擋。");
            return;
        }

        initialBlock.setType(Material.CHEST);
        if (!(initialBlock.getState() instanceof Chest chest)) {
            player.sendMessage("§c身旁放置寶箱失敗。");
            return;
        }

        // 根據難度選擇不同獎勵清單
        List<ItemStack> rewards = new ArrayList<>();
        if (diff == VoidTrialChambersPlugin.TrialDifficulty.HELL) {
            // 地獄 難度的標準獎勵
            rewards.add(new ItemStack(Material.DIAMOND, 3));
            rewards.add(new ItemStack(Material.GOLD_INGOT, 5));
            rewards.add(new ItemStack(Material.ENCHANTED_GOLDEN_APPLE, 1));
            rewards.add(new ItemStack(Material.TOTEM_OF_UNDYING, 1));
            rewards.add(new ItemStack(Material.EXPERIENCE_BOTTLE, 32));
            rewards.add(new ItemStack(Material.EMERALD, 16));
        } else {
            // 吞夢噬念 難度更高的獎勵
            rewards.add(new ItemStack(Material.DIAMOND, 5));
            rewards.add(new ItemStack(Material.NETHERITE_INGOT, 2));
            rewards.add(new ItemStack(Material.ENCHANTED_GOLDEN_APPLE, 2));
            rewards.add(new ItemStack(Material.TOTEM_OF_UNDYING, 2));
            rewards.add(new ItemStack(Material.EXPERIENCE_BOTTLE, 64));
            rewards.add(new ItemStack(Material.EMERALD, 32));
        }

        // 填入寶箱
        Inventory inv = chest.getInventory();
        inv.addItem(rewards.toArray(new ItemStack[0]));
        player.sendMessage("§a已在您腳旁生成 " +
                (diff == VoidTrialChambersPlugin.TrialDifficulty.HELL ? "【地獄難度】" : "【吞夢噬念難度】") +
                " 寶箱獎勵，請盡快領取！");
        plugin.recordClaim(playerId);

        // 5 分鐘後自動移除
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    World world = chestLoc.getWorld();
                    // 先確認該世界仍在伺服器已載入清單中，若已卸載則中止
                    if (Bukkit.getServer().getWorlds().stream().noneMatch(w -> w.getUID().equals(world.getUID()))) {
                        return;
                    }
                    int chunkX = chestLoc.getBlockX() >> 4;
                    int chunkZ = chestLoc.getBlockZ() >> 4;
                    if (!world.isChunkLoaded(chunkX, chunkZ)) {
                        return;
                    }

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
}