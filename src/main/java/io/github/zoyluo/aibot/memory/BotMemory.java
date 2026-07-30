package io.github.zoyluo.aibot.memory;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class BotMemory {
    private static final int FACT_INJECT_LIMIT = 8;
    private static final int PLACE_INJECT_LIMIT = 10;
    /** 注入大脑上下文时最多展开的未完成条目数,超出折叠成 "+N more",防列表撑爆上下文。 */
    private static final int TODO_INJECT_LIMIT = 10;
    /** 未完成(pending+doing)条目上限:纯防 LLM 失控刷列表,正常用不到。 */
    private static final int TODO_MAX_OPEN = 64;
    /** 已结束(done+cancelled)条目保留上限,超出按列表序从最前淘汰,避免无限增长。 */
    private static final int TODO_CLOSED_KEEP = 30;

    private final Map<String, String> facts = new LinkedHashMap<>();
    private final Map<String, Place> places = new LinkedHashMap<>();
    private final Deque<String> goalSteps = new ArrayDeque<>();
    private int goalCursor;
    private String goalTitle = "";
    private final List<TodoItem> todos = new ArrayList<>();
    private int todoNextId = 1;

    public void remember(String key, String value) {
        facts.put(cleanKey(key), value == null ? "" : value.trim());
    }

    public Optional<String> recall(String key) {
        return Optional.ofNullable(facts.get(cleanKey(key)));
    }

    public boolean forget(String key) {
        return facts.remove(cleanKey(key)) != null;
    }

    public void markPlace(String name, ServerWorld world, BlockPos pos) {
        places.put(cleanKey(name), new Place(world.getRegistryKey().getValue().toString(), pos.toImmutable()));
    }

    public Optional<Place> place(String name) {
        return Optional.ofNullable(places.get(cleanKey(name)));
    }

    public Optional<BlockPos> placeIn(ServerWorld world, String... names) {
        String dimension = world.getRegistryKey().getValue().toString();
        for (String name : names) {
            Place place = places.get(cleanKey(name));
            if (place != null && dimension.equals(place.dimension())) {
                return Optional.of(place.pos());
            }
        }
        return Optional.empty();
    }

    public void setGoal(String title, Iterable<String> steps) {
        goalTitle = title == null ? "" : title.trim();
        goalSteps.clear();
        for (String step : steps) {
            if (step != null && !step.isBlank()) {
                goalSteps.addLast(step.trim());
            }
        }
        goalCursor = 0;
    }

    public boolean clearGoal() {
        boolean changed = !goalTitle.isBlank() || !goalSteps.isEmpty() || goalCursor != 0;
        goalTitle = "";
        goalSteps.clear();
        goalCursor = 0;
        return changed;
    }

    public String advanceGoal(String result) {
        if (goalSteps.isEmpty()) {
            return "no_goal";
        }
        goalCursor = Math.min(goalCursor + 1, goalSteps.size());
        return goalStatus(result);
    }

    public String goalStatus(String lastResult) {
        if (goalSteps.isEmpty()) {
            return "No active long-term goal.";
        }
        String current = currentGoalStep().orElse("complete");
        String suffix = lastResult == null || lastResult.isBlank() ? "" : " last_result=" + lastResult.trim();
        return "Goal '" + goalTitle + "' step " + Math.min(goalCursor + 1, goalSteps.size()) + "/" + goalSteps.size()
                + ": " + current + suffix;
    }

    public boolean hasActiveGoal() {
        return currentGoalStep().isPresent();
    }

    public String goalTitle() {
        return goalTitle;
    }

    public int goalCurrentStepIndex() {
        return goalSteps.isEmpty() ? 0 : Math.min(goalCursor, goalSteps.size());
    }

    public int goalTotalSteps() {
        return goalSteps.size();
    }

    public List<String> goalSteps() {
        return List.copyOf(new ArrayList<>(goalSteps));
    }

    public String goalDriveStatus(String lastResult) {
        if (goalSteps.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("Goal: ").append(goalTitle.isBlank() ? "(untitled)" : goalTitle).append("\n");
        builder.append("- progress: ").append(Math.min(goalCursor, goalSteps.size())).append("/").append(goalSteps.size()).append(" completed\n");
        builder.append("- current_step: ").append(currentGoalStep().orElse("complete")).append("\n");
        int index = 0;
        int remaining = 0;
        for (String step : goalSteps) {
            if (index++ >= goalCursor) {
                remaining++;
            }
        }
        builder.append("- remaining_steps: ").append(remaining).append("\n");
        if (lastResult != null && !lastResult.isBlank()) {
            builder.append("- last_result: ").append(lastResult.trim()).append("\n");
        }
        return builder.toString().trim();
    }

    public Optional<String> currentGoalStep() {
        if (goalCursor < 0 || goalCursor >= goalSteps.size()) {
            return Optional.empty();
        }
        int index = 0;
        for (String step : goalSteps) {
            if (index == goalCursor) {
                return Optional.of(step);
            }
            index++;
        }
        return Optional.empty();
    }

    // ---- LLM 自管任务队列(todo)----
    // 纯清单层:条目完全由 LLM 经 todo_* 工具增删改序、标记状态,与 TaskManager/GoalExecutor 的实际执行不联动。
    // 随 bot NBT 持久化(BotPersistence),每轮经 inject() 注入大脑上下文,LLM 不查也看得见未完成项。
    // 非法入参(未知 id/状态、空标题)抛 IllegalArgumentException,dispatcher 转 bad_arg 结果。

    public String addTodo(String title, int afterId) {
        String clean = title == null ? "" : title.trim();
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("missing_todo_title");
        }
        if (todoOpenCount() >= TODO_MAX_OPEN) {
            throw new IllegalArgumentException("todo_list_full: max " + TODO_MAX_OPEN + " open items");
        }
        int id = todoNextId++;
        TodoItem item = new TodoItem(id, clean, TodoStatus.PENDING);
        todos.add(afterId < 0 ? todos.size() : anchorIndex(afterId), item);
        return "added #" + id + ": " + clean;
    }

    /** status/title 为 null 保留原值;move=true 时按 moveAfterId 重排(0=队首,N=排在#N后)。 */
    public String updateTodo(int id, TodoStatus status, String title, int moveAfterId, boolean move) {
        int index = indexOfTodo(id);
        boolean relocate = move && moveAfterId != id;
        if (relocate && moveAfterId != 0) {
            indexOfTodo(moveAfterId); // 先校验锚点,避免改了一半才抛异常留下半截变更;0=队首不用校验
        }
        TodoItem old = todos.get(index);
        String newTitle = title == null ? old.title() : title.trim();
        if (newTitle.isEmpty()) {
            throw new IllegalArgumentException("missing_todo_title");
        }
        TodoItem updated = new TodoItem(id, newTitle, status == null ? old.status() : status);
        todos.set(index, updated);
        if (relocate) {
            todos.remove(index);
            todos.add(anchorIndex(moveAfterId), updated);
        }
        if (!updated.status().open()) {
            trimClosed();
        }
        return "#" + updated.id() + " [" + updated.status().label() + "] " + updated.title();
    }

    public String removeTodo(int id) {
        TodoItem removed = todos.remove(indexOfTodo(id));
        return "removed #" + removed.id() + ": " + removed.title();
    }

    /** status 为 null = 清掉全部 done+cancelled;非 null 只许 done/cancelled(清未完成项太狠,拒绝)。 */
    public String clearTodos(TodoStatus status) {
        if (status != null && status.open()) {
            throw new IllegalArgumentException("todo_clear_only_done_or_cancelled");
        }
        int done = 0;
        int cancelled = 0;
        java.util.Iterator<TodoItem> iterator = todos.iterator();
        while (iterator.hasNext()) {
            TodoStatus itemStatus = iterator.next().status();
            if (itemStatus == TodoStatus.DONE && (status == null || status == TodoStatus.DONE)) {
                iterator.remove();
                done++;
            } else if (itemStatus == TodoStatus.CANCELLED && (status == null || status == TodoStatus.CANCELLED)) {
                iterator.remove();
                cancelled++;
            }
        }
        return "cleared " + (done + cancelled) + " (done=" + done + ", cancelled=" + cancelled + ")";
    }

    public List<TodoItem> todos() {
        return List.copyOf(todos);
    }

    public int todoOpenCount() {
        int count = 0;
        for (TodoItem item : todos) {
            if (item.status().open()) {
                count++;
            }
        }
        return count;
    }

    private int indexOfTodo(int id) {
        for (int index = 0; index < todos.size(); index++) {
            if (todos.get(index).id() == id) {
                return index;
            }
        }
        throw new IllegalArgumentException("unknown_todo_id: " + id);
    }

    /** 插入锚点:0=队首;N=排在#N之后;未知 id 抛异常。 */
    private int anchorIndex(int afterId) {
        if (afterId == 0) {
            return 0;
        }
        return indexOfTodo(afterId) + 1;
    }

    private void trimClosed() {
        int closed = 0;
        for (TodoItem item : todos) {
            if (!item.status().open()) {
                closed++;
            }
        }
        while (closed > TODO_CLOSED_KEEP) {
            for (int index = 0; index < todos.size(); index++) {
                if (!todos.get(index).status().open()) {
                    todos.remove(index);
                    closed--;
                    break;
                }
            }
        }
    }

    private String todoInjection() {
        StringBuilder builder = new StringBuilder();
        int shown = 0;
        int hiddenOpen = 0;
        int done = 0;
        int cancelled = 0;
        for (TodoItem item : todos) {
            if (item.status() == TodoStatus.DONE) {
                done++;
                continue;
            }
            if (item.status() == TodoStatus.CANCELLED) {
                cancelled++;
                continue;
            }
            if (shown < TODO_INJECT_LIMIT) {
                builder.append("- #").append(item.id()).append(" [").append(item.status().label()).append("] ")
                        .append(item.title()).append("\n");
                shown++;
            } else {
                hiddenOpen++;
            }
        }
        if (hiddenOpen > 0) {
            builder.append("- ...(+").append(hiddenOpen).append(" more open, use todo_list)\n");
        }
        if (done + cancelled > 0) {
            builder.append("(").append(done).append(" done, ").append(cancelled).append(" cancelled; todo_clear to prune)\n");
        }
        return builder.toString().trim();
    }

    public String inject() {
        StringBuilder builder = new StringBuilder();
        if (!places.isEmpty()) {
            builder.append("Known places:\n");
            int count = 0;
            for (Map.Entry<String, Place> entry : places.entrySet()) {
                if (count++ >= PLACE_INJECT_LIMIT) {
                    break;
                }
                Place place = entry.getValue();
                builder.append("- ").append(entry.getKey()).append(" = ")
                        .append(place.dimension()).append(" ")
                        .append(place.pos().getX()).append(",")
                        .append(place.pos().getY()).append(",")
                        .append(place.pos().getZ()).append("\n");
            }
        }
        if (!goalSteps.isEmpty()) {
            builder.append("Long-term goal:\n").append(goalDriveStatus("")).append("\n");
        }
        if (!todos.isEmpty()) {
            builder.append("Task queue:\n").append(todoInjection()).append("\n");
        }
        if (!facts.isEmpty()) {
            builder.append("Remembered facts:\n");
            int start = Math.max(0, facts.size() - FACT_INJECT_LIMIT);
            int index = 0;
            for (Map.Entry<String, String> entry : facts.entrySet()) {
                if (index++ < start) {
                    continue;
                }
                builder.append("- ").append(entry.getKey()).append(" = ").append(entry.getValue()).append("\n");
            }
        }
        return builder.toString().trim();
    }

    public NbtCompound toNbt() {
        NbtCompound root = new NbtCompound();
        NbtCompound factNbt = new NbtCompound();
        facts.forEach(factNbt::putString);
        root.put("facts", factNbt);
        NbtCompound placeNbt = new NbtCompound();
        for (Map.Entry<String, Place> entry : places.entrySet()) {
            NbtCompound place = new NbtCompound();
            place.putString("dimension", entry.getValue().dimension());
            place.putInt("x", entry.getValue().pos().getX());
            place.putInt("y", entry.getValue().pos().getY());
            place.putInt("z", entry.getValue().pos().getZ());
            placeNbt.put(entry.getKey(), place);
        }
        root.put("places", placeNbt);
        root.putString("goalTitle", goalTitle);
        root.putInt("goalCursor", goalCursor);
        NbtList steps = new NbtList();
        goalSteps.forEach(step -> steps.add(NbtString.of(step)));
        root.put("goalSteps", steps);
        root.putInt("todoNextId", todoNextId);
        NbtList todoNbt = new NbtList();
        for (TodoItem item : todos) {
            NbtCompound todo = new NbtCompound();
            todo.putInt("id", item.id());
            todo.putString("title", item.title());
            todo.putString("status", item.status().name());
            todoNbt.add(todo);
        }
        root.put("todos", todoNbt);
        return root;
    }

    public void load(NbtCompound root) {
        facts.clear();
        places.clear();
        goalSteps.clear();
        goalCursor = 0;
        goalTitle = "";
        todos.clear();
        todoNextId = 1;
        NbtCompound factNbt = root.getCompound("facts");
        for (String key : factNbt.getKeys()) {
            facts.put(key, factNbt.getString(key));
        }
        NbtCompound placeNbt = root.getCompound("places");
        for (String key : placeNbt.getKeys()) {
            NbtCompound place = placeNbt.getCompound(key);
            places.put(key, new Place(
                    place.getString("dimension"),
                    new BlockPos(place.getInt("x"), place.getInt("y"), place.getInt("z"))));
        }
        goalTitle = root.getString("goalTitle");
        goalCursor = Math.max(0, root.getInt("goalCursor"));
        NbtList steps = root.getList("goalSteps", net.minecraft.nbt.NbtElement.STRING_TYPE);
        for (int index = 0; index < steps.size(); index++) {
            goalSteps.addLast(steps.getString(index));
        }
        goalCursor = Math.min(goalCursor, goalSteps.size());
        // 容错:坏状态/空标题条目跳过;老存档没有 todoNextId 键时按已见最大 id+1 续号,保证 id 永不复用。
        // 坏条目也占号(先记 maxTodoId 再校验):LLM 上下文里的旧引用可能还指着这些号,复用会造成语义错乱。
        int maxTodoId = 0;
        NbtList todoNbt = root.getList("todos", net.minecraft.nbt.NbtElement.COMPOUND_TYPE);
        for (int index = 0; index < todoNbt.size(); index++) {
            NbtCompound todo = todoNbt.getCompound(index);
            int todoId = todo.getInt("id");
            maxTodoId = Math.max(maxTodoId, todoId);
            TodoStatus status = TodoStatus.parseLenient(todo.getString("status"));
            String todoTitle = todo.getString("title").trim();
            if (status == null || todoTitle.isEmpty()) {
                continue;
            }
            todos.add(new TodoItem(todoId, todoTitle, status));
        }
        todoNextId = Math.max(Math.max(1, root.getInt("todoNextId")), maxTodoId + 1);
    }

    private static String cleanKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("missing_memory_key");
        }
        return key.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public record Place(String dimension, BlockPos pos) {
    }

    /** todo 条目状态。parse 容忍 LLM 常见同义写法,未知值抛 IllegalArgumentException(dispatcher 转 bad_arg)。 */
    public enum TodoStatus {
        PENDING,
        DOING,
        DONE,
        CANCELLED;

        public boolean open() {
            return this == PENDING || this == DOING;
        }

        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static TodoStatus parse(String raw) {
            TodoStatus status = parseLenient(raw);
            if (status == null) {
                throw new IllegalArgumentException("unknown_todo_status: " + raw + " (want pending|doing|done|cancelled)");
            }
            return status;
        }

        /** 宽松解析:坏值返回 null 而不抛,供 NBT 加载跳过损坏条目用。 */
        public static TodoStatus parseLenient(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "pending", "todo" -> PENDING;
                case "doing", "in_progress", "active" -> DOING;
                case "done", "completed", "complete" -> DONE;
                case "cancelled", "canceled", "cancel" -> CANCELLED;
                default -> null;
            };
        }
    }

    public record TodoItem(int id, String title, TodoStatus status) {
    }
}
