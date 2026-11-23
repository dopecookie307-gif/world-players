package com.worldplayers.resident;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * 所有已储存的工作区域（存在主世界的存档数据里）。
 *
 * <p>草稿（正在画的区域）**不在这里** —— 那是客户端的临时状态，退出世界就没了。</p>
 *
 * <p>从 v0.2.12-alpha.5 起**允许同名**：区域之间互不影响，定位/删除/改名都用
 * {@link WorkZone#key()}（名字 + 第一选择点）。</p>
 */
public class WorkZoneData extends SavedData {
    private static final String DATA_NAME = "worldplayers_work_zones";
    private static final String TAG_ZONES = "Zones";

    private final List<WorkZone> zones = new ArrayList<>();

    public WorkZoneData() {
    }

    public static WorkZoneData get(ServerLevel anyLevel) {
        ServerLevel overworld = anyLevel.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(WorkZoneData::new, WorkZoneData::load), DATA_NAME);
    }

    public List<WorkZone> zones() {
        return List.copyOf(this.zones);
    }

    /**
     * 加一个区域：**同名不同位置 → 各自独立追加**；完全同一个区域（名字 + 第一选择点都相同）→ 覆盖。
     */
    public boolean add(WorkZone zone) {
        for (int i = 0; i < this.zones.size(); i++) {
            if (this.zones.get(i).key().equals(zone.key())) {
                this.zones.set(i, zone);
                this.setDirty();
                return false;
            }
        }
        this.zones.add(zone);
        this.setDirty();
        return true;
    }

    /** 按唯一键找区域（找不到返回 null）。 */
    public WorkZone findByKey(String key) {
        for (WorkZone zone : this.zones) {
            if (zone.key().equals(key)) {
                return zone;
            }
        }
        return null;
    }

    /** 这个居民在哪个工作区当员工（找不到返回 null）。 */
    @Nullable
    public WorkZone findByEmployee(String residentId) {
        if (residentId == null || residentId.isEmpty()) {
            return null;
        }
        for (WorkZone zone : this.zones) {
            if (zone.hasEmployee(residentId)) {
                return zone;
            }
        }
        return null;
    }

    /**
     * 把某个居民从**所有其它区域**的员工栏里拿掉（2026-11-22）：
     * <ul>
     *   <li>工作区选人：后选的区域**抢走并独占**员工；</li>
     *   <li>居民死亡：自动从员工栏里清理，避免「幽灵员工 / 浮窗没头像」。</li>
     * </ul>
     *
     * @param keepZoneKey 保留的区域（可为 null = 所有区域都清）
     * @return 有没有真的改动
     */
    public boolean detachFromOtherZones(String residentId, @Nullable String keepZoneKey) {
        if (residentId == null || residentId.isEmpty()) {
            return false;
        }
        boolean changed = false;
        for (int i = 0; i < this.zones.size(); i++) {
            WorkZone zone = this.zones.get(i);
            if (keepZoneKey != null && keepZoneKey.equals(zone.key())) {
                continue;
            }
            if (!zone.hasEmployee(residentId)) {
                continue;
            }
            List<String> list = new ArrayList<>(zone.employees());
            list.replaceAll(id -> residentId.equals(id) ? "" : id);
            this.zones.set(i, zone.withEmployees(list));
            changed = true;
        }
        if (changed) {
            this.setDirty();
        }
        return changed;
    }

    /** 这个位置是不是在**禁行区域**里（寻路 / 游荡都要绕开）。 */
    public boolean isForbidden(BlockPos pos) {
        for (WorkZone zone : this.zones) {
            if (WorkZone.TYPE_FORBIDDEN.equals(zone.type()) && zone.containsBlock(pos)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 这个位置是不是在**挖掘 / 填充工程区**里（规格 5.7：工程区内禁止居民站立）。
     *
     * <p>不看工程是否在运行：暂停期间居民也进不去（防止员工站进区域里）。</p>
     */
    public boolean isProjectArea(BlockPos pos) {
        for (WorkZone zone : this.zones) {
            if (zone.isProject() && zone.containsBlock(pos)) {
                return true;
            }
        }
        return false;
    }

    /** 这个位置是不是在某个**道路**区域里（通勤时偏爱走这里）。 */
    public boolean isOnRoad(BlockPos pos) {
        for (WorkZone zone : this.zones) {
            if (WorkZone.TYPE_ROAD.equals(zone.type()) && zone.containsBlock(pos)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 这个居民能不能动**这个方块上的栅栏门**（2026-11-23 主人新规则）。
     *
     * <p>门在某个有员工栏的工作区域里 → **只有该区域的员工**能打开 / 关闭；
     * 门不在任何工作区域里（或者只落在道路 / 禁行 / 娱乐区）→ 谁都能动。
     * 一扇门同时压在多个工作区域上时，只要居民是其中任意一个的员工就放行。</p>
     */
    public boolean canOperateWorkGate(BlockPos pos, String residentId) {
        boolean inWorkArea = false;
        for (WorkZone zone : this.zones) {
            if (!zone.hasEmployeeSlots() || !zone.containsBlock(pos)) {
                continue;
            }
            inWorkArea = true;
            if (zone.hasEmployee(residentId)) {
                return true;
            }
        }
        return !inWorkArea;
    }

    /**
     * 这个方块在哪个**牧场区域**里（2026-11-23：屠宰 / 剪毛时的「牧场工作动物」判定用）。
     */
    @Nullable
    public WorkZone findRanchAt(BlockPos pos) {
        for (WorkZone zone : this.zones) {
            if (zone.isRanch() && zone.containsBlock(pos)) {
                return zone;
            }
        }
        return null;
    }

    /**
     * 这个方块在哪个**有员工栏的工作区域**里（2026-11-23 栅栏门新规则用）。
     *
     * <p>道路 / 禁行 / 娱乐没有员工，不属于「工作区域」的门禁范围，直接跳过。</p>
     */
    @Nullable
    public WorkZone findWorkAreaAt(BlockPos pos) {
        for (WorkZone zone : this.zones) {
            if (zone.hasEmployeeSlots() && zone.containsBlock(pos)) {
                return zone;
            }
        }
        return null;
    }

    /** 所有非禁行区域的中心点（游荡范围限制用）。 */
    public java.util.List<BlockPos> anchorCenters() {
        java.util.List<BlockPos> centers = new ArrayList<>();
        for (WorkZone zone : this.zones) {
            if (!WorkZone.TYPE_FORBIDDEN.equals(zone.type())) {
                centers.add(BlockPos.containing(zone.box().getCenter()));
            }
        }
        return centers;
    }

    /** 按唯一键找区域（找不到返回 null）。 */
    public WorkZone find(String name, net.minecraft.core.BlockPos origin) {
        String key = name + "@" + origin.asLong();
        for (WorkZone zone : this.zones) {
            if (zone.key().equals(key)) {
                return zone;
            }
        }
        return null;
    }

    /** 按唯一键删除（同名但不同位置的区域不受影响）。 */
    public boolean remove(String name, net.minecraft.core.BlockPos origin) {
        String key = name + "@" + origin.asLong();
        boolean removed = this.zones.removeIf(zone -> zone.key().equals(key));
        if (removed) {
            this.setDirty();
        }
        return removed;
    }

    public static WorkZoneData load(CompoundTag tag, HolderLookup.Provider registries) {
        // 读档 = 换世界了：树场的槽位/树清单缓存、工程队列缓存一律作废（静态缓存跨存档会串数据）
        com.worldplayers.resident.tree.TreeFarmIndex.clearAll();
        com.worldplayers.resident.project.ProjectQueue.clearAll();
        // 牧场循环同理（v0.3.0）：静态缓存跨存档会串阶段
        com.worldplayers.resident.ranch.RanchCycle.clearAll();
        // 渔场钓点缓存同理（v0.3.0）
        com.worldplayers.resident.fishery.FisheryCache.clearAll();
        WorkZoneData data = new WorkZoneData();
        ListTag list = tag.getList(TAG_ZONES, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            net.minecraft.core.BlockPos min = net.minecraft.core.BlockPos.of(entry.getLong("Min"));
            // 旧存档没有 Origin：退化成 min 角（不崩、数据不丢）
            net.minecraft.core.BlockPos origin = entry.contains("Origin", Tag.TAG_LONG)
                ? net.minecraft.core.BlockPos.of(entry.getLong("Origin")) : min;
            // 员工列表：新存档读 Employees；旧存档读单个 Resident 字符串（退化成只有一个员工）
            List<String> employees = new ArrayList<>();
            ListTag staff = entry.getList("Employees", Tag.TAG_STRING);
            for (int j = 0; j < staff.size(); j++) {
                employees.add(staff.getString(j));
            }
            if (employees.isEmpty() && entry.contains("Resident", Tag.TAG_STRING)) {
                String legacy = entry.getString("Resident");
                if (!legacy.isBlank()) {
                    employees.add(legacy);
                }
            }
            data.zones.add(new WorkZone(
                entry.getString("Name"),
                entry.getString("Type"),
                min,
                net.minecraft.core.BlockPos.of(entry.getLong("Max")),
                origin,
                employees,
                // 树场设定（2026-11-21）：旧存档没有这三个字段 → 默认「无 / 单株 / 自动」
                entry.getString("SaplingType"),
                entry.contains("PlantMode", Tag.TAG_INT) ? entry.getInt("PlantMode") : WorkZone.PLANT_SINGLE,
                entry.getString("PlacementMode"),
                // 工程字段（v0.2.15）：旧存档没有 → 空方块 / 没在跑 / 进度 0
                entry.getString("ProjectBlock"),
                entry.contains("ProjectRunning", Tag.TAG_BYTE) && entry.getBoolean("ProjectRunning"),
                entry.contains("ProjectDone", Tag.TAG_INT) ? entry.getInt("ProjectDone") : 0,
                // 简介（v0.2.17）：旧存档没有 → 空
                entry.getString("Description"),
                // 牧场设定（v0.3.0）：旧存档没有 → 牛 / 8 只 / 不剪毛
                entry.getString("AnimalType"),
                entry.contains("AnimalMax", Tag.TAG_INT)
                    ? entry.getInt("AnimalMax") : WorkZone.ANIMAL_DEFAULT,
                entry.contains("AnimalShear", Tag.TAG_BYTE) && entry.getBoolean("AnimalShear")));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (WorkZone zone : this.zones) {
            CompoundTag entry = new CompoundTag();
            entry.putString("Name", zone.name());
            entry.putString("Type", zone.type());
            entry.putLong("Min", zone.min().asLong());
            entry.putLong("Max", zone.max().asLong());
            entry.putLong("Origin", zone.origin().asLong());
            ListTag staff = new ListTag();
            for (String id : zone.employees()) {
                staff.add(net.minecraft.nbt.StringTag.valueOf(id == null ? "" : id));
            }
            entry.put("Employees", staff);
            // 树场设定（2026-11-21）
            entry.putString("SaplingType", zone.saplingType());
            entry.putInt("PlantMode", zone.plantMode());
            entry.putString("PlacementMode", zone.placementMode());
            // 工程字段（v0.2.15）
            entry.putString("ProjectBlock", zone.projectBlock());
            entry.putBoolean("ProjectRunning", zone.projectRunning());
            entry.putInt("ProjectDone", zone.projectDone());
            // 简介（v0.2.17，只对娱乐区有意义，但所有类型都存一份）
            entry.putString("Description", zone.description());
            // 牧场设定（v0.3.0，只对牧场有意义，但所有类型都存一份）
            entry.putString("AnimalType", zone.animalType());
            entry.putInt("AnimalMax", zone.animalMax());
            entry.putBoolean("AnimalShear", zone.animalShear());
            list.add(entry);
        }
        tag.put(TAG_ZONES, list);
        return tag;
    }
}
