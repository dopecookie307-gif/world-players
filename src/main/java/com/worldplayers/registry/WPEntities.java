package com.worldplayers.registry;

import com.worldplayers.WorldPlayers;
import com.worldplayers.resident.ResidentEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class WPEntities {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
        DeferredRegister.create(Registries.ENTITY_TYPE, WorldPlayers.MOD_ID);

    /**
     * 居民：与玩家共享 0.6 × 1.8 的碰撞箱与玩家模型渲染。
     *
     * <p>{@code vehicleAttachment(0, 0.6, 0)} = 和玩家完全一致的骑乘座点
     * （玩家是 1.8 身高 / 3）。**必须写在实体类型上**：{@code Entity} 构造时直接取
     * {@code EntityType#getDimensions()} 的尺寸作为 {@code this.dimensions}，
     * 不会调用实体的 {@code getDefaultDimensions} 覆写 —— 只在那边改的话，成人居民
     * 坐船/矿车/马还是浮高 0.6 格（小孩因为换模型时会 {@code refreshDimensions} 才恰好正常）。</p>
     */
    public static final DeferredHolder<EntityType<?>, EntityType<ResidentEntity>> RESIDENT =
        ENTITY_TYPES.register("resident", () -> EntityType.Builder
            .of(ResidentEntity::new, MobCategory.CREATURE)
            .sized(0.6F, 1.8F)
            .vehicleAttachment(new Vec3(0.0D, 0.6D, 0.0D))
            .clientTrackingRange(10)
            .build("resident"));

    private WPEntities() {
    }
}
