package org.swim.voidTrialChambersPlugin;

import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;

public class WardenTargetFilter implements Listener {

    @EventHandler
    public void onWardenTarget(EntityTargetLivingEntityEvent event) {
        // 只处理 Warden 的目标事件
        if (event.getEntityType() != EntityType.WARDEN) return;

        // 如果目标为 null（比如遗忘目标事件），直接返回
        if (event.getTarget() == null) return;

        // 只在世界名以 "trail_" 开头时才生效
        String worldName = event.getEntity().getWorld().getName();
        if (!worldName.startsWith("trail_")) return;

        // 如果目标不是玩家，就取消这个事件
        if (!(event.getTarget() instanceof Player)) {
            event.setCancelled(true);
        }
    }
}
