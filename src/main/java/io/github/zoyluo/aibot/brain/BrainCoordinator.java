package io.github.zoyluo.aibot.brain;

import io.github.zoyluo.aibot.AIBotConfig;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.log.LogCategory;
import io.github.zoyluo.aibot.memory.BotMemoryStore;
import io.github.zoyluo.aibot.observe.ReplayRecorder;
import io.github.zoyluo.aibot.observe.TpsGuard;
import io.github.zoyluo.aibot.network.AIBotServerNetworking;
import io.github.zoyluo.aibot.perception.PerceptionCollector;
import io.github.zoyluo.aibot.perception.PerceptionSnapshot;
import io.github.zoyluo.aibot.persist.ConversationRecord;
import io.github.zoyluo.aibot.task.MemoryStore;
import io.github.zoyluo.aibot.task.TaskManager;
import io.github.zoyluo.aibot.task.TaskStatus;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

public final class BrainCoordinator {
    public static final BrainCoordinator INSTANCE = new BrainCoordinator();
    private final Map<UUID, BotConversation> conversations = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> manualModes = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> nextGoalWakeTick = new ConcurrentHashMap<>();
    // FLOW-2:大脑分配长任务后置 true;任务结束后 idle-watcher 据此自动唤醒大脑决定下一步(无需人催)。
    private final Map<UUID, Boolean> awaitingTask = new ConcurrentHashMap<>();
    private ToolRegistry toolRegistry = new ToolRegistry();
    private ActionDispatcher dispatcher = new ActionDispatcher(toolRegistry);
    private AsyncDecisionExecutor executor;

    private BrainCoordinator() {
    }

    public void configure(AIBotConfig config) {
        conversations.values().forEach(conversation -> conversation.decision.invalidate());
        if (executor != null) {
            executor.shutdown();
        }
        toolRegistry = new ToolRegistry();
        dispatcher = new ActionDispatcher(toolRegistry);
        executor = new AsyncDecisionExecutor(new DeepSeekApiClient(config.deepseek()));
    }

    public boolean handleMessage(AIPlayerEntity bot, String senderName, String text) {
        ensureConfigured();
        BotConversation conversation = conversations.computeIfAbsent(bot.getUuid(), BotConversation::new);
        boolean supersededDecision = conversation.decision.busy();
        DecisionLease lease = conversation.decision.beginEpoch();
        // P2 打断语义(对话式助手):玩家新消息**不再无脑清掉进行中的目标**——原 GOALFIX-CONT 的
        // "新消息=重定向,清目标"会把"顺便再搞点吃的"当成叫停,把正在挖的铁直接杀掉,与目标队列(P0)
        // 语义相反。现在保留目标,由大脑按语义决策:追加(goal 工具自动入队)/立刻改令(先 stop 再下新目标)/
        // 闲聊(say)。新消息采用 latest-wins decision epoch；旧 API 响应会被 lease 拒绝，但当前 Goal
        // 仍保留，是否追加或替换由这次新决策决定。
        if (io.github.zoyluo.aibot.goal.GoalExecutor.INSTANCE.hasActivePlan(bot)) {
            BotLog.comm(bot, "goal_kept_on_user_message");
        }
        if (supersededDecision) {
            BotLog.comm(bot, "decision_superseded",
                    "epoch", lease.epoch(),
                    "request_sequence", lease.requestSequence());
        }

        if (conversation.history.isEmpty()) {
            conversation.history.add(ChatMessage.system(systemPrompt(bot.getGameProfile().getName())));
        }
        PerceptionSnapshot snapshot = PerceptionCollector.collect(bot);
        conversation.lastPerceptionDigest = perceptionDigest(snapshot);
        conversation.history.add(ChatMessage.user("[" + senderName + "] says: " + text + "\n\nCurrent state:\n" + snapshot.toJson()));
        trimHistory(conversation);
        conversation.turnsInCurrentRequest = 0;
        conversation.maxTurnsHintInjected = false;
        io.github.zoyluo.aibot.goal.GoalExecutor.INSTANCE.clearUserGoal(bot); // B:用户发来新消息→清空原始目标记忆,本条消息触发的首个目标将成为新"用户原始目标"
        submit(bot, conversation, lease);
        return true;
    }

