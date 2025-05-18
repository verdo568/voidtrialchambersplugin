package org.swim.voidTrialChambersPlugin;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

/**
 * 在所有 trial_ 開頭的世界中：
 * 1) 禁止任何方塊破壞
 * 2) 禁止放置 TNT 方塊
 * 3) 防止 TNT 實體爆炸破壞方塊
 */
public class TrialWorldProtectionListener implements Listener {

    // 禁止破壞任何方塊
    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        World world = event.getBlock().getWorld();
        if (world.getName().startsWith("trial_")) {
            event.setCancelled(true);
            Player player = event.getPlayer();
        }
    }

    // 禁止放置 TNT 方塊
    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        World world = event.getBlockPlaced().getWorld();
        if (world.getName().startsWith("trial_")) {
            Block block = event.getBlockPlaced();
            if (block.getType() == org.bukkit.Material.TNT) {
                event.setCancelled(true);
            }
        }
    }

    // 防止 TNT 實體生成後的爆炸破壞方塊
    @EventHandler
    public void onEntityExplode(EntityExplodeEvent event) {
        World world = event.getLocation().getWorld();
        if (world != null && world.getName().startsWith("trial_")) {
            Entity entity = event.getEntity();
            if (entity instanceof TNTPrimed) {
                // 清空要破壞的方塊清單，保留爆炸效果
                event.blockList().clear();
            }
        }
    }
}