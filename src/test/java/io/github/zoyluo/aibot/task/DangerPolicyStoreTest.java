package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.AIBotConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 纯 JVM 单测:AIBotConfig.get() 未 load 时即 defaults()(combat: retreatHp=10, maxEnemies=2)。 */
final class DangerPolicyStoreTest {

    @TempDir
    Path dir;

    @BeforeEach
    void resetStore() {
        DangerPolicyStore.INSTANCE.clearAll();
    }

    @Test
    void resolveFallsBackToConfigDefaults() {
        DangerPolicyStore.Effective effective = DangerPolicyStore.INSTANCE.resolve(UUID.randomUUID());
        assertEquals(DangerPolicy.Mode.AUTO, effective.mode());
        assertEquals(AIBotConfig.get().combat().retreatHp(), effective.retreatHp());
        assertEquals(AIBotConfig.get().combat().maxEnemiesToFight(), effective.maxEnemies());
        assertTrue(effective.keepSurvival());
    }

    @Test
    void partialUpdateMergesOverCurrentPolicy() {
        UUID bot = UUID.randomUUID();
        DangerPolicyStore store = DangerPolicyStore.INSTANCE;
        store.attachDirectory(dir);

        store.update(bot, DangerPolicy.Mode.FIGHT, null, null, null, null, false);
        DangerPolicyStore.Effective afterMode = store.resolve(bot);
        assertEquals(DangerPolicy.Mode.FIGHT, afterMode.mode());
        assertEquals(10, afterMode.retreatHp()); // 未覆盖 → 配置默认
        assertTrue(afterMode.keepSurvival());

        store.update(bot, null, 6, 0, false, null, false); // 不动 mode
        DangerPolicyStore.Effective afterRest = store.resolve(bot);
        assertEquals(DangerPolicy.Mode.FIGHT, afterRest.mode());
        assertEquals(6, afterRest.retreatHp());
        assertEquals(0, afterRest.maxEnemies());
        assertFalse(afterRest.keepSurvival());
    }

    @Test
    void updatePersistsAndReloadsAcrossAttach() throws IOException {
        UUID bot = UUID.randomUUID();
        DangerPolicyStore store = DangerPolicyStore.INSTANCE;
        store.attachDirectory(dir);
        store.update(bot, DangerPolicy.Mode.FLEE, 14, null, false, null, false);

        Path file = dir.resolve("danger_policies.json");
        assertTrue(Files.exists(file));
        String json = Files.readString(file);
        assertTrue(json.contains(bot.toString()));
        assertTrue(json.contains("FLEE"));

        // 模拟重启:重挂同一目录 → 落盘策略加载回来
        store.attachDirectory(dir);
        DangerPolicyStore.Effective reloaded = store.resolve(bot);
        assertEquals(DangerPolicy.Mode.FLEE, reloaded.mode());
        assertEquals(14, reloaded.retreatHp());
        assertEquals(2, reloaded.maxEnemies()); // 未覆盖项回落配置默认
        assertFalse(reloaded.keepSurvival());
    }

    @Test
    void resetRemovesEntryAndPersistedKey() throws IOException {
        UUID bot = UUID.randomUUID();
        DangerPolicyStore store = DangerPolicyStore.INSTANCE;
        store.attachDirectory(dir);
        store.update(bot, DangerPolicy.Mode.OFF, null, null, null, null, false);
        assertTrue(Files.readString(dir.resolve("danger_policies.json")).contains(bot.toString()));

        store.reset(bot);
        assertNull(store.raw(bot));
        assertEquals(DangerPolicy.Mode.AUTO, store.resolve(bot).mode());
        assertFalse(Files.readString(dir.resolve("danger_policies.json")).contains(bot.toString()));
    }

