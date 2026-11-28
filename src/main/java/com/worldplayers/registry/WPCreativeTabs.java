package com.worldplayers.registry;

import com.worldplayers.WorldPlayers;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * World Players 独立的创造模式物品栏（草案 12.5）。
 *
 * <p>排序尽量靠前：挂在原版「建筑方块」之前，打开创造物品栏第一栏就是它。</p>
 */
public final class WPCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> TABS =
        DeferredRegister.create(Registries.CREATIVE_MODE_TAB, WorldPlayers.MOD_ID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN = TABS.register("main",
        () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.worldplayers.main"))
            .icon(() -> new ItemStack(WPItems.PATH_BRUSH.get()))
            .withTabsBefore(CreativeModeTabs.BUILDING_BLOCKS)
            .displayItems((parameters, output) -> {
                output.accept(WPItems.RESIDENT_SPAWN_EGG.get());
                output.accept(WPItems.RESIDENT_MANUAL.get());
                output.accept(WPItems.RESIDENT_WAND.get());
                output.accept(WPItems.PATH_BRUSH.get());
            })
            .build());

    private WPCreativeTabs() {
    }
}
