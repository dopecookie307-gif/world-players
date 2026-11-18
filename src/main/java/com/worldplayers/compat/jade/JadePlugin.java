package com.worldplayers.compat.jade;

import com.worldplayers.resident.ResidentEntity;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

/**
 * Jade 插件入口。Jade 会扫描 classpath 上的 {@link WailaPlugin} 注解自动加载；
 * 没装 Jade 时这个类不会被加载，也不影响模组本体。
 */
@WailaPlugin("worldplayers")
public class JadePlugin implements IWailaPlugin {
    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerEntityComponent(ResidentInfoProvider.INSTANCE, ResidentEntity.class);
    }
}