    @Test
    void clearIsMemoryOnlyWhileForgetAlsoPersists() throws IOException {
        UUID bot = UUID.randomUUID();
        DangerPolicyStore store = DangerPolicyStore.INSTANCE;
        store.attachDirectory(dir);
        store.update(bot, DangerPolicy.Mode.FIGHT, null, null, null, null, false);
        Path file = dir.resolve("danger_policies.json");

        // clear(unload 语义):内存清空,存档保留 → 重挂后策略回来
        store.clear(bot);
        assertEquals(DangerPolicy.Mode.AUTO, store.resolve(bot).mode());
        assertTrue(Files.readString(file).contains(bot.toString()));
        store.attachDirectory(dir);
        assertEquals(DangerPolicy.Mode.FIGHT, store.resolve(bot).mode());

        // forget(delete 语义):连存档键一起删 → 重挂后也没了
        store.forget(bot);
        assertFalse(Files.readString(file).contains(bot.toString()));
        store.attachDirectory(dir);
        assertEquals(DangerPolicy.Mode.AUTO, store.resolve(bot).mode());
    }

    @Test
    void updateBeforeAttachStaysInMemoryWithoutDiskError() {
        UUID bot = UUID.randomUUID();
        DangerPolicyStore.INSTANCE.update(bot, DangerPolicy.Mode.FIGHT, 5, 3, true, null, false);
        DangerPolicyStore.Effective effective = DangerPolicyStore.INSTANCE.resolve(bot);
        assertEquals(DangerPolicy.Mode.FIGHT, effective.mode());
        assertEquals(5, effective.retreatHp());
        assertEquals(3, effective.maxEnemies());
    }

    @Test
    void corruptedFileIsToleratedAsEmpty() throws IOException {
        UUID bot = UUID.randomUUID();
        DangerPolicyStore store = DangerPolicyStore.INSTANCE;
        store.attachDirectory(dir);
        store.update(bot, DangerPolicy.Mode.FIGHT, null, null, null, null, false);
        Files.writeString(dir.resolve("danger_policies.json"), "{ not json !!!");

        store.attachDirectory(dir); // 坏档 → 视为空,不抛
        assertEquals(DangerPolicy.Mode.AUTO, store.resolve(bot).mode());
    }

    @Test
    void unknownModeEntryIsSkippedNotFatal() throws IOException {
        UUID good = UUID.randomUUID();
        UUID bad = UUID.randomUUID();
        DangerPolicyStore store = DangerPolicyStore.INSTANCE;
        store.attachDirectory(dir);
        store.update(good, DangerPolicy.Mode.FIGHT, null, null, null, null, false);
        // 手改存档塞入非法 mode 条目(Gson 记录反序列化会抛 → 该条被跳过)
        String json = Files.readString(dir.resolve("danger_policies.json"));
        json = json.replaceFirst("\\}\\s*\\}\\s*$",
                "},\"" + bad + "\":{\"mode\":\"BERSERK\"}}");
        Files.writeString(dir.resolve("danger_policies.json"), json);

        store.attachDirectory(dir);
        assertEquals(DangerPolicy.Mode.FIGHT, store.resolve(good).mode()); // 好条目不受影响
        assertEquals(DangerPolicy.Mode.AUTO, store.resolve(bad).mode());
    }

    @Test
    void validationBoundaries() {
        assertEquals(1, DangerPolicy.validateRetreatHp(1));
        assertEquals(20, DangerPolicy.validateRetreatHp(20));
        assertThrows(IllegalArgumentException.class, () -> DangerPolicy.validateRetreatHp(0));
        assertThrows(IllegalArgumentException.class, () -> DangerPolicy.validateRetreatHp(21));
        assertEquals(0, DangerPolicy.validateMaxEnemies(0));
        assertEquals(20, DangerPolicy.validateMaxEnemies(20));
        assertThrows(IllegalArgumentException.class, () -> DangerPolicy.validateMaxEnemies(-1));
        assertThrows(IllegalArgumentException.class, () -> DangerPolicy.validateMaxEnemies(21));
    }

    @Test
    void modeParsingIsCaseAndWhitespaceInsensitive() {
        assertEquals(DangerPolicy.Mode.FIGHT, DangerPolicy.parseMode(" Fight "));
        assertEquals(DangerPolicy.Mode.OFF, DangerPolicy.parseMode("off"));
        assertThrows(IllegalArgumentException.class, () -> DangerPolicy.parseMode("berserk"));
        assertThrows(IllegalArgumentException.class, () -> DangerPolicy.parseMode("  "));
    }

