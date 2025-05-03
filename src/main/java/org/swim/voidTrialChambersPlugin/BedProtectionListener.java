package org.swim.voidTrialChambersPlugin;

import org.bukkit.World;
import org.bukkit.block.Bed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

public class BedProtectionListener implements Listener {

    // 只在 trial_ 開頭的世界中禁止放置床
    @EventHandler
    public void onBedPlace(BlockPlaceEvent event) {
        World world = event.getBlockPlaced().getWorld();
        if (world.getName().startsWith("trial_")
                && event.getBlockPlaced().getState() instanceof Bed) {
            event.setCancelled(true);
        }
    }

    // 只在 trial_ 開頭的世界中禁止破壞床
    @EventHandler
    public void onBedBreak(BlockBreakEvent event) {
        String worldName = event.getBlock().getWorld().getName();
        if (worldName.startsWith("trial_")
                && event.getBlock().getState() instanceof Bed) {
            event.setCancelled(true);
        }
    }

    // 禁止活塞推動床
    @EventHandler
    public void onPistonExtend(BlockPistonExtendEvent event) {
        String worldName = event.getBlock().getWorld().getName();
        if (worldName.startsWith("trial_") && event.getBlocks().stream().anyMatch(b -> b.getState() instanceof Bed)) {
            event.setCancelled(true);
        }
    }

    // 禁止活塞縮回時推動床
    @EventHandler
    public void onPistonRetract(BlockPistonRetractEvent event) {
        String worldName = event.getBlock().getWorld().getName();
        if (worldName.startsWith("trial_") && event.getBlocks().stream().anyMatch(b -> b.getState() instanceof Bed)) {
            event.setCancelled(true);
        }
    }

    // 禁止 TNT 或其他爆炸摧毀床
    @EventHandler
    public void onEntityExplode(EntityExplodeEvent event) {
        if (event.getLocation().getWorld().getName().startsWith("trial_")) {
            event.blockList().removeIf(b ->
                    b.getState() instanceof Bed
            );
        }
    }
}