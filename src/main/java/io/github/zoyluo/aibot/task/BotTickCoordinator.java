package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.coordination.IdleCoordinator;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.goal.GoalExecutor;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.observe.TpsGuard;
import net.minecraft.server.MinecraftServer;

/**
 * AI 机器人每 tick 的总调度器。
 *
 * <p>每个服务端 tick 由服务器回调一次 {@link #tick(MinecraftServer)},按固定优先级
 * 依次驱动各个子系统处理每一个存活的 AI 机器人:
 * <ol>
 *   <li>{@link NavSafetyNet} —— 环境安全网(溺水/岩浆自救),最先执行;若机器人正在自救,
 *       本 tick 直接跳过其它所有逻辑;</li>
 *   <li>{@link StuckWatcher} —— 卡住监视器,检测并处理机器人移动卡死;</li>
 *   <li>{@code DangerWatcher} —— 危险检测,按 {@link TpsGuard#dangerScanInterval()}
 *       降频执行;若发现并处理了危险,本 tick 不再执行目标与空闲逻辑;</li>
 *   <li>{@link GoalExecutor} —— 目标执行器,推进机器人当前任务;一旦接管则跳过后续;</li>
 *   <li>空闲兜底 —— 按 {@link TpsGuard#scanInterval()} 降频执行:自动装备更好的护甲,
 *       并由 {@link IdleCoordinator} 分配空闲行为。</li>
 * </ol>
 *
 * <p>危险/空闲扫描的间隔由 {@link TpsGuard} 根据服务器 TPS 动态调整,TPS 越低扫描越稀疏,
 * 以降低机器人逻辑对服务器的压力。
 */
public final class BotTickCoordinator {
    /** 全局单例。 */
    public static final BotTickCoordinator INSTANCE = new BotTickCoordinator();

    private BotTickCoordinator() {
    }

    /**
     * 服务端每 tick 调用一次,驱动所有 AI 机器人的子系统。
     *
     * @param server 当前服务端实例
     */
    public void tick(MinecraftServer server) {
        int tick = server.getTicks();
        TpsGuard guard = TpsGuard.INSTANCE;
        // 根据 TpsGuard 给出的间隔,决定本 tick 是否执行危险扫描/空闲后台逻辑
        boolean runDanger = tick % guard.dangerScanInterval() == 0;
        boolean runBackground = tick % guard.scanInterval() == 0;
        for (AIPlayerEntity bot : AIPlayerManager.INSTANCE.all()) {
            // SAFE-1:环境安全网最先跑;若正在自救(溺水/岩浆)则本 tick 接管,跳过其它检查。
            if (NavSafetyNet.INSTANCE.tickBot(server, bot)) {
                continue;
            }
            // 卡住监视器:检测移动卡死并尝试脱困
            StuckWatcher.INSTANCE.tickBot(server, bot);
            // 危险检测器:发现危险(如受击/敌对生物靠近)则本 tick 标记为已接管
            boolean handled = runDanger && DangerWatcher.INSTANCE.scanBot(server, bot);
            // 目标执行器:推进当前任务,接管则跳过后续空闲逻辑
            if (!handled && GoalExecutor.INSTANCE.tickBot(server, bot)) {
                continue;
            }
            if (!handled && runBackground) {
                io.github.zoyluo.aibot.action.EquipAction.equipBestArmor(bot); // 第3层:平时也自动穿上背包里更好的护甲
                // 空闲协调器:为无事可做的机器人分配闲逛等待类行为
                IdleCoordinator.INSTANCE.tickBot(bot);
            }
        }
    }
}