    @Test
    void mergeKeepsUnsetFieldsAndDefaultDetection() {
        DangerPolicy merged = DangerPolicy.DEFAULT.mergedWith(null, 12, null, null, null, false);
        assertNull(merged.mode());
        assertEquals(12, merged.retreatHp());
        assertNull(merged.maxEnemies());
        assertNull(merged.keepSurvival());
        assertFalse(merged.isDefault());
        assertTrue(DangerPolicy.DEFAULT.isDefault());
        assertTrue(new DangerPolicy(DangerPolicy.Mode.AUTO, null, null, null, null).isDefault());
    }

    @Test
    void mobReactionsMergeOverrideAndRemove() {
        DangerPolicyStore store = DangerPolicyStore.INSTANCE;
        UUID bot = UUID.randomUUID();

        store.update(bot, null, null, null, null,
                java.util.Map.of("minecraft:creeper", "flee", "minecraft:zombie", "fight"), false);
        DangerPolicyStore.Effective effective = store.resolve(bot);
        assertEquals("flee", effective.mobReactions().get("minecraft:creeper"));
        assertEquals("fight", effective.mobReactions().get("minecraft:zombie"));
        assertFalse(store.raw(bot).isDefault()); // 仅有 per-怪规则也不算默认

        // 增量:zombie 改 ignore,creeper 保留
        store.update(bot, null, null, null, null, java.util.Map.of("minecraft:zombie", "ignore"), false);
        effective = store.resolve(bot);
        assertEquals("flee", effective.mobReactions().get("minecraft:creeper"));
        assertEquals("ignore", effective.mobReactions().get("minecraft:zombie"));

        // "auto" = 删除该怪规则
        store.update(bot, null, null, null, null, java.util.Map.of("minecraft:creeper", "auto"), false);
        assertFalse(store.resolve(bot).mobReactions().containsKey("minecraft:creeper"));
        assertEquals("ignore", store.resolve(bot).mobReactions().get("minecraft:zombie"));

        // 非法反应值被拒
        UUID bot2 = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () ->
                store.update(bot2, null, null, null, null,
                        java.util.Map.of("minecraft:zombie", "befriend"), false));
    }

    @Test
    void resetMobReactionsClearsAllRulesButKeepsBasePolicy() {
        DangerPolicyStore store = DangerPolicyStore.INSTANCE;
        UUID bot = UUID.randomUUID();
        store.update(bot, DangerPolicy.Mode.FLEE, null, null, null,
                java.util.Map.of("minecraft:creeper", "flee"), false);

        store.update(bot, null, null, null, null, null, true); // 只清 per-怪规则
        DangerPolicyStore.Effective effective = store.resolve(bot);
        assertTrue(effective.mobReactions().isEmpty());
        assertEquals(DangerPolicy.Mode.FLEE, effective.mode()); // 基础策略不动

        // 全部规则删光且基础策略也是默认 → 条目整体删除
        store.reset(bot);
        store.update(bot, null, null, null, null, java.util.Map.of("minecraft:zombie", "fight"), false);
        store.update(bot, null, null, null, null, java.util.Map.of("minecraft:zombie", "auto"), false);
        assertNull(store.raw(bot));
    }

    @Test
    void mobReactionsPersistAndReload() throws IOException {
        UUID bot = UUID.randomUUID();
        DangerPolicyStore store = DangerPolicyStore.INSTANCE;
        store.attachDirectory(dir);
        store.update(bot, null, null, null, null,
                java.util.Map.of("minecraft:skeleton", "ignore"), false);

        store.attachDirectory(dir); // 模拟重启
        assertEquals("ignore", store.resolve(bot).mobReactions().get("minecraft:skeleton"));
        assertTrue(Files.readString(dir.resolve("danger_policies.json")).contains("minecraft:skeleton"));
    }
}
