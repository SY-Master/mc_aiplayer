package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.brain.BotReporter;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.observe.BotProfiler;
import io.github.zoyluo.aibot.observe.TpsGuard;
import io.github.zoyluo.aibot.runtime.ExecutionStack;
import io.github.zoyluo.aibot.runtime.TaskOrigin;
import net.minecraft.server.MinecraftServer;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;

public final class TaskManager {
    public static final TaskManager INSTANCE = new TaskManager();

    private final Map<UUID, Task> active = new ConcurrentHashMap<>();
    private final Map<UUID, TaskOrigin> activeOrigins = new ConcurrentHashMap<>();
    private final Map<UUID, ExecutionStack<Task>> executionStacks = new ConcurrentHashMap<>();
    private final Set<UUID> userPaused = ConcurrentHashMap.newKeySet();
    private final Map<UUID, TaskStatus> lastStatus = new ConcurrentHashMap<>();
    private final Map<UUID, FailureRecord> lastFailure = new ConcurrentHashMap<>();
    private final Map<UUID, FailureRecord> pendingFailure = new ConcurrentHashMap<>();
    private final Map<UUID, List<CompletableFuture<TaskStatus>>> completionFutures = new ConcurrentHashMap<>();

    private TaskManager() {
    }

    /**
     * 注册任务完成回调，返回一个在任务进入终态(COMPLETED/FAILED/CANCELLED)时完成的 Future。
     * 用于真同步工具调用：工具 handler 在服务器线程启动任务后，工作线程阻塞等待此 Future。
     * 支持同一 bot 注册多个 future（多个工具依次调用场景），所有 pending future 在任务完成时一起触发。
     */
    public CompletableFuture<TaskStatus> whenComplete(AIPlayerEntity bot) {
        CompletableFuture<TaskStatus> future = new CompletableFuture<>();
        completionFutures.computeIfAbsent(bot.getUuid(), k -> new ArrayList<>()).add(future);
        return future;
    }

    public void assign(AIPlayerEntity bot, Task task, TaskOrigin origin) {
        if (isUserPaused(bot) && !origin.safety()) {
            throw new IllegalStateException("mission_user_paused");
        }
        abort(bot);
        bot.getActionPack().stopAll();
        UUID uuid = bot.getUuid();
        active.put(uuid, task);
        activeOrigins.put(uuid, origin);
        try {
            task.start(bot);
        } catch (RuntimeException startFailure) {
            active.remove(uuid, task);
            activeOrigins.remove(uuid, origin);
            try {
                task.abort(bot);
                if (task instanceof AbstractTask abstractTask && task.state() == TaskState.FAILED) {
                    String message = startFailure.getMessage() == null
                            ? startFailure.getClass().getSimpleName()
                            : startFailure.getMessage();
                    abstractTask.failureReason = "start_failed:" + message;
                }
            } catch (RuntimeException cleanupFailure) {
                startFailure.addSuppressed(cleanupFailure);
            }
            try {
                bot.getActionPack().stopAll();
            } catch (RuntimeException cleanupFailure) {
                startFailure.addSuppressed(cleanupFailure);
            }
            TaskStatus failed = TaskStatus.from(task);
            lastStatus.put(uuid, failed);
            if (failed.state() == TaskState.FAILED) {
                recordFailure(bot, task.name(), failed.failureReason(), bot.getServer().getTicks());
            }
            BotReporter.INSTANCE.onStatus(bot.getServer(), bot, failed);
            BotLog.error(bot, "task_start_failed", startFailure, "name", task.name());
            throw startFailure;
        }
        TaskStatus status = TaskStatus.from(task);
        lastStatus.put(uuid, status);
        BotReporter.INSTANCE.onAssigned(bot, status);
        BotLog.task(bot, "task_assigned", "name", task.name(), "params", task.describe(),
                "origin", origin.kind(), "origin_reason", origin.reason());
    }

    public void abort(AIPlayerEntity bot) {
        Task current = active.remove(bot.getUuid());
        activeOrigins.remove(bot.getUuid());
        if (current != null) {
            current.abort(bot);
            lastStatus.put(bot.getUuid(), TaskStatus.from(current));
            BotReporter.INSTANCE.onStatus(bot.getServer(), bot, TaskStatus.from(current));
        }
    }

