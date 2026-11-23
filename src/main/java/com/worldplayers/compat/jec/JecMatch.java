package com.worldplayers.compat.jec;

import net.neoforged.fml.ModList;

/**
 * JEC（Just Enough Characters / 通用拼音搜索）软兼容：**直接借它自带的拼音库**，不再自己硬编拼音表。
 *
 * <p>JEC 把 PinIn 的匹配逻辑放在 {@code me.towdium.jecharacters.utils.Match}：</p>
 * <ul>
 *   <li>{@code Match.contains(中文文本, 拼音串)}：全拼 / 首字母 / 简繁都支持；</li>
 *   <li>{@code Match.startsWith(中文文本, 拼音前缀)}：前缀匹配。</li>
 * </ul>
 *
 * <p><b>只在客户端、且装了 JEC 时才会加载这个类</b>（调用方先查 {@link #available()}，
 * 这个类里才 import JEC 的类型），没装 JEC 时不会有任何类加载错误。</p>
 */
public final class JecMatch {
    /** 是否装了 JEC（按 modId 查，不加载 JEC 的类）。 */
    public static boolean available() {
        try {
            return ModList.get().isLoaded("jecharacters");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 中文文本里是否包含这个查询串（支持拼音全拼 / 首字母 / 中文原文）。 */
    public static boolean contains(String text, String query) {
        if (text == null || query == null || query.isEmpty()) {
            return false;
        }
        try {
            return me.towdium.jecharacters.utils.Match.contains(text, query);
        } catch (Throwable ignored) {
            // JEC 版本不兼容 / 词典还没加载好：按「不匹配」处理，绝不崩溃
            return false;
        }
    }

    /** 中文文本是否以这个查询串开头（支持拼音前缀）。 */
    public static boolean startsWith(String text, String query) {
        if (text == null || query == null || query.isEmpty()) {
            return false;
        }
        try {
            return me.towdium.jecharacters.utils.Match.startsWith(text, query);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private JecMatch() {
    }
}
