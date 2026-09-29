package com.mikumc.mikutp.paper.dialog;

import com.mikumc.mikutp.common.data.Home;
import com.mikumc.mikutp.common.data.TpRequest;
import com.mikumc.mikutp.common.data.Warp;
import com.mikumc.mikutp.paper.service.MessageService;
import com.mikumc.mikutp.paper.service.RequestService;
import com.mikumc.mikutp.paper.service.Tasks;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Native dialog menus (MC 1.21.6+ dialog feature). Every button runs a server
 * side callback; input dialogs deliver their text through the response view.
 * Callbacks may arrive off-thread, so each one re-schedules onto the player's
 * entity scheduler.
 */
public final class DialogFactory {

    private static final int INPUT_WIDTH = 250;
    private static final Duration CALLBACK_LIFETIME = Duration.ofMinutes(15);

    private final Tasks tasks;
    private final MessageService messages;
    private final int pageSize;

    public DialogFactory(Tasks tasks, MessageService messages, int pageSize) {
        this.tasks = tasks;
        this.messages = messages;
        this.pageSize = Math.max(4, pageSize);
    }

    // ------------------------------------------------------------------ tpa

    /** /tpa / /tpahere without an argument: type a player name and send. */
    public void showTpaTarget(Player viewer, boolean here, Consumer<String> onSend) {
        Component title = messages.render(viewer, "dialog.tpa.title");
        Component hint = messages.render(viewer, "dialog.tpa.hint");
        Component label = messages.render(viewer, "dialog.tpa.input-label");
        Component send = messages.render(viewer, here ? "dialog.tpa.here-send" : "dialog.tpa.send");
        Dialog dialog = Dialog.create(b -> b.empty()
                .base(DialogBase.builder(title)
                        .canCloseWithEscape(true)
                        .body(List.of(DialogBody.plainMessage(hint)))
                        .inputs(List.of(DialogInput.text("target", INPUT_WIDTH, label, true, "", 16, null)))
                        .build())
                .type(DialogType.multiAction(List.of(ActionButton.create(send, null, 200,
                        submit((response, audience) -> {
                            String typed = response.getText("target");
                            tasks.entity(viewer, () -> {
                                if (typed != null && !typed.isBlank()) {
                                    onSend.accept(typed.trim());
                                }
                            });
                        }))), null, 1)));
        viewer.showDialog(dialog);
    }

    /** Incoming request with accept / deny / block actions. */
    public void showRequest(Player target, TpRequest request, RequestService requestService) {
        Component title = messages.render(target, "dialog.request.title", "player", request.requesterName);
        Component body = messages.render(target,
                request.type == TpRequest.Type.COME ? "dialog.request.body-come" : "dialog.request.body-go",
                "player", request.requesterName);
        List<ActionButton> buttons = List.of(
                actionButton(messages.render(target, "dialog.request.accept"), 300,
                        () -> tasks.entity(target, () -> requestService.respond(target, request.id, RequestService.Response.ACCEPT))),
                actionButton(messages.render(target, "dialog.request.deny"), 300,
                        () -> tasks.entity(target, () -> requestService.respond(target, request.id, RequestService.Response.DENY))),
                actionButton(messages.render(target, "dialog.request.block"), 300,
                        () -> tasks.entity(target, () -> requestService.respond(target, request.id, RequestService.Response.BLOCK))));
        Dialog dialog = Dialog.create(b -> b.empty()
                .base(DialogBase.builder(title)
                        .canCloseWithEscape(true)
                        .body(List.of(DialogBody.plainMessage(body)))
                        .build())
                .type(DialogType.multiAction(buttons, null, 1)));
        target.showDialog(dialog);
    }

    // ------------------------------------------------------------------ homes

    public void showHomeList(Player viewer, List<Home> homes, int page, Consumer<String> onPick) {
        showHomePage(viewer, homes, page, onPick, false);
    }

    public void showHomeDelete(Player viewer, List<Home> homes, int page, Consumer<String> onPick) {
        showHomePage(viewer, homes, page, onPick, true);
    }

    // ------------------------------------------------------------------ warps

    public void showWarpList(Player viewer, List<Warp> warps, int page, Consumer<String> onPick) {
        showWarpPage(viewer, warps, page, onPick, false);
    }

    public void showWarpDelete(Player viewer, List<Warp> warps, int page, Consumer<String> onPick) {
        showWarpPage(viewer, warps, page, onPick, true);
    }