    /**
     * 把 bot 彻底复位到干净的空闲:停掉活跃/暂停任务、清失败记录与状态缓存,使 status() 返回 idle。
     * 供大脑在"反复失败已放弃"(max_turns)等场景善后调用——否则遗留任务 FAILED 后 lastStatus 会长期
     * 缓存 FAILED(面板/诊断一直显示卡死),pendingFailure 滞留也会让 idle-watcher 空转(实测发呆 13 分钟根因)。
     */
    public void resetToIdle(AIPlayerEntity bot) {
        UUID uuid = bot.getUuid();
        Task current = active.remove(uuid);
        if (current != null) {
            current.abort(bot);
        }
        ExecutionStack<Task> stack = executionStacks.remove(uuid);
        if (stack != null) {
            for (ExecutionStack.Frame<Task> frame : stack.drain()) {
                frame.work().abort(bot);
            }
        }
        activeOrigins.remove(uuid);
        userPaused.remove(uuid);
        lastFailure.remove(uuid);
        pendingFailure.remove(uuid);
        cancelFutures(uuid);
        lastStatus.put(uuid, TaskStatus.idle());
        BotReporter.INSTANCE.onStatus(bot.getServer(), bot, TaskStatus.idle());
    }

    /** User-intent cancellation: clear active and paused work without creating a failure/replan. */
    public boolean cancelIntentTasks(AIPlayerEntity bot, String reason) {
        UUID uuid = bot.getUuid();
        Task current = active.remove(uuid);
        activeOrigins.remove(uuid);
        ExecutionStack<Task> stack = executionStacks.remove(uuid);
        java.util.List<ExecutionStack.Frame<Task>> pausedFrames = stack == null ? java.util.List.of() : stack.drain();
        userPaused.remove(uuid);
        boolean hadFailure = lastFailure.remove(uuid) != null;
        boolean hadPendingFailure = pendingFailure.remove(uuid) != null;
        cancelFutures(uuid);
        if (current != null) {
            try {
                current.cancel(bot, reason);
            } catch (RuntimeException exception) {
                BotLog.error(bot, "task_cancel_cleanup_failed", exception, "name", current.name(), "reason", reason);
            }
        }
        for (ExecutionStack.Frame<Task> frame : pausedFrames) {
            Task pausedTask = frame.work();
            if (pausedTask == current) {
                continue;
            }
            try {
                pausedTask.cancel(bot, reason);
            } catch (RuntimeException exception) {
                BotLog.error(bot, "paused_task_cancel_cleanup_failed", exception, "name", pausedTask.name(), "reason", reason);
            }
        }
        Task representative = current != null ? current : pausedFrames.isEmpty() ? null : pausedFrames.get(0).work();
        if (representative != null) {
            TaskStatus cancelled = TaskStatus.from(representative);
            lastStatus.put(uuid, cancelled);
            BotReporter.INSTANCE.onStatus(bot.getServer(), bot, cancelled);
            BotLog.task(bot, "task_cancelled", "name", representative.name(), "reason", reason);
        } else if (hadFailure || hadPendingFailure) {
            lastStatus.put(uuid, TaskStatus.idle());
            BotReporter.INSTANCE.onStatus(bot.getServer(), bot, TaskStatus.idle());
        }
        return representative != null || hadFailure || hadPendingFailure;
    }

    public Optional<Task> getActive(AIPlayerEntity bot) {
        return Optional.ofNullable(active.get(bot.getUuid()));
    }

    public boolean hasPaused(AIPlayerEntity bot) {
        ExecutionStack<Task> stack = executionStacks.get(bot.getUuid());
        return stack != null && !stack.isEmpty();
    }

    public int pausedDepth(AIPlayerEntity bot) {
        ExecutionStack<Task> stack = executionStacks.get(bot.getUuid());
        return stack == null ? 0 : stack.size();
    }

    public Optional<TaskOrigin> activeOrigin(AIPlayerEntity bot) {
        return Optional.ofNullable(activeOrigins.get(bot.getUuid()));
    }

    public boolean isUserPaused(AIPlayerEntity bot) {
        return userPaused.contains(bot.getUuid());
    }

    public TaskStatus status(AIPlayerEntity bot) {
        Task current = active.get(bot.getUuid());
        if (current != null) {
            return TaskStatus.from(current);
        }
        ExecutionStack<Task> stack = executionStacks.get(bot.getUuid());
        if (stack != null && stack.peek().isPresent()) {
            return TaskStatus.from(stack.peek().orElseThrow().work());
        }
        return lastStatus.getOrDefault(bot.getUuid(), TaskStatus.idle());
    }