    private void onResponse(AIPlayerEntity bot, DecisionLease lease, ChatResponse response) {
        BotConversation conversation = conversations.get(lease.botId());
        if (conversation == null || !conversation.decision.tryAcceptResponse(lease)) {
            logStaleDecision(lease, "response");
            return;
        }

        BotLog.api(bot, "api_response",
                "tokens_in", response.promptTokens(),
                "tokens_out", response.completionTokens(),
                "cache_hit", response.promptCacheHitTokens(),
                "finish_reason", response.finishReason());

        if (response.content() != null && !response.content().isBlank()) {
            sendPanelChat(bot, "bot", response.content());
        }
        conversation.lastPromptTokens = response.promptTokens();
        conversation.lastCompletionTokens = response.completionTokens();
        conversation.lastCacheHitTokens = response.promptCacheHitTokens();
        conversation.history.add(ChatMessage.assistant(response.content(), response.toolCalls()));

        if (response.wantsToolCalls()) {
            // 真同步工具调用：在服务器线程启动所有任务（收集 futures），
            // 然后释放服务器线程，在工作线程上阻塞等待任务完成。
            var guard = new Object() { boolean valid = true; };
            BooleanSupplier leaseGuard = () -> conversation.decision.isApplying(lease) && guard.valid;
            ActionDispatcher.AsyncDispatchBatch batch = dispatcher.dispatchBatchAsync(
                    bot,
                    response.toolCalls(),
                    leaseGuard);

            if (!conversation.decision.isApplying(lease)) {
                logStaleDecision(lease, "tool_batch");
                // 取消刚注册的 futures，避免泄漏
                batch.futures().forEach(f -> f.cancel(false));
                return;
            }

            ReplayRecorder.INSTANCE.onDecision(bot, conversation.lastPerceptionDigest, response.toolCalls(), "");

            // 释放服务器线程：在工作线程上等待所有工具完成
            int toolTimeout = AIBotConfig.get().brain().toolTimeoutSeconds();
            var server = bot.getServer();
            var botId = bot.getUuid();

            CompletableFuture.allOf(batch.futures().toArray(CompletableFuture[]::new))
                    .orTimeout(toolTimeout, TimeUnit.SECONDS)
                    .handle((v, timeoutEx) -> {
                        // 收集结果（在工作线程上）
                        return batch.futures().stream()
                                .map(f -> {
                                    try {
                                        ChatMessage msg = f.getNow(null);
                                        return msg != null ? msg : ChatMessage.toolResult("error",
                                                new ToolDefinition.ToolResult(false,
                                                        timeoutEx != null ? "timeout:" + toolTimeout + "s" : "internal_error").toToolContent());
                                    } catch (Exception e) {
                                        return ChatMessage.toolResult("error",
                                                new ToolDefinition.ToolResult(false, "error:" + e.getMessage()).toToolContent());
                                    }
                                })
                                .toList();
                    })
                    .thenAcceptAsync(messages -> {
                        // 回到服务器线程：写入历史、继续 LLM 对话
                        if (!conversation.decision.isApplying(lease)) {
                            logStaleDecision(lease, "tool_result_stale");
                            return;
                        }
                        conversation.history.addAll(messages);

                        // 这些都是假同步有用的代码，现在改成真同步，这些内容先注释掉
                        // conversation.turnsInCurrentRequest++;
                        // maybeInjectMaxTurnsHint(conversation);
                        // if (conversation.turnsInCurrentRequest >= AIBotConfig.get().brain().maxTurnsPerRequest()) {
                        //     BotLog.warn(LogCategory.COMM, bot, "max_turns_reached", "turns", conversation.turnsInCurrentRequest);
                        //     if (!conversation.decision.complete(lease)) {
                        //         logStaleDecision(lease, "max_turns_completion");
                        //         return;
                        //     }
                        //     sendPanelChat(bot, "system", "工具调用轮次已达上限，已停止思考。");
                        //     trimHistory(conversation);
                        //     return;
                        // }

                        // 注入当前世界状态作为上下文（不再伪装成 user 消息，改用 system 消息提供感知快照）
                        // PerceptionSnapshot snapshot = PerceptionCollector.collect(bot);
                        // conversation.lastPerceptionDigest = perceptionDigest(snapshot);
                        // conversation.history.add(ChatMessage.system("Current world state (after tool execution):\n" + snapshot.toJson()));

                        trimHistory(conversation);
                        if (!conversation.decision.awaitContinuation(lease)) {
                            logStaleDecision(lease, "continuation_wait");
                            return;
                        }
                        submit(bot, conversation, lease);
                    }, server::execute);
            return;
        }

        if (!conversation.decision.complete(lease)) {
            logStaleDecision(lease, "response_completion");
            return;
        }
        ReplayRecorder.INSTANCE.onDecision(bot, conversation.lastPerceptionDigest, List.of(), response.content());
        trimHistory(conversation);
        BotLog.comm(bot, "conversation_done", "finish_reason", response.finishReason());
    }

