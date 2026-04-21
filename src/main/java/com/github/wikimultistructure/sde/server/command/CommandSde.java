package com.github.wikimultistructure.sde.server.command;

import java.nio.file.Files;
import java.nio.file.Paths;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.MovingObjectPosition;

import com.github.wikimultistructure.sde.core.session.ExportSession;
import com.github.wikimultistructure.sde.core.util.RayTraceUtil;
import com.github.wikimultistructure.sde.network.SdeNetwork;
import com.github.wikimultistructure.sde.server.SdePermissions;
import com.github.wikimultistructure.sde.server.web.SdeWebServer;

public class CommandSde extends CommandBase {

    @Override
    public String getCommandName() {
        return "sde";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/sde <pos1|pos2|start|end|setName|setFrame|setStructureId|record|export [raw]|dump|status|web [port]|webstop>";
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
                    EntityPlayerMP exporter = (EntityPlayerMP) sender;
                    boolean writeRaw = args.length >= 2 && "raw".equalsIgnoreCase(args[1]);
                    String path = s.exportToFile();
                    sender.addChatMessage(new ChatComponentText("SDE: 已写出场景文件: " + path));
                    java.io.File outFile = new java.io.File(path);
                    byte[] payload = Files.readAllBytes(Paths.get(path));
                    SdeNetwork.sendEnrichExportedScene(exporter, outFile.getName(), payload, writeRaw);
                    break;
                case "dump":
                    if (!checkPlayer(sender)) return;
                    String dumpRoot = s.requestRegistryDump();
                    sender.addChatMessage(
                        new ChatComponentText(
                            "SDE: 已写入 pending_dump.json，客户端将生成全量 block_registry.json / material_registry.json: "
                                + dumpRoot));
                    break;
                case "status":
                    sender.addChatMessage(new ChatComponentText(s.statusLine()));
                    break;
                case "web": {
                    int port = 37564;
                    if (args.length >= 2) {
                        port = Integer.parseInt(args[1]);
                    }
                    String tok = java.util.UUID.randomUUID()
                        .toString()
                        .replace("-", "");
                    SdeWebServer.start(port, tok);
                    int boundPort = SdeWebServer.getBoundPort();
                    if (boundPort != port) {
                        sender.addChatMessage(
                            new ChatComponentText("SDE: 端口 " + port + " 已被占用，Web 已使用 " + boundPort));
                    }
                    // 勿用 MinecraftServer.getServerHostname()/getHostname()：1.7.10 上为 @SideOnly(SERVER)，
                    // 集成服客户端环境会 NoSuchMethodError。本机浏览器用回环即可；远程访问请自行换为机器局域网 IP。
                    String host = "127.0.0.1";
                    String base = "http://" + host + ":" + boundPort;
                    String workbenchUrl = base + "/?apiBase="
                        + java.net.URLEncoder.encode(base, "UTF-8")
                        + "&token="
                        + tok;

                    IChatComponent webLine = new ChatComponentText("");
                    webLine.appendSibling(new ChatComponentText("SDE Web "));
                    webLine.appendSibling(sdeClickableLink("[根地址]", base, "浏览器打开 " + base));
                    webLine.appendSibling(new ChatComponentText("  "));
                    webLine.appendSibling(sdeClickableLink("[工作台]", workbenchUrl, "浏览器打开工作台（含 token）"));
                    sender.addChatMessage(webLine);

                    IChatComponent tokLine = new ChatComponentText("");
                    tokLine.appendSibling(new ChatComponentText("SDE Token（Bearer） "));
                    ChatComponentText tokHover = new ChatComponentText("[悬停查看]");
                    tokHover.setChatStyle(
                        new ChatStyle()
                            .setChatHoverEvent(
                                new HoverEvent(
                                    HoverEvent.Action.SHOW_TEXT,
                                    new ChatComponentText("Authorization: Bearer " + tok + "\n\n全文：\n" + tok)))
                            .setColor(EnumChatFormatting.GRAY));
                    tokLine.appendSibling(tokHover);
                    tokLine.appendSibling(new ChatComponentText(" "));
                    tokLine.appendSibling(sdeSuggestToken("[填入聊天栏]", tok));
                    sender.addChatMessage(tokLine);
                    break;
                }
                case "webstop":
                    SdeWebServer.stopServer();
                    sender.addChatMessage(new ChatComponentText("SDE Web 已停止"));
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

    private static ChatComponentText sdeClickableLink(String label, String url, String hover) {
        ChatComponentText t = new ChatComponentText(label);
        t.setChatStyle(
            new ChatStyle().setChatClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))
                .setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ChatComponentText(hover)))
                .setUnderlined(true)
                .setColor(EnumChatFormatting.AQUA));
        return t;
    }

    private static ChatComponentText sdeSuggestToken(String label, String token) {
        ChatComponentText t = new ChatComponentText(label);
        t.setChatStyle(
            new ChatStyle().setChatClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, token))
                .setChatHoverEvent(
                    new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ChatComponentText("点击将 token 填入聊天栏")))
                .setUnderlined(true)
                .setColor(EnumChatFormatting.GREEN));
        return t;
    }
}
