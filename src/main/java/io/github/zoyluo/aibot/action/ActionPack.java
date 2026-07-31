package io.github.zoyluo.aibot.action;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.log.LogCategory;
import io.github.zoyluo.aibot.pathfinding.AStarPathfinder;
import io.github.zoyluo.aibot.pathfinding.PathExecutor;
import io.github.zoyluo.aibot.pathfinding.PathfindingResult;
import io.github.zoyluo.aibot.pathfinding.Standability;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class ActionPack {
    private static final int PATHFIND_SUCCESS_COOLDOWN_TICKS = 5;
    private static final int PATHFIND_FAILURE_COOLDOWN_TICKS = 20;
    // NAV-OPT 两阶段寻路预算:纯步行只搜空气格(空间小,给足额度);挖穿限额更小,压住被困/地下时的 3D 体积爆搜。
    private static final int WALK_MAX_NODES = 10_000;
    private static final int DIG_MAX_NODES = 4_000;
    // 接近原语专用大预算:接近被包裹的矿必然要挖,直接 DIG 且预算放大(挖掘邻居分支因子小,
    // 24k 节点覆盖 ~40 格穿山直达;普通 startPathTo 的小预算 DIG 仅作走路兜底,语义不变)。
    private static final int DIG_APPROACH_MAX_NODES = 24_000;
    private static final long PATHFIND_MAX_MILLIS = 50L;

    private final AIPlayerEntity player;
    private final List<CompletableFuture<ActionResult>> actionFutures = new ArrayList<>();
    private float forward;
    private float strafing;
    private boolean sneaking;
    private boolean sprinting;
    private boolean jumping;
    private int jumpTicks;
    private WalkToController walkTo;
    private MiningController mining;
    private PathExecutor pathExecutor;
    private int itemUseCooldown;
    private int blockHitDelay;
    private BlockPos lastPathGoal;
    private BlockPos activePathGoal;
    private int nextPathfindTick;

    /** 为指定 bot 创建动作执行器：统一管理移动输入、行走/寻路/挖掘控制器与动作完成回调。 */
    public ActionPack(AIPlayerEntity player) {
        this.player = player;
    }

    /** 把移动输入夹到 [-1, 1]（Minecraft 移动输入的有效范围）。 */
    private static float clampInput(float value) {
        return Math.clamp(value, -1.0F, 1.0F);
    }

    /**
     * 注册动作完成回调，返回在未来动作（寻路/行走/挖掘）完成时触发的 Future。
     * 所有已注册 future 在任意动作完成时一起触发（列表模式，支持顺序分发场景）。
     */
    public CompletableFuture<ActionResult> whenActionComplete() {
        CompletableFuture<ActionResult> future = new CompletableFuture<>();
        actionFutures.add(future);
        return future;
    }

    /** 以给定结果完成所有已注册的回调（见 {@link #whenActionComplete()}）并清空列表（回调是一次性的）。 */
    private void fireActionComplete(ActionResult result) {
        List<CompletableFuture<ActionResult>> pending = new ArrayList<>(actionFutures);
        actionFutures.clear();
        for (CompletableFuture<ActionResult> f : pending) {
            f.complete(result);
        }
    }

    /** 取消所有等待中的动作完成回调（stopAll 时调用，避免悬挂的 future）。 */
    public void cancelActionFutures() {
        List<CompletableFuture<ActionResult>> pending = new ArrayList<>(actionFutures);
        actionFutures.clear();
        for (CompletableFuture<ActionResult> f : pending) {
            f.cancel(false);
        }
    }

    /** 返回本动作包驱动的 bot 实体。 */
    public AIPlayerEntity player() {
        return player;
    }

    /** 设置前后移动输入（正=前进，负=后退），自动夹到 [-1, 1]，下一 tick 生效。 */
    public void setForward(float value) {
        this.forward = clampInput(value);
    }

    /** 设置左右平移输入，自动夹到 [-1, 1]，下一 tick 生效。 */
    public void setStrafing(float value) {
        this.strafing = clampInput(value);
    }

    /** 设置潜行状态（同步到实体）；潜行与疾跑互斥，开启潜行会自动关闭疾跑。 */
    public void setSneaking(boolean sneaking) {
        this.sneaking = sneaking;
        player.setSneaking(sneaking);
        if (sneaking && sprinting) {
            setSprinting(false);
        }
    }

    /** 设置疾跑状态（同步到实体）；疾跑与潜行互斥，开启疾跑会自动关闭潜行。 */
    public void setSprinting(boolean sprinting) {
        this.sprinting = sprinting;
        player.setSprinting(sprinting);
        if (sprinting && sneaking) {
            setSneaking(false);
        }
    }

    /** 设置持续跳跃开关（每 tick 写入实体直到关闭）；一次性跳跃请用 {@link #jumpOnce()}。 */
    public void setJumping(boolean jumping) {
        this.jumping = jumping;
    }

    /** 触发一次跳跃（维持 2 tick 的跳跃按键后自动松开）。 */
    public void jumpOnce() {
        this.jumpTicks = 2;
    }

    /**
     * 启动直线行走控制器走向目标点（无寻路、不绕障，走不到由控制器自行判定失败），
     * 同时清掉当前的挖掘与寻路。返回 {@link ActionResult#IN_PROGRESS}，
     * 完成/失败经 {@link #whenActionComplete()} 通知。
     */
    public ActionResult startWalkTo(Vec3d target) {
        this.walkTo = new WalkToController(target);
        this.mining = null;
        this.pathExecutor = null;
        return ActionResult.IN_PROGRESS;
    }

    /**
     * 统一接近原语入口:挖掘感知寻路(大预算 DIG 直达,目标可为"挖开即站"的实心格——见
     * AStarPathfinder.resolveEndpoint 的挖掘终点豁免)。接近被包裹的矿/穿山直达用这个;
     * 普通走路仍用 startPathTo(先 WALK 后小预算 DIG)。
     */
    public ActionResult startDigPathTo(BlockPos goal) {
        int now = player.getServer().getTicks();
        BlockPos immutableGoal = goal.toImmutable();
        if (lastPathGoal != null && lastPathGoal.equals(immutableGoal) && now < nextPathfindTick) {
            return ActionResult.failed("pathfinding_throttled");
        }
        if (!snapPlayerToNearestStandable("path_start_invalid")) {
            nextPathfindTick = now + PATHFIND_FAILURE_COOLDOWN_TICKS;
            return ActionResult.failed("pathfinding_failed: NO_START");
        }
        boolean canPillar = PathExecutor.hasPlaceableBlock(player);
        PathfindingResult result = new AStarPathfinder(player.getServerWorld(), player.getBlockPos(), goal, DIG_APPROACH_MAX_NODES, PATHFIND_MAX_MILLIS, canPillar, true, 10.0D).findPath();
        if (!result.success()) {
            lastPathGoal = immutableGoal;
            activePathGoal = null;
            nextPathfindTick = now + PATHFIND_FAILURE_COOLDOWN_TICKS;
            return ActionResult.failed("pathfinding_failed: " + result.reason());
        }
        lastPathGoal = immutableGoal;
        nextPathfindTick = now + PATHFIND_SUCCESS_COOLDOWN_TICKS;
        BlockPos resolvedGoal = result.resolvedGoal() == null ? immutableGoal : result.resolvedGoal();
        activePathGoal = resolvedGoal;
        this.pathExecutor = new PathExecutor(result.path(), resolvedGoal);
        this.walkTo = null;
        this.mining = null;
        return ActionResult.IN_PROGRESS;
    }

    /**
     * 启动 A* 寻路走向目标格：先纯步行搜索（禁挖、收敛快），无解再退化为小预算挖穿兜底（隧道/破障）。
     * 同目标在冷却期内重复调用返回 pathfinding_throttled；起点不可站立时先尝试就近纠正
     * （见 {@link #snapPlayerToNearestStandable(String)}）。成功启程返回 {@link ActionResult#IN_PROGRESS}，
     * 完成/失败经 {@link #whenActionComplete()} 通知。
     */
    public ActionResult startPathTo(BlockPos goal) {
        int now = player.getServer().getTicks();
        BlockPos immutableGoal = goal.toImmutable();
        if (lastPathGoal != null && lastPathGoal.equals(immutableGoal) && now < nextPathfindTick) {
            return ActionResult.failed("pathfinding_throttled");
        }
        if (!snapPlayerToNearestStandable("path_start_invalid")) {
            lastPathGoal = immutableGoal;
            activePathGoal = null;
            nextPathfindTick = now + PATHFIND_FAILURE_COOLDOWN_TICKS;
            return ActionResult.failed("pathfinding_failed: NO_START");
        }
        boolean canPillar = PathExecutor.hasPlaceableBlock(player);
        ServerWorld world = player.getServerWorld();
        BlockPos from = player.getBlockPos();
        // NAV-OPT 两阶段寻路:先纯步行(禁挖,搜索空间=空气格,收敛快、不会被挖穿邻居撑爆到 SEARCH_LIMIT);
        // 纯步行无解再允许挖穿兜底(隧道/破障),挖穿预算更小以限制被困/地下时的 3D 体积爆搜。
        PathfindingResult result = new AStarPathfinder(world, from, goal, WALK_MAX_NODES, PATHFIND_MAX_MILLIS, canPillar, false).findPath();
        if (!result.success()) {
            PathfindingResult dig = new AStarPathfinder(world, from, goal, DIG_MAX_NODES, PATHFIND_MAX_MILLIS, canPillar, true).findPath();
            if (dig.success()) {
                result = dig;
            }
        }
        if (!result.success()) {
            lastPathGoal = immutableGoal;
            activePathGoal = null;
            nextPathfindTick = now + PATHFIND_FAILURE_COOLDOWN_TICKS;
            return ActionResult.failed("pathfinding_failed: " + result.reason());
        }
        lastPathGoal = immutableGoal;
        nextPathfindTick = now + PATHFIND_SUCCESS_COOLDOWN_TICKS;
        BlockPos resolvedGoal = result.resolvedGoal() == null ? immutableGoal : result.resolvedGoal();
        activePathGoal = resolvedGoal;
        this.pathExecutor = new PathExecutor(result.path(), resolvedGoal);
        this.walkTo = null;
        this.mining = null;
        return ActionResult.IN_PROGRESS;
    }

    /** 当前寻路的实际目标格（终点经寻路器解析/吸附后的版本）；无进行中寻路时为 null。 */
    public BlockPos activePathGoal() {
        return activePathGoal;
    }

    /**
     * 确保 bot 站在可站立格上：已可站立直接返回 true；否则经紧急传送特权批准后，
     * 在附近搜索一个可站立格并把 bot 移过去。reason 仅用于日志标注触发场景。
     */
    public boolean snapPlayerToNearestStandable(String reason) {
        ServerWorld world = player.getServerWorld();
        BlockPos current = player.getBlockPos();
        Standability.clearCache();
        if (Standability.isStandable(world, current)) {
            return true;
        }
        // A valid current start is ordinary pathfinding and must not require an emergency
        // capability. Only the fallback relocation to a different cell is privileged.
        if (!io.github.zoyluo.aibot.mode.CapabilityRuntime.decide(player, io.github.zoyluo.aibot.mode.PrivilegedCapability.EMERGENCY_TELEPORT, "action_pack_snap:" + reason).allowed()) {
            return false;
        }
        Optional<BlockPos> snapped = Standability.findNearestStandable(world, current, 8, 128, 32);
        if (snapped.isEmpty()) {
            BotLog.warn(LogCategory.PATH, player, "path_start_snap_failed", "reason", reason, "from", io.github.zoyluo.aibot.log.LogFields.pos(current));
            return false;
        }
        BlockPos safe = snapped.get();
        stopMovement();
        player.teleport(world, safe.getX() + 0.5D, safe.getY(), safe.getZ() + 0.5D, Collections.emptySet(), player.getYaw(), player.getPitch(), true);
        Standability.clearCache();
        BotLog.path(player, "path_start_snapped", "reason", reason, "from", io.github.zoyluo.aibot.log.LogFields.pos(current), "to", io.github.zoyluo.aibot.log.LogFields.pos(safe));
        return true;
    }

    /**
     * 主动把 bot 下沉一格到指定(已为空气的)格子。
     * 关键:bot 是 ServerPlayerEntity,服务端**不跑 travel()**(真实玩家的移动/重力由客户端驱动,
     * fake player 没有客户端),因此**没有被动重力**——挖空脚下不会自动下落。竖井下挖类任务
     * (DigDownTask / OreDigTask.digDownOneLayer)必须靠本方法主动驱动下沉,否则会站着空转直到看门狗失败
     * (实测:dig_down 全程 y 恒定、200t no_progress 卡死的共享根因)。
     * 幂等:bot 已在该层或更低则不动。teleport 会清零 fallDistance,不会摔伤。
     */
    public void descendInto(BlockPos target) {
        if (player.getBlockPos().getY() <= target.getY()) {
            return;
        }
        io.github.zoyluo.aibot.mode.FakePlayerMotion.stepTo(player, target, "descend_into");
    }

    /**
     * 开始挖掘指定方块（朝给定方块面持续攻击），同时清掉寻路并归零移动输入。
     * 返回 {@link ActionResult#IN_PROGRESS}，挖完/失败经 {@link #whenActionComplete()} 通知。
     */
    public ActionResult startMining(BlockPos pos, Direction face) {
        this.mining = new MiningController(pos, face);
        this.pathExecutor = null;
        this.forward = 0.0F;
        this.strafing = 0.0F;
        return ActionResult.IN_PROGRESS;
    }

    /** 中止当前挖掘（无进行中的挖掘则无事发生）。 */
    public void stopMining() {
        if (this.mining != null) {
            this.mining.abort(player);
            this.mining = null;
        }
    }

    /** 清零所有移动输入与姿态（前后/平移/潜行/疾跑/跳跃），但不影响寻路与挖掘控制器。 */
    public void stopMovement() {
        setSneaking(false);
        setSprinting(false);
        this.forward = 0.0F;
        this.strafing = 0.0F;
        this.jumping = false;
        this.jumpTicks = 0;
        player.setJumping(false);
    }

    /** 停止一切动作：中止寻路、挖掘、行走与移动输入，停止使用物品，并取消所有完成回调。 */
    public void stopAll() {
        if (pathExecutor != null) {
            pathExecutor.abort(this);
            pathExecutor = null;
        }
        activePathGoal = null;
        stopMining();
        this.walkTo = null;
        stopMovement();
        player.stopUsingItem();
        cancelActionFutures();
    }

    /** 是否还有任何进行中的动作（控制器活动、非零移动输入、跳跃或正在使用物品）。 */
    public boolean hasActiveActions() {
        return pathExecutor != null || walkTo != null || mining != null || forward != 0.0F || strafing != 0.0F || sneaking || sprinting || jumping || jumpTicks > 0 || player.isUsingItem();
    }

    /** 寻路执行器是否空闲（没有进行中的路径）。 */
    public boolean isPathExecutorIdle() {
        return pathExecutor == null;
    }

    /** 直线行走控制器是否空闲。 */
    public boolean isWalkToIdle() {
        return walkTo == null;
    }

    /** 挖掘控制器是否空闲。 */
    public boolean isMiningIdle() {
        return mining == null;
    }

    /**
     * 每 tick 驱动入口：依次推进寻路/行走/挖掘三个控制器，递减使用/击打冷却，
     * 再把当前移动输入（潜行时降速至 0.3）与跳跃状态写入实体。
     */
    public void onUpdate() {
        tickPathExecutor();
        tickWalkTo();
        tickMining();

        if (itemUseCooldown > 0) {
            itemUseCooldown--;
        }
        if (blockHitDelay > 0) {
            blockHitDelay--;
        }

        float velocity = sneaking ? 0.3F : 1.0F;
        player.forwardSpeed = forward * velocity;
        player.sidewaysSpeed = strafing * velocity;
        boolean jumpNow = jumping || jumpTicks > 0;
        player.setJumping(jumpNow);
        if (jumpTicks > 0) {
            jumpTicks--;
        }
    }

    /** 物品使用冷却（tick，{@link #onUpdate()} 每 tick 自动递减）。 */
    public int itemUseCooldown() {
        return itemUseCooldown;
    }

    /** 设置物品使用冷却（负数按 0 处理）。 */
    public void setItemUseCooldown(int itemUseCooldown) {
        this.itemUseCooldown = Math.max(0, itemUseCooldown);
    }

    /** 方块击打延迟（tick，{@link #onUpdate()} 每 tick 自动递减；用于模拟原版挖掘间隔）。 */
    public int blockHitDelay() {
        return blockHitDelay;
    }

    /** 设置方块击打延迟（负数按 0 处理）。 */
    public void setBlockHitDelay(int blockHitDelay) {
        this.blockHitDelay = Math.max(0, blockHitDelay);
    }

    /** 推进直线行走控制器；结束时记日志、清控制器与移动输入，并触发完成回调。 */
    private void tickWalkTo() {
        if (walkTo == null) {
            return;
        }

        ActionResult result = walkTo.tick(this);
        if (result.isInProgress()) {
            return;
        }

        if (result.isSuccess()) {
            BotLog.action(player, "walk_complete");
        } else {
            BotLog.warn(LogCategory.ERROR, player, "walk_failed", "reason", result.reason());
        }
        walkTo = null;
        forward = 0.0F;
        strafing = 0.0F;
        jumping = false;
        player.setJumping(false);
        fireActionComplete(result);
    }

    /** 推进寻路执行器；结束时记日志、清控制器与路径目标，并触发完成回调。 */
    private void tickPathExecutor() {
        if (pathExecutor == null) {
            return;
        }

        ActionResult result = pathExecutor.tick(this);
        if (result.isInProgress()) {
            return;
        }

        if (result.isSuccess()) {
            BotLog.path(player, "path_complete", "ticks", pathExecutor.totalTicks());
        } else {
            BotLog.warn(LogCategory.ERROR, player, "path_failed", "reason", result.reason());
        }
        pathExecutor = null;
        activePathGoal = null;
        forward = 0.0F;
        strafing = 0.0F;
        jumping = false;
        player.setJumping(false);
        fireActionComplete(result);
    }

    /** 推进挖掘控制器；结束时记日志、清控制器，并触发完成回调。 */
    private void tickMining() {
        if (mining == null) {
            return;
        }

        ActionResult result = mining.tick(this);
        if (result.isInProgress()) {
            return;
        }

        if (result.isSuccess()) {
            BotLog.action(player, "mine_complete");
        } else {
            BotLog.warn(LogCategory.ERROR, player, "mine_failed", "reason", result.reason());
        }
        mining = null;
        fireActionComplete(result);
    }
}