    static boolean shouldContinueAfterControl(ActionDispatcher.ControlEffect effect,
                                              boolean activeTask,
                                              boolean activeGoal,
                                              int queuedGoals,
                                              boolean activeAction) {
        return effect != ActionDispatcher.ControlEffect.NONE
                && hasRuntimeWork(activeTask, activeGoal, queuedGoals, activeAction);
    }

    static boolean hasRuntimeWork(boolean activeTask,
                                  boolean activeGoal,
                                  int queuedGoals,
                                  boolean activeAction) {
        return activeTask || activeGoal || queuedGoals > 0 || activeAction;
    }

    private void onError(AIPlayerEntity bot, DecisionLease lease, Throwable throwable) {
        BotConversation conversation = conversations.get(lease.botId());
        if (conversation == null || !conversation.decision.tryAcceptError(lease)) {
            logStaleDecision(lease, "error");
            return;
        }
        String message = throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
        BotLog.error(bot, "brain_hiccup", throwable, "message", message);
        sendPanelChat(bot, "system", "大脑请求失败: " + message);
    }

    public void reset(AIPlayerEntity bot) {
        BotConversation conversation = conversations.remove(bot.getUuid());
        if (conversation != null) {
            conversation.decision.invalidate();
        }
        manualModes.remove(bot.getUuid());
        awaitingTask.remove(bot.getUuid());
        nextGoalWakeTick.remove(bot.getUuid());
        BotRuntimeOptions.INSTANCE.clear(bot);
        BotLog.comm(bot, "conversation_reset");
    }

    /** Invalidates only the asynchronous decision; P0-02 owns full Mission/Task cancellation. */
    public boolean invalidateDecision(AIPlayerEntity bot, String reason) {
        BotConversation conversation = conversations.get(bot.getUuid());
        if (conversation == null || !conversation.decision.invalidateIfBusy()) {
            return false;
        }
        BotLog.comm(bot, "decision_invalidated", "reason", reason);
        return true;
    }

    public boolean clearIntentWakeSources(AIPlayerEntity bot) {
        boolean awaitingCleared = awaitingTask.remove(bot.getUuid()) != null;
        boolean wakeTickCleared = nextGoalWakeTick.remove(bot.getUuid()) != null;
        return awaitingCleared || wakeTickCleared;
    }

    public void setManualMode(AIPlayerEntity bot, boolean enabled) {
        if (enabled) {
            manualModes.put(bot.getUuid(), true);
        } else {
            manualModes.remove(bot.getUuid());
        }
        BotLog.comm(bot, "manual_mode_set", "enabled", enabled);
    }

    public boolean manualMode(AIPlayerEntity bot) {
        return manualModes.getOrDefault(bot.getUuid(), false);
    }

    public boolean maybeWakeForFailure(AIPlayerEntity bot) {
        return maybeWakeForFailureOrGoal(bot);
    }

