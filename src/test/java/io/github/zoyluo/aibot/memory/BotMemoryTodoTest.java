package io.github.zoyluo.aibot.memory;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BotMemoryTodoTest {

    @Test
    void addAssignsIncrementingIdsAndAppends() {
        BotMemory memory = new BotMemory();
        assertEquals("added #1: mine iron", memory.addTodo("mine iron", -1));
        assertEquals("added #2: build house", memory.addTodo("build house", -1));
        List<BotMemory.TodoItem> items = memory.todos();
        assertEquals(2, items.size());
        assertEquals(1, items.get(0).id());
        assertEquals(2, items.get(1).id());
        assertEquals(BotMemory.TodoStatus.PENDING, items.get(0).status());
    }

    @Test
    void addRespectsAnchorAndFrontInsertion() {
        BotMemory memory = new BotMemory();
        memory.addTodo("a", -1);
        memory.addTodo("b", -1);
        memory.addTodo("front", 0);        // 0 = 队首
        memory.addTodo("after-a", 1);      // 排在 #1(a) 后
        List<BotMemory.TodoItem> items = memory.todos();
        assertEquals(List.of("front", "a", "after-a", "b"),
                items.stream().map(BotMemory.TodoItem::title).toList());
    }

    @Test
    void addRejectsBlankTitleAndUnknownAnchor() {
        BotMemory memory = new BotMemory();
        assertThrows(IllegalArgumentException.class, () -> memory.addTodo("   ", -1));
        assertThrows(IllegalArgumentException.class, () -> memory.addTodo("x", 99));
    }

    @Test
    void idsAreNeverReusedAfterRemoval() {
        BotMemory memory = new BotMemory();
        memory.addTodo("a", -1);
        memory.addTodo("b", -1);
        memory.removeTodo(2);
        memory.addTodo("c", -1);
        assertEquals(3, memory.todos().get(1).id());
    }

    @Test
    void updateChangesStatusAndTitleIncrementally() {
        BotMemory memory = new BotMemory();
        memory.addTodo("mine iron", -1);
        assertEquals("#1 [doing] mine iron", memory.updateTodo(1, BotMemory.TodoStatus.DOING, null, 0, false));
        assertEquals("#1 [doing] mine diamonds", memory.updateTodo(1, null, "mine diamonds", 0, false));
        BotMemory.TodoItem item = memory.todos().get(0);
        assertEquals(BotMemory.TodoStatus.DOING, item.status());
        assertEquals("mine diamonds", item.title());
    }

    @Test
    void updateMovesItemWithoutPartialMutationOnBadAnchor() {
        BotMemory memory = new BotMemory();
        memory.addTodo("a", -1);
        memory.addTodo("b", -1);
        memory.addTodo("c", -1);
        // c 移到 a 之前(队首)
        memory.updateTodo(3, null, null, 0, true);
        assertEquals(List.of("c", "a", "b"), memory.todos().stream().map(BotMemory.TodoItem::title).toList());
        // 坏锚点:整体拒绝,位置不变(先校验再改)
        assertThrows(IllegalArgumentException.class, () -> memory.updateTodo(1, BotMemory.TodoStatus.DONE, null, 99, true));
        assertEquals(List.of("c", "a", "b"), memory.todos().stream().map(BotMemory.TodoItem::title).toList());
        assertEquals(BotMemory.TodoStatus.PENDING, memory.todos().get(1).status());
    }

    @Test
    void removeUnknownIdThrows() {
        BotMemory memory = new BotMemory();
        assertThrows(IllegalArgumentException.class, () -> memory.removeTodo(7));
    }

    @Test
    void clearOnlyRemovesClosedItems() {
        BotMemory memory = new BotMemory();
        memory.addTodo("a", -1);
        memory.addTodo("b", -1);
        memory.addTodo("c", -1);
        memory.updateTodo(1, BotMemory.TodoStatus.DONE, null, 0, false);
        memory.updateTodo(2, BotMemory.TodoStatus.CANCELLED, null, 0, false);
        assertEquals("cleared 1 (done=1, cancelled=0)", memory.clearTodos(BotMemory.TodoStatus.DONE));
        assertEquals(2, memory.todos().size());
        assertEquals("cleared 1 (done=0, cancelled=1)", memory.clearTodos(null));
        assertEquals(1, memory.todos().size());
        assertEquals("c", memory.todos().get(0).title());
        // 不允许清未完成项
        assertThrows(IllegalArgumentException.class, () -> memory.clearTodos(BotMemory.TodoStatus.PENDING));
    }

    @Test
    void closedItemsAreTrimmedBeyondKeepLimit() {
        BotMemory memory = new BotMemory();
        for (int i = 0; i < 35; i++) {
            memory.addTodo("task" + i, -1);
        }
        for (int id = 1; id <= 35; id++) {
            memory.updateTodo(id, BotMemory.TodoStatus.DONE, null, 0, false);
        }
        // 保留上限 30:最旧的 5 个已完成项被淘汰
        assertEquals(30, memory.todos().size());
        assertEquals(6, memory.todos().get(0).id());
        assertTrue(memory.todos().stream().allMatch(item -> item.status() == BotMemory.TodoStatus.DONE));
    }

    @Test
    void nbtRoundTripPreservesQueueAndContinuesIds() {
        BotMemory memory = new BotMemory();
        memory.addTodo("a", -1);
        memory.addTodo("b", -1);
        memory.updateTodo(1, BotMemory.TodoStatus.DOING, null, 0, false);
        memory.removeTodo(2);

        BotMemory fresh = new BotMemory();
        fresh.load(memory.toNbt());

        assertEquals(memory.todos(), fresh.todos());
        // 续号不复用:id 继续从 3 开始
        assertEquals("added #3: c", fresh.addTodo("c", -1));
    }

    @Test
    void nbtLoadSkipsCorruptEntriesAndKeepsIdsSafe() {
        NbtCompound root = new NbtCompound();
        NbtList list = new NbtList();
        NbtCompound good = new NbtCompound();
        good.putInt("id", 4);
        good.putString("title", "valid");
        good.putString("status", "DOING");
        list.add(good);
        NbtCompound badStatus = new NbtCompound();
        badStatus.putInt("id", 5);
        badStatus.putString("title", "broken status");
        badStatus.putString("status", "EXPLODED");
        list.add(badStatus);
        NbtCompound blankTitle = new NbtCompound();
        blankTitle.putInt("id", 6);
        blankTitle.putString("title", "  ");
        blankTitle.putString("status", "PENDING");
        list.add(blankTitle);
        root.put("todos", list);

        BotMemory memory = new BotMemory();
        memory.load(root);

        assertEquals(1, memory.todos().size());
        assertEquals("valid", memory.todos().get(0).title());
        // 老存档没有 todoNextId 键 → 按已见最大坏 id(6)+1 续号,依旧永不复用
        assertEquals("added #7: next", memory.addTodo("next", -1));
    }

    @Test
    void injectShowsOpenItemsWithClosedSummary() {
        BotMemory memory = new BotMemory();
        memory.addTodo("mine iron", -1);
        memory.addTodo("build house", -1);
        memory.updateTodo(1, BotMemory.TodoStatus.DOING, null, 0, false);
        memory.updateTodo(2, BotMemory.TodoStatus.DONE, null, 0, false);

        String injected = memory.inject();
        assertTrue(injected.contains("Task queue:"), injected);
        assertTrue(injected.contains("- #1 [doing] mine iron"), injected);
        assertTrue(injected.contains("(1 done, 0 cancelled"), injected);
        assertFalse(injected.contains("build house"), "closed items must not be expanded in injection");
    }

    @Test
    void injectFoldsOpenItemsBeyondLimit() {
        BotMemory memory = new BotMemory();
        for (int i = 0; i < 12; i++) {
            memory.addTodo("task" + i, -1);
        }
        String injected = memory.inject();
        assertTrue(injected.contains("- #10 [pending] task9"), injected);
        assertFalse(injected.contains("- #11 "), injected);
        assertTrue(injected.contains("(+2 more open"), injected);
    }

    @Test
    void statusParsingAcceptsAliasesAndRejectsUnknown() {
        assertEquals(BotMemory.TodoStatus.PENDING, BotMemory.TodoStatus.parse("pending"));
        assertEquals(BotMemory.TodoStatus.DOING, BotMemory.TodoStatus.parse("IN_PROGRESS"));
        assertEquals(BotMemory.TodoStatus.DONE, BotMemory.TodoStatus.parse(" completed "));
        assertEquals(BotMemory.TodoStatus.CANCELLED, BotMemory.TodoStatus.parse("canceled"));
        assertThrows(IllegalArgumentException.class, () -> BotMemory.TodoStatus.parse("exploded"));
        assertThrows(IllegalArgumentException.class, () -> BotMemory.TodoStatus.parse("  "));
    }
}
