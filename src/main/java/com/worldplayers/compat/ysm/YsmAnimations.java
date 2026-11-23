package com.worldplayers.compat.ysm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.worldplayers.WorldPlayers;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * YSM 动画（第 ④ 步）：把 {@code animations/*.json} 解析成「骨骼 → 通道 → 关键帧」，
 * 再按时间取样（线性插值）。表达式用 {@link Molang} 求值，环境由 {@link #envFor} 之类的调用方填。
 *
 * <p>只做渲染要用的三件：rotation（度，骨骼局部）、position（模型单位，沿父骨骼偏移）、
 * scale（倍数）；另有 {@code visible} 通道（false = 整棵子树不画）。</p>
 */
public final class YsmAnimations {
    public static final String ROTATION = "rotation";
    public static final String POSITION = "position";
    public static final String SCALE = "scale";
    public static final String VISIBLE = "visible";

    /** 一段通道：若干关键帧，每个关键帧的每个分量要么是常量，要么是 Molang 程序。 */
    /**
     * 一段通道：若干关键帧，每帧都有「进入值（pre）」和「离开值（post）」两套。
     *
     * <p>★ Bedrock 的 {@code {"pre":…, "post":…}} 是**阶跃**：在这一帧之前保持 pre，过了这一帧用 post。
     * 以前只取 post 当普通关键帧插值，于是「瞬间跳变」被摊成了「缓慢滑动」—— 大酒狐 idel 的
     * {@code weixiao} 在 20.96/24.46/46.5 秒各有一次 pre≠post，被摊薄后嘴就在脸上慢慢游走
     * （主人看到的「嘴还在脸上飘」）。普通数字/数组帧的 pre 与 post 相同，行为不变。</p>
     */
    public static final class Channel {
        private final double[] times;
        private final float[][] enterConstants;
        private final float[][] exitConstants;
        private final Molang.Program[][] enterPrograms;
        private final Molang.Program[][] exitPrograms;
        /**
         * 有分量是 Molang 表达式但**编译不出来**（不认识的函数签名、新语法等）。
         *
         * <p>这种通道整段不生效：以前编译失败会退化成常量 0，对 `scale` 通道就是
         * <b>三轴全 0 = 骨骼隐藏</b> —— 大酒狐的 `LeftArm` scale 是
         * {@code "... || q.is_item_name_any('Mainhand', 'bow')?1:0"}，而旧 tokenizer 不认单引号字符串，
         * 编译失败 → 左手臂整根消失（主人看到的「左手跟物品消失」）。宁可不动这根骨，也不能把它藏掉。</p>
         */
        private final boolean broken;

        private Channel(double[] times, float[][] enterConstants, float[][] exitConstants,
                        Molang.Program[][] enterPrograms, Molang.Program[][] exitPrograms, boolean broken) {
            this.times = times;
            this.enterConstants = enterConstants;
            this.exitConstants = exitConstants;
            this.enterPrograms = enterPrograms;
            this.exitPrograms = exitPrograms;
            this.broken = broken;
        }

        public boolean isEmpty() {
            return this.times.length == 0;
        }

        public boolean isBroken() {
            return this.broken;
        }

        /**
         * 取样：写进 {@code out}（长度 3）；返回 false 表示这段通道没有任何有效值。
         *
         * @param cycleLength &gt;0 表示这是个 loop 动画的周期长度（秒）。此时「最后一个关键帧之后」
         *                    到周期末尾的那一段，按 {@code 末帧 → 首帧} 线性补上 —— 也就是自动闭合。
         *                    不补的话：一过末帧就卡在末帧姿势，等周期一到「啪」地跳回首帧，
         *                    主人看到的「呼吸动画结尾处抖四下的震颤」就是它（idle 的 Head/UpBody 首末相差十几度）。
         *                    ≤0 表示不循环，照旧 clamp 到末帧。
         */
        public boolean sample(double time, Molang.Env env, float[] out, double cycleLength) {
            if (this.times.length == 0 || this.broken) {
                return false;
            }
            double t = time;
            if (this.times.length == 1 || t <= this.times[0]) {
                evaluate(0, true, env, out);
                return true;
            }
            int last = this.times.length - 1;
            if (t >= this.times[last]) {
                double tail = cycleLength - this.times[last];
                if (cycleLength > 0.0D && tail > 1.0E-4D) {
                    // 闭合段：末帧的 post → 首帧的 pre
                    double ratio = Math.min(1.0D, (t - this.times[last]) / tail);
                    float[] from = new float[3];
                    float[] to = new float[3];
                    evaluate(last, false, env, from);
                    evaluate(0, true, env, to);
                    for (int i = 0; i < 3; i++) {
                        out[i] = from[i] + (to[i] - from[i]) * (float) ratio;
                    }
                    return true;
                }
                evaluate(last, false, env, out);
                return true;
            }
            // 段内插值：上一帧的 **post** → 本帧的 **pre**；t 正好等于某帧时间时已经跳到它的 post。
            int index = 0;
            while (index + 1 < this.times.length && this.times[index + 1] <= t) {
                index++;
            }
            double span = this.times[index + 1] - this.times[index];
            float ratio = span <= 1.0E-6D ? 0.0F : (float) ((t - this.times[index]) / span);
            float[] from = new float[3];
            float[] to = new float[3];
            evaluate(index, false, env, from);
            evaluate(index + 1, true, env, to);
            for (int i = 0; i < 3; i++) {
                out[i] = from[i] + (to[i] - from[i]) * ratio;
            }
            return true;
        }

        private void evaluate(int key, boolean entering, Molang.Env env, float[] out) {
            for (int i = 0; i < 3; i++) {
                Molang.Program program = entering ? this.enterPrograms[key][i] : this.exitPrograms[key][i];
                float constant = entering ? this.enterConstants[key][i] : this.exitConstants[key][i];
                out[i] = program != null ? program.run(env) : constant;
            }
        }
    }

    public static final class Animation {
        public final String name;
        public final boolean loop;
        /** 循环长度（秒）；≤0 表示没有写 animation_length，用外部时间取模。 */
        public final double length;
        public final Map<String, Map<String, Channel>> bones;

        private Animation(String name, boolean loop, double length, Map<String, Map<String, Channel>> bones) {
            this.name = name;
            this.loop = loop;
            this.length = length;
            this.bones = bones;
        }
    }

    private final Map<String, Animation> animations;

    private YsmAnimations(Map<String, Animation> animations) {
        this.animations = animations;
    }

    public static final YsmAnimations EMPTY = new YsmAnimations(Map.of());

    @javax.annotation.Nullable
    public Animation get(String name) {
        return this.animations.get(name);
    }

    public Map<String, Animation> all() {
        return this.animations;
    }

    public boolean isEmpty() {
        return this.animations.isEmpty();
    }

    /** 加载一个模型的所有动画文件（合并；同名的后者覆盖前者）。 */
    public static YsmAnimations load(YsmModels.Model model) {
        Map<String, Animation> out = new LinkedHashMap<>();
        for (String path : YsmDefaultPose.animationPaths(model)) {
            try {
                JsonObject root = com.google.gson.JsonParser.parseString(model.readText(path)).getAsJsonObject();
                JsonObject animations = root.has("animations") && root.get("animations").isJsonObject()
                    ? root.getAsJsonObject("animations") : null;
                if (animations == null) {
                    continue;
                }
                for (Map.Entry<String, JsonElement> entry : animations.entrySet()) {
                    if (!entry.getValue().isJsonObject()) {
                        continue;
                    }
                    Animation animation = parseAnimation(entry.getKey(), entry.getValue().getAsJsonObject());
                    if (animation != null) {
                        out.put(animation.name, animation);
                    }
                }
            } catch (IOException | RuntimeException error) {
                WorldPlayers.LOGGER.debug("World Players: 读不到 YSM 动画 {}：{}", path, error.toString());
            }
        }
        return new YsmAnimations(out);
    }

    @javax.annotation.Nullable
    private static Animation parseAnimation(String name, JsonObject json) {
        JsonElement loopElement = json.get("loop");
        boolean loop = loopElement != null && loopElement.isJsonPrimitive()
            && (loopElement.getAsJsonPrimitive().isBoolean() ? loopElement.getAsBoolean()
                : !"false".equalsIgnoreCase(loopElement.getAsString()));
        double length = json.has("animation_length") ? json.get("animation_length").getAsDouble() : 0.0D;
        JsonObject bonesJson = json.has("bones") && json.get("bones").isJsonObject()
            ? json.getAsJsonObject("bones") : null;
        if (bonesJson == null) {
            return new Animation(name, loop, length, Map.of());
        }
        Map<String, Map<String, Channel>> bones = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> boneEntry : bonesJson.entrySet()) {
            if (!boneEntry.getValue().isJsonObject()) {
                continue;
            }
            JsonObject channelsJson = boneEntry.getValue().getAsJsonObject();
            Map<String, Channel> channels = new LinkedHashMap<>();
            for (String channelName : new String[]{ROTATION, POSITION, SCALE, VISIBLE}) {
                if (!channelsJson.has(channelName)) {
                    continue;
                }
                Channel channel = parseChannel(channelsJson.get(channelName));
                if (channel != null && !channel.isEmpty()) {
                    channels.put(channelName, channel);
                }
            }
            if (!channels.isEmpty()) {
                bones.put(boneEntry.getKey(), channels);
            }
        }
        return new Animation(name, loop, length, bones);
    }

    /**
     * 这个对象是不是「阶跃帧」{@code {"pre":…, "post":…}}。
     *
     * <p>★ 必须 `pre` 或 `post` **任一个**存在就算 —— Blockbench 导出带 `lerp_mode` 的帧经常只写
     * {@code {"post": […], "lerp_mode": "catmullrom"}}（没有 pre）。alpha.44 只判断了 `has("pre")`，
     * 于是这种帧被当成「数值对象」→ 分量保持常量 0 → 骨骼 scale 三轴全 0 = **整根隐藏**。
     * 主人看到的大酒狐（以及部分模型）「除了睫毛以外眼睛整个不见」就是它：
     * 眼珠/高光的 scale 通道是 {@code {"0.0": {"post": [1,1,1], "lerp_mode": …}}}，被读成 0；
     * 睫毛（`RightEyelid`）的 scale 在 `pre_parallel0` 里是 Molang 字符串，没受影响。</p>
     */
    private static boolean isStepObject(JsonObject object) {
        return object.has("pre") || object.has("post");
    }

    /** 关键帧字典 / 三元数组 / 单标量 → Channel（每帧带 pre/post 两套值）。 */
    @javax.annotation.Nullable
    private static Channel parseChannel(JsonElement element) {
        List<Double> times = new ArrayList<>();
        List<float[]> enterConstants = new ArrayList<>();
        List<Molang.Program[]> enterPrograms = new ArrayList<>();
        List<float[]> exitConstants = new ArrayList<>();
        List<Molang.Program[]> exitPrograms = new ArrayList<>();
        boolean[] broken = {false};
        if (element.isJsonObject() && isStepObject(element.getAsJsonObject())) {
            // 整个通道就是一个阶跃（没有时间键）：t=0 之前用 pre，之后用 post
            JsonObject stepping = element.getAsJsonObject();
            times.add(0.0D);
            addComponents(stepping.has("pre") ? stepping.get("pre") : stepping.get("post"),
                enterConstants, enterPrograms, broken);
            addComponents(stepping.has("post") ? stepping.get("post") : stepping.get("pre"),
                exitConstants, exitPrograms, broken);
        } else if (element.isJsonObject()) {
            JsonObject keys = element.getAsJsonObject();
            List<Map.Entry<String, JsonElement>> frames = new ArrayList<>(keys.entrySet());
            frames.sort((a, b) -> Double.compare(timeOf(a.getKey()), timeOf(b.getKey())));
            for (Map.Entry<String, JsonElement> frame : frames) {
                JsonElement pre = frame.getValue();
                JsonElement post = frame.getValue();
                if (post != null && post.isJsonObject()) {
                    JsonObject stepping = post.getAsJsonObject();
                    if (isStepObject(stepping)) {
                        // 缺哪边就用另一边（Blockbench 常只写 post）
                        pre = stepping.has("pre") ? stepping.get("pre") : stepping.get("post");
                        post = stepping.has("post") ? stepping.get("post") : stepping.get("pre");
                    }
                }
                if (post == null && pre == null) {
                    continue;
                }
                if (pre == null) {
                    pre = post;
                }
                if (post == null) {
                    post = pre;
                }
                times.add(timeOf(frame.getKey()));
                addComponents(pre, enterConstants, enterPrograms, broken);
                addComponents(post, exitConstants, exitPrograms, broken);
            }
        } else {
            times.add(0.0D);
            addComponents(element, enterConstants, enterPrograms, broken);
            addComponents(element, exitConstants, exitPrograms, broken);
        }
        if (times.isEmpty()) {
            return null;
        }
        double[] timeArray = new double[times.size()];
        for (int i = 0; i < times.size(); i++) {
            timeArray[i] = times.get(i);
        }
        return new Channel(timeArray,
            enterConstants.toArray(new float[0][]), exitConstants.toArray(new float[0][]),
            enterPrograms.toArray(new Molang.Program[0][]), exitPrograms.toArray(new Molang.Program[0][]),
            broken[0]);
    }

    private static void addComponents(JsonElement value, List<float[]> constants, List<Molang.Program[]> programs,
                                      boolean[] broken) {
        float[] constant = new float[]{0.0F, 0.0F, 0.0F};
        Molang.Program[] program = new Molang.Program[3];
        if (value.isJsonArray()) {
            JsonArray array = value.getAsJsonArray();
            for (int i = 0; i < 3; i++) {
                JsonElement component = i < array.size() ? array.get(i) : array.get(array.size() - 1);
                fill(component, i, constant, program, broken);
            }
        } else {
            for (int i = 0; i < 3; i++) {
                fill(value, i, constant, program, broken);
            }
        }
        constants.add(constant);
        programs.add(program);
    }

    private static void fill(JsonElement component, int index, float[] constant, Molang.Program[] program,
                             boolean[] broken) {
        if (component == null) {
            return;
        }
        if (component.isJsonPrimitive()) {
            JsonPrimitive primitive = component.getAsJsonPrimitive();
            if (primitive.isNumber()) {
                constant[index] = primitive.getAsFloat();
                return;
            }
            String text = primitive.getAsString().trim();
            try {
                constant[index] = Float.parseFloat(text);
                return;
            } catch (NumberFormatException notANumber) {
                // 落到 Molang
            }
            Molang.Program compiled = Molang.compile(text);
            if (compiled != null) {
                program[index] = compiled;
            } else {
                // 编译失败：整段通道作废（见 Channel#broken 的注释），绝不当成常量 0
                broken[0] = true;
            }
        }
    }

    private static double timeOf(String key) {
        try {
            return Double.parseDouble(key);
        } catch (NumberFormatException error) {
            return 0.0D;
        }
    }
}
