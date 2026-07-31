package io.github.zoyluo.aibot.task;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 单个 bot 的危险应对策略(LLM 经 behavior_control 工具设置,持久化到世界存档)。
 * 字段为 null = "不覆盖";由 {@link DangerPolicyStore#resolve} 逐字段回落 AIBotConfig.combat() 默认值——
 * 原配置即默认配置,LLM 覆盖只是加了一层 per-bot 优先级。
 * mobReactions:实体 id → 反应名(fight/flee/ignore)的 per-怪规则,优先级高于全局 mode;
 * null/空 = 无 per-怪规则,全部按 mode 走。
 */
public record DangerPolicy(Mode mode, Integer retreatHp, Integer maxEnemies, Boolean keepSurvival,
                           Map<String, String> mobReactions) {

    public enum Mode {
        /** 默认启发式:打得赢(数量上限/血量/武器)→ 战斗,否则逃跑或夜间封墙。 */
        AUTO,
        /** 主战:canFight 满足(武器+非苦力怕+血量>撤退线,忽略 maxEnemies 上限)即战斗,否则 fallback 逃/封墙。 */
        FIGHT,
        /** 主逃:永不接战,逃跑或夜间封墙。 */
        FLEE,
        /** 关闭自主怪物应对:DangerWatcher 威胁派发整段跳过,当前作业不被打断。 */
        OFF
    }

    /**
     * 遇到某种怪物时的反应(LLM behavior_control 的 mob_reactions 设置)。
     * per-怪规则比全局 mode 更具体,优先生效。
     */
    public enum Reaction {
        /** 遇该怪主战:血量/武器闸保留,但忽略 maxEnemies 数量闸与苦力怕近战保护(显式指令,责任自负)。 */
        FIGHT,
        /** 遇该怪绝不接战,只逃跑或夜间封墙(含绝境反击升级也不对它生效)。 */
        FLEE,
        /** 完全无视该怪:自主威胁扫描当它不存在,当前作业不被它打断。 */
        IGNORE;

        /** 解析工具入参;"auto" 不是反应——它是"删除该怪规则"的记号,调用方先拦,到不了这里。 */
        public static Reaction parse(String raw) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException("missing_or_bad_arg: mob_reactions value");
            }
            try {
                return Reaction.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                throw new IllegalArgumentException("unknown_mob_reaction: " + raw + " (want fight|flee|ignore|auto)");
            }
        }
    }

    /** mob_reactions 里表示"删除该怪规则、回落全局 mode"的记号值。 */
    public static final String REACTION_REMOVE = "auto";

    public static final DangerPolicy DEFAULT = new DangerPolicy(null, null, null, null, null);

    public static Mode parseMode(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("missing_or_bad_arg: mode");
        }
        try {
            return Mode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            throw new IllegalArgumentException("unknown_danger_mode: " + raw + " (want auto|fight|flee|off)");
        }
    }

    public static int validateRetreatHp(int value) {
        if (value < 1 || value > 20) {
            throw new IllegalArgumentException("retreat_hp_out_of_range: " + value + " (want 1-20)");
        }
        return value;
    }

    public static int validateMaxEnemies(int value) {
        if (value < 0 || value > 20) {
            throw new IllegalArgumentException("max_enemies_out_of_range: " + value + " (want 0-20)");
        }
        return value;
    }

    /** 增量合并:null 参数保留当前值;非 null 覆盖(数值先校验)。
     *  注意:校验函数返回 int,三元式两支必须都是 Integer(显式装箱),否则 JLS 把整式降为 int,
     *  "保留当前值"分支会对 null 拆箱炸 NPE。
     *  mobReactionsDelta:per-怪规则增量——值 fight/flee/ignore 覆盖/新增,值 "auto" 删除该怪规则;
     *  resetMobReactions=true 先清空全部 per-怪规则再套增量。 */
    public DangerPolicy mergedWith(Mode newMode, Integer newRetreatHp, Integer newMaxEnemies, Boolean newKeepSurvival,
                                   Map<String, String> mobReactionsDelta, boolean resetMobReactions) {
        Map<String, String> merged = resetMobReactions || mobReactions == null
                ? null
                : new LinkedHashMap<>(mobReactions);
        if (mobReactionsDelta != null && !mobReactionsDelta.isEmpty()) {
            merged = merged == null ? new LinkedHashMap<>() : merged;
            for (Map.Entry<String, String> entry : mobReactionsDelta.entrySet()) {
                if (REACTION_REMOVE.equals(entry.getValue())) {
                    merged.remove(entry.getKey());
                } else {
                    Reaction.parse(entry.getValue()); // 校验,非法值在这里就抛
                    merged.put(entry.getKey(), entry.getValue().trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        if (merged != null && merged.isEmpty()) {
            merged = null; // 空表与 null 同义,归一化,别让 isDefault/落盘出现两种形态
        }
        return new DangerPolicy(
                newMode == null ? mode : newMode,
                newRetreatHp == null ? retreatHp : Integer.valueOf(validateRetreatHp(newRetreatHp)),
                newMaxEnemies == null ? maxEnemies : Integer.valueOf(validateMaxEnemies(newMaxEnemies)),
                newKeepSurvival == null ? keepSurvival : newKeepSurvival,
                merged);
    }

    /** 与默认等价(mode 空/AUTO 且无覆盖、无 per-怪规则)→ 不必持有/持久化,存储层据此删条目。 */
    public boolean isDefault() {
        return (mode == null || mode == Mode.AUTO) && retreatHp == null && maxEnemies == null && keepSurvival == null
                && (mobReactions == null || mobReactions.isEmpty());
    }
}
