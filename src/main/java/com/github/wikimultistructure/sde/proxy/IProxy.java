package com.github.wikimultistructure.sde.proxy;

import com.github.wikimultistructure.sde.network.packet.PacketSyncSelection;

public interface IProxy {

    void initNetwork();

    void applySelectionSync(PacketSyncSelection packet);
}
