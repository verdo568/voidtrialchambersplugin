package org.swim.voidTrialChambersPlugin;

import org.bukkit.World;
import org.bukkit.block.Bed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

public class BedProtectionListener implements Listener {

    // 僅在世界名稱以 "trial_" 開頭時，禁止玩家放置床鋪
    @EventHandler
    public void onBedPlace(BlockPlaceEvent event) {
        World world = event.getBlockPlaced().getWorld();
        if (world.getName().startsWith("trial_") && event.getBlockPlaced().getState() instanceof Bed) {
            event.setCancelled(true);
        }
    }

    // 僅在世界名稱以 "trial_" 開頭時，禁止玩家破壞床鋪
    @EventHandler
    public void onBedBreak(BlockBreakEvent event) {
        String worldName = event.getBlock().getWorld().getName();
        if (worldName.startsWith("trial_") && event.getBlock().getState() instanceof Bed) {
            event.setCancelled(true);
        }
    }

    // 禁止活塞在延伸時推動床鋪
    @EventHandler
    public void onPistonExtend(BlockPistonExtendEvent event) {
        String worldName = event.getBlock().getWorld().getName();
        if (worldName.startsWith("trial_") && event.getBlocks().stream().anyMatch(b -> b.getState() instanceof Bed)) {
            event.setCancelled(true);
        }
    }

    // 禁止活塞在收縮時推動床鋪
    @EventHandler
    public void onPistonRetract(BlockPistonRetractEvent event) {
        String worldName = event.getBlock().getWorld().getName();
        if (worldName.startsWith("trial_") && event.getBlocks().stream().anyMatch(b -> b.getState() instanceof Bed)) {
            event.setCancelled(true);
        }
    }

    // 在世界名稱以 "trial_" 開頭時，移除爆炸對床鋪造成的任何破壞
    @EventHandler
    public void onEntityExplode(EntityExplodeEvent event) {
        if (event.getLocation().getWorld().getName().startsWith("trial_")) {
            event.blockList().removeIf(b ->
                    b.getState() instanceof Bed
            );
        }
    }
}