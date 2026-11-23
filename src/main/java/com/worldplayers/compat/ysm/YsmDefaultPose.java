package com.worldplayers.compat.ysm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.worldplayers.WorldPlayers;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * YSM 的「默认帧」（第 ③ 步）：把 {@code pre_parallel*} / {@code parallel*} 动画在 t=0 的
 * scale / visible 套到几何上，用来把 GUI 背景板、表情贴片、备用形态、特效件这些
 * 「平时应该藏起来」的部件藏掉。
 *
 * <p>背景：YSM 自己每帧都在跑这几套并行动画，模型作者靠它们把部件摆正 / 隐藏；
 * 我们不做整条动画管线（第 ④ 步）之前，先把**静态默认状态**求值出来，
 * 否则这些件会原样摊在世界里（主人看到的「雷霆巨大模型 + 漂浮的几块」）。</p>
 *
 * <p>只求值**常量**（没有帧、没有变量也能算）：表达式里出现 q./v./ysm. 一律按 0 算，
 * {@code ??} 按「左边没定义就用右边」处理。算不出来的通道直接跳过，绝不乱藏。</p>
 */
public final class YsmDefaultPose {
    public static void apply(YsmModels.Model model, Map<String, YsmGeometry.Bone> bones) {
        for (String path : animationPaths(model)) {
            JsonObject animations;
            try {
                animations = JsonParserHelper.animations(model.readText(path));
            } catch (IOException | RuntimeException error) {
                WorldPlayers.LOGGER.debug("World Players: 读不到 YSM 动画 {}：{}", path, error.toString());
                continue;
            }
            if (animations == null) {
                continue;
            }
            for (Map.Entry<String, JsonElement> entry : animations.entrySet()) {
                String name = entry.getKey();
                if (!isDefaultAnimation(name) || !entry.getValue().isJsonObject()) {
                    continue;
                }
                JsonObject boneChannels = entry.getValue().getAsJsonObject().getAsJsonObject("bones");
                if (boneChannels == null) {
                    continue;
                }
                for (Map.Entry<String, JsonElement> channel : boneChannels.entrySet()) {
                    YsmGeometry.Bone bone = bones.get(channel.getKey());
                    if (bone == null || !channel.getValue().isJsonObject()) {
                        continue;
                    }
                    JsonObject json = channel.getValue().getAsJsonObject();
                    applyChannel(bone, json);
                }
            }
        }
        // 兜底：这些名字明显是预览界面用的背景板（没有动画文件的模型也会露出来）
        for (YsmGeometry.Bone bone : bones.values()) {
            String lower = bone.name.toLowerCase(java.util.Locale.ROOT);
            if ("gui".equals(lower) || "curtain".equals(lower) || "white_curtain".equals(lower)
                || lower.startsWith("background")) {
                bone.hidden = true;
            }
        }
    }

    /** 这几套是 YSM 一直挂着的并行动画（不是动作）。 */
    private static boolean isDefaultAnimation(String name) {
        return name.startsWith("pre_parallel") || name.matches("parallel\\d+");
    }

    private static void applyChannel(YsmGeometry.Bone bone, JsonObject channel) {
        if (channel.has("visible")) {
            Float visible = evalScalar(channel.get("visible"));
            if (visible != null && Math.abs(visible) < 1e-4F) {
                bone.hidden = true;
            }
        }
        if (!channel.has("scale")) {
            return;
        }
        float[] scale = evalVector(channel.get("scale"));
        if (scale == null) {
            return;
        }
        if (Math.abs(scale[0]) < 1e-4F && Math.abs(scale[1]) < 1e-4F && Math.abs(scale[2]) < 1e-4F) {
            bone.hidden = true;
            return;
        }
        if (Math.abs(scale[0] - 1.0F) > 1e-4F || Math.abs(scale[1] - 1.0F) > 1e-4F
            || Math.abs(scale[2] - 1.0F) > 1e-4F) {
            bone.scale = scale;
        }
    }

    /** 关键帧 / 静态值 → 三个浮点；求不出来返回 null。 */
    @javax.annotation.Nullable
    private static float[] evalVector(JsonElement element) {
        JsonElement value = firstFrame(element);
        if (value == null) {
            return null;
        }
        if (value.isJsonArray()) {
            JsonArray array = value.getAsJsonArray();
            if (array.size() < 3) {
                Float scalar = evalScalar(value);
                return scalar == null ? null : new float[]{scalar, scalar, scalar};
            }
            float[] out = new float[3];
            for (int i = 0; i < 3; i++) {
                Float component = evalScalar(array.get(i));
                if (component == null) {
                    return null;
                }
                out[i] = component;
            }
            return out;
        }
        Float scalar = evalScalar(value);
        return scalar == null ? null : new float[]{scalar, scalar, scalar};
    }

