package com.worldplayers.resident;

import javax.annotation.Nullable;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 居民的物品栏视图：41 个槽位，全部都是**真实**存储 —— 主手与 4 个盔甲槽 + 副手直接映射到实体的
 * {@link EquipmentSlot}，所以穿上装备就是真的穿上（音效、附魔、渲染、耐久损耗全部走原版通道）。
 *
 * <pre>
 *   0        主手（EquipmentSlot.MAINHAND）
 *   1..8     快捷栏其余 8 格
 *   9..35    主背包 27 格
 *   36..39   头盔 / 胸甲 / 护腿 / 靴子
 *   40       副手
 * </pre>
 *
 * 不再保留"背包第 0 格镜像到手"的副本方案 —— 那份副本正是"穿上胸甲后主手还显示胸甲"的病根。
 */
public class ResidentInventory implements Container {
    public static final int MAIN_HAND = 0;
    public static final int HOTBAR_START = 1;
    public static final int BACKPACK_START = 9;
    public static final int ARMOR_START = 36;
    public static final int ARMOR_COUNT = 4;
    public static final int OFFHAND = 40;
    public static final int SIZE = 41;

    /** 背包本体（槽位 1..35 有效；0 号留空不用，避免与主手混淆）。 */
    private static final int STORAGE_SIZE = 36;

