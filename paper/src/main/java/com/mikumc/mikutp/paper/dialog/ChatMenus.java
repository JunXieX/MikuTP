package com.mikumc.mikutp.paper.dialog;

import com.mikumc.mikutp.common.data.Home;
import com.mikumc.mikutp.common.data.Warp;
import com.mikumc.mikutp.paper.service.MessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.function.Consumer;

/**
 * Clickable chat fallback for every dialog menu, used when dialogs are
 * disabled in the config or the client cannot open them.
 */
public final class ChatMenus {

    private final MessageService messages;

    public ChatMenus(MessageService messages) {
        this.messages = messages;
    }

    public void homeList(Player viewer, List<Home> homes, boolean delete, Consumer<String> unused) {
        if (homes.isEmpty()) {
            messages.send(viewer, "home.empty");
            return;
        }
        viewer.sendMessage(messages.render(viewer, "dialog.list.title-homes"));
        for (Home home : homes) {
            String command = (delete ? "/delhome " : "/home ") + home.name;
            viewer.sendMessage(line(home.name, command, home.position == null ? "" : home.position.shortText()));
        }
    }

    public void warpList(Player viewer, List<Warp> warps, boolean delete) {
        if (warps.isEmpty()) {
            messages.send(viewer, "warp.empty");
            return;
        }
        viewer.sendMessage(messages.render(viewer, "dialog.list.title-warps"));
        for (Warp warp : warps) {
            String command = (delete ? "/delwarp " : "/warp ") + warp.name;
            viewer.sendMessage(line(warp.name, command, warp.position == null ? "" : warp.position.shortText()));
        }
    }

    private Component line(String label, String command, String tooltip) {
        Component clickable = Component.text(" " + label)
                .clickEvent(ClickEvent.runCommand(command));
        if (tooltip != null && !tooltip.isBlank()) {
            clickable = clickable.hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                    Component.text(tooltip)));
        }
        return clickable;
    }
}