    @javax.annotation.Nullable
    private static Float evalScalar(JsonElement element) {
        JsonElement value = firstFrame(element);
        if (value == null) {
            return null;
        }
        if (value.isJsonPrimitive()) {
            if (value.getAsJsonPrimitive().isNumber()) {
                return value.getAsFloat();
            }
            if (value.getAsJsonPrimitive().isString()) {
                try {
                    return Float.parseFloat(value.getAsString().trim());
                } catch (NumberFormatException notANumber) {
                    return MolangSimple.eval(value.getAsString());
                }
            }
        }
        return null;
    }

    /** 关键帧字典取最早的一帧（优先 post）；不是关键帧就原样返回。 */
    @javax.annotation.Nullable
    private static JsonElement firstFrame(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return element;
        }
        JsonObject object = element.getAsJsonObject();
        String earliest = null;
        double earliestTime = Double.MAX_VALUE;
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            double time;
            try {
                time = Double.parseDouble(entry.getKey());
            } catch (NumberFormatException notTime) {
                continue;
            }
            if (time < earliestTime) {
                earliestTime = time;
                earliest = entry.getKey();
            }
        }
        if (earliest == null) {
            return null;
        }
        JsonElement frame = object.get(earliest);
        if (frame.isJsonObject()) {
            JsonObject frameObject = frame.getAsJsonObject();
            if (frameObject.has("post")) {
                return frameObject.get("post");
            }
            if (frameObject.has("pre")) {
                return frameObject.get("pre");
            }
            return null;
        }
        return frame;
    }

    /**
     * 模型里**主**动画文件（{@code files.player.animation.main}）。
     *
     * <p>踩过的大坑（alpha.23）：一开始把 {@code files.player.animation} 下的全部文件
     * （main / extra / tac / carryon / tlm / fp_arm）都当成「并行动画」叠加求值 ——
     * 结果 extra/tac/tlm 里那些**给别的场景准备**的同名 `parallel*` 互相覆盖，
     * 模型被拧得横躺、部件散开（主人看到「巨大 + 半个身子在地里 + 所有表情同时出现」）。
     * YSM 自己平时只跑 main，所以这里也只认 main。</p>
     */
    public static List<String> animationPaths(YsmModels.Model model) {
        Set<String> paths = new LinkedHashSet<>();
        try {
            JsonObject root = com.google.gson.JsonParser.parseString(model.readText("ysm.json")).getAsJsonObject();
            JsonObject files = root.getAsJsonObject("files");
            JsonObject player = files == null ? null : files.getAsJsonObject("player");
            JsonObject animation = player == null ? null : player.getAsJsonObject("animation");
            if (animation != null && animation.has("main")) {
                JsonElement main = animation.get("main");
                if (main.isJsonPrimitive() && main.getAsJsonPrimitive().isString()) {
                    paths.add(main.getAsString());
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // 老格式没有 ysm.json
        }
        if (paths.isEmpty()) {
            paths.addAll(scanAnimationFiles(model));
        }
        return new ArrayList<>(paths);
    }

    private static List<String> scanAnimationFiles(YsmModels.Model model) {
        List<String> out = new ArrayList<>();
        try {
            if (model.inZip()) {
                try (java.util.zip.ZipFile file = new java.util.zip.ZipFile(model.zip().toFile())) {
                    for (java.util.zip.ZipEntry entry : java.util.Collections.list(file.entries())) {
                        String name = entry.getName();
                        if (name.startsWith(model.base() + "animations/") && name.endsWith(".json")) {
                            out.add(name.substring(model.base().length()));
                        }
                    }
                }
            } else if (model.dir() != null) {
                java.nio.file.Path dir = model.dir().resolve("animations");
                if (java.nio.file.Files.isDirectory(dir)) {
                    try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.list(dir)) {
                        stream.filter(path -> path.toString().endsWith(".json"))
                            .forEach(path -> out.add("animations/" + path.getFileName()));
                    }
                }
            }
        } catch (IOException error) {
            WorldPlayers.LOGGER.debug("World Players: 扫描 YSM 动画文件失败：{}", error.toString());
        }
        return out;
    }

    /** 小工具：把动画文件的 {@code animations} 节点取出来。 */
    private static final class JsonParserHelper {
        @javax.annotation.Nullable
        static JsonObject animations(String text) {
            JsonObject root = com.google.gson.JsonParser.parseString(text).getAsJsonObject();
            return root.has("animations") && root.get("animations").isJsonObject()
                ? root.getAsJsonObject("animations") : null;
        }
    }

    private YsmDefaultPose() {
    }
}
