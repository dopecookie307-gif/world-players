package com.worldplayers.compat.jade;

import com.worldplayers.WorldPlayers;
import com.worldplayers.resident.ResidentEntity;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

/**
 * Jade 插件入口（软兼容）。
 *
 * <p>Jade 会扫描 classpath 上的 {@link WailaPlugin} 注解自动加载；没装 Jade 时这个类**不会被加载**
 * （注解类型不存在，扫描器根本看不到它），也不会拖累模组本体。</p>
 *
 * <p>版本无关：不写死 Jade 的版本范围，注册调用整体包 try/catch —— 对方的 API 万一变了，
 * 最坏情况只是没有 Jade 信息栏 / 没有那两个开关，绝不会让游戏崩在启动阶段。</p>
 */
@WailaPlugin("worldplayers")
public class JadePlugin implements IWailaPlugin {
    @Override
    public void registerClient(IWailaClientRegistration registration) {
        try {
            registration.registerEntityComponent(ResidentInfoProvider.INSTANCE, ResidentEntity.class);
        } catch (Throwable incompatible) {
            WorldPlayers.LOGGER.warn("World Players: Jade 存在但接口不兼容，跳过 Jade 信息栏", incompatible);
        }
        // 设置里「显示居民信息」下面的五个开关：饥饿/工作/心情/体力 + 饥饿是否显示数字
        // （key 的 lang 名规则 = config.jade.plugin_<命名空间>.<路径>）
        try {
            registration.addConfig(ResidentInfoProvider.CONFIG_HUNGER, true);
            registration.addConfig(ResidentInfoProvider.CONFIG_HUNGER_NUMBERS, true);
            registration.addConfig(ResidentInfoProvider.CONFIG_WORK, true);
            registration.addConfig(ResidentInfoProvider.CONFIG_MOOD, true);
            registration.addConfig(ResidentInfoProvider.CONFIG_STAMINA, true);
            ResidentInfoProvider.markConfigRegistered();
        } catch (Throwable incompatible) {
            WorldPlayers.LOGGER.warn("World Players: Jade 配置项注册失败，信息行按默认值显示", incompatible);
        }
    }
}
