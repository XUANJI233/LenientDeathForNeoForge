package com.lenientdeath.neoforge;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 死亡快照箱子界面（6x9）：
 * 1) 玩家列表 2) 某玩家快照列表 3) 快照详情（含恢复二次确认）。
 */
@SuppressWarnings("null") // Minecraft API 与静态分析注解不一致导致的空安全误报
public class SnapshotChestMenu extends AbstractContainerMenu {
    private static final int CHEST_SIZE = 54;
    private static final int LIST_START_SLOT = 9;
    private static final int LIST_END_SLOT_EXCLUSIVE = 45;
    private static final int LIST_PAGE_SIZE = LIST_END_SLOT_EXCLUSIVE - LIST_START_SLOT;

    private enum ViewKind {
        ROOT,
        PLAYER,
        SNAPSHOT
    }

    private static final class Session {
        ViewKind kind = ViewKind.ROOT;
        UUID targetPlayerId;
        String targetPlayerName;
        int snapshotId;
        boolean restoreConfirm;
        int rootPage = 1;
        int playerPage = 1;
    }

    private final ServerPlayer viewer;
    private final SimpleContainer chest;
    private final Session session;
    private final Map<Integer, Runnable> actions = new HashMap<>();

    private SnapshotChestMenu(int containerId, Inventory inventory, Session session) {
        super(MenuType.GENERIC_9x6, containerId);
        this.viewer = (ServerPlayer) inventory.player;
        this.chest = new SimpleContainer(CHEST_SIZE);
        this.session = session;

        for (int i = 0; i < CHEST_SIZE; i++) {
            addSlot(new Slot(chest, i, 8 + (i % 9) * 18, 18 + (i / 9) * 18) {
                @Override
                public boolean mayPickup(Player player) { return false; }
                @Override
                public boolean mayPlace(ItemStack stack) { return false; }
            });
        }

        for (int row = 0; row < 3; ++row) {
            for (int col = 0; col < 9; ++col) {
                addSlot(new Slot(inventory, col + row * 9 + 9, 8 + col * 18, 140 + row * 18));
            }
        }
        for (int col = 0; col < 9; ++col) {
            addSlot(new Slot(inventory, col, 8 + col * 18, 198));
        }

        render();
    }

    static void openRoot(ServerPlayer viewer) {
        Session s = new Session();
        open(viewer, s, titleFor(s));
    }

    static void openPlayer(ServerPlayer viewer, String playerName) {
        Session s = new Session();
        UUID id = findSnapshotPlayerIdByName(playerName);
        if (id == null) {
            viewer.sendSystemMessage(Component.translatable("lenientdeath.command.snapshot.player_not_found", playerName).withStyle(ChatFormatting.RED));
            return;
        }
        s.kind = ViewKind.PLAYER;
        s.targetPlayerId = id;
        s.targetPlayerName = playerName;
        s.playerPage = 1;
        open(viewer, s, titleFor(s));
    }

    static void openSnapshot(ServerPlayer viewer, String playerName, int snapshotId) {
        Session s = new Session();
        UUID id = findSnapshotPlayerIdByName(playerName);
        if (id == null) {
            viewer.sendSystemMessage(Component.translatable("lenientdeath.command.snapshot.player_not_found", playerName).withStyle(ChatFormatting.RED));
            return;
        }
        if (DeathEventHandler.getDeathDropSnapshot(id, snapshotId) == null) {
            viewer.sendSystemMessage(Component.translatable("lenientdeath.command.snapshot.not_found", snapshotId).withStyle(ChatFormatting.RED));
            return;
        }
        s.kind = ViewKind.SNAPSHOT;
        s.targetPlayerId = id;
        s.targetPlayerName = playerName;
        s.snapshotId = snapshotId;
        s.playerPage = 1;
        open(viewer, s, titleFor(s));
    }

    private static void open(ServerPlayer viewer, Session session, Component title) {
        viewer.openMenu(new SimpleMenuProvider((id, inv, player) -> new SnapshotChestMenu(id, inv, session), title));
    }

    private static Component titleFor(Session s) {
        return switch (s.kind) {
            case ROOT -> Component.translatable("lenientdeath.gui.snapshot.title.root");
            case PLAYER -> Component.translatable("lenientdeath.gui.snapshot.title.player", s.targetPlayerName);
            case SNAPSHOT -> Component.translatable("lenientdeath.gui.snapshot.title.snapshot", s.targetPlayerName, s.snapshotId);
        };
    }

    private void render() {
        actions.clear();
        for (int i = 0; i < CHEST_SIZE; i++) {
            chest.setItem(i, ItemStack.EMPTY);
        }

        switch (session.kind) {
            case ROOT -> renderRoot();
            case PLAYER -> renderPlayer();
            case SNAPSHOT -> renderSnapshot();
        }

        broadcastChanges();
    }

