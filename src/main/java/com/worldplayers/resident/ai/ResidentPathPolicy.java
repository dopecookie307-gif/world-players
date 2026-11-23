package com.worldplayers.resident.ai;

import net.minecraft.world.level.pathfinder.NodeEvaluator;

/**
 * 居民的寻路策略开关（可被未来的「超大型寻路系统」统一调用）。
 *
 * <p>现在只有一个开关：**愿不愿意通过门 / 栅栏门**。以前 {@code ResidentPathNavigation}
 * 把 {@code setCanOpenDoors(false)} 写死了，门在寻路里就变成障碍，居民永远到不了门后的房间。
 * 现在默认允许穿门（关着的门也算可走），配合 {@code ResidentDoors} 自动开关门。</p>
 */
public final class ResidentPathPolicy {
    /** 是否愿意通过 / 打开门。默认 true。 */
    private static boolean passDoors = true;

    public static void setPassDoors(boolean value) {
        passDoors = value;
    }

    public static boolean passDoors() {
        return passDoors;
    }

    /** 把当前策略套到节点评估器上。未来换寻路实现时也只需要调用这一句。 */
    public static void apply(NodeEvaluator evaluator) {
        evaluator.setCanPassDoors(passDoors);
        evaluator.setCanOpenDoors(passDoors);
    }

    private ResidentPathPolicy() {
    }
}