    private static final EquipmentSlot[] ARMOR_SLOTS = {
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    private final ResidentEntity resident;
    private final ItemStack[] storage = new ItemStack[STORAGE_SIZE];

    public ResidentInventory(ResidentEntity resident) {
        this.resident = resident;
        java.util.Arrays.fill(this.storage, ItemStack.EMPTY);
    }

    // ------------------------------------------------------------------
    // 槽位映射
    // ------------------------------------------------------------------

    @Nullable
    private EquipmentSlot equipmentSlotOf(int slot) {
        if (slot == MAIN_HAND) {
            return EquipmentSlot.MAINHAND;
        }
        if (slot == OFFHAND) {
            return EquipmentSlot.OFFHAND;
        }
        if (slot >= ARMOR_START && slot < ARMOR_START + ARMOR_COUNT) {
            return ARMOR_SLOTS[slot - ARMOR_START];
        }
        return null;
    }

    public static boolean isEquipmentSlot(int slot) {
        return slot == MAIN_HAND || slot == OFFHAND
            || (slot >= ARMOR_START && slot < ARMOR_START + ARMOR_COUNT);
    }

    /**
     * 能不能当"背包里的东西"看待（主手 + 快捷栏 + 主背包 + **副手**）。
     *
     * <p>副手也算：主人要求"把副手也当成背包的一部分"，否则放在副手的盔甲/武器永远选不上。</p>
     */
    public static boolean isStorageSlot(int slot) {
        if (slot == OFFHAND) {
            return true;
        }
        return slot >= 0 && slot < BACKPACK_START + 27;
    }

    @Override
    public int getContainerSize() {
        return SIZE;
    }

    @Override
    public boolean isEmpty() {
        for (int i = 0; i < SIZE; i++) {
            if (!this.getItem(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        if (slot < 0 || slot >= SIZE) {
            return ItemStack.EMPTY;
        }
        EquipmentSlot equipment = this.equipmentSlotOf(slot);
        if (equipment != null) {
            return this.resident.getItemBySlot(equipment);
        }
        return this.storage[slot];
    }

    @Override
    public ItemStack removeItem(int slot, int count) {
        ItemStack current = this.getItem(slot);
        if (current.isEmpty() || count <= 0) {
            return ItemStack.EMPTY;
        }
        ItemStack taken = current.split(count);
        if (current.isEmpty()) {
            this.setItem(slot, ItemStack.EMPTY);
        } else {
            this.setItem(slot, current);
        }
        return taken;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        ItemStack current = this.getItem(slot);
        if (current.isEmpty()) {
            return ItemStack.EMPTY;
        }
        this.setItem(slot, ItemStack.EMPTY);
        return current;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot < 0 || slot >= SIZE) {
            return;
        }
        EquipmentSlot equipment = this.equipmentSlotOf(slot);
        if (equipment != null) {
            // setItemSlot 会触发原版 onEquipItem：装备音效、GameEvent.EQUIP、渲染同步全都在这条路上
            this.resident.setItemSlot(equipment, stack);
        } else {
            this.storage[slot] = stack;
        }
        this.setChanged();
    }

    @Override
    public void setChanged() {
        this.resident.inventoryChanged();
    }

    @Override
    public boolean stillValid(Player player) {
        return !this.resident.isRemoved() && player.distanceToSqr(this.resident) <= 64.0D;
    }

    @Override
    public void clearContent() {
        for (int i = 0; i < SIZE; i++) {
            this.setItem(i, ItemStack.EMPTY);
        }
    }

    // ------------------------------------------------------------------
    // 拾取用：只在 0..35 里装东西，不碰装备槽
    // ------------------------------------------------------------------

    /** 拾取时的插入顺序：主手 → 快捷栏 → 主背包；先合并可堆叠的，再找空位。 */
    public ItemStack addItem(ItemStack offered) {
        if (offered.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack rest = offered.copy();
        for (int slot = 0; slot < BACKPACK_START + 27 && !rest.isEmpty(); slot++) {
            ItemStack existing = this.getItem(slot);
            if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, rest)) {
                continue;
            }
            int space = Math.min(existing.getMaxStackSize(), rest.getMaxStackSize()) - existing.getCount();
            if (space <= 0) {
                continue;
            }
            int moved = Math.min(space, rest.getCount());
            existing.grow(moved);
            rest.shrink(moved);
            this.setChanged();
        }
        for (int slot = 0; slot < BACKPACK_START + 27 && !rest.isEmpty(); slot++) {
            if (!this.getItem(slot).isEmpty()) {
                continue;
            }
            this.setItem(slot, rest.copy());
            rest = ItemStack.EMPTY;
        }
        return rest;
    }

    /** 能不能塞下这件物品（用于"背包满了就别绕路去捡"的判断）。 */
    public boolean canAccept(ItemStack offered) {
        if (offered.isEmpty()) {
            return false;
        }
        for (int slot = 0; slot < BACKPACK_START + 27; slot++) {
            ItemStack existing = this.getItem(slot);
            if (existing.isEmpty()) {
                return true;
            }
            if (ItemStack.isSameItemSameComponents(existing, offered)
                    && existing.getCount() < existing.getMaxStackSize()) {
                return true;
            }
        }
        return false;
    }

    /** 把背包里能穿的装备穿到对应槽位（只补空槽）。 */
    public void autoEquip() {
        for (int slot = 0; slot < BACKPACK_START + 27; slot++) {
            ItemStack stack = this.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            EquipmentSlot target = equipmentSlotFor(stack);
            if (target == null) {
                continue;
            }
            if (!this.resident.getItemBySlot(target).isEmpty()) {
                continue;
            }
            this.setItem(slot, ItemStack.EMPTY);
            this.resident.setItemSlot(target, stack);
        }
    }

    /**
     * 从背包里挑**最好**的装备穿上：空槽补上、身上差的换掉（旧的放回背包）。
     *
     * <p>评分走原版属性表（{@code ARMOR} + 一半 {@code ARMOR_TOUGHNESS}），
     * 所以模组护甲只要正常注册属性也能比。0 分的（南瓜头之类）不穿。</p>
     */
    public void equipBestGear() {
        for (int i = 0; i < ARMOR_COUNT; i++) {
            int slot = ARMOR_START + i;
            EquipmentSlot target = ARMOR_SLOTS[i];
            ItemStack current = this.getItem(slot);
            double bestScore = current.isEmpty() ? 0.0D : gearScore(current);
            int bestSlot = -1;
            for (int candidate = 0; candidate < SIZE; candidate++) {
                if (candidate == MAIN_HAND || candidate == slot || !isStorageSlot(candidate)) {
                    continue;
                }
                ItemStack stack = this.getItem(candidate);
                if (stack.isEmpty() || equipmentSlotFor(stack) != target) {
                    continue;
                }
                double score = gearScore(stack);
                if (score > bestScore) {
                    bestScore = score;
                    bestSlot = candidate;
                }
            }
            if (bestSlot < 0) {
                continue;
            }
            ItemStack better = this.getItem(bestSlot);
            ItemStack old = current;
            this.setItem(bestSlot, old);
            this.resident.setItemSlot(target, better);
        }
    }

    /** 装备分数：护甲值 + 韧性的一半（够区分铁/钻石/下界合金，也认得模组属性）。 */
    private static double gearScore(ItemStack stack) {
        double armor = 0.0D;
        double toughness = 0.0D;
        for (var entry : stack.getAttributeModifiers().modifiers()) {
            if (entry.attribute().value() == net.minecraft.world.entity.ai.attributes.Attributes.ARMOR.value()) {
                armor += entry.modifier().amount();
            } else if (entry.attribute().value()
                    == net.minecraft.world.entity.ai.attributes.Attributes.ARMOR_TOUGHNESS.value()) {
                toughness += entry.modifier().amount();
            }
        }
        return armor + toughness * 0.5D;
    }

    @Nullable
    private static EquipmentSlot equipmentSlotFor(ItemStack stack) {
        net.minecraft.world.item.Equipable equipable = net.minecraft.world.item.Equipable.get(stack);
        return equipable == null ? null : equipable.getEquipmentSlot();
    }

    /** 死亡掉落：按槽位散出去。 */
    public void dropAll(net.minecraft.world.level.Level level) {
        for (int slot = 0; slot < SIZE; slot++) {
            ItemStack stack = this.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            this.resident.spawnAtLocation(stack.copy());
            this.setItem(slot, ItemStack.EMPTY);
        }
    }
}
