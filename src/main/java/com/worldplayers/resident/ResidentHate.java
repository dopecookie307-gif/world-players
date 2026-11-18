package com.worldplayers.resident;

import com.worldplayers.WorldPlayers;
import java.lang.reflect.Field;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Enemy;

/**
 * 敌对生物对居民的仇恨。
 *
 * <p>MC 里「会主动打玩家」的怪，靠的是写死 {@code Player} 类型的
 * {@code NearestAttackableTargetGoal<Player>}，没法把居民塞进那个判定。这里反过来做：
 * 给每个敌对生物（{@code Enemy} 接口——原版怪和绝大多数模组怪都实现它）补一个
 * {@code NearestAttackableTargetGoal<ResidentEntity>}，优先级固定 <b>2</b>——
 * 和僵尸 / 骷髅锁定玩家用的是同一档，所以居民和玩家是同一优先级。</p>
 *
 * <p>幂等：同一只怪重复 attach 不会重复加 goal。{@code targetSelector} 是 protected，
 * 用反射拿一次（MC 跑在 unnamed module 上，setAccessible 没问题）。</p>
 */
public final class ResidentHate {
    private static Field targetSelectorField;

    /** 给这只怪补上居民目标（已经有就跳过；中立 / 绝对不打名单里的直接跳过）。 */
    public static void attach(Mob mob) {
        // 中立生物（末影人、僵尸猪灵、狼…以及数据包 tag 里登记的模组中立生物）不主动仇恨居民；
        // 铁傀儡这类"绝对不打"的也一律跳过。它们只会在被惹之后通过自己的 HurtByTargetGoal 还手。
        if (com.worldplayers.resident.ai.ResidentCombat.isNeutral(mob)
                || com.worldplayers.resident.ai.ResidentCombat.isProtected(mob)) {
            return;
        }
        try {
            GoalSelector selector = selector(mob);
            for (WrappedGoal wrapped : selector.getAvailableGoals()) {
                if (wrapped.getGoal() instanceof ResidentHateGoal) {
                    return;
                }
            }
            selector.addGoal(2, new ResidentHateGoal(mob));
        } catch (ReflectiveOperationException e) {
            WorldPlayers.LOGGER.warn("World Players: 给 {} 添加居民仇恨目标失败", mob.getType(), e);
        }
    }

    private static GoalSelector selector(Mob mob) throws ReflectiveOperationException {
        if (targetSelectorField == null) {
            Field field = Mob.class.getDeclaredField("targetSelector");
            field.setAccessible(true);
            targetSelectorField = field;
        }
        return (GoalSelector) targetSelectorField.get(mob);
    }

    /** 和打玩家同款：看得见就能锁定，不要求能走到（远程怪也有效）。 */
    private static final class ResidentHateGoal extends NearestAttackableTargetGoal<ResidentEntity> {
        private ResidentHateGoal(Mob mob) {
            super(mob, ResidentEntity.class, true);
        }
    }

    private ResidentHate() {
    }
}
