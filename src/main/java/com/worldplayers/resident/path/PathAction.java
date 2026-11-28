package com.worldplayers.resident.path;

/**
 * 路径点在居民走到它时要做的动作（路径网第一轮只存数据 + 渲染，第二轮才真的执行）。
 *
 * <p>顺序是固定的：客户端滚轮切换、服务端循环改动作都依赖这个顺序。</p>
 */
public enum PathAction {
    /** 走：普通地面/台阶点。 */
    WALK("walk"),
    /** 跳：一格高低差。 */
    JUMP("jump"),
    /** 爬梯：贴梯子上下。 */
    CLIMB("climb"),
    /** 传送：瞬移到下一个点（大落差/封闭楼层用）。 */
    TELEPORT("teleport");

    private final String id;

    PathAction(String id) {
        this.id = id;
    }

    /** 语言键后缀（{@code gui.worldplayers.path.action.<id>}）。 */
    public String id() {
        return this.id;
    }

    /** 按序号取，越界就回落到 WALK（旧存档/坏包不崩）。 */
    public static PathAction byOrdinal(int ordinal) {
        PathAction[] values = values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : WALK;
    }

    /** 按存档里的名字取，认不出来就回落到 WALK。 */
    public static PathAction byName(String name) {
        for (PathAction action : values()) {
            if (action.name().equals(name)) {
                return action;
            }
        }
        return WALK;
    }

    /** 切到下一个动作（滚轮 / 右键循环用）。 */
    public PathAction next() {
        return values()[(ordinal() + 1) % values().length];
    }
}
