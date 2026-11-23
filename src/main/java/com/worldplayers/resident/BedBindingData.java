package com.worldplayers.resident;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * 「哪张床属于哪个居民」的存档数据（存在主世界）。
 *
 * <p>居民用**实体 UUID** 认领床位；{@link ResidentRevival} 复活时会保留同一个 UUID，
 * 所以床绑定在死亡/复活后依然有效。</p>
 */
public class BedBindingData extends SavedData {
    private static final String DATA_NAME = "worldplayers_bed_bindings";
    private static final String TAG_BEDS = "Beds";

    /** 一条绑定：哪个维度的哪个床 → 哪个居民（带一份名字缓存，聊天提示不用去查实体）。 */
    public record Entry(ResourceKey<Level> dimension, BlockPos pos, UUID resident, String name) {
    }

    private final Map<GlobalPos, Entry> beds = new HashMap<>();

    public BedBindingData() {
    }

    public static BedBindingData get(ServerLevel anyLevel) {
        ServerLevel overworld = anyLevel.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(BedBindingData::new, BedBindingData::load), DATA_NAME);
    }

    /** 这张床属于谁；没有绑定返回 null。 */
    @Nullable
    public Entry find(ResourceKey<Level> dimension, BlockPos pos) {
        return this.beds.get(GlobalPos.of(dimension, pos));
    }

    /** 某个居民的床位（活着的时候最多一个）；没有返回 null。 */
    @Nullable
    public Entry findFor(UUID resident) {
        for (Entry entry : this.beds.values()) {
            if (entry.resident().equals(resident)) {
                return entry;
            }
        }
        return null;
    }

    public List<Entry> all() {
        return new ArrayList<>(this.beds.values());
    }

    /** 绑定（同一张床直接覆盖，同一个居民换床会先解掉旧床）。 */
    public void bind(ResourceKey<Level> dimension, BlockPos pos, UUID resident, String name) {
        this.unbindResident(resident);
        this.beds.put(GlobalPos.of(dimension, pos), new Entry(dimension, pos.immutable(), resident, name));
        this.setDirty();
    }

    /** 解除这张床的绑定；返回被解掉的那条（没有就 null）。 */
    @Nullable
    public Entry unbind(ResourceKey<Level> dimension, BlockPos pos) {
        Entry removed = this.beds.remove(GlobalPos.of(dimension, pos));
        if (removed != null) {
            this.setDirty();
        }
        return removed;
    }

    /** 解除某个居民的所有床位。 */
    public void unbindResident(UUID resident) {
        if (this.beds.values().removeIf(entry -> entry.resident().equals(resident))) {
            this.setDirty();
        }
    }

    public static BedBindingData load(CompoundTag tag, HolderLookup.Provider registries) {
        BedBindingData data = new BedBindingData();
        ListTag list = tag.getList(TAG_BEDS, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            ResourceLocation dimension = ResourceLocation.tryParse(entry.getString("Dim"));
            if (dimension == null) {
                continue;
            }
            ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, dimension);
            BlockPos pos = BlockPos.of(entry.getLong("Pos"));
            UUID resident;
            try {
                resident = UUID.fromString(entry.getString("Resident"));
            } catch (IllegalArgumentException malformed) {
                continue;
            }
            data.beds.put(GlobalPos.of(key, pos),
                new Entry(key, pos, resident, entry.getString("Name")));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Entry entry : this.beds.values()) {
            CompoundTag out = new CompoundTag();
            out.putString("Dim", entry.dimension().location().toString());
            out.putLong("Pos", entry.pos().asLong());
            out.putString("Resident", entry.resident().toString());
            out.putString("Name", entry.name());
            list.add(out);
        }
        tag.put(TAG_BEDS, list);
        return tag;
    }
}