    public boolean maybeWakeForFailureOrGoal(AIPlayerEntity bot) {
        // 真同步模式下：工具调用已阻塞等待任务完成，无需 taskJustFinished 唤醒。
        // 仅处理外部触发的失败（如 DangerWatcher）和长期目标推进。
        if (io.github.zoyluo.aibot.goal.GoalExecutor.INSTANCE.hasActivePlan(bot)) {
            return false;
        }
        boolean hasFailure = TaskManager.INSTANCE.peekFailure(bot).isPresent();
        boolean hasGoal = BotMemoryStore.INSTANCE.of(bot.getUuid()).hasActiveGoal();
        if (!hasFailure && !shouldWakeForGoal(bot, hasGoal)) {
            return false;
        }
        ensureConfigured();
        BotConversation conversation = conversations.computeIfAbsent(bot.getUuid(), BotConversation::new);
        if (conversation.decision.busy()) {
            return false;
        }
        if (conversation.history.isEmpty()) {
            conversation.history.add(ChatMessage.system(systemPrompt(bot.getGameProfile().getName())));
        }
        conversation.turnsInCurrentRequest = 0;
        conversation.maxTurnsHintInjected = false;
        if (hasFailure && maybeInjectFailure(bot, conversation)) {
            trimHistory(conversation);
            submit(bot, conversation, conversation.decision.beginEpoch());
            return true;
        }
        if (hasGoal && maybeInjectGoalContinuation(bot, conversation, "当前没有正在执行的任务,但还有长期目标未完成。请继续推进当前步骤;需要时先分配一个高层任务。")) {
            nextGoalWakeTick.put(bot.getUuid(), bot.getServer().getTicks() + 200);
            trimHistory(conversation);
            submit(bot, conversation, conversation.decision.beginEpoch());
            return true;
        }
        return false;
    }

    public void shutdown() {
        conversations.values().forEach(conversation -> conversation.decision.invalidate());
        if (executor != null) {
            executor.shutdown();
            executor = null;
        }
        conversations.clear();
        manualModes.clear();
        nextGoalWakeTick.clear();
        awaitingTask.clear();
    }

    public BrainStatus status(AIPlayerEntity bot) {
        BotConversation conversation = conversations.get(bot.getUuid());
        if (conversation == null) {
            return new BrainStatus(false, 0, 0, 0, 0);
        }
        return new BrainStatus(
                conversation.decision.busy(),
                conversation.history.size(),
                conversation.lastPromptTokens,
                conversation.lastCompletionTokens,
                conversation.lastCacheHitTokens);
    }

    public void sendPanelChat(AIPlayerEntity bot, String role, String text) {
        AIBotServerNetworking.INSTANCE.sendBotChat(bot, role, text);
    }

    /** Snapshots the conversation history for persistence. Transient decision-flow state is not captured. */
    public ConversationRecord captureConversation(AIPlayerEntity bot) {
        BotConversation conv = conversations.get(bot.getUuid());
        if (conv == null || conv.history.isEmpty()) {
            return ConversationRecord.empty();
        }
        int max = AIBotConfig.get().brain().maxHistoryMessages();
        List<ChatMessage> messages = new ArrayList<>(conv.history);
        if (messages.size() > max) {
            int keep = max;
            int offset = 0;
            if (!messages.isEmpty() && "system".equals(messages.get(0).role())) {
                keep--;
                offset = 1;
            }
            int from = Math.max(offset, messages.size() - keep);
            List<ChatMessage> trimmed = new ArrayList<>(messages.subList(0, offset));
            trimmed.addAll(messages.subList(from, messages.size()));
            messages = trimmed;
        }
        return new ConversationRecord(List.copyOf(messages), 0L);
    }

    /** Restores a previously persisted conversation. No-op if the bot already has live conversation state. */
    public void restoreConversation(AIPlayerEntity bot, ConversationRecord record) {
        if (record == null || record.isEmpty()) {
            return;
        }
        BotConversation conv = conversations.computeIfAbsent(bot.getUuid(), BotConversation::new);
        if (!conv.history.isEmpty()) {
            return;
        }
        conv.history.addAll(record.history());
        conv.turnsInCurrentRequest = 0;
        conv.maxTurnsHintInjected = false;
    }

    /** Invalidates the in-flight decision and clears wake state without discarding conversation history.
     *  Used on bot death so the bot remembers what it was doing after respawn. */
    public void softReset(AIPlayerEntity bot) {
        BotConversation conv = conversations.get(bot.getUuid());
        if (conv != null) {
            conv.decision.invalidate();
        }
        awaitingTask.remove(bot.getUuid());
        nextGoalWakeTick.remove(bot.getUuid());
    }

    public int conversationCount() {
        return conversations.size();
    }

