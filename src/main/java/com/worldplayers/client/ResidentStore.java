package com.worldplayers.client;

import com.worldplayers.network.ResidentListPayload;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;

/**
 * 客户端缓存：服务端同步过来的居民（成员）列表。
 *
 * <p>手册「成员列表」页、绑定床铺模式的床高亮、已绑定床的归属判断都读这一份。</p>
 */
public final class ResidentStore {
    private static volatile List<ResidentListPayload.Member> members = List.of();
    /** 招募一名新居民要消耗的食物数量（服务端配置同步过来的，默认 16）。 */
    private static volatile int recruitCost = 16;

    public static void set(List<ResidentListPayload.Member> list) {
        members = list == null ? List.of() : List.copyOf(list);
    }

    public static int recruitCost() {
        return recruitCost;
    }

    public static void setRecruitCost(int cost) {
        recruitCost = Math.max(0, cost);
    }

    public static List<ResidentListPayload.Member> members() {
        return members;
    }

    public static boolean isEmpty() {
        return members.isEmpty();
    }

    /** 这个位置上的床属于谁；不是已绑定的床返回 null。 */
    @Nullable
    public static ResidentListPayload.Member bedOwner(BlockPos pos) {
        if (pos == null) {
            return null;
        }
        for (ResidentListPayload.Member member : members) {
            if (member.hasBed() && pos.equals(member.bed())) {
                return member;
            }
        }
        return null;
    }

    private ResidentStore() {
    }
}
