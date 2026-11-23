package com.worldplayers.resident;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * 一个工作区域（v0.2.12 起）：两个对角点 + 名字 + 类型 + 第一选择点 + 员工列表 + 各类型的设定。
 *
 * <p>{@code origin} 是玩家**第一次左键**选的那个方块（不一定等于 min 角）：
 * 聊天提示、清单里的坐标栏、高亮都用它来当唯一标识的一部分（主人 2026-11-20 起**允许同名**，
 * 所以「名字 + 第一选择点」才是唯一键）。</p>
 *
 * <p>{@code employees} 是绑定的员工（居民 UUID 字符串）。空槽位用空字符串占位，
 * 最多 {@link #MAX_EMPLOYEES} 个栏位（主人 2026-11-20 起从「一个绑定居民」升级成「员工栏」）。</p>
 *
 * <p>v0.2.15 起新增工程字段（挖掘 / 填充，规格见 12 号文件）：</p>
 * <ul>
 *   <li>{@code projectBlock}：填充方块 id（空字符串 = 未选），挖掘不用；</li>
 *   <li>{@code projectRunning}：工程是否运行中（勾 / 叉）；</li>
 *   <li>{@code projectDone}：已完成格数（只存不算，实时进度另有同步包）。</li>
 * </ul>
 *
 * <p>v0.3.0 起新增牧场设定（只有牧场区用）：{@code animalType}（牛 / 绵羊 / 猪 / 鸡）、
 * {@code animalMax}（2~64，默认 8）、{@code animalShear}（只对绵羊有效的剪毛勾选）。</p>
 */
public record WorkZone(String name, String type, BlockPos min, BlockPos max, BlockPos origin,
                       List<String> employees,
                       String saplingType, int plantMode, String placementMode,
                       String projectBlock, boolean projectRunning, int projectDone,
                       String description,
                       String animalType, int animalMax, boolean animalShear) {
    public static final String TYPE_ROAD = "road";
    public static final String TYPE_FARM = "farm";
    /** 树场（2026-11-21）：工作内容 = 砍树 + 种树，规则见 10 号文件。 */
    public static final String TYPE_TREE_FARM = "tree_farm";
    /** 牧场（2026-11-21）：同上，先登记类型。 */
    public static final String TYPE_RANCH = "ranch";
    /** 渔场（2026-11-21）：同上，先登记类型。 */
    public static final String TYPE_FISHERY = "fishery";
    /**
     * 娱乐区域（2026-11-22，14 号文档第 1 包）：居民自由时间会来这里恢复心情。
     * 没有员工栏，只有一段玩家写的简介。
     */
    public static final String TYPE_ENTERTAINMENT = "entertainment";
    /** 挖掘工程（2026-11-22）：把区域内的方块挖空。 */
    public static final String TYPE_MINE = "mine";
    /** 填充工程（2026-11-22）：用指定方块把区域填满。 */
    public static final String TYPE_FILL = "fill";
    /** 禁行区域（主人 2026-11-20）：居民通勤 / 游荡都会绕开。 */
    public static final String TYPE_FORBIDDEN = "forbidden";
    /** 一个工作区最多几个员工栏。 */
    public static final int MAX_EMPLOYEES = 5;
    /** 娱乐区简介的字符上限（14 号文档 8.3：防同步包 / 存档膨胀）。 */
    public static final int MAX_DESCRIPTION = 500;
    /** 树场：没选树种（默认）——员工只在区域内踱步。 */
    public static final String TREE_NONE = "none";
    /** 树场种植模式：单株。 */
    public static final int PLANT_SINGLE = 1;
    /** 树场种植模式：2×2。 */
    public static final int PLANT_2X2 = 2;
    /** 树场种植位置：自动（我们算槽位）。 */
    public static final String PLACE_AUTO = "auto";
    /** 树场种植位置：手动（泥土定点）。 */
    public static final String PLACE_MANUAL = "manual";
    /** 牧场动物数量下限（主人 2026-11-23 定：可输入 2~64）。 */
    public static final int ANIMAL_MIN = 2;
    /** 牧场动物数量上限。 */
    public static final int ANIMAL_MAX = 64;
    /** 牧场没填数量时的默认值。 */
    public static final int ANIMAL_DEFAULT = 8;

    public WorkZone {
        employees = employees == null ? List.of() : List.copyOf(employees);
        saplingType = saplingType == null || saplingType.isBlank() ? TREE_NONE : saplingType;
        plantMode = plantMode == PLANT_2X2 ? PLANT_2X2 : PLANT_SINGLE;
        placementMode = PLACE_MANUAL.equals(placementMode) ? PLACE_MANUAL : PLACE_AUTO;
        // 工程字段归一化（旧存档 / 旧构造调用都落在这里）
        projectBlock = projectBlock == null ? "" : projectBlock.strip();
        projectDone = Math.max(0, projectDone);
        // 简介（v0.2.17）：空值兜底 + 500 字上限（客户端已限制，这里再兜一层）
        description = description == null ? "" : description.strip();
        if (description.length() > MAX_DESCRIPTION) {
            description = description.substring(0, MAX_DESCRIPTION);
        }
        // 牧场设定（v0.3.0）：类型非法回退牛，数量夹在 2~64
        animalType = com.worldplayers.resident.ranch.RanchType.byId(animalType).id();
        animalMax = Math.max(ANIMAL_MIN, Math.min(ANIMAL_MAX, animalMax));
    }

    /** 兼容旧调用：没有员工、所有设定默认（树场「无 / 单株 / 自动」、工程空、简介空、牧场默认）。 */
    public static WorkZone of(String name, String type, BlockPos min, BlockPos max, BlockPos origin) {
        return new WorkZone(name, type, min, max, origin, List.of(),
            TREE_NONE, PLANT_SINGLE, PLACE_AUTO, "", false, 0, "",
            com.worldplayers.resident.ranch.RanchType.DEFAULT.id(), ANIMAL_DEFAULT, false);
    }

    /** 兼容 13 参数调用（v0.2.17：带简介、没有牧场设定）。 */
    public WorkZone(String name, String type, BlockPos min, BlockPos max, BlockPos origin,
                    List<String> employees,
                    String saplingType, int plantMode, String placementMode,
                    String projectBlock, boolean projectRunning, int projectDone,
                    String description) {
        this(name, type, min, max, origin, employees, saplingType, plantMode, placementMode,
            projectBlock, projectRunning, projectDone, description,
            com.worldplayers.resident.ranch.RanchType.DEFAULT.id(), ANIMAL_DEFAULT, false);
    }

    /** 兼容 12 参数调用（v0.2.15~v0.2.16：带工程字段、没有简介）。 */
    public WorkZone(String name, String type, BlockPos min, BlockPos max, BlockPos origin,
                    List<String> employees,
                    String saplingType, int plantMode, String placementMode,
                    String projectBlock, boolean projectRunning, int projectDone) {
        this(name, type, min, max, origin, employees, saplingType, plantMode, placementMode,
            projectBlock, projectRunning, projectDone, "");
    }

    /** 兼容旧构造调用（2026-11-21 之前的 6 参数版本）：设定全默认。 */
    public WorkZone(String name, String type, BlockPos min, BlockPos max, BlockPos origin,
                    List<String> employees) {
        this(name, type, min, max, origin, employees, TREE_NONE, PLANT_SINGLE, PLACE_AUTO,
            "", false, 0, "");
    }

    /** 兼容 9 参数调用（v0.2.14 之前：带树场设定、没有工程字段）。 */
    public WorkZone(String name, String type, BlockPos min, BlockPos max, BlockPos origin,
                    List<String> employees, String saplingType, int plantMode,
                    String placementMode) {
        this(name, type, min, max, origin, employees, saplingType, plantMode, placementMode,
            "", false, 0, "");
    }

    public WorkZone withEmployees(List<String> list) {
        return new WorkZone(this.name, this.type, this.min, this.max, this.origin, list,
            this.saplingType, this.plantMode, this.placementMode,
            this.projectBlock, this.projectRunning, this.projectDone, this.description,
            this.animalType, this.animalMax, this.animalShear);
    }

    /** 改名后的副本（员工 / 范围 / 第一选择点都不变）。 */
    public WorkZone withName(String newName) {
        return new WorkZone(newName, this.type, this.min, this.max, this.origin, this.employees,
            this.saplingType, this.plantMode, this.placementMode,
            this.projectBlock, this.projectRunning, this.projectDone, this.description,
            this.animalType, this.animalMax, this.animalShear);
    }

    /** 简介改过的新副本（娱乐区 2026-11-22）。 */
    public WorkZone withDescription(String text) {
        return new WorkZone(this.name, this.type, this.min, this.max, this.origin, this.employees,
            this.saplingType, this.plantMode, this.placementMode,
            this.projectBlock, this.projectRunning, this.projectDone, text,
            this.animalType, this.animalMax, this.animalShear);
    }

    /** 牧场设定（动物类型 / 最大数量 / 剪毛）改过的新副本（v0.3.0）。 */
    public WorkZone withRanch(String animalType, int animalMax, boolean shear) {
        return new WorkZone(this.name, this.type, this.min, this.max, this.origin, this.employees,
            this.saplingType, this.plantMode, this.placementMode,
            this.projectBlock, this.projectRunning, this.projectDone, this.description,
            animalType, animalMax, shear);
    }

    /** 树场设定（树种 / 种植模式 / 种植位置）改过的新副本。 */
    public WorkZone withTreeSettings(String species, int mode, String placement) {
        return new WorkZone(this.name, this.type, this.min, this.max, this.origin, this.employees,
            species, mode, placement, this.projectBlock, this.projectRunning, this.projectDone,
            this.description, this.animalType, this.animalMax, this.animalShear);
    }

    /** 工程字段（方块 / 是否运行中）改过的新副本。 */
    public WorkZone withProject(String block, boolean running) {
        return new WorkZone(this.name, this.type, this.min, this.max, this.origin, this.employees,
            this.saplingType, this.plantMode, this.placementMode, block, running, this.projectDone,
            this.description, this.animalType, this.animalMax, this.animalShear);
    }

    /** 进度改过的新副本（只存不算）。 */
    public WorkZone withProjectDone(int done) {
        return new WorkZone(this.name, this.type, this.min, this.max, this.origin, this.employees,
            this.saplingType, this.plantMode, this.placementMode,
            this.projectBlock, this.projectRunning, done, this.description,
            this.animalType, this.animalMax, this.animalShear);
    }

    /** 是不是树场（工作内容是砍树 / 种树）。 */
    public boolean isTreeFarm() {
        return TYPE_TREE_FARM.equals(this.type);
    }

    /** 是不是牧场（v0.3.0：剪毛 / 繁殖 / 屠宰）。 */
    public boolean isRanch() {
        return TYPE_RANCH.equals(this.type);
    }

    /**
     * 是不是渔场（v0.3.0，规则见 `纸上谈兵\15-渔场规则.md`）：
     * 框里只标「居民站哪」，水在框外 4.5 格内找。
     */
    public boolean isFishery() {
        return TYPE_FISHERY.equals(this.type);
    }

    /**
     * 这个区域能有几个员工栏（主人 2026-11-23 定：**渔场只允许 1 名员工、只有 1 个格子**）。
     *
     * <p>UI 画格子数、服务端整流员工列表都用这一个口径，别再各写一套。</p>
     */
    public int employeeSlots() {
        return this.isFishery() ? 1 : MAX_EMPLOYEES;
    }

    /** 牧场养的动物种类（旧存档 / 非法 id → 牛）。 */
    public com.worldplayers.resident.ranch.RanchType ranchType() {
        return com.worldplayers.resident.ranch.RanchType.byId(this.animalType);
    }

    /** 是不是娱乐区域（2026-11-22：自由时间来这儿恢复心情）。 */
    public boolean isEntertainment() {
        return TYPE_ENTERTAINMENT.equals(this.type);
    }

    /** 是不是挖掘工程。 */
    public boolean isMine() {
        return TYPE_MINE.equals(this.type);
    }

    /** 是不是填充工程。 */
    public boolean isFill() {
        return TYPE_FILL.equals(this.type);
    }

    /** 是不是工程型区域（挖掘 / 填充）：员工站在区域外干活。 */
    public boolean isProject() {
        return this.isMine() || this.isFill();
    }

    /** 树场选中的树种（非树场 / 旧存档 → 「无」）。 */
    public com.worldplayers.resident.tree.TreeSpecies species() {
        return com.worldplayers.resident.tree.TreeSpecies.byId(this.saplingType);
    }

    /**
     * 是不是 2×2 种植（2026-11-22 起**按树种自动**，玩家不用再选）：
     * 深色橡木 / 丛林 = 2×2，其余单株。旧存档里的 {@code plantMode} 字段只保留兼容，不再参与判定。
     */
    public boolean isTwoByTwo() {
        return this.species().needsTwoByTwo();
    }

    /** 按树种算出来的种植模式（写入存档字段用，让旧字段与新行为一致）。 */
    public static int autoPlantMode(String saplingType) {
        return com.worldplayers.resident.tree.TreeSpecies.byId(saplingType).needsTwoByTwo()
            ? PLANT_2X2 : PLANT_SINGLE;
    }

    /** 是不是「玩家自己规划」的手动种植位置。 */
    public boolean isManualPlacement() {
        return PLACE_MANUAL.equals(this.placementMode);
    }

    /**
     * 树场设定的「签名」：任何一项变了都要重建槽位表、清扫不是新树种的树苗（规则 4）。
     */
    public String treeSignature() {
        return this.saplingType + "|" + this.plantMode + "|" + this.placementMode;
    }

    /** 工程的「签名」：方块 / 体积变了都要重建工程队列（规格 2.4）。 */
    public String projectSignature() {
        return this.projectBlock + "|" + this.volume();
    }

    /** 工程总格数（= 区域体积）。 */
    public long projectTotal() {
        return this.volume();
    }

    /**
     * 手写编解码：字段多（12 个），不走 `StreamCodec.composite` 的重载，免得撞上限。
     * 客户端 / 服务端永远同版本，直接按顺序读写。
     */
    public static final StreamCodec<FriendlyByteBuf, WorkZone> STREAM_CODEC = StreamCodec.of(
        (buf, zone) -> {
            buf.writeUtf(zone.name());
            buf.writeUtf(zone.type());
            BlockPos.STREAM_CODEC.encode(buf, zone.min());
            BlockPos.STREAM_CODEC.encode(buf, zone.max());
            BlockPos.STREAM_CODEC.encode(buf, zone.origin());
            ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.STRING_UTF8)
                .encode(buf, new ArrayList<>(zone.employees()));
            buf.writeUtf(zone.saplingType());
            buf.writeVarInt(zone.plantMode());
            buf.writeUtf(zone.placementMode());
            // v0.2.15：工程字段
            buf.writeUtf(zone.projectBlock());
            buf.writeBoolean(zone.projectRunning());
            buf.writeVarInt(zone.projectDone());
            // v0.2.17：娱乐区简介
            buf.writeUtf(zone.description(), MAX_DESCRIPTION);
            // v0.3.0：牧场设定
            buf.writeUtf(zone.animalType());
            buf.writeVarInt(zone.animalMax());
            buf.writeBoolean(zone.animalShear());
        },
        buf -> {
            String name = buf.readUtf();
            String type = buf.readUtf();
            BlockPos min = BlockPos.STREAM_CODEC.decode(buf);
            BlockPos max = BlockPos.STREAM_CODEC.decode(buf);
            BlockPos origin = BlockPos.STREAM_CODEC.decode(buf);
            List<String> employees = ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.STRING_UTF8)
                .decode(buf);
            String species = buf.readUtf();
            int mode = buf.readVarInt();
            String placement = buf.readUtf();
            String projectBlock = buf.readUtf();
            boolean projectRunning = buf.readBoolean();
            int projectDone = buf.readVarInt();
            String description = buf.readUtf(MAX_DESCRIPTION);
            String animalType = buf.readUtf();
            int animalMax = buf.readVarInt();
            boolean animalShear = buf.readBoolean();
            return new WorkZone(name, type, min, max, origin, employees, species, mode, placement,
                projectBlock, projectRunning, projectDone, description,
                animalType, animalMax, animalShear);
        });

    /** 唯一键：同名区域靠「名字 + 第一选择点」区分（删除/改名/高亮都按它）。 */
    public String key() {
        return this.name + "@" + this.origin().asLong();
    }

    /** 这个居民是不是这里的员工。 */
    public boolean hasEmployee(String residentId) {
        return residentId != null && !residentId.isEmpty() && this.employees.contains(residentId);
    }

    /** 员工列表里第一个非空槽位（浮窗头像用）；没有返回空字符串。 */
    public String firstEmployee() {
        for (String id : this.employees) {
            if (id != null && !id.isEmpty()) {
                return id;
            }
        }
        return "";
    }

    /** 区域体积（格数）。 */
    public long volume() {
        return (long) (max.getX() - min.getX() + 1)
            * (max.getY() - min.getY() + 1)
            * (max.getZ() - min.getZ() + 1);
    }

    /** 画线用的包围盒（到方块外沿）。 */
    public net.minecraft.world.phys.AABB box() {
        return new net.minecraft.world.phys.AABB(
            min.getX(), min.getY(), min.getZ(),
            max.getX() + 1.0D, max.getY() + 1.0D, max.getZ() + 1.0D);
    }

    /** 这个方块坐标是不是在区域范围内（按方块坐标，不做浮点包围盒判断）。 */
    public boolean containsBlock(BlockPos pos) {
        return pos.getX() >= this.min.getX() && pos.getX() <= this.max.getX()
            && pos.getY() >= this.min.getY() && pos.getY() <= this.max.getY()
            && pos.getZ() >= this.min.getZ() && pos.getZ() <= this.max.getZ();
    }

    /**
     * 实体「算不算在区域里」：**脚所在的方块**在区域内，或者**脚下一格**在区域内。
     *
     * <p>2026-11-23 主人报「走到区域里却被弹回、永远通勤」：区域只占一层时，
     * 站在区域地面上的居民脚底 y = max.y + 1，正好落在 {@code box().contains()} 的开区间外，
     * 于是永远判成「不在区域」。用方块坐标判断（脚 / 脚下一格）就不会再错。</p>
     */
    public boolean containsBody(net.minecraft.world.entity.Entity entity) {
        BlockPos feet = entity.blockPosition();
        return this.containsBlock(feet) || this.containsBlock(feet.below());
    }

    /**
     * 树场允许的「站立 / 通勤范围」外扩（格）。
     *
     * <p>2026-11-23 主人要求：树场被树叶 / 树苗占满时，居民站到**区域外几格**也能待着。
     * 只在树场生效，其它区域一律 0（行为与 {@link #containsBlock} 完全一致）。</p>
     *
     * <p><b>注意（2026-11-23 主人改口）</b>：外圈**只给站立 / 通勤 / 踱步 / 工作计时用**，
     * **砍树与种苗只认区域内**（{@link #containsBlock}）——扫描范围已收回区域本身。</p>
     */
    public static final int TREE_FARM_MARGIN = 3;

    /** 这个区域算「站立 / 通勤范围」时允许外扩多少格。 */
    public int workMargin() {
        return this.isTreeFarm() ? TREE_FARM_MARGIN : 0;
    }

    /** 这个方块在不在「站立 / 通勤范围」里（树场外扩 {@link #workMargin()} 格）。 */
    public boolean inWorkRange(BlockPos pos) {
        int margin = this.workMargin();
        return pos.getX() >= this.min.getX() - margin && pos.getX() <= this.max.getX() + margin
            && pos.getY() >= this.min.getY() && pos.getY() <= this.max.getY()
            && pos.getZ() >= this.min.getZ() - margin && pos.getZ() <= this.max.getZ() + margin;
    }

    /** 实体「算不算在工作范围里」：脚所在方块或脚下一格（与 {@link #containsBody} 同一口径 + 外扩）。 */
    public boolean inWorkRangeBody(net.minecraft.world.entity.Entity entity) {
        BlockPos feet = entity.blockPosition();
        return this.inWorkRange(feet) || this.inWorkRange(feet.below());
    }

    /**
     * 有没有员工栏（道路 / 禁行 / 娱乐都没有）。
     * 栅栏门归属判断（2026-11-23 主人新规则）也用它：只有「有员工的工作区域」才限制门。
     */
    public boolean hasEmployeeSlots() {
        return !TYPE_ROAD.equals(this.type) && !TYPE_FORBIDDEN.equals(this.type)
            && !this.isEntertainment();
    }

    /**
     * 统一的类型顺序（界面用）。
     *
     * <p>主人 2026-11-22 定：**道路第一，挖掘第二，填充第三**，禁行区域永远在最底下。
     * （12 号规格速查表写的是「放在渔场后面」，以主人当面的说法为准。）</p>
     */
    public static java.util.List<String> types() {
        return java.util.List.of(TYPE_ROAD, TYPE_MINE, TYPE_FILL, TYPE_FARM, TYPE_TREE_FARM,
            TYPE_RANCH, TYPE_FISHERY, TYPE_ENTERTAINMENT, TYPE_FORBIDDEN);
    }
}