    private void submit(AIPlayerEntity bot, BotConversation conversation, DecisionLease lease) {
        try {
            List<ChatMessage> historySnapshot = MemoryStore.INSTANCE.prepareHistory(bot, List.copyOf(conversation.history));
            AIBotConfig.Brain brainConfig = AIBotConfig.get().brain();
            List<ToolDefinition> toolsSnapshot = toolRegistry.tools(
                    brainConfig,
                    brainConfig.exposesLowLevelTools() || manualMode(bot),
                    BotRuntimeOptions.INSTANCE.memoryToolsEnabled(bot),
                    brainConfig.coordinationToolsEnabled());
            executor.submit(
                    bot,
                    lease,
                    historySnapshot,
                    toolsSnapshot,
                    (responseLease, response) -> onResponse(bot, responseLease, response),
                    (errorLease, throwable) -> onError(bot, errorLease, throwable));
        } catch (RuntimeException exception) {
            if (!conversation.decision.failSubmission(lease)) {
                logStaleDecision(lease, "submission_error");
                return;
            }
            String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            BotLog.error(bot, "decision_submit_failed", exception, "message", message);
            sendPanelChat(bot, "system", "大脑请求未能提交: " + message);
        }
    }

    private void ensureConfigured() {
        if (executor == null) {
            configure(AIBotConfig.get());
        }
    }

    private void trimHistory(BotConversation conversation) {
        int max = AIBotConfig.get().brain().maxHistoryMessages();
        if (conversation.history.size() <= max) {
            return;
        }
        ChatMessage system = conversation.history.peekFirst();
        List<ChatMessage> rest = new ArrayList<>(conversation.history);
        conversation.history.clear();
        if (system != null && "system".equals(system.role())) {
            conversation.history.add(system);
            rest = rest.subList(1, rest.size());
        }
        int keep = Math.max(0, max - conversation.history.size());
        int start = Math.max(0, rest.size() - keep);
        for (int index = start; index < rest.size(); index++) {
            conversation.history.add(rest.get(index));
        }
    }

    /**
     * 这是一个防死循环保护。当 LLM 在同一轮请求中连续调用工具快达到上限（maxTurnsPerRequest，默认 12）时，提前 2 步注入一条 system 消息，让它改用高层工具或停下来。<br/>
     *
     * 在旧的"假同步"模式下这个很重要——LLM 调 10+ 次工具，每次拿到的都是 "assigned: xxx"，它不知道任务到底完了没有，就不停地调。这条 hint 是在耗光轮次前最后的"刹车"。<br/>
     *
     * 改真同步之后其实作用不大了。因为现在每个工具调用都会阻塞到任务真正完成，LLM 拿到的就是真实结果，它自然知道该不该继续。正常情况下一轮就调 1~2 个工具。<br/>
     *
     * 可以留着当安全兜底，也可以直接删掉。删掉的话把 turnsInCurrentRequest、maxTurnsHintInjected 字段一并清理掉就行。
     *
     * @param conversation
     */
    private void maybeInjectMaxTurnsHint(BotConversation conversation) {
        int maxTurns = AIBotConfig.get().brain().maxTurnsPerRequest();
        if (conversation.maxTurnsHintInjected || conversation.turnsInCurrentRequest < maxTurns - 2) {
            return;
        }
        conversation.history.add(ChatMessage.system("你已多次调用工具仍未完成。请改用高层工具一次完成(如 mine_ore / achieve_goal / craft)、或用 say 说明原因。"));
        conversation.maxTurnsHintInjected = true;
    }

    private boolean maybeInjectFailure(AIPlayerEntity bot, BotConversation conversation) {
        return TaskManager.INSTANCE.consumeFailure(bot)
                .map(failure -> {
                    int maxRetries = AIBotConfig.get().brain().maxTaskRetries();
                    String retryHint = failure.count() >= maxRetries
                            ? " 已经连续多次同样失败,请倾向于换方法或用 say 说明无法完成。"
                            : "";
                    String strategyHint = failure.count() >= 2
                            ? " 同一任务和原因已经连续失败,禁止原样重试;必须换工具/任务策略,或先补齐前置条件。"
                            : "";
                    String executableHint = executableFailureHint(failure);
                    PerceptionSnapshot snapshot = PerceptionCollector.collect(bot);
                    conversation.lastPerceptionDigest = perceptionDigest(snapshot);
                    conversation.history.add(ChatMessage.system("上一个任务失败:"
                            + failure.name()
                            + ",原因:"
                            + failure.reason()
                            + "(第"
                            + failure.count()
                            + "次)。请判断:补齐前置条件后重试 / 换用其它方法 / 用 say 说明无法完成。"
                            + retryHint
                            + strategyHint
                            + executableHint
                            + "\n\nCurrent state:\n"
                            + snapshot.toJson()));
                    BotLog.comm(bot, "failure_injected",
                            "name", failure.name(),
                            "reason", failure.reason(),
                            "count", failure.count(),
                            "tick", failure.tick());
                    return true;
                })
                .orElse(false);
    }