    public void pauseFor(AIPlayerEntity bot, String why) {
        pauseFor(bot, why, false);
    }

    /**
     * 暂停当前任务压栈。等待该任务的工具 future 立即以 PAUSED 状态完成(带上打断原因),
     * 让 LLM 马上知道"工作被打断了",而不是等下一个无关任务结束时被错填结果。
     *
     * @param explicitResumeOnly true = 该帧只能被显式 resume(LLM behavior_control action=resume)弹栈,
     *                           系统自动恢复跳过;用于死亡打断。false = 危险解除后自动恢复(威胁暂停)。
     */
    public void pauseFor(AIPlayerEntity bot, String why, boolean explicitResumeOnly) {
        UUID uuid = bot.getUuid();
        Task current = active.remove(uuid);
        TaskOrigin origin = activeOrigins.remove(uuid);
        if (current == null) {
            return;
        }
        current.pause(bot);
        if (current instanceof AbstractTask at) {
            at.failureReason = why; // 暂停原因透传给工具结果(PAUSED 状态的 message 要用)
        }
        TaskOrigin preservedOrigin = origin == null
                ? TaskOrigin.of(TaskOrigin.Kind.SYSTEM_BACKGROUND, "unknown_origin") : origin;
        ExecutionStack<Task> stack = executionStacks.computeIfAbsent(uuid, ignored -> new ExecutionStack<>());
        stack.push(current, preservedOrigin, explicitResumeOnly);
        TaskStatus status = TaskStatus.from(current);
        lastStatus.put(bot.getUuid(), status);
        BotReporter.INSTANCE.onStatus(bot.getServer(), bot, status);
        completeFuture(uuid, status); // 打断即通知:等待中的 LLM 工具立刻收到 paused 结果
        BotLog.task(bot, "task_paused", "name", current.name(), "why", why,
                "origin", preservedOrigin.kind(), "stack_depth", stack.size(),
                "explicit_resume_only", explicitResumeOnly);
    }

    /**
     * 死亡打断:不销毁工作——活跃任务暂停压栈并标记"仅显式恢复"(整条已暂停链同样标记),
     * 等 LLM 决定继续(behavior_control action=resume)还是放弃(action=stop)。
     * 等待中的工具 future 以 PAUSED 状态完成,不再是 cancelIntentTasks 的直接取消。
     */
    public void interruptForDeath(AIPlayerEntity bot) {
        UUID uuid = bot.getUuid();
        Task current = active.remove(uuid);
        TaskOrigin origin = activeOrigins.remove(uuid);
        ExecutionStack<Task> stack = executionStacks.get(uuid);
        if (stack != null) {
            stack.markAllExplicitResumeOnly();
        }
        userPaused.remove(uuid);
        lastFailure.remove(uuid);
        pendingFailure.remove(uuid);
        if (current == null || current.state() != TaskState.RUNNING) {
            cancelFutures(uuid); // 没有在跑的任务,不该有等任务的 future;防御性清掉
            if (current != null) {
                lastStatus.put(uuid, TaskStatus.from(current));
            }
            return;
        }
        current.pause(bot);
        if (current instanceof AbstractTask at) {
            at.failureReason = "bot_died";
        }
        TaskOrigin preservedOrigin = origin == null
                ? TaskOrigin.of(TaskOrigin.Kind.SYSTEM_BACKGROUND, "unknown_origin") : origin;
        executionStacks.computeIfAbsent(uuid, ignored -> new ExecutionStack<>())
                .push(current, preservedOrigin, true);
        TaskStatus status = TaskStatus.from(current);
        lastStatus.put(uuid, status);
        BotReporter.INSTANCE.onStatus(bot.getServer(), bot, status);
        completeFuture(uuid, status);
        BotLog.task(bot, "task_interrupted_by_death", "name", current.name(),
                "stack_depth", pausedDepth(bot));
    }

    public void resumeFromPause(AIPlayerEntity bot) {
        UUID uuid = bot.getUuid();
        if (active.containsKey(uuid)) {
            return;
        }
        ExecutionStack<Task> stack = executionStacks.get(uuid);
        if (stack == null) {
            return;
        }
        Optional<ExecutionStack.Frame<Task>> resumable = stack.popResumable(userPaused.contains(uuid));
        if (resumable.isEmpty()) {
            return;
        }
        resumeFrame(bot, stack, resumable.get(), false);
    }

