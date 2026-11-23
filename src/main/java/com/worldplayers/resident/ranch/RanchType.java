package com.worldplayers.resident.ranch;

import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Animal;

/**
 * 牧场能养的动物种类（主人 2026-11-23 定）：**只限原版友善动物**——牛 / 绵羊 / 猪 / 鸡。
 *
 * <p>id 进存档与同步包，名称直接复用原版实体名（{@code entity.minecraft.cow}），
 * 所以下拉里「只有名字、没有图标」这件事天然满足，也不用多写两份语言文件。</p>
 */
public enum RanchType {
    COW("cow"),
    SHEEP("sheep"),
    PIG("pig"),
    CHICKEN("chicken");

    /** 旧存档 / 非法 id 的回退值。 */
    public static final RanchType DEFAULT = COW;
    /** 下拉里的顺序（界面用）。 */
    private static final List<RanchType> SELECTABLE = List.of(COW, SHEEP, PIG, CHICKEN);

    private final String id;

    RanchType(String id) {
        this.id = id;
    }

    public String id() {
        return this.id;
    }

    /** 这个种类能不能剪毛（目前只有绵羊）。 */
    public boolean canShear() {
        return this == SHEEP;
    }

    /** 实体类型是否属于这一档（比较注册名，避免泛型擦除后的强转）。 */
    public boolean matches(Entity entity) {
        return entity != null && this.matches(entity.getType());
    }

    /** 是不是这一档的动物（类型 + 活着的 Animal 判断交给调用方）。 */
    public boolean matches(EntityType<?> type) {
        if (type == null) {
            return false;
        }
        net.minecraft.resources.ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        return key != null && key.getPath().equals(this.id);
    }

    /** 下拉里显示的名字（原版实体名：牛 / 绵羊 / 猪 / 鸡）。 */
    public Component displayName() {
        return Component.translatable("entity.minecraft." + this.id);
    }

    /** 按 id 取（空 / 未知 → 牛）。 */
    public static RanchType byId(String id) {
        if (id != null) {
            for (RanchType type : SELECTABLE) {
                if (type.id.equals(id)) {
                    return type;
                }
            }
        }
        return DEFAULT;
    }

    /** 下拉可选项（顺序固定）。 */
    public static List<RanchType> selectable() {
        return SELECTABLE;
    }

    /**
     * 这个实体算不算本牧场的动物。
     *
     * <p>用 {@code entity instanceof Animal} + 注册名比较：兼容模组出的变种实体
     * （它们注册名不同，按类型区分是对的），也不会把僵尸之类误判进来。</p>
     */
    public boolean isRanchAnimal(Entity entity) {
        return entity instanceof Animal && this.matches(entity);
    }
}