    private static String executableFailureHint(TaskManager.FailureRecord failure) {
        String reason = failure.reason() == null ? "" : failure.reason();
        if (reason.startsWith("no_exposed_ore:use_strip_mine")) {
            return " 可执行建议:目标是矿石但附近没有暴露矿块,不要再用 mine;改用 strip_mine/assign_task strip_mine 并设置 target_ores 为目标矿石。";
        }
        return "";
    }

    private boolean maybeInjectGoalContinuation(AIPlayerEntity bot, BotConversation conversation, String reason) {
        String goal = BotMemoryStore.INSTANCE.of(bot.getUuid()).goalDriveStatus("");
        if (goal.isBlank()) {
            return false;
        }
        PerceptionSnapshot snapshot = PerceptionCollector.collect(bot);
        conversation.lastPerceptionDigest = perceptionDigest(snapshot);
        conversation.history.add(ChatMessage.system(reason
                + "\n\n长期目标状态:\n"
                + goal
                + "\n\nCurrent state:\n"
                + snapshot.toJson()));
        BotLog.comm(bot, "goal_continuation_injected", "reason", reason);
        return true;
    }

    private boolean shouldWakeForGoal(AIPlayerEntity bot, boolean hasGoal) {
        if (!hasGoal) {
            return false;
        }
        return bot.getServer().getTicks() >= nextGoalWakeTick.getOrDefault(bot.getUuid(), 0);
    }

    private static String perceptionDigest(PerceptionSnapshot snapshot) {
        String json = snapshot.toJson();
        return json.length() <= 1400 ? json : json.substring(0, 1397) + "...";
    }

