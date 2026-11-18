package com.worldplayers.registry;

import com.worldplayers.WorldPlayers;
import com.worldplayers.item.ResidentManualItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.common.DeferredSpawnEggItem;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class WPItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(WorldPlayers.MOD_ID);

    public static final DeferredItem<Item> RESIDENT_SPAWN_EGG = ITEMS.register("resident_spawn_egg",
        () -> new DeferredSpawnEggItem(WPEntities.RESIDENT, 0x6E4B3A, 0xE8C9A0, new Item.Properties()));

    /** 居民手册：手持并看向居民时出现浮窗。 */
    public static final DeferredItem<Item> RESIDENT_MANUAL = ITEMS.register("resident_manual",
        () -> new ResidentManualItem(new Item.Properties().stacksTo(1)));

    private WPItems() {
    }
}
