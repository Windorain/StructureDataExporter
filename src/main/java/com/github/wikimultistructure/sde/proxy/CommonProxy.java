package com.github.wikimultistructure.sde.proxy;

import com.github.wikimultistructure.sde.network.SdeNetwork;
import com.github.wikimultistructure.sde.network.packet.PacketSyncSelection;

public class CommonProxy implements IProxy {

    @Override
    public void initNetwork() {
        SdeNetwork.init();
    }

    @Override
    public void applySelectionSync(PacketSyncSelection packet) {
        // 服务端无客户端缓存
    }
}