    /**
     * 显式恢复栈顶被打断的工作(LLM behavior_control action=resume)。
     * 不看 explicitResumeOnly 标记——LLM 明确点名要继续。userPaused 锁仍由 IntentController 路径先解。
     *
     * @return true = 有工作被恢复;false = 没有可恢复的(栈空或有活跃任务)
     */
    public boolean resumeExplicit(AIPlayerEntity bot) {
        UUID uuid = bot.getUuid();
        if (active.containsKey(uuid)) {
            return false;
        }
        ExecutionStack<Task> stack = executionStacks.get(uuid);
        if (stack == null) {
            return false;
        }
        Optional<ExecutionStack.Frame<Task>> frame = stack.popExplicit();
        if (frame.isEmpty()) {
            return false;
        }
        resumeFrame(bot, stack, frame.get(), true);
        return true;
    }

    private void resumeFrame(AIPlayerEntity bot, ExecutionStack<Task> stack, ExecutionStack.Frame<Task> frame, boolean explicit) {
        UUID uuid = bot.getUuid();
        Task task = frame.work();
        if (task instanceof AbstractTask at) {
            at.failureReason = ""; // 清掉暂停原因,恢复后的 RUNNING 状态不带陈旧信息
        }
        active.put(uuid, task);
        activeOrigins.put(uuid, frame.origin());
        task.resume(bot);
        TaskStatus status = TaskStatus.from(task);
        lastStatus.put(uuid, status);
        BotReporter.INSTANCE.onStatus(bot.getServer(), bot, status);
        if (stack.isEmpty()) {
            executionStacks.remove(uuid, stack);
        }
        BotLog.task(bot, "task_resumed", "name", task.name(), "origin", frame.origin().kind(),
                "stack_depth", stack.size(), "user_paused", userPaused.contains(uuid), "explicit", explicit);
    }

    public boolean pauseUserIntent(AIPlayerEntity bot, String why) {
        UUID uuid = bot.getUuid();
        boolean changed = userPaused.add(uuid);
        TaskOrigin origin = activeOrigins.get(uuid);
        if (active.containsKey(uuid) && (origin == null || !origin.safety())) {
            pauseFor(bot, "user_pause:" + why);
            changed = true;
        }
        bot.getActionPack().stopAll();
        BotLog.task(bot, "mission_user_paused", "why", why, "stack_depth", pausedDepth(bot));
        return changed;
    }

    public boolean resumeUserIntent(AIPlayerEntity bot, String why) {
        UUID uuid = bot.getUuid();
        boolean changed = userPaused.remove(uuid);
        if (!active.containsKey(uuid)) {
            int before = pausedDepth(bot);
            resumeFromPause(bot);
            changed |= pausedDepth(bot) != before;
        }
        BotLog.task(bot, "mission_user_resumed", "why", why, "stack_depth", pausedDepth(bot));
        return changed;
    }

    public void tickAll(MinecraftServer server) {
        for (Map.Entry<UUID, Task> entry : new ArrayList<>(active.entrySet())) {
            UUID uuid = entry.getKey();
            Task task = entry.getValue();
            Optional<AIPlayerEntity> bot = AIPlayerManager.INSTANCE.getByUuid(uuid);
            if (bot.isEmpty()) {
                active.remove(uuid);
                activeOrigins.remove(uuid);
                executionStacks.remove(uuid);
                userPaused.remove(uuid);
                continue;
            }
            AIPlayerEntity player = bot.get();
            TaskOrigin origin = activeOrigins.get(uuid);
            if ((origin == null || !origin.safety()) && !isCritical(task)
                    && !TpsGuard.INSTANCE.shouldTickNonCriticalTask(server)) {
                BotProfiler.INSTANCE.record(player, "task_tick_skipped", 0L);
                continue;
            }
            // V1 统一生存层:任务 tick 前熔断检查——溺水/岩浆/着火/垂死作业一律叫停,
            // 失败原因透传给 goal 层 replan。任务私有熔断可以更早更聪明,但漏配时这里兜底。
            String breaker = SurvivalGuard.INSTANCE.check(player, task);
            if (breaker != null && task.state() == TaskState.RUNNING) {
                task.abort(player);
                if (task instanceof AbstractTask at) {
                    at.failureReason = breaker; // abort 默认 "aborted",改成可诊断的熔断理由
                }
                BotLog.danger(player, "survival_guard_abort", "task", task.name(), "why", breaker);
            }
            long started = System.nanoTime();
            try {
                task.tick(player);
            } finally {
                BotProfiler.INSTANCE.record(player, "task_tick", System.nanoTime() - started);
            }
            TaskStatus status = TaskStatus.from(task);
            lastStatus.put(uuid, status);
            BotReporter.INSTANCE.onStatus(server, player, status);
            if (task.state() == TaskState.COMPLETED) {
                active.remove(uuid);
                activeOrigins.remove(uuid);
                lastFailure.remove(uuid);
                pendingFailure.remove(uuid);
                BotLog.task(player, "task_completed", "name", task.name(), "elapsed_ticks", task.elapsedTicks());
                completeFuture(uuid, status);
            } else if (task.state() == TaskState.FAILED) {
                active.remove(uuid);
                activeOrigins.remove(uuid);
                recordFailure(player, task.name(), task.failureReason(), server.getTicks());
                BotLog.warn(io.github.zoyluo.aibot.log.LogCategory.TASK, player, "task_failed",
                        "name", task.name(), "reason", task.failureReason(), "elapsed_ticks", task.elapsedTicks());
                completeFuture(uuid, status);
            } else if (task.state() == TaskState.CANCELLED) {
                active.remove(uuid);
                activeOrigins.remove(uuid);
                lastFailure.remove(uuid);
                pendingFailure.remove(uuid);
                BotLog.task(player, "task_cancelled", "name", task.name(), "reason", task.failureReason());
                completeFuture(uuid, status);
            }
        }
    }

