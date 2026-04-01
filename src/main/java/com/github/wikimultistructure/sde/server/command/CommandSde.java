package com.github.wikimultistructure.sde.server.command;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.MovingObjectPosition;

import com.github.wikimultistructure.sde.core.session.ExportSession;
import com.github.wikimultistructure.sde.core.util.RayTraceUtil;
import com.github.wikimultistructure.sde.network.SdeNetwork;
import com.github.wikimultistructure.sde.server.SdePermissions;

public class CommandSde extends CommandBase {

    @Override
    public String getCommandName() {
        return "sde";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/sde <pos1|pos2|start|end|setName|setFrame|setStructureId|record|export|status>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length == 0) {
            sender.addChatMessage(new ChatComponentText(getCommandUsage(sender)));
            return;
        }
        ExportSession s = ExportSession.get();
        String sub = args[0].toLowerCase();
        try {
            switch (sub) {
                case "pos1":
                    if (!checkPlayer(sender)) return;
                    applyPos1FromRay((EntityPlayerMP) sender, s);
                    break;
                case "pos2":
                    if (!checkPlayer(sender)) return;
                    applyPos2FromRay((EntityPlayerMP) sender, s);
                    break;
                case "start":
                    s.startSession();
                    sender.addChatMessage(new ChatComponentText("SDE: 会话已开始"));
                    if (sender instanceof EntityPlayerMP) {
                        SdeNetwork.sendSelectionSync((EntityPlayerMP) sender);
                    }
                    break;
                case "end":
                    s.endSession();
                    sender.addChatMessage(new ChatComponentText("SDE: 会话已结束"));
                    if (sender instanceof EntityPlayerMP) {
                        SdeNetwork.sendSelectionSync((EntityPlayerMP) sender);
                    }
                    break;
                case "setname":
                    s.setOutputName(joinArgs(args, 1));
                    sender.addChatMessage(new ChatComponentText("SDE: 输出文件名（无后缀）=" + s.getOutputName()));
                    break;
                case "setframe":
                    s.setActiveFrame(parseIntRequired(args, 1));
                    sender.addChatMessage(new ChatComponentText("SDE: 活动帧=" + s.getActiveFrame()));
                    break;
                case "setstructureid":
                    s.setStructureId(joinArgs(args, 1));
                    sender.addChatMessage(new ChatComponentText("SDE: structureId=" + s.getStructureId()));
                    break;
                case "record":
                    if (!checkPlayer(sender)) return;
                    s.record((EntityPlayerMP) sender);
                    sender.addChatMessage(new ChatComponentText("SDE: 已写入内存帧 " + s.getActiveFrame() + "（未落盘）"));
                    break;
                case "export":
                    if (!checkPlayer(sender)) return;
                    String path = s.exportToFile();
                    sender.addChatMessage(
                        new ChatComponentText("SDE: 已写出 " + path + "；已写入 pending_bundle.json，客户端将自动读盘生成材质包（无网络包）"));
                    break;
                case "status":
                    sender.addChatMessage(new ChatComponentText(s.statusLine()));
                    break;
                default:
                    sender.addChatMessage(new ChatComponentText("未知子命令: " + sub));
                    sender.addChatMessage(new ChatComponentText(getCommandUsage(sender)));
                    break;
            }
        } catch (Exception e) {
            sender.addChatMessage(new ChatComponentText("SDE 错误: " + e.getMessage()));
        }
    }

    private static void applyPos1FromRay(EntityPlayerMP player, ExportSession s) {
        if (!SdePermissions.canUseSde(player)) {
            player.addChatMessage(new ChatComponentText("SDE: 需要 OP 权限"));
            return;
        }
        MovingObjectPosition mop = RayTraceUtil.rayTraceBlock(player, RayTraceUtil.DEFAULT_REACH);
        if (!RayTraceUtil.isBlockHit(mop)) {
            player.addChatMessage(new ChatComponentText("SDE: 未指向方块"));
            return;
        }
        s.setPos1Block(mop.blockX, mop.blockY, mop.blockZ);
        SdeNetwork.sendSelectionSync(player);
        player.addChatMessage(new ChatComponentText("SDE: pos1 已记录（方块）"));
    }

    private static void applyPos2FromRay(EntityPlayerMP player, ExportSession s) {
        if (!SdePermissions.canUseSde(player)) {
            player.addChatMessage(new ChatComponentText("SDE: 需要 OP 权限"));
            return;
        }
        MovingObjectPosition mop = RayTraceUtil.rayTraceBlock(player, RayTraceUtil.DEFAULT_REACH);
        if (!RayTraceUtil.isBlockHit(mop)) {
            player.addChatMessage(new ChatComponentText("SDE: 未指向方块"));
            return;
        }
        s.setPos2Block(mop.blockX, mop.blockY, mop.blockZ);
        SdeNetwork.sendSelectionSync(player);
        player.addChatMessage(new ChatComponentText("SDE: pos2 已记录（方块）"));
    }

    private static boolean checkPlayer(ICommandSender sender) {
        if (!(sender instanceof EntityPlayerMP)) {
            sender.addChatMessage(new ChatComponentText("SDE: 该子命令仅玩家可用"));
            return false;
        }
        return true;
    }

    private static String joinArgs(String[] args, int from) {
        if (from >= args.length) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < args.length; i++) {
            if (i > from) sb.append(' ');
            sb.append(args[i]);
        }
        return sb.toString();
    }

    private static int parseIntRequired(String[] args, int i) {
        if (i >= args.length) throw new IllegalArgumentException("缺少整数参数");
        return Integer.parseInt(args[i]);
    }
}
