package com.worldplayers.menu;

import com.worldplayers.registry.WPMenus;
import com.worldplayers.resident.ResidentEntity;
import com.worldplayers.resident.ResidentInventory;
import javax.annotation.Nullable;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.item.ItemStack;

/**
 * 居民背包菜单：上半居民（装备列 + 背包 + 快捷栏），下半玩家背包。
 *
 * <pre>
 *   居民容器：0 主手、1–8 快捷栏、9–35 主背包、36–39 头/胸/腿/脚、40 副手
 * </pre>
 */
public class ResidentMenu extends AbstractContainerMenu {
    public static final int SLOT = 18;
    public static final int IMAGE_WIDTH = 176;
    public static final int IMAGE_HEIGHT = 300;

    /** 槽位物品的左上角（= 纹理凹槽 + 1）。 */
    public static final int EQUIP_X = 9;
    public static final int EQUIP_Y = 21;
    public static final int OFFHAND_Y = 97;
    public static final int HOTBAR_X = 9;
    public static final int HOTBAR_Y = 191;
    public static final int BACKPACK_Y = 133;
    public static final int PLAYER_X = 9;
    public static final int PLAYER_Y = 219;
    public static final int PLAYER_HOTBAR_Y = 277;

    @Nullable
    private final ResidentEntity resident;
    private final Container residentContainer;
    /** 只读查看（从手册成员列表点开）：不加玩家背包槽、槽位不可编辑、不冻结居民 AI。 */
    private final boolean remote;

    public ResidentMenu(int containerId, Inventory playerInventory, @Nullable ResidentEntity resident) {
        this(containerId, playerInventory, resident, false);
    }

    public ResidentMenu(int containerId, Inventory playerInventory, @Nullable ResidentEntity resident,
                        boolean remote) {
        super(WPMenus.RESIDENT.get(), containerId);
        this.resident = resident;
        this.remote = remote;
        this.residentContainer = resident != null ? resident.inventory() : new SimpleContainer(ResidentInventory.SIZE);

        this.addResidentSlots();
        if (!remote) {
            this.addPlayerSlots(playerInventory);
        }

        if (!remote && resident != null
                && playerInventory.player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            resident.startViewing(serverPlayer);
        }
    }

    /** 是否只读查看模式（手册点开的那种）。 */
    public boolean remote() {
        return this.remote;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (!this.remote && this.resident != null
                && player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            this.resident.stopViewing(serverPlayer);
        }
    }

    private void addResidentSlots() {
        // 快捷栏（0 号 = 主手）
        for (int col = 0; col < 9; col++) {
            this.addSlot(this.plainSlot(col, HOTBAR_X + col * SLOT, HOTBAR_Y));
        }
        // 主背包 3 行
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(this.plainSlot(9 + row * 9 + col,
                    HOTBAR_X + col * SLOT, BACKPACK_Y + row * SLOT));
            }
        }
        // 盔甲 4 格
        EquipmentSlot[] armor = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
        };
        for (int i = 0; i < armor.length; i++) {
            this.addSlot(new EquipmentSlotLimit(this.residentContainer, ResidentInventory.ARMOR_START + i,
                armor[i], EQUIP_X, EQUIP_Y + i * SLOT, this.remote));
        }
        // 副手
        this.addSlot(new EquipmentSlotLimit(this.residentContainer, ResidentInventory.OFFHAND,
            EquipmentSlot.OFFHAND, EQUIP_X, OFFHAND_Y, this.remote));
    }

    /** 普通背包槽；只读模式下换成不可拿不可放的槽。 */
    private Slot plainSlot(int index, int x, int y) {
        if (this.remote) {
            return new ReadOnlySlot(this.residentContainer, index, x, y);
        }
        return new Slot(this.residentContainer, index, x, y);
    }

    private void addPlayerSlots(Inventory playerInventory) {
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9,
                    PLAYER_X + col * SLOT, PLAYER_Y + row * SLOT));
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInventory, col, PLAYER_X + col * SLOT, PLAYER_HOTBAR_Y));
        }
    }

    @Nullable
    public ResidentEntity resident() {
        return this.resident;
    }

    @Override
    public boolean stillValid(Player player) {
        if (this.resident == null || this.resident.isRemoved()) {
            return false;
        }
        // 只读查看是从手册点开的，允许隔远一点看
        if (this.remote) {
            return true;
        }
        return player.distanceToSqr(this.resident) <= 64.0D;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (this.remote) {
            return ItemStack.EMPTY;
        }
        Slot slot = this.slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        int residentSlots = ResidentInventory.SIZE;

        // 用副本搬运：原版 moveItemStackTo 在"源槽与目标槽是同一个 ItemStack 对象"时会死循环，
        // 而居民容器对装备槽返回的是 entity 那边的活引用，正好会踩这个坑（shift 放不死图腾直接卡死）。
        ItemStack work = stack.copy();
        boolean moved;
        if (index < residentSlots) {
            moved = this.moveItemStackTo(work, residentSlots, this.slots.size(), true);
        } else {
            // 先试装备槽（4 盔甲 + 副手），槽位自己会拒绝放不进去的东西；再试背包
            moved = this.moveItemStackTo(work, ResidentInventory.ARMOR_START, ResidentInventory.OFFHAND + 1, false);
            if (!moved || !work.isEmpty()) {
                moved |= this.moveItemStackTo(work, 0, ResidentInventory.BACKPACK_START + 27, false);
            }
        }
        if (!moved) {
            return ItemStack.EMPTY;
        }
        if (work.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setByPlayer(work);
        }
        slot.setChanged();
        return original;
    }

    /** 只读槽：看得见、拿不走、放不进。 */
    private static final class ReadOnlySlot extends Slot {
        ReadOnlySlot(Container container, int index, int x, int y) {
            super(container, index, x, y);
        }

        @Override
        public boolean mayPickup(Player player) {
            return false;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }
    }

    /** 只接受能放进该装备槽的物品。 */
    private static final class EquipmentSlotLimit extends Slot {
        private final EquipmentSlot equipmentSlot;
        private final boolean readOnly;

        EquipmentSlotLimit(Container container, int index, EquipmentSlot equipmentSlot, int x, int y,
                           boolean readOnly) {
            super(container, index, x, y);
            this.equipmentSlot = equipmentSlot;
            this.readOnly = readOnly;
        }

        @Override
        public boolean mayPickup(Player player) {
            return !this.readOnly;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            if (this.readOnly) {
                return false;
            }
            // 副手像玩家一样什么都能放；盔甲槽只收对应部位
            if (this.equipmentSlot == EquipmentSlot.OFFHAND) {
                return true;
            }
            Equipable equipable = Equipable.get(stack);
            return equipable != null && equipable.getEquipmentSlot() == this.equipmentSlot;
        }

        @Override
        public int getMaxStackSize() {
            // 副手按物品自己的堆叠上限（原版玩家副手也是这么算的）
            return this.equipmentSlot == EquipmentSlot.OFFHAND ? 64 : 1;
        }

        @Override
        public int getMaxStackSize(ItemStack stack) {
            if (this.equipmentSlot == EquipmentSlot.OFFHAND) {
                return Math.max(1, stack.getMaxStackSize());
            }
            return 1;
        }
    }
}