    private void renderRoot() {
        chest.setItem(4, named(Items.BOOK, Component.translatable("lenientdeath.gui.snapshot.root.header")));

        List<DeathEventHandler.DeathDropSnapshotPlayerSummary> players = DeathEventHandler.getDeathDropSnapshotPlayerSummaries();
        if (players.isEmpty()) {
            chest.setItem(22, named(Items.BARRIER, Component.translatable("lenientdeath.gui.snapshot.root.empty")));
            return;
        }

        int totalPages = Math.max(1, (players.size() + LIST_PAGE_SIZE - 1) / LIST_PAGE_SIZE);
        session.rootPage = Math.max(1, Math.min(session.rootPage, totalPages));
        int start = (session.rootPage - 1) * LIST_PAGE_SIZE;
        int end = Math.min(players.size(), start + LIST_PAGE_SIZE);

        int slot = LIST_START_SLOT;
        for (int i = start; i < end; i++) {
            DeathEventHandler.DeathDropSnapshotPlayerSummary p = players.get(i);
            ItemStack icon = named(Items.PAPER,
                    Component.translatable("lenientdeath.gui.snapshot.root.player", p.playerName(), p.snapshotCount()));
            chest.setItem(slot, icon);
            int clickSlot = slot;
            actions.put(clickSlot, () -> {
                session.kind = ViewKind.PLAYER;
                session.targetPlayerId = p.playerId();
                session.targetPlayerName = p.playerName();
                session.restoreConfirm = false;
                session.playerPage = 1;
                render();
            });
            slot++;
        }

        renderPageControls(session.rootPage, totalPages, true);
    }

    private void renderPlayer() {
        if (session.targetPlayerId == null) {
            session.kind = ViewKind.ROOT;
            render();
            return;
        }

        chest.setItem(4, named(Items.WRITABLE_BOOK,
                Component.translatable("lenientdeath.gui.snapshot.player.header", session.targetPlayerName)));

        List<DeathEventHandler.DeathDropSnapshotSummary> snapshots = DeathEventHandler.getDeathDropSnapshotSummaries(session.targetPlayerId);
        snapshots.sort(Comparator.comparingInt(DeathEventHandler.DeathDropSnapshotSummary::id).reversed());

        int totalPages = Math.max(1, (snapshots.size() + LIST_PAGE_SIZE - 1) / LIST_PAGE_SIZE);
        session.playerPage = Math.max(1, Math.min(session.playerPage, totalPages));

        if (snapshots.isEmpty()) {
            chest.setItem(22, named(Items.BARRIER,
                    Component.translatable("lenientdeath.gui.snapshot.player.empty", session.targetPlayerName)));
        } else {
            int start = (session.playerPage - 1) * LIST_PAGE_SIZE;
            int end = Math.min(snapshots.size(), start + LIST_PAGE_SIZE);
            int slot = LIST_START_SLOT;
            for (int i = start; i < end; i++) {
                DeathEventHandler.DeathDropSnapshotSummary summary = snapshots.get(i);
                ItemStack icon = named(Items.CHEST,
                        Component.translatable("lenientdeath.gui.snapshot.player.snapshot_entry",
                                summary.id(),
                                summary.itemStacks(),
                                summary.dimension().location().toString(),
                                summary.deathPos().getX(),
                                summary.deathPos().getY(),
                                summary.deathPos().getZ()));
                chest.setItem(slot, icon);
                int clickSlot = slot;
                actions.put(clickSlot, () -> {
                    session.kind = ViewKind.SNAPSHOT;
                    session.snapshotId = summary.id();
                    session.restoreConfirm = false;
                    render();
                });
                slot++;
            }
        }

        renderPageControls(session.playerPage, totalPages, false);

        chest.setItem(49, named(Items.ARROW, Component.translatable("lenientdeath.gui.snapshot.back.players")));
        actions.put(49, () -> {
            session.kind = ViewKind.ROOT;
            session.restoreConfirm = false;
            render();
        });
    }

    private void renderSnapshot() {
        if (session.targetPlayerId == null) {
            session.kind = ViewKind.ROOT;
            render();
            return;
        }

        DeathEventHandler.DeathDropSnapshotView view = DeathEventHandler.getDeathDropSnapshot(session.targetPlayerId, session.snapshotId);
        if (view == null) {
            session.kind = ViewKind.PLAYER;
            render();
            return;
        }

        var summary = view.summary();
        chest.setItem(4, named(Items.ENDER_CHEST,
                Component.translatable("lenientdeath.gui.snapshot.detail.header",
                        session.targetPlayerName,
                        summary.id(),
                        summary.itemStacks())));

        // 主背包 9-35 -> 行 1-3
        for (int slot = 9; slot <= 35; slot++) {
            placeSnapshotSlot(view, slot, slot);
        }

        // 快捷栏 0-8 -> 行 4
        for (int i = 0; i < 9; i++) {
            placeSnapshotSlot(view, i, 36 + i);
        }

        // 额外槽位 36-40 -> 行 5 前半
        for (int i = 36; i <= 40; i++) {
            placeSnapshotSlot(view, i, 45 + (i - 36));
        }

        ServerPlayer target = viewer.getServer() == null ? null : viewer.getServer().getPlayerList().getPlayerByName(session.targetPlayerName);
        boolean targetOnline = target != null;

        if (!targetOnline) {
            session.restoreConfirm = false;
            chest.setItem(52, named(Items.BARRIER,
                    Component.translatable("lenientdeath.gui.snapshot.detail.offline_warning", session.targetPlayerName)));
        } else {
            if (!session.restoreConfirm) {
                chest.setItem(52, named(Items.LIME_CONCRETE,
                        Component.translatable("lenientdeath.gui.snapshot.detail.restore")));
                actions.put(52, () -> {
                    session.restoreConfirm = true;
                    render();
                });
            } else {
                chest.setItem(51, named(Items.RED_CONCRETE,
                        Component.translatable("lenientdeath.gui.snapshot.detail.confirm_yes")));
                actions.put(51, this::doRestore);

                chest.setItem(53, named(Items.GRAY_CONCRETE,
                        Component.translatable("lenientdeath.gui.snapshot.detail.confirm_no")));
                actions.put(53, () -> {
                    session.restoreConfirm = false;
                    render();
                });
            }
        }

        chest.setItem(49, named(Items.ARROW,
                Component.translatable("lenientdeath.gui.snapshot.back.snapshots", session.targetPlayerName)));
        actions.put(49, () -> {
            session.kind = ViewKind.PLAYER;
            session.restoreConfirm = false;
            render();
        });
    }

