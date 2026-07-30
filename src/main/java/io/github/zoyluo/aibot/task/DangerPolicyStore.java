package io.github.zoyluo.aibot.task;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import io.github.zoyluo.aibot.AIBotConfig;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.persist.AtomicSnapshotFile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每 bot 危险应对策略存储(LLM 经 set_danger_policy 工具设置)。
 * 内存 ConcurrentHashMap,改动即落盘(world 存档 aibot/danger_policies.json,原子写),服务器启动整体加载。
 * 未设字段经 {@link #resolve} 逐层回落 AIBotConfig.combat() 默认值——原配置即默认配置。
 * 生命周期挂 RuntimeLifecycleCoordinator(attach/detach/clear/forget/clearAll),同 KnowledgeBase 模式。
 * bot 死亡不清策略(它是持久化"性格");unload 仅清内存(存档保留),despawn=删除才连存档条目一起移除。
 * 读写均在服务器线程(tick 扫描与工具 handler),ConcurrentHashMap 只是保险,同 BotRuntimeOptions 屋风。
 */
public final class DangerPolicyStore {
    public static final DangerPolicyStore INSTANCE = new DangerPolicyStore();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int CURRENT_SCHEMA = 1;
    private static final String FILE_NAME = "danger_policies.json";

    /** resolve 后的有效值(无 null):per-bot 覆盖 → AIBotConfig.combat() 默认。 */
    public record Effective(DangerPolicy.Mode mode, int retreatHp, int maxEnemies, boolean keepSurvival) {
    }

    // 磁盘格式:{ "schema":1, "policies": { "<uuid>": { "mode":"FIGHT", "retreatHp":14, "keepSurvival":false } } }
    // 磁盘字段 camelCase(Gson 记录直出),工具参数 snake_case——有意区分,别当不一致"修正"。
    private record PolicyFile(Integer schema, Map<String, JsonObject> policies) {
    }

    private final Map<UUID, DangerPolicy> policies = new ConcurrentHashMap<>();
    private volatile Path dir; // null = 未挂载存档(不读写磁盘,纯内存)

    private DangerPolicyStore() {
    }

    // ==================== 读写 ====================

    public Effective resolve(AIPlayerEntity bot) {
        return resolve(bot.getUuid());
    }

    /** 当前生效策略:per-bot 覆盖 → AIBotConfig.combat() 默认;keepSurvival 缺省 true。 */
    public Effective resolve(UUID botId) {
        DangerPolicy policy = policies.getOrDefault(botId, DangerPolicy.DEFAULT);
        AIBotConfig.Combat combat = AIBotConfig.get().combat();
        return new Effective(
                policy.mode() == null ? DangerPolicy.Mode.AUTO : policy.mode(),
                policy.retreatHp() == null ? combat.retreatHp() : policy.retreatHp(),
                policy.maxEnemies() == null ? combat.maxEnemiesToFight() : policy.maxEnemies(),
                policy.keepSurvival() == null || policy.keepSurvival());
    }

    /** 原始策略条目(get_danger_policy 标注字段来源用);无覆盖时返回 null。 */
    public DangerPolicy raw(UUID botId) {
        return policies.get(botId);
    }

    /** 增量更新;null 参数="不改该字段"。结果与默认等价则删条目。返回合并后的策略(可能是 DEFAULT)。 */
    public DangerPolicy update(UUID botId, DangerPolicy.Mode mode, Integer retreatHp, Integer maxEnemies, Boolean keepSurvival) {
        DangerPolicy merged = policies.getOrDefault(botId, DangerPolicy.DEFAULT)
                .mergedWith(mode, retreatHp, maxEnemies, keepSurvival);
        if (merged.isDefault()) {
            policies.remove(botId);
        } else {
            policies.put(botId, merged);
        }
        save();
        return merged;
    }

    /** 显式重置回默认(删条目 + 存档键)。 */
    public DangerPolicy reset(UUID botId) {
        policies.remove(botId);
        save();
        return DangerPolicy.DEFAULT;
    }

    /** 有效策略描述(get_danger_policy 用):JSON,每字段附来源 policy/config/default。 */
    public String describe(UUID botId) {
        DangerPolicy policy = policies.get(botId);
        Effective effective = resolve(botId);
        return "{\"mode\":\"" + effective.mode().name().toLowerCase(Locale.ROOT)
                + "\",\"mode_source\":\"" + (policy != null && policy.mode() != null ? "policy" : "default")
                + "\",\"retreat_hp\":" + effective.retreatHp()
                + ",\"retreat_hp_source\":\"" + (policy != null && policy.retreatHp() != null ? "policy" : "config")
                + "\",\"max_enemies\":" + effective.maxEnemies()
                + ",\"max_enemies_source\":\"" + (policy != null && policy.maxEnemies() != null ? "policy" : "config")
                + "\",\"keep_survival\":" + effective.keepSurvival()
                + ",\"keep_survival_source\":\"" + (policy != null && policy.keepSurvival() != null ? "policy" : "default")
                + "\"}";
    }

    // ==================== 生命周期 ====================

    public void attachServer(MinecraftServer server) {
        policies.clear();
        dir = server.getSavePath(WorldSavePath.ROOT).resolve("aibot");
        load();
    }

    public void detachServer() {
        policies.clear();
        dir = null;
    }

    public void clearAll() {
        policies.clear();
    }

    /** 仅清内存(unload/服务器停用走这里):存档保留,重启加载回来。 */
    public void clear(UUID botId) {
        policies.remove(botId);
    }

    /** 连存档条目一起删除(despawn=删除语义):仅 deleteBot 调用。 */
    public void forget(UUID botId) {
        if (policies.remove(botId) != null) {
            save();
        }
    }

    /** 测试钩子:挂目录即启用持久化,无需 MinecraftServer。 */
    void attachDirectory(Path directory) {
        policies.clear();
        dir = directory;
        load();
    }

    // ==================== 落盘 ====================

    private void save() {
        Path target = dir;
        if (target == null) {
            return; // 未挂载(单测/服务器未起):纯内存,不落盘
        }
        Map<String, JsonObject> snapshot = new LinkedHashMap<>();
        policies.forEach((id, policy) -> snapshot.put(id.toString(), GSON.toJsonTree(policy).getAsJsonObject()));
        try {
            AtomicSnapshotFile.write(target.resolve(FILE_NAME), GSON.toJson(new PolicyFile(CURRENT_SCHEMA, snapshot)));
        } catch (IOException e) {
            BotLog.error("danger_policy_save_failed", e);
        }
    }

    private void load() {
        Path target = dir;
        if (target == null) {
            return;
        }
        Path file = target.resolve(FILE_NAME);
        if (!Files.exists(file)) {
            return;
        }
        try {
            PolicyFile parsed = GSON.fromJson(Files.readString(file), PolicyFile.class);
            if (parsed == null || parsed.policies() == null) {
                return;
            }
            if (parsed.schema() == null || parsed.schema() != CURRENT_SCHEMA) {
                BotLog.error("danger_policy_load_failed",
                        new IllegalStateException("unsupported_schema:" + parsed.schema()));
                return;
            }
            for (Map.Entry<String, JsonObject> entry : parsed.policies().entrySet()) {
                try {
                    DangerPolicy policy = GSON.fromJson(entry.getValue(), DangerPolicy.class);
                    if (policy != null && !policy.isDefault()) {
                        policies.put(UUID.fromString(entry.getKey()), policy);
                    }
                } catch (RuntimeException badEntry) {
                    BotLog.error("danger_policy_entry_skipped", badEntry, "key", entry.getKey());
                }
            }
        } catch (IOException | RuntimeException e) {
            BotLog.error("danger_policy_load_failed", e);
        }
    }
}
