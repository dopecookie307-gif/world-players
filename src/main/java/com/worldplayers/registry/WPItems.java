package com.worldplayers.registry;

import com.worldplayers.WorldPlayers;
import com.worldplayers.item.ResidentManualItem;
import com.worldplayers.item.ResidentWandItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.common.DeferredSpawnEggItem;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class WPItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(WorldPlayers.MOD_ID);

    /** 「可喂给居民」的物品标签：数据包里加物品就能扩展（金苹果 / 附魔金苹果默认在里面）。 */
    public static final TagKey<Item> FEEDABLE_RESIDENT =
        TagKey.create(Registries.ITEM, WorldPlayers.id("feedable_resident"));

    public static final DeferredItem<Item> RESIDENT_SPAWN_EGG = ITEMS.register("resident_spawn_egg",
        () -> new DeferredSpawnEggItem(WPEntities.RESIDENT, 0x6E4B3A, 0xE8C9A0, new Item.Properties()));

    /** 居民手册：手持并看向居民时出现浮窗。 */
    public static final DeferredItem<Item> RESIDENT_MANUAL = ITEMS.register("resident_manual",
        () -> new ResidentManualItem(new Item.Properties().stacksTo(1)));

    /** 居民调试棒：左键选居民、右键选目的地，驱使其通勤（临时调试工具）。 */
    public static final DeferredItem<Item> RESIDENT_WAND = ITEMS.register("resident_wand",
        () -> new ResidentWandItem(new Item.Properties().stacksTo(1)));

    /**
     * 路径笔（路径网第一轮，2026-11-24）：画锚点 / 路径点，手持时渲染 32 格内的路网。
     *
     * <p>临时用**木棍**的模型（草案 12.2），以后换专属贴图。</p>
     */
    public static final DeferredItem<Item> PATH_BRUSH = ITEMS.register("path_brush",
        () -> new com.worldplayers.item.PathBrushItem(new Item.Properties().stacksTo(1)));

    private WPItems() {
    }
}