    /** Generic name input dialog (used by /sethome and /setwarp without arguments). */
    public void showNameInput(Player viewer, String titleKey, Consumer<String> onConfirm) {
        Component title = messages.render(viewer, titleKey);
        Component hint = messages.render(viewer, "dialog.name.hint");
        Component label = messages.render(viewer, "dialog.name.input-label");
        Component confirm = messages.render(viewer, "dialog.name.confirm");
        Dialog dialog = Dialog.create(b -> b.empty()
                .base(DialogBase.builder(title)
                        .canCloseWithEscape(true)
                        .body(List.of(DialogBody.plainMessage(hint)))
                        .inputs(List.of(DialogInput.text("name", INPUT_WIDTH, label, true, "", 32, null)))
                        .build())
                .type(DialogType.multiAction(List.of(ActionButton.create(confirm, null, 200,
                        submit((response, audience) -> {
                            String typed = response.getText("name");
                            tasks.entity(viewer, () -> {
                                if (typed != null && !typed.isBlank()) {
                                    onConfirm.accept(typed.trim());
                                }
                            });
                        }))), null, 1)));
        viewer.showDialog(dialog);
    }

    // ------------------------------------------------------------------ internals

    private void showHomePage(Player viewer, List<Home> homes, int page, Consumer<String> onPick, boolean delete) {
        int[] w = window(homes.size(), page);
        List<ActionButton> buttons = new ArrayList<>();
        for (int i = w[2]; i < w[3]; i++) {
            Home home = homes.get(i);
            buttons.add(actionButton(Component.text(home.name), 250,
                    () -> tasks.entity(viewer, () -> onPick.accept(home.name))));
        }
        Consumer<Integer> reopen = p -> showHomePage(viewer, homes, p, onPick, delete);
        addPageButtons(buttons, viewer, w[0], w[1], reopen);
        showList(viewer, delete ? "dialog.list.title-delhome" : "dialog.list.title-homes", buttons, w[1]);
    }

    private void showWarpPage(Player viewer, List<Warp> warps, int page, Consumer<String> onPick, boolean delete) {
        int[] w = window(warps.size(), page);
        List<ActionButton> buttons = new ArrayList<>();
        for (int i = w[2]; i < w[3]; i++) {
            Warp warp = warps.get(i);
            buttons.add(actionButton(Component.text(warp.name), 250,
                    () -> tasks.entity(viewer, () -> onPick.accept(warp.name))));
        }
        Consumer<Integer> reopen = p -> showWarpPage(viewer, warps, p, onPick, delete);
        addPageButtons(buttons, viewer, w[0], w[1], reopen);
        showList(viewer, delete ? "dialog.list.title-delwarp" : "dialog.list.title-warps", buttons, w[1]);
    }

    private void showList(Player viewer, String titleKey, List<ActionButton> buttons, int page) {
        Dialog dialog = Dialog.create(b -> b.empty()
                .base(DialogBase.builder(messages.render(viewer, titleKey, "page", String.valueOf(page)))
                        .canCloseWithEscape(true)
                        .build())
                .type(DialogType.multiAction(buttons, closeButton(viewer), 2)));
        viewer.showDialog(dialog);
    }

    /** Clamps the page and computes {pages, current, from, to} for a slice of {@code total} entries. */
    private int[] window(int total, int page) {
        int pages = Math.max(1, (total + pageSize - 1) / pageSize);
        int current = Math.min(Math.max(1, page), pages);
        int from = (current - 1) * pageSize;
        int to = Math.min(total, from + pageSize);
        return new int[]{pages, current, from, to};
    }

    private ActionButton actionButton(Component label, int width, Runnable onClick) {
        return ActionButton.create(label, null, width, submit((response, audience) -> onClick.run()));
    }

    private ActionButton closeButton(Player viewer) {
        return ActionButton.create(messages.render(viewer, "dialog.list.close"), null, 150, null);
    }

    private DialogAction submit(io.papermc.paper.registry.data.dialog.action.DialogActionCallback callback) {
        return DialogAction.customClick(callback,
                ClickCallback.Options.builder().uses(1).lifetime(CALLBACK_LIFETIME).build());
    }

    private void addPageButtons(List<ActionButton> buttons, Player viewer, int pages, int current,
                                Consumer<Integer> reopen) {
        if (pages <= 1) {
            return;
        }
        if (current > 1) {
            buttons.add(actionButton(messages.render(viewer, "dialog.list.prev"), 150, () -> reopen.accept(current - 1)));
        }
        if (current < pages) {
            buttons.add(actionButton(messages.render(viewer, "dialog.list.next"), 150, () -> reopen.accept(current + 1)));
        }
    }
}
