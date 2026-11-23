package com.worldplayers.resident.fishery;

import com.worldplayers.resident.ResidentEntity;
import com.worldplayers.resident.WorkZone;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.server.level.ServerLevel;

/**
 * 渔场的「区域 → 钓点」缓存（15 号规则 2.2 第 6 条）：不每 tick 解析，只在
 * **区域新建 / 改大小 / 员工上班 / 水被填掉**时重建。
 *
 * <p>干涸（找不到能站 + 够得着 + 看得见的水）时不立刻重算，等
 * {@link #RETRY_TICKS}（60 秒）再试一次——规则 2.3：每 60 秒重试、仍然干涸再喊一句。</p>
 */
public final class FisheryCache {
    /** 干涸后的重试间隔：60 秒（规则 2.3）。 */
    public static final int RETRY_TICKS = 20 * 60;

    /** 一个区域的解析结果；{@code spot == null} 就是干涸。 */
    public record Entry(long min, long max, @Nullable FisherySpot spot, long retryAt) {
        public boolean dry() {
            return this.spot == null;
        }
    }

    private static final Map<String, Entry> CACHE = new HashMap<>();

    /**
     * 取这个区域的钓点（必要时重新解析）。
     *
     * @param force true = 无条件重算（员工上班 / 水被填掉时用）
     */
    public static Entry get(ServerLevel level, WorkZone zone, ResidentEntity resident,
                            long now, boolean force) {
        String key = zone.key();
        long min = zone.min().asLong();
        long max = zone.max().asLong();
        Entry entry = CACHE.get(key);
        if (force || entry == null || entry.min() != min || entry.max() != max
                || (entry.spot() == null && now >= entry.retryAt())) {
            FisherySpot spot = Fishery.resolve(level, zone, resident);
            entry = new Entry(min, max, spot, spot == null ? now + RETRY_TICKS : 0L);
            CACHE.put(key, entry);
        }
        return entry;
    }

    /** 区域被删 / 改名 / 改大小：缓存作废。 */
    public static void invalidate(String zoneKey) {
        CACHE.remove(zoneKey);
    }

    /** 换存档：全部清掉（静态缓存跨存档会串数据）。 */
    public static void clearAll() {
        CACHE.clear();
    }

    private FisheryCache() {
    }
}
