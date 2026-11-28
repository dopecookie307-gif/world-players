package com.worldplayers.resident.path;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.HashMap;
import net.minecraft.server.level.ServerLevel;

/**
 * 路网「段」的通行预约表（第三轮，`0.3.1-alpha.11`，2026-11-24）。
 *
 * <p>主人的要求：一栋楼同一层 10 户同时下楼，要**自发排队、有序通过**，别在窄楼梯上互相推挤；
 * 万一是一个上楼一个下楼**迎面相遇**，也要保证每个人都能走过去。</p>
 *
 * <p>做法（比「迎面时互相后退重算路」简单得多，也不会来回弹）：</p>
 * <ul>
 *   <li>一条段 = 一份通行资源，**容量 = 两端点宽度取小**（默认 1 = 单轨，一次只放一个人进去）；</li>
 *   <li>进段前 {@link #tryEnter}，满了就在段口的节点上排队（状态「等待通行」），每 10 tick 重试；</li>
 *   <li>段容量 1 时，反向来的人同样进不去 → 不会在同一段上正面相遇，自然的「让一个人先过」；</li>
 *   <li>宽度 ≥2 的段容量 ≥2，两个方向可以同时过（各走半边）。</li>
 * </ul>
 *
 * <p>占用记录**不进存档**（运行时表，按 {@link ServerLevel} 分开）。占用者每 10 tick 刷新一次
 * {@link #touch}，超过 {@link #STALE_TICKS} 没刷新的记录自动作废 —— 实体被卸载、goal 异常结束
 * 都不会把整条路永久锁死。</p>
 */
public final class PathTraffic {
    /** 一次进段的结果。 */
    public enum Enter {
        /** 进去了（或本来就占着这一段）。 */
        ENTERED,
        /** 段满了（有同向或反向的人），在段口排队。 */
        FULL
    }

    private static final Map<ServerLevel, PathTraffic> TABLES = new WeakHashMap<>();
    /** 占用记录多久没刷新就作废（10 秒）。 */
    private static final long STALE_TICKS = 200L;

    /** 一条占用记录：谁、从哪向哪、什么时候进来的、最后什么时候刷新。 */
    private static final class Claim {
        private final UUID resident;
        private final long enteredAt;
        private long lastSeen;

        private Claim(UUID resident, long enteredAt, long lastSeen) {
            this.resident = resident;
            this.enteredAt = enteredAt;
            this.lastSeen = lastSeen;
        }
    }

    private final Map<Long, List<Claim>> claims = new HashMap<>();

    public static PathTraffic of(ServerLevel level) {
        synchronized (TABLES) {
            return TABLES.computeIfAbsent(level, key -> new PathTraffic());
        }
    }

    /** 无向段 key（两个点 id 不分先后，同一条段只记一份）。 */
    private static long key(int a, int b) {
        int low = Math.min(a, b);
        int high = Math.max(a, b);
        return ((long) low << 32) | (high & 0xffffffffL);
    }

    /**
     * 试着让 {@code resident} 走 {@code from → to} 这一段。
     *
     * @param capacity 这一段能同时容纳几个人（= min(两端点宽度)，至少 1）
     * @return {@link Enter#ENTERED} 可以进；{@link Enter#FULL} 在段口排队
     */
    public Enter tryEnter(int from, int to, UUID resident, int capacity, long tick) {
        long key = key(from, to);
        List<Claim> list = this.claims.get(key);
        if (list == null) {
            list = new ArrayList<>(2);
            this.claims.put(key, list);
        }
        prune(list, tick);
        for (Claim claim : list) {
            if (claim.resident.equals(resident)) {
                claim.lastSeen = tick;
                return Enter.ENTERED;
            }
        }
        if (list.size() >= Math.max(1, capacity)) {
            return Enter.FULL;
        }
        list.add(new Claim(resident, tick, tick));
        return Enter.ENTERED;
    }

    /** 刷新占用（每 10 tick 一次即可，防止静态清理把活人踢掉）。 */
    public void touch(int from, int to, UUID resident, long tick) {
        List<Claim> list = this.claims.get(key(from, to));
        if (list == null) {
            return;
        }
        for (Claim claim : list) {
            if (claim.resident.equals(resident)) {
                claim.lastSeen = tick;
                return;
            }
        }
    }

    /** 离开段（到达下一个点 / goal 结束）。 */
    public void release(int from, int to, UUID resident) {
        long key = key(from, to);
        List<Claim> list = this.claims.get(key);
        if (list == null) {
            return;
        }
        list.removeIf(claim -> claim.resident.equals(resident));
        if (list.isEmpty()) {
            this.claims.remove(key);
        }
    }

    /** 把某个居民的所有占用清掉（goal 结束 / 被卸载时的兜底）。 */
    public void releaseAll(UUID resident) {
        Iterator<Map.Entry<Long, List<Claim>>> iterator = this.claims.entrySet().iterator();
        while (iterator.hasNext()) {
            List<Claim> list = iterator.next().getValue();
            list.removeIf(claim -> claim.resident.equals(resident));
            if (list.isEmpty()) {
                iterator.remove();
            }
        }
    }

    /** 这一段现在有几个人（诊断用；不刷新 lastSeen）。 */
    public int occupancy(int from, int to) {
        List<Claim> list = this.claims.get(key(from, to));
        return list == null ? 0 : list.size();
    }

    private static void prune(List<Claim> list, long tick) {
        list.removeIf(claim -> tick - claim.lastSeen > STALE_TICKS);
    }

    private PathTraffic() {
    }
}
