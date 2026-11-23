package com.worldplayers.compat.ysm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import net.minecraft.world.entity.LivingEntity;

/**
 * YSM 的 {@code animation_controllers}（最小可用版，alpha.41）。
 *
 * <p>模型里的控制器就是一个状态机：每个状态挂一组动画，靠 {@code transitions} 里的 Molang 条件切换，
 * 切换时按 {@code blend_transition} 混合。酒狐的用法是
 * {@code player.post_main}：default 里挂 {@code new_idle_empty}，当 {@code v.next_idle == 1..4}
 * 时切到 {@code idle1..4}（作者的彩蛋待机动作），播完（{@code q.all_animations_finished}）回 default。</p>
 *
 * <p><b>支持范围</b>（刻意的边界，不做更多）：</p>
 * <ul>
 *   <li>每个模型只取第一个控制器（多个时忽略其余）；</li>
 *   <li>条件用现成的 {@link Molang} 求值，环境里提供 {@code v.*} / {@code ctrl.*} / {@code q.all_animations_finished}；</li>
 *   <li>{@code on_exit} 只认 {@code v.next_idle = 0;} 这一种（YSM 官方的清理写法）；</li>
 *   <li>{@code v.next_idle} 由我们自己随机挑：default 空闲 10~20 秒后随机 1~4。</li>
 * </ul>
 *
 * <p>解析失败 / 没有控制器文件时返回 empty —— **绝不因为控制器把模型弄坏**。</p>
 */
public final class YsmControllers {
    /** 一个状态的切换条件。 */
    public record Transition(String target, Molang.Program condition) {
    }

    /** 一个状态：动画列表 + 切换条件 + 退出动作。 */
    public record State(List<String> animations, List<Transition> transitions, List<String> onExit, float blend) {
    }

    /** 一个控制器：状态表 + 初始状态。 */
    public record Controller(String name, Map<String, State> states, String initial) {
    }

    /** 这一帧要叠加的动画（名字 + 已播时间，秒）。 */
    public record Playing(String animation, double time) {
    }

    private static final Map<String, Optional<Controller>> CACHE = new HashMap<>();
    private static final Map<LivingEntity, Run> RUNS = new WeakHashMap<>();
    /** 空闲多久之后挑一个 idle 变体（秒）。 */
    private static final double IDLE_TRIGGER_MIN = 10.0D;
    private static final double IDLE_TRIGGER_SPAN = 10.0D;

    private static final class Run {
        private String state;
        private double enteredAt = Double.NaN;
        private final Map<String, Double> vars = new HashMap<>();
    }

    /** 按模型名取控制器（解析结果缓存；没有/坏掉 → empty）。 */
    public static Optional<Controller> of(String modelName) {
        if (modelName == null || modelName.isEmpty()) {
            return Optional.empty();
        }
        return CACHE.computeIfAbsent(modelName, name -> YsmModels.find(name).flatMap(YsmControllers::load));
    }

    private static Optional<Controller> load(YsmModels.Model model) {
        try {
            JsonObject spec = JsonParser.parseString(model.readText("ysm.json")).getAsJsonObject();
            JsonObject player = spec.getAsJsonObject("files") == null ? null
                : spec.getAsJsonObject("files").getAsJsonObject("player");
            if (player == null || !player.has("animation_controllers")) {
                return Optional.empty();
            }
            JsonArray paths = player.getAsJsonArray("animation_controllers");
            for (JsonElement element : paths) {
                JsonObject root = JsonParser.parseString(model.readText(element.getAsString())).getAsJsonObject();
                JsonObject controllers = root.getAsJsonObject("animation_controllers");
                if (controllers == null) {
                    continue;
                }
                for (Map.Entry<String, JsonElement> entry : controllers.entrySet()) {
                    Controller parsed = parse(entry.getKey(), entry.getValue().getAsJsonObject());
                    if (parsed != null) {
                        return Optional.of(parsed);
                    }
                }
            }
        } catch (Exception broken) {
            com.worldplayers.WorldPlayers.LOGGER.debug("World Players: 控制器解析失败（{}），忽略", model.label(), broken);
        }
        return Optional.empty();
    }

    private static Controller parse(String name, JsonObject json) {
        JsonObject statesJson = json.getAsJsonObject("states");
        if (statesJson == null || statesJson.isEmpty()) {
            return null;
        }
        Map<String, State> states = new LinkedHashMap<>();
        String initial = "default";
        for (Map.Entry<String, JsonElement> entry : statesJson.entrySet()) {
            states.put(entry.getKey(), parseState(entry.getValue().getAsJsonObject()));
        }
        if (!states.containsKey(initial)) {
            initial = states.keySet().iterator().next();
        }
        return new Controller(name, states, initial);
    }

