package com.worldplayers.resident.ai;

import com.worldplayers.resident.ResidentEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Creeper;

/**
 * 危险物识别（v0.2.3）：哪些东西该躲、哪些永远不许打、哪些必须保持距离打。
 *
 * <h3>规则（主人 2026-11-19 定）</h3>
 * <ul>
 *   <li><b>点燃的 TNT</b>（{@link PrimedTnt}，引信在走）—— 所有居民都躲。</li>
 *   <li><b>被玩家用打火石/火焰弹点燃的苦力怕</b>（{@link Creeper#isIgnited()}）—— 「必定爆炸」，
 *       所有居民都躲，**谁都不打**。注意：这是原版单独的 ignited 标记，普通苦力怕被玩家靠近时
 *       只是 swell（{@code swellDir}）不会设这个标记，所以不会误伤"正常战斗"。</li>
 *   <li><b>闪电苦力怕</b>（{@link Creeper#isPowered()}）—— 所有居民躲；**只有弓弩手会打**
 *       （背包里有弓/弩 + 弹药），而且必须保持距离，不许贴脸砍。</li>
 *   <li>普通苦力怕 —— 按原来的战斗逻辑处理。</li>
 * </ul>
 */
public final class ResidentDanger {
    /** 判定"弓弩手"：背包里有能用的远程武器（弓/弩 + 匹配弹药）。 */
    public static boolean isRangedShooter(ResidentEntity resident) {
        return ResidentCombat.bestRangedSlot(resident.inventory()) >= 0;
    }

    /**
     * 该不该躲（{@link ResidentDangerFleeGoal} 用）。
     *
     * <p>TNT 只要引信还在走就算；苦力怕看 ignited / powered 两个原版标记。</p>
     */
    public static boolean isFleeDanger(ResidentEntity resident, Entity entity) {
        if (entity instanceof PrimedTnt tnt) {
            return tnt.getFuse() > 0;
        }
        if (entity instanceof Creeper creeper) {
            // 玩家点着的：必定爆炸，谁也别犹豫
            if (creeper.isIgnited()) {
                return true;
            }
            // 闪电苦力怕：近战居民躲，弓弩手留下打
            if (creeper.isPowered()) {
                return !isRangedShooter(resident);
            }
        }
        return false;
    }

    /**
     * 永远不许当攻击目标（{@code ResidentCombat.findTarget} / {@code threatsWithin} 用）。
     *
     * <p>比 {@link #isFleeDanger} 严格一点：ignited 的苦力怕对**任何人**都不选；
     * 闪电苦力怕只对非弓弩手不选。</p>
     */
    public static boolean isNeverTarget(ResidentEntity resident, Entity entity) {
        if (entity instanceof Creeper creeper) {
            return creeper.isIgnited() || (creeper.isPowered() && !isRangedShooter(resident));
        }
        return false;
    }

    /**
     * 必须保持距离打：闪电苦力怕（未被点燃时）对弓弩手也禁止切近战。
     *
     * <p>近战居民根本不会选中它（见 {@link #isNeverTarget}），所以这里只影响弓弩手的交战方式。</p>
     */
    public static boolean mustKeepDistance(Entity target) {
        return target instanceof Creeper creeper && creeper.isPowered() && !creeper.isIgnited();
    }

    private ResidentDanger() {
    }
}
