package org.swim.voidTrialChambersPlugin;

import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;

public class WardenTargetFilter implements Listener {

    @EventHandler
    public void onWardenTarget(EntityTargetLivingEntityEvent event) {
        // 僅處理 Warden 的目標事件
        if (event.getEntityType() != EntityType.WARDEN) return;

        // 若目標為 null（例如遺忘目標事件），則直接返回
        if (event.getTarget() == null) return;

        // 僅在世界名稱以 "trial_" 開頭時啟用此邏輯
        String worldName = event.getEntity().getWorld().getName();
        if (!worldName.startsWith("trial_")) return;

        // 若目標不是玩家，則取消此事件
        if (!(event.getTarget() instanceof Player)) {
            event.setCancelled(true);
        }
    }
}