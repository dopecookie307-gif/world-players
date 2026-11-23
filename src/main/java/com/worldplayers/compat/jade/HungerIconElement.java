package com.worldplayers.compat.jade;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec2;
import snownee.jade.api.ui.Element;

/**
 * Jade 信息栏里的鸡腿图标（v0.2.11）。
 *
 * <p><b>两种画法</b>：</p>
 * <ul>
 *   <li>{@code single = false}：一排 10 格（满/半/空），步进 8px —— 和原版 HUD 的饥饿条一样，
 *       相邻图标重叠 1px，看起来是紧贴的一整条；</li>
 *   <li>{@code single = true}：只画**一个满格鸡腿**（给「饥饿值显示数字」用，后面跟 18/20）。</li>
 * </ul>
 *
 * <p><b>黑边怎么来的</b>（主人 2026-11-19 的方案）：原版 {@code hud/food_full} 贴图本身没有黑边，
 * 只有 {@code food_empty} 是黑轮廓。所以每一格都先画 {@code food_empty} **垫底**，再把
 * {@code food_full}/{@code food_half} 盖在上面 —— 空鸡腿的黑边就成了满鸡腿的描边。
 * 全是同一张 GUI 图集里的 sprite，所以**材质包换图标时（满/空各自带不带黑边）自动适配**，
 * 我们不用带贴图、也不用再自己画描边。</p>
 */
public class HungerIconElement extends Element {
    private static final ResourceLocation FOOD_FULL = ResourceLocation.withDefaultNamespace("hud/food_full");
    private static final ResourceLocation FOOD_HALF = ResourceLocation.withDefaultNamespace("hud/food_half");
    private static final ResourceLocation FOOD_EMPTY = ResourceLocation.withDefaultNamespace("hud/food_empty");
    private static final int ICON_SIZE = 9;
    /** 原版 HUD 的步进也是 8px（9px 图标重叠 1px）。 */
    private static final int ICON_STEP = 8;
    /** 10 个鸡腿 = 20 点（和玩家一样）。 */
    private static final int ICON_COUNT = 10;

    /** 饥饿值换算成的点数（0..20）。 */
    private final int points;
    /** true = 只画一个满格图标（配合数字）；false = 10 格条。 */
    private final boolean single;

    public HungerIconElement(int points, boolean single) {
        this.points = Math.max(0, Math.min(ICON_COUNT * 2, points));
        this.single = single;
    }

    @Override
    public Vec2 getSize() {
        if (this.single) {
            return new Vec2(ICON_SIZE, ICON_SIZE);
        }
        return new Vec2(ICON_STEP * (ICON_COUNT - 1) + ICON_SIZE, ICON_SIZE);
    }

    @Override
    public void render(GuiGraphics gui, float x, float y, float width, float height) {
        int baseX = Math.round(x);
        int baseY = Math.round(y);
        if (this.single) {
            this.drawOne(gui, baseX, baseY, FOOD_FULL);
            return;
        }
        for (int i = 0; i < ICON_COUNT; i++) {
            this.drawOne(gui, baseX + i * ICON_STEP, baseY, this.spriteFor(i));
        }
    }

    /** 一格：先空鸡腿垫底（黑边），再盖满/半（空格就只留黑轮廓）。 */
    private void drawOne(GuiGraphics gui, int x, int y, ResourceLocation top) {
        gui.blitSprite(FOOD_EMPTY, x, y, ICON_SIZE, ICON_SIZE);
        if (top != FOOD_EMPTY) {
            gui.blitSprite(top, x, y, ICON_SIZE, ICON_SIZE);
        }
    }

    /** 第 index 格画满/半/空（每格 2 点）。 */
    private ResourceLocation spriteFor(int index) {
        int filled = Math.max(0, Math.min(2, this.points - index * 2));
        if (filled >= 2) {
            return FOOD_FULL;
        }
        return filled == 1 ? FOOD_HALF : FOOD_EMPTY;
    }
}
