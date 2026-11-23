package com.worldplayers.compat.ysm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.worldplayers.WorldPlayers;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 解析 YSM 的 bedrock 几何（{@code main.json}）并烘焙成可以直接丢给 {@code VertexConsumer} 的四边形。
 *
 * <p><b>坐标语义（2026-11-21 alpha.31 重写，照抄 folkways 的 YSM GEO 移植，MIT，YSM GEO Compat by HSZK2017）：</b></p>
 * <ul>
 *   <li>骨骼 {@code pivot}：{@code (-x/16, y/16, z/16)} —— <b>绝对</b>模型像素，只翻 x；</li>
 *   <li>骨骼 {@code rotation}：{@code (-rx, -ry, +rz)} 转弧度（注意 z <b>不</b>取负）；</li>
 *   <li>立方体顶点：{@code ox=-(o.x+sx)/16, oy=+o.y/16, oz=+o.z/16}，同样只翻 x；</li>
 *   <li>立方体自转：{@code T(cp)·Rz·Ry·Rx·T(-cp)}（cp 也是 {@code (-x, y, z)/16}），<b>直接烘焙进顶点</b>；</li>
 *   <li>骨骼矩阵：{@code parent · T(p)·Rz·Ry·Rx·S·T(-p)}，沿父子链左乘。</li>
 * </ul>
 *
 * <p>为什么不用「相对父 pivot + 交换 X/Y」那套：alpha.30 那么做过，模型仍是碎的。
 * folkways 这份实现是 YSM 官方 {@code GeoBuilder/GeoCube/GeoQuad} 的逐行复刻，还用 BIPED 骨架的比例
 * 做了硬校验（头 y 1.5~2、手臂中心 x ±0.375、腿 y 0~0.75），可信度远高于文档推导。</p>
 *
 * <p>这样得到的模型空间是：<b>y 向上、脚底 y=0、x 已镜像（LeftArm 在负 x）</b>。
 * 渲染时必须先 {@code scale(-1,1,1)} 把 x 还原成 YSM 原生方向，详见 {@code ResidentRenderer#renderYsm}。</p>
 */
public final class YsmGeometry {
    /** 骨骼：pivot 是绝对模型坐标（格），rotation 是度、已按 {@code (-x,-y,+z)} 转换。 */
    public static final class Bone {
        public final String name;
        /** 绝对 pivot（格）。 */
        public final float[] pivot;
        /** 度，合成顺序 Z→Y→X。 */
        public final float[] rotation;
        /** 四边形顶点已经是绝对模型坐标（含立方体自转）。 */
        public final List<Quad> quads;
        public final List<Bone> children = new ArrayList<>();
        /** 默认帧静下来的缩放（{@link YsmDefaultPose} 填；1,1,1 = 原样）。 */
        public float[] scale = new float[]{1.0F, 1.0F, 1.0F};
        /** 默认帧/兜底里被藏掉的部件，整棵子树都不画。 */
        public boolean hidden;
        /**
         * 嘴（自身名字含 mouth，或父骨骼就叫 Mouth）。
         *
         * <p>酒狐的嘴是「一个表情一根骨骼」（`Mouth_smile`、`weixiao`、`xiao`…），位置靠动画
         * `position` 从脸里推出来。但有些模型（大酒狐 `13_matured`）的 idle 在 46.5 秒之后
         * 还挂着 {@code position y = −5.4 像素}（≈ 往下挪 0.27 格，嘴直接掉到下巴外面），那是
         * 作者没排完的片段。嘴这种贴脸件承受不了这么大的位移 —— 渲染时把「|x|/|y| > 2 像素」
         * 的分量当 0 处理（z 仍然照搬），嘴就稳稳留在脸上。</p>
         */
        public boolean mouthPart;

        private Bone(String name, float[] pivot, float[] rotation, List<Quad> quads) {
            this.name = name;
            this.pivot = pivot;
            this.rotation = rotation;
            this.quads = quads;
        }
    }

    /** 一个面：4 个绝对顶点 + 4 组归一化 UV + 法线。顺序 TL / BL / BR / TR（从面外侧看）。 */
    public static final class Quad {
        public final float[] pos;
        public final float[] uv;
        public final float[] normal;

        private Quad(float[] pos, float[] uv, float[] normal) {
            this.pos = pos;
            this.uv = uv;
            this.normal = normal;
        }
    }

    public final List<Bone> roots;
    public final float heightScale;
    public final float widthScale;
    public final float texWidth;
    public final float texHeight;
    public final int quadCount;

    private YsmGeometry(List<Bone> roots, float heightScale, float widthScale,
                        float texWidth, float texHeight, int quadCount) {
        this.roots = roots;
        this.heightScale = heightScale;
        this.widthScale = widthScale;
        this.texWidth = texWidth;
        this.texHeight = texHeight;
        this.quadCount = quadCount;
    }

    private static final float PIXEL = 1.0F / 16.0F;

    /** 解析一个模型；失败返回 empty（调用方退回普通玩家模型）。 */
    public static Optional<YsmGeometry> load(YsmModels.Model model) {
        try {
            YsmGeometry geometry = parse(model);
            int[] counts = new int[2];
            Map<String, float[]> ranges = new LinkedHashMap<>();
            for (Bone root : geometry.roots) {
                staticRanges(root, Affine.IDENTITY, ranges, counts);
            }
            float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
            float[] max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
            for (float[] range : ranges.values()) {
                for (int axis = 0; axis < 3; axis++) {
                    min[axis] = Math.min(min[axis], range[axis]);
                    max[axis] = Math.max(max[axis], range[axis + 3]);
                }
            }
            WorldPlayers.LOGGER.info(
                "World Players: YSM 几何 {}：骨骼 {}（隐藏 {}）、渲染包围盒 {}×{}×{} 格（脚底 y {}）",
                model.label(), counts[0], counts[1],
                Math.round((max[0] - min[0]) * 100.0F) / 100.0F,
                Math.round((max[1] - min[1]) * 100.0F) / 100.0F,
                Math.round((max[2] - min[2]) * 100.0F) / 100.0F,
                Math.round(min[1] * 100.0F) / 100.0F);
            return Optional.of(geometry);
        } catch (Exception error) {
            WorldPlayers.LOGGER.warn("World Players: 解析 YSM 几何失败 {}：{}", model.label(), error.toString());
            return Optional.empty();
        }
    }

    /** 骨骼矩阵：{@code parent · T(pivot) · Rz·Ry·Rx · S · T(-pivot)}（和 {@code YsmModelRenderer} 一致）。 */
    public static float[] boneMatrix(float[] parent, Bone bone, float[] scale) {
        float[] m = Affine.mul(parent, Affine.translate(bone.pivot[0], bone.pivot[1], bone.pivot[2]));
        float rx = bone.rotation[0];
        float ry = bone.rotation[1];
        float rz = bone.rotation[2];
        if (rz != 0.0F) {
            m = Affine.mul(m, Affine.rotation(2, rz));
        }
        if (ry != 0.0F) {
            m = Affine.mul(m, Affine.rotation(1, ry));
        }
        if (rx != 0.0F) {
            m = Affine.mul(m, Affine.rotation(0, rx));
        }
        if (scale[0] != 1.0F || scale[1] != 1.0F || scale[2] != 1.0F) {
            m = Affine.mul(m, Affine.scale(scale[0], scale[1], scale[2]));
        }
        return Affine.mul(m, Affine.translate(-bone.pivot[0], -bone.pivot[1], -bone.pivot[2]));
    }

    /** 静态姿态下每个骨骼的顶点包围盒（方块单位），给日志 / 隐藏判定用。 */
    private static void staticRanges(Bone bone, float[] parent, Map<String, float[]> out, int[] counts) {
        counts[0]++;
        if (bone.hidden) {
            counts[1]++;
            return;
        }
        float[] m = boneMatrix(parent, bone, bone.scale);
        float[] range = out.get(bone.name);
        if (range == null) {
            range = new float[]{Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE,
                -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
            out.put(bone.name, range);
        }
        for (Quad quad : bone.quads) {
            for (int i = 0; i < 4; i++) {
                float[] p = Affine.apply(m, quad.pos[i * 3], quad.pos[i * 3 + 1], quad.pos[i * 3 + 2]);
                for (int axis = 0; axis < 3; axis++) {
                    range[axis] = Math.min(range[axis], p[axis]);
                    range[axis + 3] = Math.max(range[axis + 3], p[axis]);
                }
            }
        }
        for (Bone child : bone.children) {
            staticRanges(child, m, out, counts);
        }
    }

    /** 3×4 仿射矩阵（行主序）。 */
    public static final class Affine {
        public static final float[] IDENTITY = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0};

        public static float[] translate(float x, float y, float z) {
            return new float[]{1, 0, 0, x, 0, 1, 0, y, 0, 0, 1, z};
        }

        public static float[] scale(float x, float y, float z) {
            return new float[]{x, 0, 0, 0, 0, y, 0, 0, 0, 0, z, 0};
        }

        public static float[] rotation(int axis, float degrees) {
            float[] m = IDENTITY.clone();
            if (degrees == 0.0F) {
                return m;
            }
            double angle = Math.toRadians(degrees);
            float c = (float) Math.cos(angle);
            float s = (float) Math.sin(angle);
            if (axis == 0) {
                m[5] = c;
                m[6] = -s;
                m[9] = s;
                m[10] = c;
            } else if (axis == 1) {
                m[0] = c;
                m[2] = s;
                m[8] = -s;
                m[10] = c;
            } else {
                m[0] = c;
                m[1] = -s;
                m[4] = s;
                m[5] = c;
            }
            return m;
        }

        /** a·b（先 b 后 a）。 */
        public static float[] mul(float[] a, float[] b) {
            float[] out = new float[12];
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 3; col++) {
                    out[row * 4 + col] = a[row * 4] * b[col]
                        + a[row * 4 + 1] * b[4 + col]
                        + a[row * 4 + 2] * b[8 + col];
                }
                out[row * 4 + 3] = a[row * 4] * b[3]
                    + a[row * 4 + 1] * b[7]
                    + a[row * 4 + 2] * b[11]
                    + a[row * 4 + 3];
            }
            return out;
        }

        public static float[] apply(float[] m, float x, float y, float z) {
            return new float[]{
                m[0] * x + m[1] * y + m[2] * z + m[3],
                m[4] * x + m[5] * y + m[6] * z + m[7],
                m[8] * x + m[9] * y + m[10] * z + m[11]};
        }
    }

    /** 找头部骨骼的 pivot y（像素）；找不到返回 0。 */
    private static float headPivotY(JsonArray bonesJson) {
        float best = 0.0F;
        for (JsonElement element : bonesJson) {
            JsonObject json = element.getAsJsonObject();
            String name = json.has("name") ? json.get("name").getAsString() : "";
            if (!"head".equalsIgnoreCase(name)) {
                continue;
            }
            return vec3(json, "pivot", new float[3])[1];
        }
        for (JsonElement element : bonesJson) {
            JsonObject json = element.getAsJsonObject();
            String lower = (json.has("name") ? json.get("name").getAsString() : "").toLowerCase(java.util.Locale.ROOT);
            if (!lower.startsWith("head") || lower.startsWith("headwear")) {
                continue;
            }
            best = Math.max(best, vec3(json, "pivot", new float[3])[1]);
        }
        return best;
    }

    private static YsmGeometry parse(YsmModels.Model model) throws Exception {
        JsonObject root = JsonParser.parseString(model.readText(model.geometryPath())).getAsJsonObject();
        JsonArray geometries = root.getAsJsonArray("minecraft:geometry");
        if (geometries == null || geometries.isEmpty()) {
            throw new IllegalStateException("minecraft:geometry 为空");
        }
        JsonObject geometry = geometries.get(0).getAsJsonObject();
        JsonObject description = geometry.getAsJsonObject("description");
        float texW = description != null && description.has("texture_width")
            ? description.get("texture_width").getAsFloat() : 64.0F;
        float texH = description != null && description.has("texture_height")
            ? description.get("texture_height").getAsFloat() : 64.0F;

        JsonArray bonesJson = geometry.has("bones") ? geometry.getAsJsonArray("bones") : new JsonArray();

        // 尺寸：ysm.json 里写了 height_scale/width_scale 就用它；没写就按「Head 骨骼 pivot 归一到 BIPED 的
        // 24 像素（1.5 格）」自动缩放 —— 这正是 YSM 让各种模型都显示成玩家身材的做法。
        float hs = model.heightScale();
        float ws = model.widthScale();
        float auto = 0.0F;
        if (hs <= 0.0F) {
            float headPx = headPivotY(bonesJson);
            hs = headPx > 1.0F ? 24.0F / headPx : 1.0F;
            auto = hs;
        }
        if (ws <= 0.0F) {
            ws = hs;
        }
        WorldPlayers.LOGGER.debug("World Players: YSM 几何 {} 缩放 高×{} 宽×{}{}",
            model.label(), hs, ws, auto > 0 ? "（按 Head pivot 自动归一）" : "");

        Map<String, JsonObject> authored = new LinkedHashMap<>();
        Map<String, String> parentOf = new LinkedHashMap<>();
        for (JsonElement element : bonesJson) {
            JsonObject json = element.getAsJsonObject();
            String name = json.has("name") ? json.get("name").getAsString() : "bone";
            authored.put(name, json);
            parentOf.put(name, json.has("parent") ? json.get("parent").getAsString() : null);
        }

        Map<String, Bone> byName = new LinkedHashMap<>();
        for (Map.Entry<String, JsonObject> entry : authored.entrySet()) {
            String name = entry.getKey();
            JsonObject json = entry.getValue();
            float[] pivotPx = vec3(json, "pivot", new float[3]);
            float[] pivot = {
                -pivotPx[0] * ws * PIXEL,
                pivotPx[1] * hs * PIXEL,
                pivotPx[2] * ws * PIXEL};
            float[] rot = vec3(json, "rotation", new float[3]);
            float[] rotation = {-rot[0], -rot[1], rot[2]};
            boolean boneMirror = json.has("mirror") && json.get("mirror").getAsBoolean();
            float boneInflate = json.has("inflate") ? json.get("inflate").getAsFloat() : 0.0F;
            List<Quad> quads = new ArrayList<>();
            if (json.has("cubes")) {
                for (JsonElement cubeElement : json.getAsJsonArray("cubes")) {
                    buildCube(cubeElement.getAsJsonObject(), boneMirror, boneInflate,
                        texW, texH, ws, hs, quads);
                }
            }
            byName.put(name, new Bone(name, pivot, rotation, quads));
        }
        for (Bone bone : byName.values()) {
            String parent = parentOf.get(bone.name);
            String lowerName = bone.name.toLowerCase(java.util.Locale.ROOT);
            bone.mouthPart = lowerName.contains("mouth")
                || (parent != null && "mouth".equals(parent.toLowerCase(java.util.Locale.ROOT)));
        }

        List<Bone> roots = new ArrayList<>();
        for (Bone bone : byName.values()) {
            String parent = parentOf.get(bone.name);
            Bone parentBone = parent == null ? null : byName.get(parent);
            if (parentBone == null || parentBone == bone) {
                roots.add(bone);
            } else {
                parentBone.children.add(bone);
            }
        }

        // 第 ③ 步：默认帧（scale / visible）→ 藏掉 GUI 背景板、表情贴片、备用形态
        YsmDefaultPose.apply(model, byName);
        hideOversizedFaceParts(byName);
        hideUndergroundParts(roots);
        // ★ 不要调用 hideExtraMouths：alpha.41 试过「只留第一个 Mouth_*」，结果主人看到的是「嘴消失了」——
        // 说明第一个嘴不是作者实际显示的那个（各模型不同）。等控制器能真正决定 visible 时再说。
        int quads = 0;
        for (Bone bone : byName.values()) {
            quads += bone.quads.size();
        }
        return new YsmGeometry(roots, hs, ws, texW, texH, quads);
    }

    /**
     * 一个立方体 → 若干四边形（顶点已是绝对模型坐标）。
     *
     * <p>逐行对应 folkways {@code BedrockGeometry#buildCube}：{@code ox=-(x+sx)/16, oy=y/16, oz=z/16}，
     * 顶点 p1..p8 的命名、面到顶点的映射、mirror 的面交换、UV 分配都照搬。</p>
     */
    private static void buildCube(JsonObject json, boolean boneMirror, float boneInflatePx,
                                  float texW, float texH, float ws, float hs, List<Quad> out) {
        float[] origin = vec3(json, "origin", new float[3]);
        float[] size = vec3(json, "size", new float[3]);
        if (size[0] == 0.0F && size[1] == 0.0F && size[2] == 0.0F) {
            return;
        }
        boolean cubeMirror = json.has("mirror") && json.get("mirror").getAsBoolean();
        boolean mirror = cubeMirror || boneMirror;
        float inflatePx = json.has("inflate") ? json.get("inflate").getAsFloat() : boneInflatePx;

        float ox = -(origin[0] + size[0]) * ws * PIXEL;
        float oy = origin[1] * hs * PIXEL;
        float oz = origin[2] * ws * PIXEL;
        float sx = size[0] * ws * PIXEL;
        float sy = size[1] * hs * PIXEL;
        float sz = size[2] * ws * PIXEL;
        // ★ inflate 是**模型像素**单位，必须和 size 一样按 ws/hs 缩放（x/z 用宽度系数，y 用高度系数）。
        // 以前只乘了 PIXEL：hs=0.7~0.8 的模型上 inflate 被相对放大 1/0.7 倍 —— 负 inflate 的
        // 眼睛高光（size 1px、inflate −0.35）直接被削成 0 厚度，主人看到的「没有瞳孔 / 高光小成豆子」
        // 就是它；嘴方块（inflate −0.5）被削薄后深度变了，多个表情块在脸前轮流浮出/沉入。
        float inflateX = inflatePx * ws * PIXEL;
        float inflateY = inflatePx * hs * PIXEL;
        float inflateZ = inflatePx * ws * PIXEL;

        float[] p1 = {ox - inflateX, oy - inflateY, oz - inflateZ};
        float[] p2 = {ox - inflateX, oy - inflateY, oz + sz + inflateZ};
        float[] p3 = {ox - inflateX, oy + sy + inflateY, oz - inflateZ};
        float[] p4 = {ox - inflateX, oy + sy + inflateY, oz + sz + inflateZ};
        float[] p5 = {ox + sx + inflateX, oy - inflateY, oz - inflateZ};
        float[] p6 = {ox + sx + inflateX, oy - inflateY, oz + sz + inflateZ};
        float[] p7 = {ox + sx + inflateX, oy + sy + inflateY, oz - inflateZ};
        float[] p8 = {ox + sx + inflateX, oy + sy + inflateY, oz + sz + inflateZ};

        float bsx = (float) Math.floor(size[0]);
        float bsy = (float) Math.floor(size[1]);
        float bsz = (float) Math.floor(size[2]);

        float[] west = null;
        float[] east = null;
        float[] north = null;
        float[] south = null;
        float[] up = null;
        float[] down = null;
        JsonElement uvElement = json.get("uv");
        if (uvElement != null && uvElement.isJsonObject()) {
            JsonObject faceUv = uvElement.getAsJsonObject();
            west = faceRect(faceUv, "west");
            east = faceRect(faceUv, "east");
            north = faceRect(faceUv, "north");
            south = faceRect(faceUv, "south");
            up = faceRect(faceUv, "up");
            down = faceRect(faceUv, "down");
        } else {
            float u = 0.0F;
            float v = 0.0F;
            if (uvElement != null && uvElement.isJsonArray() && uvElement.getAsJsonArray().size() >= 2) {
                u = uvElement.getAsJsonArray().get(0).getAsFloat();
                v = uvElement.getAsJsonArray().get(1).getAsFloat();
            }
            west = new float[]{u + bsz + bsx, v + bsz, bsz, bsy};
            east = new float[]{u, v + bsz, bsz, bsy};
            north = new float[]{u + bsz, v + bsz, bsx, bsy};
            south = new float[]{u + bsz + bsx + bsz, v + bsz, bsx, bsy};
            up = new float[]{u + bsz, v, bsx, bsz};
            // ★ 底面：和 up 占同一行带（rows v..v+bsz），只是 V 方向翻过来（高度取负、基线是 v+bsz）。
            // 之前写成 (u+bsz+bsx, v, bsx, -bsz) 会采样到「上方那一带」—— 于是每块碎片的底面
            // 都吃到别人的颜色或透明像素，主人看到的「每块模型底部破损 / 颜色不对」就是它。
            // 证据：同一模型里 Blockbench 导出的显式面 UV，down 的 v 恒等于 up 的 v + up 的高度，
            // 且 uv_size 的高度是负数；up/down 横向并排、纵向同一带。
            down = new float[]{u + bsz + bsx, v + bsz, bsx, -bsz};
        }

        float[][] rects = {west, east, north, south, up, down};
        // mirror 时只交换 **west/east**（x 镜像后 +x 面变成 -x 面）。
        // ★ up / down 绝对**不能**换：x 镜像不改变「哪面在上、哪面在下」。
        // folkways 在这里把 up/down 的顶点组也换了（它那段实现有问题），照抄的后果是：
        // 所有 mirror 方块的**底面画的是 up 的贴图**。大部分方块上下贴图差不多（袖口/腿都一样），
        // 看不出来；但手掌这种上下花纹差别大的，就会在「底部」看到顶面的图案
        // —— 主人 2026-11-21 报的「右手底部是一圈灰色像素加白色中间」就是手套顶面那只圈。
        float[][][] defaultPos = {
            {p4, p3, p1, p2},
            {p7, p8, p6, p5},
            {p3, p7, p5, p1},
            {p8, p4, p2, p6},
            {p4, p8, p7, p3},
            {p1, p5, p6, p2}};
        float[][][] mirroredPos = {
            {p7, p8, p6, p5},
            {p4, p3, p1, p2},
            {p3, p7, p5, p1},
            {p8, p4, p2, p6},
            {p4, p8, p7, p3},
            {p1, p5, p6, p2}};
        float[][] faceNormal = {{-1, 0, 0}, {1, 0, 0}, {0, 0, -1}, {0, 0, 1}, {0, 1, 0}, {0, -1, 0}};

        float[][] quadsPos = new float[6][];
        float[][] quadsUv = new float[6][];
        float[][] quadsNormal = new float[6][];
        float[][][] order = mirror ? mirroredPos : defaultPos;
        for (int face = 0; face < 6; face++) {
            float[] a = order[face][0];
            float[] b = order[face][1];
            float[] c = order[face][2];
            float[] d = order[face][3];
            float[] n = faceNormal[face];
            makeQuad(a, b, c, d, rects[face], texW, texH, cubeMirror,
                n[0], n[1], n[2], face, quadsPos, quadsUv, quadsNormal);
        }

        // 立方体自转：绕自己的 pivot（也是绝对像素）旋转，直接烘焙进顶点
        float[] cubePivotPx = vec3(json, "pivot", new float[3]);
        float[] cubeRot = vec3(json, "rotation", new float[3]);
        float[] m = null;
        if (cubeRot[0] != 0.0F || cubeRot[1] != 0.0F || cubeRot[2] != 0.0F) {
            float cpx = -cubePivotPx[0] * ws * PIXEL;
            float cpy = cubePivotPx[1] * hs * PIXEL;
            float cpz = cubePivotPx[2] * ws * PIXEL;
            m = Affine.translate(cpx, cpy, cpz);
            if (cubeRot[2] != 0.0F) {
                m = Affine.mul(m, Affine.rotation(2, cubeRot[2]));
            }
            if (cubeRot[1] != 0.0F) {
                m = Affine.mul(m, Affine.rotation(1, -cubeRot[1]));
            }
            if (cubeRot[0] != 0.0F) {
                m = Affine.mul(m, Affine.rotation(0, -cubeRot[0]));
            }
            m = Affine.mul(m, Affine.translate(-cpx, -cpy, -cpz));
        }

        for (int face = 0; face < 6; face++) {
            float[] pos = quadsPos[face];
            if (pos == null) {
                continue;
            }
            float[] world = new float[12];
            for (int i = 0; i < 4; i++) {
                if (m == null) {
                    System.arraycopy(pos, i * 3, world, i * 3, 3);
                } else {
                    float[] p = Affine.apply(m, pos[i * 3], pos[i * 3 + 1], pos[i * 3 + 2]);
                    world[i * 3] = p[0];
                    world[i * 3 + 1] = p[1];
                    world[i * 3 + 2] = p[2];
                }
            }
            float[] normal = quadsNormal[face];
            if (m != null) {
                normal = normalize(
                    m[0] * normal[0] + m[1] * normal[1] + m[2] * normal[2],
                    m[4] * normal[0] + m[5] * normal[1] + m[6] * normal[2],
                    m[8] * normal[0] + m[9] * normal[1] + m[10] * normal[2]);
            }
            out.add(new Quad(world, quadsUv[face], normal));
        }
    }

    /** 生成一个面的 4 个顶点 + UV + 法线，写进 {@code out} 的第 {@code slot} 位。 */
    private static void makeQuad(float[] a, float[] b, float[] c, float[] d, float[] rect,
                                 float texW, float texH, boolean mirror,
                                 float nx, float ny, float nz, int slot,
                                 float[][] outPos, float[][] outUv, float[][] outNormal) {
        if (rect == null) {
            outPos[slot] = null;
            outUv[slot] = null;
            outNormal[slot] = null;
            return;
        }
        float u1 = rect[0] / texW;
        float v1 = rect[1] / texH;
        float u2 = (rect[0] + rect[2]) / texW;
        float v2 = (rect[1] + rect[3]) / texH;
        float[] uv;
        if (mirror) {
            uv = new float[]{u1, v1, u2, v1, u2, v2, u1, v2};
        } else {
            uv = new float[]{u2, v1, u1, v1, u1, v2, u2, v2};
        }
        float[] pos = {a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2], d[0], d[1], d[2]};
        outPos[slot] = pos;
        outUv[slot] = uv;
        outNormal[slot] = normalize(mirror ? -nx : nx, ny, nz);
    }

    private static float[] normalize(float x, float y, float z) {
        float len = (float) Math.sqrt(x * x + y * y + z * z);
        if (len < 1.0E-6F) {
            return new float[]{0.0F, 1.0F, 0.0F};
        }
        return new float[]{x / len, y / len, z / len};
    }

    /** uv: {u, v, w, h} 像素；没写这个面返回 null。 */
    private static float[] faceRect(JsonObject faceUv, String face) {
        if (!faceUv.has(face)) {
            return null;
        }
        JsonObject json = faceUv.getAsJsonObject(face);
        JsonArray uv = json.getAsJsonArray("uv");
        JsonArray size = json.getAsJsonArray("uv_size");
        if (uv == null || size == null) {
            return null;
        }
        return new float[]{uv.get(0).getAsFloat(), uv.get(1).getAsFloat(),
            size.get(0).getAsFloat(), size.get(1).getAsFloat()};
    }

    /** 藏掉「超大的表情 / 嘴部贴片」：薄片且最长边超过头骨骼 2 倍。 */
    private static void hideOversizedFaceParts(Map<String, Bone> bones) {
        float head = 0.0F;
        for (Bone bone : bones.values()) {
            if (!"head".equalsIgnoreCase(bone.name)) {
                continue;
            }
            float[] size = localSize(bone);
            if (size != null) {
                head = Math.max(head, Math.max(size[0], Math.max(size[1], size[2])));
            }
        }
        if (head <= 0.0F) {
            return;
        }
        float limit = head * 2.0F;
        for (Bone bone : bones.values()) {
            if (bone.hidden || bone.quads.isEmpty()) {
                continue;
            }
            String name = bone.name.toLowerCase(java.util.Locale.ROOT);
            if (!(name.contains("mouth") || name.contains("expression") || name.contains("eyebrow")
                || name.contains("foxeye") || name.contains("face") || name.contains("blush")
                || name.contains("tear"))) {
                continue;
            }
            float[] size = localSize(bone);
            if (size == null) {
                continue;
            }
            float thin = Math.min(size[0], Math.min(size[1], size[2]));
            float span = Math.max(size[0], Math.max(size[1], size[2]));
            if (thin <= 2.5F && span > limit) {
                bone.hidden = true;
            }
        }
    }

    /** 藏掉「渲染后穿到地下很深」的部件（比头最低点还低 2.5 格）。 */
    private static void hideUndergroundParts(List<Bone> roots) {
        Map<String, float[]> ranges = new LinkedHashMap<>();
        int[] counts = new int[2];
        for (Bone root : roots) {
            staticRanges(root, Affine.IDENTITY, ranges, counts);
        }
        float headY = Float.MAX_VALUE;
        for (Map.Entry<String, float[]> entry : ranges.entrySet()) {
            if ("head".equalsIgnoreCase(entry.getKey())) {
                headY = Math.min(headY, entry.getValue()[1]);
            }
        }
        if (headY == Float.MAX_VALUE) {
            return;
        }
        float limit = headY - 2.5F;
        for (Bone bone : allBones(roots)) {
            float[] range = ranges.get(bone.name);
            if (range != null && range[1] < limit) {
                bone.hidden = true;
            }
        }
    }

    /**
     * 同一父骨骼下多个 {@code Mouth_*}（酒狐有 chill/surprise/smile/smile2 四个）只有一个是"当前表情"，
     * 由控制器的 {@code visible}/{@code scale} 决定。没有控制器时四个全画 → 叠成一坨白块。
     * 这里只留第一个（模型 JSON 里的顺序就是作者的主表情），其余隐藏。
     */
    private static void hideExtraMouths(List<Bone> roots) {
        for (Bone bone : allBones(roots)) {
            Bone kept = null;
            for (Bone child : bone.children) {
                String lower = child.name.toLowerCase(java.util.Locale.ROOT);
                if (!lower.startsWith("mouth_")) {
                    continue;
                }
                if (kept == null) {
                    kept = child;
                } else {
                    child.hidden = true;
                }
            }
        }
    }

    /** 骨骼自身四边形的包围盒（骨骼局部坐标，方块单位）。 */
    @javax.annotation.Nullable
    private static float[] localSize(Bone bone) {
        float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        boolean any = false;
        for (Quad quad : bone.quads) {
            any = true;
            for (int i = 0; i < 4; i++) {
                for (int axis = 0; axis < 3; axis++) {
                    float value = quad.pos[i * 3 + axis];
                    min[axis] = Math.min(min[axis], value);
                    max[axis] = Math.max(max[axis], value);
                }
            }
        }
        if (!any) {
            return null;
        }
        return new float[]{max[0] - min[0], max[1] - min[1], max[2] - min[2]};
    }

    private static List<Bone> allBones(List<Bone> roots) {
        List<Bone> out = new ArrayList<>();
        for (Bone root : roots) {
            collectBones(root, out);
        }
        return out;
    }

    private static void collectBones(Bone bone, List<Bone> out) {
        out.add(bone);
        for (Bone child : bone.children) {
            collectBones(child, out);
        }
    }

    private static float[] vec3(JsonObject json, String key, float[] fallback) {
        if (!json.has(key) || !json.get(key).isJsonArray()) {
            return fallback;
        }
        JsonArray array = json.getAsJsonArray(key);
        if (array.size() < 3) {
            return fallback;
        }
        return new float[]{array.get(0).getAsFloat(), array.get(1).getAsFloat(), array.get(2).getAsFloat()};
    }
}
