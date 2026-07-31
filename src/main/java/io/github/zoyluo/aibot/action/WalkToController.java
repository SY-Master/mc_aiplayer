package io.github.zoyluo.aibot.action;

import io.github.zoyluo.aibot.AIBotConfig;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.log.LogCategory;
import io.github.zoyluo.aibot.log.LogFields;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * 直线行走控制器：按 tick 驱动 bot 沿水平方向径直走向目标点（无寻路、不绕障，走不到由自身判定失败）。
 * 由 {@link ActionPack#startWalkTo(Vec3d)} 创建、{@link ActionPack} 每 tick 推进（见其 tickWalkTo），
 * 结果经 {@link ActionResult} 返回：到达（水平距离 ≤ 0.6）返回 SUCCESS；超时（160 tick）、
 * 硬卡死（stuck_hard）或绕行失败（stuck_blocked）返回 failed。
 *
 * <p>每 tick 依次做五件事：
 * <ol>
 *   <li>超时看门狗（160 tick）与到达判定（水平距离 ≤ 0.6）；</li>
 *   <li>转向与前进：按目标方向（绕行模式下为偏转后的方向）经 {@link LookAction#lookHorizontallyAt}
 *       只调水平朝向（看向行进方向 4 格处），前进输入恒为 1.0；</li>
 *   <li>跳跃决策（shouldJump）：前方 jumpReach 处有碰撞且顶高 ≤ 1、头顶净空 → 上台阶；
 *       碰撞更高 → 标记受阻（不跳也不疾跑）；前方为缺口且落点可站立 → 跳过缺口。
 *       拟人化：仅在已落地时 {@link ActionPack#jumpOnce()} 点跳一次，绝不长按跳键；</li>
 *   <li>疾跑决策（shouldSprint）：距离 ≥ sprintMinDist、未受阻、不在爬台阶（跨缺口允许）、
 *       且前方 1、2 格处脚下与头顶碰撞形状均空时才疾跑；</li>
 *   <li>卡死检测与横移绕行（sidle）：每 tick 位移 &lt; 0.04 累计无进展，达到 sidleAfter 后进入绕行——
 *       每 8 tick 循环按 ±35°/±60° 偏转行进方向并加横移输入尝试蹭开障碍；绕行超过 sidleLimit 判 stuck_blocked；
 *       位移 &lt; 0.005 累计硬卡死，超过 hardLimit 判 stuck_hard。</li>
 * </ol>
 *
 * <p>可调阈值来自 {@link AIBotConfig.Nav}：jumpReach（跳跃前探距离）、sidleAfter/sidleLimit/hardLimit
 * （绕行与卡死阈值）、sprintMinDist（最短疾跑距离）。卡死失败时经 logStuck 记录前方方块、朝向与目标便于排查。
 */
public final class WalkToController {
    private static final double ARRIVAL_THRESHOLD = 0.6D;
    private static final double PROGRESS_EPSILON = 0.04D;
    private static final double HARD_PROGRESS_EPSILON = 0.005D;
    private static final int MAX_TICKS = 160;
    private static final int SIDLE_STEP_TICKS = 8;

    private final Vec3d target;
    private Vec3d lastPos;
    private int noProgressTicks;
    private int hardStuckTicks;
    private int sidleTicks;
    private int elapsed;

    public WalkToController(Vec3d target) {
        this.target = target;
    }

    public ActionResult tick(ActionPack pack) {
        elapsed++;
        if (elapsed > MAX_TICKS) {
            pack.stopMovement();
            return ActionResult.failed("timeout");
        }

        var player = pack.player();
        ServerWorld world = player.getServerWorld();
        AIBotConfig.Nav nav = AIBotConfig.get().nav();
        Vec3d current = player.getPos();
        double dx = target.x - current.x;
        double dz = target.z - current.z;
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        if (horizontalDistance <= ARRIVAL_THRESHOLD) {
            pack.stopMovement();
            return ActionResult.SUCCESS;
        }

        Vec3d move = new Vec3d(dx / horizontalDistance, 0.0D, dz / horizontalDistance);
        SidleCommand sidle = sidleCommand(move, nav);
        LookAction.lookHorizontallyAt(player, current.add(sidle.lookVector.multiply(4.0D)));
        pack.setForward(1.0F);
        pack.setStrafing(sidle.strafing);

        JumpDecision jump = shouldJump(current, move, world, nav);
        // 拟人化:只在"已落地 + 前方确有台阶/缺口"时点跳一次(单跳),绝不长按跳键。
        // 旧实现 setJumping(jump.jump) 会在障碍持续存在的多 tick 里一直按住跳——bot 落地即连跳(兔子跳),
        // 既不像正常玩家,跳跃还会拉低水平速度(实测"边跳边砍树、影响速度")。落地门控确保一台阶只跳一次。
        if (jump.jump && player.isOnGround()) {
            pack.jumpOnce();
        }
        pack.setJumping(false);
        pack.setSprinting(shouldSprint(horizontalDistance, jump, current, move, world, nav));

        if (lastPos != null && current.distanceTo(lastPos) < PROGRESS_EPSILON) {
            noProgressTicks++;
        } else {
            noProgressTicks = 0;
            sidleTicks = 0;
        }
        if (lastPos != null && current.distanceTo(lastPos) < HARD_PROGRESS_EPSILON) {
            hardStuckTicks++;
        } else {
            hardStuckTicks = 0;
        }
        lastPos = current;

        boolean sidling = noProgressTicks >= nav.sidleAfter();
        if (hardStuckTicks > nav.hardLimit() && !sidling) {
            pack.stopMovement();
            logStuck(pack, "hard", current, move, world);
            return ActionResult.failed("stuck_hard");
        }
        if (sidling) {
            sidleTicks++;
        }
        if (sidleTicks > nav.sidleLimit()) {
            pack.stopMovement();
            logStuck(pack, "blocked", current, move, world);
            return ActionResult.failed("stuck_blocked");
        }
        return ActionResult.IN_PROGRESS;
    }

    private SidleCommand sidleCommand(Vec3d move, AIBotConfig.Nav nav) {
        if (noProgressTicks < nav.sidleAfter()) {
            return new SidleCommand(move, 0.0F);
        }
        int step = Math.floorMod(sidleTicks / SIDLE_STEP_TICKS, 4);
        return switch (step) {
            case 0 -> new SidleCommand(rotate(move, 35.0D), 1.0F);
            case 1 -> new SidleCommand(rotate(move, -35.0D), -1.0F);
            case 2 -> new SidleCommand(rotate(move, 60.0D), 0.7F);
            default -> new SidleCommand(rotate(move, -60.0D), -0.7F);
        };
    }

    private static JumpDecision shouldJump(Vec3d current, Vec3d move, ServerWorld world, AIBotConfig.Nav nav) {
        BlockPos front = footPos(current, move, nav.jumpReach());
        BlockState frontState = world.getBlockState(front);
        BlockState aboveFront = world.getBlockState(front.up());
        BlockPos playerPos = BlockPos.ofFloored(current);
        BlockState abovePlayer = world.getBlockState(playerPos.up());
        boolean headClear = isClear(world, front.up()) && isClear(world, playerPos.up());

        if (hasCollision(frontState, world, front)) {
            double top = collisionTop(frontState, world, front);
            if (top <= 1.0D && headClear) {
                return new JumpDecision(true, false, false);
            }
            return new JumpDecision(false, true, false);
        }

        if (isGapAhead(current, move, world) && isClear(world, abovePlayer, playerPos.up())) {
            return new JumpDecision(true, false, true);
        }
        return new JumpDecision(false, false, false);
    }

    private static boolean isGapAhead(Vec3d current, Vec3d move, ServerWorld world) {
        BlockPos near = footPos(current, move, 1.35D);
        if (!isClear(world, near) || !isClear(world, near.up()) || !isClear(world, near.down())) {
            return false;
        }
        BlockPos landing = footPos(current, move, 2.1D);
        return isClear(world, landing)
                && isClear(world, landing.up())
                && hasCollision(world.getBlockState(landing.down()), world, landing.down());
    }

    private static boolean shouldSprint(double horizontalDistance, JumpDecision jump, Vec3d current, Vec3d move, ServerWorld world, AIBotConfig.Nav nav) {
        if (horizontalDistance < nav.sprintMinDist()) {
            return false;
        }
        if (jump.blocked || (jump.jump && !jump.gap)) {
            return false;
        }
        return clearAhead(current, move, world, 1.0D) && clearAhead(current, move, world, 2.0D);
    }

    private static boolean clearAhead(Vec3d current, Vec3d move, ServerWorld world, double distance) {
        BlockPos pos = footPos(current, move, distance);
        return isClear(world, pos) && isClear(world, pos.up());
    }

    private static boolean isClear(ServerWorld world, BlockPos pos) {
        return isClear(world, world.getBlockState(pos), pos);
    }

    private static boolean isClear(ServerWorld world, BlockState state, BlockPos pos) {
        return state.getCollisionShape(world, pos).isEmpty();
    }

    private static boolean hasCollision(BlockState state, ServerWorld world, BlockPos pos) {
        return !state.getCollisionShape(world, pos).isEmpty();
    }

    private static double collisionTop(BlockState state, ServerWorld world, BlockPos pos) {
        if (!hasCollision(state, world, pos)) {
            return 0.0D;
        }
        return state.getCollisionShape(world, pos).getMax(Direction.Axis.Y);
    }

    private static BlockPos footPos(Vec3d current, Vec3d move, double distance) {
        return BlockPos.ofFloored(current.x + move.x * distance, current.y, current.z + move.z * distance);
    }

    private static Vec3d rotate(Vec3d move, double degrees) {
        double radians = Math.toRadians(degrees);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        return new Vec3d(move.x * cos - move.z * sin, 0.0D, move.x * sin + move.z * cos);
    }

    private static void logStuck(ActionPack pack, String reason, Vec3d current, Vec3d move, ServerWorld world) {
        BlockPos front = footPos(current, move, 1.0D);
        BlockState state = world.getBlockState(front);
        BotLog.warn(LogCategory.PATH, pack.player(), "walk_stuck",
                "reason", reason,
                "front", LogFields.pos(front),
                "front_block", Registries.BLOCK.getId(state.getBlock()),
                "yaw", Math.round(pack.player().getYaw()),
                "target", String.format(java.util.Locale.ROOT, "%.2f,%.2f,%.2f", current.x + move.x, current.y, current.z + move.z));
    }

    private record SidleCommand(Vec3d lookVector, float strafing) {
    }

    private record JumpDecision(boolean jump, boolean blocked, boolean gap) {
    }
}
