package com.github.wikimultistructure.sde.proxy;

import com.github.wikimultistructure.sde.network.packet.PacketSyncSelection;

public interface IProxy {

    void initNetwork();

    void applySelectionSync(PacketSyncSelection packet);

    /** 客户端：将导出 JSON 负载加入队列，在 tick 中捕获并写盘；服务端：空操作。 */
    void enqueueMeshCapturePayload(String fileName, byte[] utf8Json, boolean writeRaw);

    /** 客户端：打开体素备注编辑界面；服务端：空操作。 */
    void openCellNoteEditor(int frameIndex, int zSlice, int row, int column, String initialText);
}
