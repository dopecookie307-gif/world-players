package com.worldplayers.client;

import com.worldplayers.compat.ysm.YsmAnimations;
import com.worldplayers.compat.ysm.YsmGeometry;
import com.worldplayers.compat.ysm.YsmModels;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** YSM 缓存：模型名 → 几何 / 动画（首次渲染时解析，失败记住空的不反复报错）。 */
public final class YsmGeometryCache {
    private static final Map<String, Optional<YsmGeometry>> CACHE = new HashMap<>();
    private static final Map<String, YsmAnimations> ANIMATIONS = new HashMap<>();

    public static Optional<YsmGeometry> get(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return Optional.empty();
        }
        return CACHE.computeIfAbsent(modelName,
            key -> YsmModels.find(key).flatMap(YsmGeometry::load));
    }

    /** 同一模型的动画（第 ④ 步）；没有动画文件返回空集合。 */
    public static YsmAnimations animations(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return YsmAnimations.EMPTY;
        }
        return ANIMATIONS.computeIfAbsent(modelName, key ->
            YsmModels.find(key).map(YsmAnimations::load).orElse(YsmAnimations.EMPTY));
    }

    private YsmGeometryCache() {
    }
}