    private void renderPageControls(int page, int totalPages, boolean rootView) {
        chest.setItem(50, named(Items.COMPASS,
                Component.translatable("lenientdeath.gui.snapshot.page_info", page, totalPages)));

        if (page > 1) {
            chest.setItem(47, named(Items.ARROW, Component.translatable("lenientdeath.gui.snapshot.page_prev")));
            actions.put(47, () -> {
                if (rootView) {
                    session.rootPage--;
                } else {
                    session.playerPage--;
                }
                render();
            });
        }

        if (page < totalPages) {
            chest.setItem(51, named(Items.SPECTRAL_ARROW, Component.translatable("lenientdeath.gui.snapshot.page_next")));
            actions.put(51, () -> {
                if (rootView) {
                    session.rootPage++;
                } else {
                    session.playerPage++;
                }
                render();
            });
        }
    }

    private void placeSnapshotSlot(DeathEventHandler.DeathDropSnapshotView view, int snapshotSlot, int chestSlot) {
        ItemStack stack = view.slotItems().get(snapshotSlot);
        if (stack == null || stack.isEmpty()) {
            chest.setItem(chestSlot, named(Items.GRAY_STAINED_GLASS_PANE,
                    Component.translatable("lenientdeath.gui.snapshot.slot.empty", snapshotSlot)));
            return;
        }

        ItemStack shown = stack.copy();
        shown.set(DataComponents.CUSTOM_NAME,
            Component.translatable("lenientdeath.gui.snapshot.slot.filled", snapshotSlot)
                .append(Component.literal(" "))
                .append(stack.getHoverName()));
        chest.setItem(chestSlot, shown);
    }

    private void doRestore() {
        ServerPlayer target = viewer.getServer() == null ? null : viewer.getServer().getPlayerList().getPlayerByName(session.targetPlayerName);
        if (target == null) {
            viewer.sendSystemMessage(Component.translatable("lenientdeath.command.snapshot.restore.player_offline", session.targetPlayerName)
                    .withStyle(ChatFormatting.RED));
            session.restoreConfirm = false;
            render();
            return;
        }

        int restored = DeathEventHandler.restoreDeathDropSnapshot(target, session.snapshotId);
        if (restored < 0) {
            viewer.sendSystemMessage(Component.translatable("lenientdeath.command.snapshot.not_found", session.snapshotId)
                    .withStyle(ChatFormatting.RED));
            session.restoreConfirm = false;
            render();
            return;
        }

        viewer.sendSystemMessage(Component.translatable("lenientdeath.command.snapshot.restore.success",
                session.snapshotId,
                session.targetPlayerName,
                restored).withStyle(ChatFormatting.GREEN));

        session.restoreConfirm = false;
        render();
    }

    private static UUID findSnapshotPlayerIdByName(String playerName) {
        for (DeathEventHandler.DeathDropSnapshotPlayerSummary p : DeathEventHandler.getDeathDropSnapshotPlayerSummaries()) {
            if (p.playerName().equalsIgnoreCase(playerName)) {
                return p.playerId();
            }
        }
        return null;
    }

    private static ItemStack named(net.minecraft.world.item.Item item, Component name) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, name.copy().withStyle(ChatFormatting.YELLOW));
        return stack;
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        // 顶部 6x9 仅作 UI 按钮和展示：触发动作后立即返回（render() 内已调用 broadcastChanges()）；
        // 无动作或非 PICKUP 类型时交由父类处理，父类将安全地执行空操作（mayPickup/mayPlace 均返回 false），
        // 同时发送游标同步包，防止幽灵物品出现。
        if (slotId >= 0 && slotId < CHEST_SIZE && clickType == ClickType.PICKUP) {
            Runnable action = actions.get(slotId);
            if (action != null) {
                action.run();
                return;
            }
        }

        // 底部玩家背包区域及无动作的展示槽：转交父类正常处理，保持客户端与服务端状态同步，避免幽灵物品
        super.clicked(slotId, button, clickType, player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }
}
