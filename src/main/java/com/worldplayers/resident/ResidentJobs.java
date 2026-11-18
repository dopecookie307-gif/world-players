package com.worldplayers.resident;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.network.chat.Component;

/**
 * 工作状态的中央登记表。
 *
 * <p>UI（浮窗 / Jade / 以后的其他地方）**都不再写死文案**，只问这里要：
 * 新增一种工作状态 = {@code ResidentJobs.register("mining", "job.worldplayers.mining")} + 语言文件加一条，
 * 不用碰 {@code ResidentNameplate} 和 {@code ResidentInfoProvider}。</p>
 *
 * <p>以后如果工作状态要跟着殖民地/日程系统走，只要那边的注册入口调 {@code register} 就行。</p>
 */
public final class ResidentJobs {
    public static final String IDLE = "idle";
    public static final String FOLLOW = "follow";
    public static final String STAND = "stand";

    /** key 是状态 id（进 NBT），value 是语言键。 */
    private static final Map<String, String> REGISTRY = new LinkedHashMap<>();

    static {
        register(IDLE, "job.worldplayers.idle");
        register(FOLLOW, "job.worldplayers.follow");
        register(STAND, "job.worldplayers.stand");
    }

    /** 登记一种工作状态（重复登记会覆盖语言键，方便别的系统把同名状态接到自己的文案上）。 */
    public static void register(String id, String translationKey) {
        REGISTRY.put(id, translationKey);
    }

    /** 状态 id → 可直接显示的文字。没登记的 id 回退成 {@code job.worldplayers.<id>}。 */
    public static Component text(String id) {
        String key = id == null ? null : REGISTRY.get(id);
        if (key == null) {
            key = "job.worldplayers." + (id == null || id.isBlank() ? IDLE : id);
        }
        return Component.translatable(key);
    }

    private ResidentJobs() {
    }
}
