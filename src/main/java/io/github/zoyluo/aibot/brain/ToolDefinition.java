package io.github.zoyluo.aibot.brain;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;

import java.util.concurrent.CompletableFuture;

public record ToolDefinition(
        String name,
        String description,
        JsonObject parametersSchema,
        Handler handler,
        AsyncHandler asyncHandler,
        Group group
) {
    public ToolDefinition(String name, String description, JsonObject parametersSchema, Handler handler) {
        this(name, description, parametersSchema, handler, null, Group.CORE);
    }

    public ToolDefinition(String name, String description, JsonObject parametersSchema,
                          Handler handler, Group group) {
        this(name, description, parametersSchema, handler, null, group);
    }

    public ToolDefinition(String name, String description, JsonObject parametersSchema,
                          Handler handler, AsyncHandler asyncHandler, Group group) {
        this.name = name;
        this.description = description;
        this.parametersSchema = parametersSchema;
        this.handler = handler;
        this.asyncHandler = asyncHandler;
        this.group = group;
    }

    public enum Group {
        CORE,
        MEMORY,
        COORDINATION,
        LOW_LEVEL
    }

    @FunctionalInterface
    public interface Handler {
        ToolResult invoke(AIPlayerEntity bot, JsonObject args);
    }

    /**
     * 异步工具 handler：在服务器线程上启动任务并返回 CompletableFuture。
     * 工作线程阻塞等待 Future 完成，实现真同步工具调用。
     * 若为 null，回退到同步 {@link Handler}。
     */
    @FunctionalInterface
    public interface AsyncHandler {
        CompletableFuture<ToolResult> prepare(AIPlayerEntity bot, JsonObject args);
    }

    public record ToolResult(boolean ok, String message) {
        private static final Gson GSON = new Gson();

        public String toToolContent() {
            return GSON.toJson(this);
        }
    }
}