    private static State parseState(JsonObject json) {
        List<String> animations = new ArrayList<>();
        JsonArray anims = json.getAsJsonArray("animations");
        if (anims != null) {
            for (JsonElement element : anims) {
                if (element.isJsonPrimitive()) {
                    animations.add(element.getAsString());
                }
            }
        }
        List<Transition> transitions = new ArrayList<>();
        JsonArray trans = json.getAsJsonArray("transitions");
        if (trans != null) {
            for (JsonElement element : trans) {
                if (!element.isJsonObject()) {
                    continue;
                }
                for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                    try {
                        transitions.add(new Transition(entry.getKey(),
                            Molang.compile(entry.getValue().getAsString())));
                    } catch (RuntimeException badExpression) {
                        // 单条条件写坏了就跳过，别拖垮整个状态机
                    }
                }
            }
        }
        List<String> onExit = new ArrayList<>();
        JsonArray exits = json.getAsJsonArray("on_exit");
        if (exits != null) {
            for (JsonElement element : exits) {
                if (element.isJsonPrimitive()) {
                    onExit.add(element.getAsString());
                }
            }
        }
        float blend = json.has("blend_transition") ? json.get("blend_transition").getAsFloat() : 0.2F;
        return new State(animations, transitions, onExit, blend);
    }

    /**
     * 每帧推进一次状态机，返回这一帧该叠加的动画。
     *
     * @param env  每帧的 Molang 环境（会被写入 {@code v.*} / {@code ctrl.*}，不影响主状态动画）
     * @param now  当前时间（秒，和动画时间同一时钟）
     * @param idle 主状态动画是不是 idle
     */
    public static List<Playing> update(LivingEntity entity, Controller controller, Molang.Env env,
                                       YsmAnimations animations, double now, boolean idle,
                                       boolean extraAnimation) {
        Run run = RUNS.computeIfAbsent(entity, key -> new Run());
        if (run.state == null || !controller.states().containsKey(run.state)) {
            run.state = controller.initial();
            run.enteredAt = now;
        }
        State state = controller.states().get(run.state);
        double playTime = Math.max(0.0D, now - run.enteredAt);

        // 空闲时随机挑一个 idle 变体（YSM 官方是随机事件，这里用同样的触发条件：只在 default 且空闲时）
        if (state == controller.states().get(controller.initial()) && idle) {
            double wait = IDLE_TRIGGER_MIN + entity.getRandom().nextDouble() * IDLE_TRIGGER_SPAN;
            if (playTime > wait) {
                run.vars.put("v.next_idle", (double) (1 + entity.getRandom().nextInt(4)));
            }
        }

        // 这个状态里的动画都播完了吗（给 q.all_animations_finished 用）
        double longest = 0.0D;
        for (String name : state.animations()) {
            YsmAnimations.Animation animation = animations == null ? null : animations.get(name);
            if (animation != null && animation.length > 0.0D) {
                longest = Math.max(longest, animation.length);
            }
        }
        double finished = longest > 0.0D && playTime >= longest ? 1.0D : 0.0D;

        env.queries.put("v.next_idle", run.vars.getOrDefault("v.next_idle", 0.0D));
        env.queries.put("ctrl.idle", idle ? 1.0D : 0.0D);
        env.queries.put("ctrl.playing_extra_animation", extraAnimation ? 1.0D : 0.0D);
        env.queries.put("q.all_animations_finished", finished);
        env.queries.put("query.all_animations_finished", finished);

        // 切换条件（第一个成立的生效）
        for (Transition transition : state.transitions()) {
            if (transition.condition().run(env) > 0.5F) {
                applyOnExit(run, state);
                run.state = transition.target();
                run.enteredAt = now;
                state = controller.states().getOrDefault(run.state, state);
                playTime = 0.0D;
                break;
            }
        }

        List<Playing> playing = new ArrayList<>();
        for (String animation : state.animations()) {
            playing.add(new Playing(animation, playTime));
        }
        return playing;
    }

    /** {@code on_exit}：只认 YSM 官方的清理写法 {@code v.next_idle = 0;}。 */
    private static void applyOnExit(Run run, State state) {
        for (String statement : state.onExit()) {
            if (statement.toLowerCase(Locale.ROOT).contains("next_idle")) {
                run.vars.put("v.next_idle", 0.0D);
            }
        }
    }

    private YsmControllers() {
    }
}