    public void recordFailure(AIPlayerEntity bot, String name, String reason, int tick) {
        UUID uuid = bot.getUuid();
        FailureRecord previous = lastFailure.get(uuid);
        int count = previous != null && previous.name().equals(name) && previous.reason().equals(reason)
                ? previous.count() + 1
                : 1;
        FailureRecord record = new FailureRecord(name, reason, count, tick);
        lastFailure.put(uuid, record);
        pendingFailure.put(uuid, record);
    }

    public Optional<FailureRecord> peekFailure(AIPlayerEntity bot) {
        return Optional.ofNullable(pendingFailure.get(bot.getUuid()));
    }

    public Optional<FailureRecord> consumeFailure(AIPlayerEntity bot) {
        return Optional.ofNullable(pendingFailure.remove(bot.getUuid()));
    }

    public void onServerStopping(MinecraftServer server) {
        for (AIPlayerEntity bot : AIPlayerManager.INSTANCE.all()) {
            cancelIntentTasks(bot, "server_unload");
        }
        active.clear();
        activeOrigins.clear();
        executionStacks.clear();
        userPaused.clear();
        lastStatus.clear();
        lastFailure.clear();
        pendingFailure.clear();
        BotLog.task(null, "tasks_cleared");
    }

    public void onBotDespawn(AIPlayerEntity bot) {
        cancelIntentTasks(bot, "bot_unload");
        executionStacks.remove(bot.getUuid());
        activeOrigins.remove(bot.getUuid());
        userPaused.remove(bot.getUuid());
        lastStatus.remove(bot.getUuid());
        lastFailure.remove(bot.getUuid());
        pendingFailure.remove(bot.getUuid());
        cancelFutures(bot.getUuid());
        BotReporter.INSTANCE.onCleared(bot);
    }

    public void clearAllRuntime() {
        active.clear();
        activeOrigins.clear();
        executionStacks.clear();
        userPaused.clear();
        lastStatus.clear();
        lastFailure.clear();
        pendingFailure.clear();
        completionFutures.values().forEach(list -> list.forEach(f -> f.cancel(false)));
        completionFutures.clear();
    }

    public int activeCount() {
        return active.size();
    }

    private void completeFuture(UUID uuid, TaskStatus status) {
        List<CompletableFuture<TaskStatus>> futures = completionFutures.remove(uuid);
        if (futures != null) {
            for (CompletableFuture<TaskStatus> f : futures) {
                f.complete(status);
            }
        }
    }

    private void cancelFutures(UUID uuid) {
        List<CompletableFuture<TaskStatus>> futures = completionFutures.remove(uuid);
        if (futures != null) {
            for (CompletableFuture<TaskStatus> f : futures) {
                f.cancel(false);
            }
        }
    }

    private static boolean isCritical(Task task) {
        return task instanceof EvadeTask || task instanceof CombatTask || task instanceof EatTask || task instanceof ResupplyTask;
    }

    public record FailureRecord(String name, String reason, int count, int tick) {
    }
}
