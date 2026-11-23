package com.worldplayers.compat.modulargolems;

import com.worldplayers.resident.ResidentEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;

/**
 * Modular Golems 单方面兼容（v0.2.1）。
 *
 * <h3>为什么是单方面</h3>
 * <p>主人要求：不需要 Modular Golems 的作者做任何改动，也不用等它更新。所以这里**不 import 对方任何类**，
 * 只按 **mod id（{@code modulargolems}）** 判定一个实体是不是傀儡 —— 对方以后新增的傀儡、换名字的傀儡
 * 只要还在自己的 mod 下注册，就自动被覆盖，不需要我们跟着改代码。</p>
 *
 * <h3>做到什么程度</h3>
 * <ul>
 *   <li><b>居民侧</b>：傀儡进 {@code isProtected} 名单 —— 不主动打、不还手、不参与横扫/被围判定、
 *       玩家打傀儡时居民也不会来"帮忙"（{@link com.worldplayers.resident.ai.ResidentCombat}）。</li>
 *   <li><b>傀儡侧</b>：傀儡的目标一旦指向居民立刻清掉，居民的目标一旦指向傀儡也清掉。
 *       玩家用指令强制傀儡打居民这类"硬指派"也会被清（Modular Golems 的 forcedTarget 每 tick 都会
 *       重新尝试锁定，我们每 tick 清一次，它就永远打不出来）。</li>
 *   <li><b>数据包</b>：已知的三种傀儡同时写进了 {@code data/worldplayers/tags/entity_type/protected.json}
 *       （{@code required: false}，没装 Modular Golems 时不会报错），方便以后按需扩展。</li>
 * </ul>
 *
 * <p>所有入口都先查 {@link #isLoaded()}：没装 Modular Golems 时除了一个静态布尔判定之外零开销，
 * 也不会加载/链接对方的任何类。</p>
 */
public final class ModularGolemsCompat {
    /** Modular Golems 的 mod id（固定值，不随版本变化）。 */
    public static final String MODID = "modulargolems";

    /** 延迟缓存：只在第一次用到时问一次 {@link ModList}。 */
    private static Boolean loaded;

    /** 装了 Modular Golems 吗（缓存，永不抛异常）。 */
    public static boolean isLoaded() {
        Boolean cached = loaded;
        if (cached == null) {
            boolean value;
            try {
                value = ModList.get().isLoaded(MODID);
            } catch (Throwable ignored) {
                // 早期加载阶段 ModList 还没就绪时按"没装"处理，下一 tick 再问一次
                value = false;
            }
            // 只在真正用到时问一次：此时 mod 列表早已就绪，装与没装都可以安全缓存
            loaded = value;
            cached = value;
        }
        return cached;
    }

    /**
     * 这个实体是不是 Modular Golems 的傀儡。
     *
     * <p>按**注册 id 的命名空间**判定 —— 对方所有当前与未来的傀儡都在这个命名空间下，
     * 不用维护实体清单，也不依赖对方的 API 版本。</p>
     */
    public static boolean isGolem(Entity entity) {
        if (entity == null) {
            return false;
        }
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return id != null && MODID.equals(id.getNamespace());
    }

    /**
     * 目标变更拦截（即时生效）：傀儡 ↔ 居民永远不把对方设成攻击目标。
     *
     * <p>这是主路径；{@link #onEntityTick(Mob)} 是兜底（防对方哪天绕过 {@code Mob#setTarget}）。</p>
     */
    public static void onLivingChangeTarget(LivingChangeTargetEvent event) {
        if (!isLoaded() || event.getNewAboutToBeSetTarget() == null) {
            return;
        }
        Entity changer = event.getEntity();
        Entity newTarget = event.getNewAboutToBeSetTarget();
        if (isGolem(changer) && newTarget instanceof ResidentEntity) {
            event.setCanceled(true);
        } else if (changer instanceof ResidentEntity && isGolem(newTarget)) {
            event.setCanceled(true);
        }
    }

    /** 每 tick 兜底：把任何方向上残留下来的"傀儡 ↔ 居民"目标清掉。 */
    public static void onEntityTick(Mob mob) {
        if (!isLoaded()) {
            return;
        }
        if (isGolem(mob)) {
            if (mob.getTarget() instanceof ResidentEntity) {
                mob.setTarget(null);
            }
            return;
        }
        if (mob instanceof ResidentEntity resident && isGolem(resident.getTarget())) {
            resident.setTarget(null);
        }
    }

    private ModularGolemsCompat() {
    }
}
