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

        store.update(bot, DangerPolicy.Mode.FIGHT, null, null, null);
        DangerPolicyStore.Effective afterMode = store.resolve(bot);
        assertEquals(DangerPolicy.Mode.FIGHT, afterMode.mode());
        assertEquals(10, afterMode.retreatHp()); // 未覆盖 → 配置默认
        assertTrue(afterMode.keepSurvival());

        store.update(bot, null, 6, 0, false); // 不动 mode
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
        store.update(bot, DangerPolicy.Mode.FLEE, 14, null, false);

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
        store.update(bot, DangerPolicy.Mode.OFF, null, null, null);
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
        store.update(bot, DangerPolicy.Mode.FIGHT, null, null, null);
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
        DangerPolicyStore.INSTANCE.update(bot, DangerPolicy.Mode.FIGHT, 5, 3, true);
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
        store.update(bot, DangerPolicy.Mode.FIGHT, null, null, null);
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
        store.update(good, DangerPolicy.Mode.FIGHT, null, null, null);
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
        DangerPolicy merged = DangerPolicy.DEFAULT.mergedWith(null, 12, null, null);
        assertNull(merged.mode());
        assertEquals(12, merged.retreatHp());
        assertNull(merged.maxEnemies());
        assertNull(merged.keepSurvival());
        assertFalse(merged.isDefault());
        assertTrue(DangerPolicy.DEFAULT.isDefault());
        assertTrue(new DangerPolicy(DangerPolicy.Mode.AUTO, null, null, null).isDefault());
    }
}
