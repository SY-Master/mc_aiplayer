package io.github.zoyluo.aibot.brain;

import com.google.gson.JsonObject;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;

import java.util.Objects;
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

    /**
     * 工具统一返回结构：序列化为 {"status":"success","message":"……"}。
     * status 为可扩展状态枚举（原 ok=true/false → success/failed），
     * 需要新语义时只需在 {@link Status} 增加枚举项。
     */
    public record ToolResult(Status status, String message) {
        public ToolResult {
            Objects.requireNonNull(status, "status");
        }

        public boolean isSuccess() {
            return status == Status.SUCCESS;
        }

        public static ToolResult success(String message) {
            return new ToolResult(Status.SUCCESS, message);
        }

        public static ToolResult failure(String message) {
            return new ToolResult(Status.FAILED, message);
        }

        public static ToolResult paused(String message) {
            return new ToolResult(Status.PAUSED, message);
        }

        public String toToolContent() {
            JsonObject json = new JsonObject();
            json.addProperty("status", status.label());
            json.addProperty("message", message);
            return json.toString();
        }
    }

    /** 工具执行状态，序列化时经 {@link #label()} 输出小写值。 */
    public enum Status {
        /** 执行成功（原 ok=true）。 */
        SUCCESS("success"),
        /** 执行失败（原 ok=false）。 */
        FAILED("failed"),
        /** 用户已暂停，工具调用未执行。 */
        PAUSED("paused");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}
