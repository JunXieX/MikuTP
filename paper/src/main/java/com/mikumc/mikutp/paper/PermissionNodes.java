package com.mikumc.mikutp.paper;

import java.util.List;

/**
 * Single source of truth for MikuTP permission nodes. The permissions section
 * of paper-plugin.yml must stay in sync (guarded by PaperPluginFileTest).
 */
public final class PermissionNodes {

    /** {@code def} matches {@link org.bukkit.permissions.PermissionDefault} names (lowercase). */
    public record Node(String node, String description, String def) {
    }

    public static final List<Node> NODES = List.of(
            new Node("mikutp.home", "使用 /home 与 /homes 回到自己的家", "true"),
            new Node("mikutp.home.set", "使用 /sethome 设置家", "true"),
            new Node("mikutp.home.delete", "使用 /delhome 删除家", "true"),
            new Node("mikutp.warp", "使用 /warp 前往服务器地标", "true"),
            new Node("mikutp.warp.manage", "使用 /setwarp 与 /delwarp 管理服务器地标", "op"),
            new Node("mikutp.tpa", "发起与应答传送请求（/tpa /tpahere 等）", "true"),
            new Node("mikutp.wild", "使用 /wild 随机传送", "true"),
            new Node("mikutp.back", "使用 /back 返回上次传送的位置", "true"),
            new Node("mikutp.dback", "使用 /dback 返回死亡地点", "true"),
            new Node("mikutp.otp", "使用 /otp 与 /otph 强制传送（管理员）", "op"),
            new Node("mikutp.admin", "使用 /mtp 管理命令（reload/resync/permissions/info）", "op"),
            new Node("mikutp.bypass.warmup", "免除传送预热等待", "false"),
            new Node("mikutp.bypass.cooldown", "免除传送冷却", "false"),
            new Node("mikutp.homes.unlimited", "不受家的数量上限限制", "false"));

    private PermissionNodes() {
    }
}
