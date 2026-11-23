package com.worldplayers.registry;

import com.worldplayers.WorldPlayers;
import com.worldplayers.menu.ResidentMenu;
import com.worldplayers.resident.ResidentEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class WPMenus {
    public static final DeferredRegister<MenuType<?>> MENUS =
        DeferredRegister.create(Registries.MENU, WorldPlayers.MOD_ID);

    public static final DeferredHolder<MenuType<?>, MenuType<ResidentMenu>> RESIDENT = MENUS.register("resident",
        () -> IMenuTypeExtension.create((containerId, inventory, data) -> {
            Player player = inventory.player;
            int entityId = data.readVarInt();
            // 只读查看（手册成员列表点开）会多带一个标记
            boolean remote = data.isReadable() && data.readBoolean();
            net.minecraft.world.entity.Entity entity = player.level().getEntity(entityId);
            ResidentEntity resident = entity instanceof ResidentEntity found ? found : null;
            return new ResidentMenu(containerId, inventory, resident, remote);
        }));

    private WPMenus() {
    }
}
