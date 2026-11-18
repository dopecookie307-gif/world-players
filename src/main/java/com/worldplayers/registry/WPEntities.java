package com.worldplayers.registry;

import com.worldplayers.WorldPlayers;
import com.worldplayers.resident.ResidentEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class WPEntities {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
        DeferredRegister.create(Registries.ENTITY_TYPE, WorldPlayers.MOD_ID);

    /** 居民：与玩家共享 0.6 × 1.8 的碰撞箱与玩家模型渲染。 */
    public static final DeferredHolder<EntityType<?>, EntityType<ResidentEntity>> RESIDENT =
        ENTITY_TYPES.register("resident", () -> EntityType.Builder
            .of(ResidentEntity::new, MobCategory.CREATURE)
            .sized(0.6F, 1.8F)
            .clientTrackingRange(10)
            .build("resident"));

    private WPEntities() {
    }
}