    private static String replayResult(List<ChatMessage> toolResults) {
        if (toolResults == null || toolResults.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (ChatMessage message : toolResults) {
            if (!builder.isEmpty()) {
                builder.append(" | ");
            }
            builder.append(message.toolCallId()).append(":").append(message.content());
            if (builder.length() > 1600) {
                return builder.substring(0, 1597) + "...";
            }
        }
        return builder.toString();
    }

    private static void logStaleDecision(DecisionLease lease, String callback) {
        BotLog.commSystem("stale_decision_dropped",
                "bot_uuid", lease.botId(),
                "session_id", lease.sessionId(),
                "epoch", lease.epoch(),
                "request_sequence", lease.requestSequence(),
                "callback", callback);
    }

    private static String systemPrompt(String botName) {
        return """
                You are a player in Minecraft named %s. You exist as a real player in the world and can interact with it using the tools provided.

                Rules:
                1. Understand the human's intent first, then break it into tool calls.
                2. Coordinates are integers (block positions).
                3. Prefer high-level deterministic tasks for survival work. For ores or raw ore materials, use mine_ore; it automatically prepares the required pickaxe before mining. For an item/tool goal such as iron_pickaxe or iron_ingot, use achieve_goal. Do not manually decompose these into gather/craft/mine steps unless the goal tool fails.
                4. Low-level tools such as move_to, mine_block, select_hotbar, and place_block are for one-off manual actions only. Do not use them for gathering materials or placing a crafting table for recipes unless the human explicitly asks for manual control.
                5. High-level tasks such as craft, smelt, eat, or assign_task run over multiple ticks. Start only one such task at a time, then use get_task_status or the Current state task field on later turns until it is COMPLETED or FAILED before assigning the next task. EXCEPTION: goal tools (achieve_goal, mine_ore, harvest_crop, provision_food, set_goal) support QUEUEING — if a goal is already running, a new goal call is queued and starts automatically when the current one finishes (the system announces each transition). So for a compound request like "先搞点吃的,然后挖些铁" call provision_food then mine_ore back-to-back in the same turn, then STOP. While a goal is running, ADDING work ("顺便/然后/再做X") = just call the goal tool (it queues); REPLACING ("别挖了/先停下,改做X") = call stop first, then the new goal tool; CANCELLING EVERYTHING including queued goals = call cancel_all; a pure question ("干得怎么样") = goal_status or say only — never call stop for questions or additions.
                6. Always reply to humans in Simplified Chinese. Use the say tool to reply to humans. Keep replies short (one sentence).
                7. For survival crafting, call plan_craft first when materials may be missing. Use missing[].source to choose assign_task mine, smelt, craft, or forage before retrying craft for the intended target. CraftTask expands recipe-table intermediates such as planks and sticks, so do not craft planks or sticks as standalone steps unless the human asks for those items.
                8. For 3x3 recipes, do not manually select or place a crafting table. If a crafting table is nearby or in inventory, the craft task can use or place it.
                9. For "挖铁矿", call mine_ore with ore=minecraft:iron_ore. For "做一把铁镐" or "给我铁锭", call achieve_goal with item=minecraft:iron_pickaxe or minecraft:iron_ingot. The deterministic goal executor will plan gathering, crafting, mining, and smelting. CRITICAL: a single mine_ore/achieve_goal call runs the ENTIRE multi-step plan autonomously (gather wood, craft tools, mine stone, mine ore). After you call it, STOP immediately — do NOT call any other tool (no say, no inventory, no assign_task, no mine, no strip_mine) and do NOT narrate intermediate steps. The system executes every step itself and will notify you only when the whole goal is finished or has truly failed. Calling other tools meanwhile will abort the goal and break it. For "种小麦/收点小麦/给我小麦" (or carrot/potato), call harvest_crop with crop=wheat/carrot/potato — it auto-prepares a hoe, tills, plants, waits, and harvests; same rule: call once then STOP. For "盖房子/建个房/造个家", call build_house (blueprint optional) — it auto-gathers all materials then builds; same rule: call once then STOP.
                10. After each action, look at the next world state (passed in user messages) and decide the next step.
                11. When the task is complete or impossible, say so and stop calling tools.
                12. You are fully autonomous and self-reliant. NEVER ask the human for help, for resources, or to move/carry you — the human will not help. NEVER mine ore with bare hands and NEVER use strip_mine or assign_task mine to dig without a proper pickaxe (that wastes blocks and drops nothing). To get ore always use mine_ore, and to get an item/tool use achieve_goal — these automatically walk to find wood, craft the needed pickaxe, then mine. If mine_ore/achieve_goal reports it cannot proceed, just retry the SAME mine_ore once (do NOT switch to an easier or different goal such as achieve_goal a pickaxe — mine_ore already auto-prepares the pickaxe, so switching only loses the real goal); if it still cannot, state the situation in one short sentence and stop — do not flail with move/strip_mine and do not beg.
                13. EFFICIENCY CHECK FOR GATHERING: Before executing any block-gathering task (e.g., chopping trees, mining), you MUST evaluate efficiency based on current state. Priority logic: Check Inventory > Check Tool Tier > Decide Action. Example for wood: Do I have an axe? > Is it stone/iron/diamond tier? > If no axe or only wooden axe while better materials are available, prioritize upgrading the tool via achieve_goal BEFORE mass gathering. Never perform repetitive gathering with bare hands or suboptimal tools when an upgrade path exists.
                14. BIOME AWARENESS & CONFIRMATION: Before gathering biome-specific resources (especially logs/wood), check the current biome from world state. If the current biome lacks the target resource naturally (e.g., Desert, Ocean, Deep Ocean for trees), DO NOT start searching blindly. Instead, use say to ask the player for confirmation or direction (e.g., "当前位于沙漠，附近没有树木，是否继续搜索或前往其他群系？"). Only proceed after receiving explicit confirmation. This overrides Rule 12's "never ask" restriction specifically for biome-resource mismatches to prevent futile long-distance pathfinding.

                Available tools are declared in the tools field. You MUST use them; do not invent tools.
                """.formatted(botName);
    }

    private static final class BotConversation {
        private final DecisionSession decision;
        private final Deque<ChatMessage> history = new ArrayDeque<>();
        private int turnsInCurrentRequest;
        private boolean maxTurnsHintInjected;
        private int lastPromptTokens;
        private int lastCompletionTokens;
        private int lastCacheHitTokens;
        private String lastPerceptionDigest = "";

        private BotConversation(UUID botId) {
            decision = new DecisionSession(botId);
        }
    }

    public record BrainStatus(boolean busy, int historySize, int promptTokens, int completionTokens, int cacheHitTokens) {
    }
}
