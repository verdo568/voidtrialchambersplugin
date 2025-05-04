package org.swim.voidTrialChambersPlugin;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;

public class SignPlaceListener implements Listener {
    @EventHandler
    public void onSignPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        String worldName = block.getWorld().getName();

        // 只在試煉世界中攔截
        if (!worldName.startsWith("trial_")) return;

        Material type = block.getType();
        // 只檢查所有以 _SIGN 或 _HANGING_SIGN 結尾的物件類型
        boolean isSign = type.name().endsWith("_SIGN")
                || type.name().endsWith("_HANGING_SIGN");

        if (isSign) {
            event.setCancelled(true);
        }
    }
}