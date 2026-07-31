package io.github.zoyluo.aibot.brain;

import com.google.gson.JsonObject;
import io.github.zoyluo.aibot.AIBotConfig;
import io.github.zoyluo.aibot.action.BuildAction;
import io.github.zoyluo.aibot.action.EquipAction;
import io.github.zoyluo.aibot.action.FarmAction;
import io.github.zoyluo.aibot.action.InteractAction;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.action.LookAction;
import io.github.zoyluo.aibot.action.MiningAction;
import io.github.zoyluo.aibot.action.MovementAction;
import io.github.zoyluo.aibot.action.ToolSelector;
import io.github.zoyluo.aibot.coordination.Job;
import io.github.zoyluo.aibot.coordination.TaskBoard;
import io.github.zoyluo.aibot.auth.BotAuthorizationGate;
import io.github.zoyluo.aibot.auth.BotAuthorizationPolicy;
import io.github.zoyluo.aibot.craft.AcquisitionHints;
import io.github.zoyluo.aibot.craft.CraftingHelper;
import io.github.zoyluo.aibot.goal.Goal;
import io.github.zoyluo.aibot.goal.GoalExecutor;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.memory.BotMemory;
import io.github.zoyluo.aibot.memory.BotMemoryStore;
import io.github.zoyluo.aibot.mining.OreScan;
import io.github.zoyluo.aibot.mode.CapabilityRuntime;
import io.github.zoyluo.aibot.mode.ObservableWorldQuery;
import io.github.zoyluo.aibot.mode.PrivilegedCapability;
import io.github.zoyluo.aibot.runtime.IntentController;
import io.github.zoyluo.aibot.runtime.TaskOrigin;
import io.github.zoyluo.aibot.runtime.IntentControlTransaction;
import io.github.zoyluo.aibot.task.*;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.registry.tag.BiomeTags;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.biome.Biome;

import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class ToolRegistry {
    private final Map<String, ToolDefinition> tools = new LinkedHashMap<>();

    public ToolRegistry() {
        registerDefaults();
    }

    public Optional<ToolDefinition> get(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public List<ToolDefinition> allTools() {
        return List.copyOf(tools.values());
    }

    public List<ToolDefinition> tools(AIBotConfig.Brain config) {
        return tools(config, config.exposesLowLevelTools());
    }

    public List<ToolDefinition> tools(AIBotConfig.Brain config, boolean exposeLowLevelTools) {
        return tools(config, exposeLowLevelTools, config.memoryToolsEnabled(), config.coordinationToolsEnabled());
    }

    public List<ToolDefinition> tools(AIBotConfig.Brain config,
                                      boolean exposeLowLevelTools,
                                      boolean memoryToolsEnabled,
                                      boolean coordinationToolsEnabled) {
        return tools.values().stream()
                .filter(tool -> switch (tool.group()) {
                    case CORE -> true;
                    case MEMORY -> memoryToolsEnabled;
                    case COORDINATION -> coordinationToolsEnabled;
                    case LOW_LEVEL -> exposeLowLevelTools;
                })
                .toList();
    }

    private void registerDefaults() {
        register("look_at", "Turn the bot's head toward a coordinate", xyzSchema(), ToolDefinition.Group.LOW_LEVEL, (bot, args) -> {
            LookAction.lookAt(bot, new Vec3d(requiredInt(args, "x"), requiredInt(args, "y"), requiredInt(args, "z")));
            return ok("looked");
        });

        register("move_to", "Pathfind to a coordinate. Falls back to straight-line walking if pathfinding fails.", xyzSchema(),
            (bot, args) -> {
                BlockPos goal = blockPos(args);
                io.github.zoyluo.aibot.action.ActionResult pathResult = MovementAction.startPathTo(bot, goal);
                if (pathResult.isInProgress() || pathResult.isSuccess()) return ok("pathfinding_started");
                io.github.zoyluo.aibot.action.ActionResult fallback = MovementAction.startWalkTo(bot, Vec3d.ofCenter(goal));
                return (fallback.isInProgress() || fallback.isSuccess()) ? ok("fallback_walk_started: " + pathResult.reason()) : fail("path_and_walk_both_failed: " + pathResult.reason());
            },
            (bot, args) -> {
                CompletableFuture<ToolDefinition.ToolResult> future = new CompletableFuture<>();
                BlockPos goal = blockPos(args);
                io.github.zoyluo.aibot.action.ActionResult pathResult = MovementAction.startPathTo(bot, goal);
                if (pathResult.isInProgress() || pathResult.isSuccess()) {
                    bot.getActionPack().whenActionComplete().thenAccept(r ->
                        future.complete(r.isSuccess() ? ok("arrived") : fail(r.reason())));
                } else {
                    io.github.zoyluo.aibot.action.ActionResult fallback = MovementAction.startWalkTo(bot, Vec3d.ofCenter(goal));
                    if (fallback.isInProgress() || fallback.isSuccess()) {
                        bot.getActionPack().whenActionComplete().thenAccept(r ->
                            future.complete(r.isSuccess() ? ok("arrived") : fail(r.reason())));
                    } else {
                        future.complete(fail("path_and_walk_both_failed: " + pathResult.reason()));
                    }
                }
                return future;
            }, ToolDefinition.Group.LOW_LEVEL);

        register("mine_block", "Low-level single-block break at given coords. Bot must already be within reach.", xyzSchema(),
            (bot, args) -> { BlockPos pos = blockPos(args); MiningAction.startMining(bot, pos, Direction.getFacing(bot.getEyePos().subtract(pos.toCenterPos()))); return ok("started"); },
            actionAsyncHandler((bot, args) -> {
                BlockPos pos = blockPos(args);
                MiningAction.startMining(bot, pos, Direction.getFacing(bot.getEyePos().subtract(pos.toCenterPos())));
            }), ToolDefinition.Group.LOW_LEVEL);

        register("place_block", "Low-level manual placement of the currently held block at given coords.", xyzSchema(),
            (bot, args) -> result(BuildAction.placeBlockAt(bot, blockPos(args))),
            (bot, args) -> {
                io.github.zoyluo.aibot.action.ActionResult r = BuildAction.placeBlockAt(bot, blockPos(args));
                return CompletableFuture.completedFuture(r.isSuccess() ? ok("placed") : fail(r.reason()));
            }, ToolDefinition.Group.LOW_LEVEL);

        register("select_hotbar", "Select hotbar slot 0..8", objectSchema()
                .property("slot", integerSchema("hotbar slot", 0, 8))
                .required("slot")
                .build(), ToolDefinition.Group.LOW_LEVEL, (bot, args) -> result(InventoryAction.selectHotbar(bot, requiredInt(args, "slot"))));

        register("inventory", "Get the bot's current inventory", objectSchema().build(), (bot, args) ->
                ok(InventoryAction.summarize(bot).toString()));

        register("check_biome", "Query the current biome where the bot is standing. Returns biome id and relevant biome tags (IS_FOREST, IS_OCEAN, etc.). Use this BEFORE committing to long-distance resource gathering if the biome may not contain the needed resources.", objectSchema().build(), (bot, args) -> {
            var entry = bot.getServerWorld().getBiome(bot.getBlockPos());
            String biomeId = entry.getKeyOrValue()
                    .map(key -> key.getValue().toString(), b -> "unregistered");
            StringBuilder tags = new StringBuilder();
            checkTag(entry, BiomeTags.IS_FOREST, "IS_FOREST", tags);
            checkTag(entry, BiomeTags.IS_OCEAN, "IS_OCEAN", tags);
            checkTag(entry, BiomeTags.IS_DEEP_OCEAN, "IS_DEEP_OCEAN", tags);
            checkTag(entry, BiomeTags.IS_BEACH, "IS_BEACH", tags);
            checkTag(entry, BiomeTags.IS_BADLANDS, "IS_BADLANDS", tags);
            checkTag(entry, BiomeTags.IS_MOUNTAIN, "IS_MOUNTAIN", tags);
            checkTag(entry, BiomeTags.IS_RIVER, "IS_RIVER", tags);
            checkTag(entry, BiomeTags.IS_TAIGA, "IS_TAIGA", tags);
            checkTag(entry, BiomeTags.IS_JUNGLE, "IS_JUNGLE", tags);
            return ok("{\"biome\":\"" + escape(biomeId)
                    + "\",\"tags\":[" + tags + "]}");
        });

        register("equip_best_tool", "Equip the best available tool for breaking a block type", objectSchema()
                .property("block", stringSchema("block id, for example minecraft:stone"))
                .required("block")
                .build(), (bot, args) -> {
            Block block = requiredBlock(args, "block");
            ToolSelector.Selection selection = ToolSelector.equipBestTool(bot, block.getDefaultState());
            return ok(selection.describe());
        });

        register("plan_craft", "Read-only preflight for crafting. Returns feasible, deterministic craft steps, missing materials, and each missing material's acquisition source.", objectSchema()
                .property("item", stringSchema("target item id, for example minecraft:stone_pickaxe"))
                .property("count", integerSchema("desired count"))
                .required("item")
                .build(), (bot, args) -> ok(craftPlanJson(CraftingHelper.plan(bot, requiredItem(args, "item"), optionalInt(args, "count", 1)))));

        register("craft", "Craft an item using known survival recipes. It resolves planks and sticks recursively. Fails with need: <item> xN when base materials are missing.", objectSchema()
                .property("item", stringSchema("item id, for example minecraft:stone_pickaxe"))
                .property("count", integerSchema("desired count"))
                .required("item")
                .build(),
            (bot, args) -> {
                Task task = new CraftTask(requiredItem(args, "item"), optionalInt(args, "count", 1));
                assignLlm(bot, task);
                return ok("assigned: " + task.name());
            },
            (bot, args) -> {
                CompletableFuture<ToolDefinition.ToolResult> future = new CompletableFuture<>();
                CompletableFuture<TaskStatus> taskFuture = TaskManager.INSTANCE.whenComplete(bot);
                Task task = new CraftTask(requiredItem(args, "item"), optionalInt(args, "count", 1));
                assignLlm(bot, task);
                taskFuture.thenAccept(status -> future.complete(fromTaskStatus(status)));
                return future;
            });

        register("eat", "Eat available food from inventory", objectSchema().build(),
            (bot, args) -> { Task task = new EatTask(); assignLlm(bot, task); return ok("assigned: " + task.name()); },
            taskAsyncHandler(args -> new EatTask()));

        register("smelt", "Smelt input items in a nearby or held furnace using available fuel.", objectSchema()
                .property("input_item", stringSchema("input item id, for example minecraft:raw_iron"))
                .property("output_item", stringSchema("expected output item id, for example minecraft:iron_ingot"))
                .property("count", integerSchema("output count"))
                .required("input_item")
                .required("output_item")
                .build(),
            (bot, args) -> {
                Task task = new SmeltTask(requiredItem(args, "input_item"), requiredItem(args, "output_item"), optionalInt(args, "count", 1));
                assignLlm(bot, task);
                return ok("assigned: " + task.name());
            },
            taskAsyncHandler(args -> new SmeltTask(requiredItem(args, "input_item"), requiredItem(args, "output_item"), optionalInt(args, "count", 1))));

        register("gather", "Gather an item until the inventory contains the requested quota.", objectSchema()
                .property("item", stringSchema("target item id, for example minecraft:cobblestone"))
                .property("count", integerSchema("desired inventory count"))
                .required("item")
                .build(),
            (bot, args) -> { Task task = new GatherQuotaTask(requiredItem(args, "item"), optionalInt(args, "count", 1)); assignLlm(bot, task); return ok("assigned: " + task.name()); },
            taskAsyncHandler(args -> new GatherQuotaTask(requiredItem(args, "item"), optionalInt(args, "count", 1))));

        register("fish", "Fish at nearby water with a fishing rod.", objectSchema()
                .property("max_catches", integerSchema("number of successful catches"))
                .property("max_ticks", integerSchema("maximum task duration in ticks"))
                .build(),
            (bot, args) -> { Task task = new FishTask(optionalInt(args, "max_catches", 1), optionalInt(args, "max_ticks", 6000)); assignLlm(bot, task); return ok("assigned: " + task.name()); },
            taskAsyncHandler(args -> new FishTask(optionalInt(args, "max_catches", 1), optionalInt(args, "max_ticks", 6000))));

        register("trade", "Trade directly with a nearby villager.", objectSchema()
                .property("target_item", stringSchema("optional item id to buy, for example minecraft:bread"))
                .property("max_distance", integerSchema("search radius"))
                .build(),
            (bot, args) -> { Task task = new TradeTask(optionalItem(args, "target_item"), optionalInt(args, "max_distance", 16)); assignLlm(bot, task); return ok("assigned: " + task.name()); },
            taskAsyncHandler(args -> new TradeTask(optionalItem(args, "target_item"), optionalInt(args, "max_distance", 16))));

        register("set_base", "Remember the bot's current position as the base for stockpiling and resupply tasks.", objectSchema().build(), ToolDefinition.Group.MEMORY, (bot, args) -> {
            BotMemoryStore.INSTANCE.of(bot.getUuid()).markPlace("base", bot.getServerWorld(), bot.getBlockPos());
            return ok("marked_base: " + bot.getBlockPos().toShortString());
        });

        register("deposit_all", "Deposit carried items into containers near the remembered base.", objectSchema()
                .property("all_except_tools", booleanSchema("deposit all non-damageable items and keep tools/equipment"))
                .build(),
            (bot, args) -> { Task task = new StockpileTask(optionalBoolean(args, "all_except_tools", true)); assignLlm(bot, task); return ok("assigned: " + task.name()); },
            taskAsyncHandler(args -> new StockpileTask(optionalBoolean(args, "all_except_tools", true))));

        register("strip_mine", "Mine a 2-high branch tunnel in a direction.", objectSchema()
                .property("direction", stringSchema("north, south, east, or west"))
                .property("length", integerSchema("main tunnel length"))
                .property("spacing", integerSchema("branch spacing and branch depth"))
                .property("depot_x", integerSchema("optional depot chest x"))
                .property("depot_y", integerSchema("optional depot chest y"))
                .property("depot_z", integerSchema("optional depot chest z"))
                .property("target_ores", stringSchema("optional comma separated ore block ids"))
                .build(),
            (bot, args) -> { Task task = new StripMineTask(optionalDirection(args, "direction", Direction.NORTH), optionalInt(args, "length", 16), optionalInt(args, "spacing", 4), optionalBlockPos(args, "depot_x", "depot_y", "depot_z"), optionalBlocksCsv(args, "target_ores")); assignLlm(bot, task); return ok("assigned: " + task.name()); },
            taskAsyncHandler(args -> new StripMineTask(optionalDirection(args, "direction", Direction.NORTH), optionalInt(args, "length", 16), optionalInt(args, "spacing", 4), optionalBlockPos(args, "depot_x", "depot_y", "depot_z"), optionalBlocksCsv(args, "target_ores"))));

        register("mine_vein", "Mine the nearest visible ore vein in range using bounded BFS.", objectSchema()
                .property("target_ores", stringSchema("optional comma separated ore block ids"))
                .build(),
            (bot, args) -> { Task task = StripMineTask.mineNearbyVein(optionalBlocksCsv(args, "target_ores")); assignLlm(bot, task); return ok("assigned: " + task.name()); },
            taskAsyncHandler(args -> StripMineTask.mineNearbyVein(optionalBlocksCsv(args, "target_ores"))));

        register("mine_ore", "PREFERRED way to obtain ores (e.g. minecraft:iron_ore or raw item minecraft:raw_iron). Starts a deterministic goal plan: prepare the required pickaxe first, then mine the ore. Do not manually break this into gather/craft/mine steps.", objectSchema()
                .property("ore", stringSchema("ore block id or raw item, e.g. minecraft:iron_ore or minecraft:raw_iron"))
                .property("count", integerSchema("how many ore blocks to mine"))
                .required("ore")
                .build(),
            (bot, args) -> {
                if (!AIBotConfig.get().goal().autoToolFillEnabled()) {
                    Task task = new OreDigTask(oreTargetsFrom(requiredString(args, "ore")), optionalInt(args, "count", 1));
                    assignLlm(bot, task);
                    return ok("assigned: " + task.name());
                }
                boolean started = GoalExecutor.INSTANCE.submit(bot,
                        new Goal.MineOre(oreTargetsFrom(requiredString(args, "ore")), optionalInt(args, "count", 1)));
                return started ? ok("goal_assigned: mine_ore") : fail("goal_plan_failed");
            },
            (bot, args) -> {
                // 异步：注册 goal 完成回调，等待整个目标执行完毕后返回真实结果
                CompletableFuture<ToolDefinition.ToolResult> future = new CompletableFuture<>();
                CompletableFuture<io.github.zoyluo.aibot.goal.GoalResult> goalFuture = GoalExecutor.INSTANCE.whenComplete(bot);
                if (!AIBotConfig.get().goal().autoToolFillEnabled()) {
                    Task task = new OreDigTask(oreTargetsFrom(requiredString(args, "ore")), optionalInt(args, "count", 1));
                    CompletableFuture<TaskStatus> taskFuture = TaskManager.INSTANCE.whenComplete(bot);
                    assignLlm(bot, task);
                    taskFuture.thenAccept(status -> future.complete(fromTaskStatus(status)));
                    return future;
                }
                boolean started = GoalExecutor.INSTANCE.submit(bot,
                        new Goal.MineOre(oreTargetsFrom(requiredString(args, "ore")), optionalInt(args, "count", 1)));
                goalFuture.thenAccept(result ->
                    future.complete(result.status() == io.github.zoyluo.aibot.goal.GoalResult.Status.COMPLETED
                        ? ok("goal_completed: " + GoalExecutor.INSTANCE.resultSummary(result))
                        : fail(result.reason())));
                return future;
            });

        register("achieve_goal", "Achieve an item/tool inventory goal with deterministic planning. Use this for requests like make an iron pickaxe or obtain 10 iron ingots; do not manually decompose the steps.", objectSchema()
                .property("item", stringSchema("target item/tool id, for example minecraft:iron_pickaxe or minecraft:iron_ingot"))
                .property("count", integerSchema("desired inventory count"))
                .required("item")
                .build(),
            (bot, args) -> {
                boolean started = GoalExecutor.INSTANCE.submit(bot,
                        new Goal.HaveItem(requiredItem(args, "item"), optionalInt(args, "count", 1)));
                return started ? ok("goal_assigned: achieve_goal") : fail("goal_plan_failed");
            },
            (bot, args) -> {
                CompletableFuture<ToolDefinition.ToolResult> future = new CompletableFuture<>();
                CompletableFuture<io.github.zoyluo.aibot.goal.GoalResult> goalFuture = GoalExecutor.INSTANCE.whenComplete(bot);
                GoalExecutor.INSTANCE.submit(bot,
                        new Goal.HaveItem(requiredItem(args, "item"), optionalInt(args, "count", 1)));
                goalFuture.thenAccept(result ->
                    future.complete(result.status() == io.github.zoyluo.aibot.goal.GoalResult.Status.COMPLETED
                        ? ok("goal_completed: " + GoalExecutor.INSTANCE.resultSummary(result))
                        : fail(result.reason())));
                return future;
            });

        register("harvest_crop", "Grow and harvest a crop with deterministic planning. Use for requests like 种小麦/收点小麦/get wheat. Crop is wheat, carrot, or potato. The system auto-prepares a hoe, tills, plants, waits for growth, and harvests; do not decompose manually.", objectSchema()
                .property("crop", stringSchema("crop: wheat, carrot, or potato"))
                .property("count", integerSchema("how many to harvest"))
                .required("crop")
                .build(),
            (bot, args) -> {
                FarmAction.CropSpec spec = FarmAction.cropSpec(requiredString(args, "crop"));
                net.minecraft.item.Item produce = spec.crop() == net.minecraft.block.Blocks.WHEAT
                        ? net.minecraft.item.Items.WHEAT
                        : spec.seed();
                boolean started = GoalExecutor.INSTANCE.submit(bot,
                        new Goal.HarvestCrop(spec.crop(), spec.seed(), produce, optionalInt(args, "count", 1)));
                return started ? ok("goal_assigned: harvest_crop") : fail("goal_plan_failed");
            },
            (bot, args) -> {
                CompletableFuture<ToolDefinition.ToolResult> future = new CompletableFuture<>();
                CompletableFuture<io.github.zoyluo.aibot.goal.GoalResult> goalFuture = GoalExecutor.INSTANCE.whenComplete(bot);
                FarmAction.CropSpec spec = FarmAction.cropSpec(requiredString(args, "crop"));
                net.minecraft.item.Item produce = spec.crop() == net.minecraft.block.Blocks.WHEAT
                        ? net.minecraft.item.Items.WHEAT
                        : spec.seed();
                GoalExecutor.INSTANCE.submit(bot,
                        new Goal.HarvestCrop(spec.crop(), spec.seed(), produce, optionalInt(args, "count", 1)));
                goalFuture.thenAccept(result ->
                    future.complete(result.status() == io.github.zoyluo.aibot.goal.GoalResult.Status.COMPLETED
                        ? ok("goal_completed: " + GoalExecutor.INSTANCE.resultSummary(result))
                        : fail(result.reason())));
                return future;
            });

        register("provision_food", "Stock food end-to-end; AUTO-PICKS hunting or farming by scanning what's actually around (perception-driven). "
                + "This is the DEFAULT for ANY general 'get food' request. "
                + "Auto-plans (hunt->cook meat OR farm->bread) based on surroundings; do NOT decompose manually. count = how many food items (default 4).", objectSchema()
                .property("count", integerSchema("how many cooked food items to stock (default 4)"))
                .build(),
            (bot, args) -> {
                boolean started = GoalExecutor.INSTANCE.submit(bot,
                        new Goal.Food(optionalInt(args, "count", 4)));
                return started ? ok("goal_assigned: provision_food") : fail("goal_plan_failed");
            },
            (bot, args) -> {
                CompletableFuture<ToolDefinition.ToolResult> future = new CompletableFuture<>();
                CompletableFuture<io.github.zoyluo.aibot.goal.GoalResult> goalFuture = GoalExecutor.INSTANCE.whenComplete(bot);
                GoalExecutor.INSTANCE.submit(bot, new Goal.Food(optionalInt(args, "count", 4)));
                goalFuture.thenAccept(result ->
                    future.complete(result.status() == io.github.zoyluo.aibot.goal.GoalResult.Status.COMPLETED
                        ? ok("goal_completed: " + GoalExecutor.INSTANCE.resultSummary(result))
                        : fail(result.reason())));
                return future;
            });

        register("forage", "Forage SPECIFIC wild berries/melon nearby. ONLY when the user EXPLICITLY asks for berries/wild fruit, NOT for general food. "
                + "Use for 采点野果/采点浆果/摘浆果/采甜浆果/摘西瓜/想吃浆果; needs berry bushes or melons around. "
                + "For ANY general 找吃的/搞点吃的 request use provision_food instead (it auto-picks hunt or farm). count = how many (default 4).", objectSchema()
                .property("count", integerSchema("how many wild food to gather (default 4)"))
                .build(), (bot, args) -> {
            boolean started = GoalExecutor.INSTANCE.submit(bot,
                    new Goal.HaveItem(net.minecraft.item.Items.SWEET_BERRIES, optionalInt(args, "count", 4)));
            return started ? ok("goal_assigned: forage") : fail("goal_plan_failed");
        });

        register("achieve_armor", "Make and equip a full set of iron armor plus an iron sword with deterministic planning. Use for 武装起来/做一身装备/给我穿上盔甲/gear up. Auto-plans mining, smelting and crafting; do not decompose manually.", objectSchema()
                .build(),
            (bot, args) -> {
                boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.Armor());
                return started ? ok("goal_assigned: achieve_armor") : fail("goal_plan_failed");
            },
            (bot, args) -> {
                CompletableFuture<ToolDefinition.ToolResult> future = new CompletableFuture<>();
                CompletableFuture<io.github.zoyluo.aibot.goal.GoalResult> goalFuture = GoalExecutor.INSTANCE.whenComplete(bot);
                GoalExecutor.INSTANCE.submit(bot, new Goal.Armor());
                goalFuture.thenAccept(result ->
                    future.complete(result.status() == io.github.zoyluo.aibot.goal.GoalResult.Status.COMPLETED
                        ? ok("goal_completed: " + GoalExecutor.INSTANCE.resultSummary(result))
                        : fail(result.reason())));
                return future;
            });

        register("achieve_workstation", "Set up a base: craft and place a crafting table, furnace and chest nearby. Use for 建个家/搭个工作台/摆好工作台熔炉箱子/set up a base. Auto-plans gathering and crafting; do not decompose manually.", objectSchema()
                .build(),
            (bot, args) -> {
                boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.Workstation());
                return started ? ok("goal_assigned: achieve_workstation") : fail("goal_plan_failed");
            },
            (bot, args) -> {
                CompletableFuture<ToolDefinition.ToolResult> future = new CompletableFuture<>();
                CompletableFuture<io.github.zoyluo.aibot.goal.GoalResult> goalFuture = GoalExecutor.INSTANCE.whenComplete(bot);
                GoalExecutor.INSTANCE.submit(bot, new Goal.Workstation());
                goalFuture.thenAccept(result ->
                    future.complete(result.status() == io.github.zoyluo.aibot.goal.GoalResult.Status.COMPLETED
                        ? ok("goal_completed: " + GoalExecutor.INSTANCE.resultSummary(result))
                        : fail(result.reason())));
                return future;
            });

        register("build_house", "Build a house/shelter. The goal system auto-gathers ALL missing materials then builds — call once then STOP.", objectSchema()
                .property("blueprint", stringSchema("preset blueprint name: small_hut (default) or hut_5x5"))
                .property("width", integerSchema("custom house outer width in blocks (3..16)", 3, 16))
                .property("depth", integerSchema("custom house outer depth in blocks (3..16)", 3, 16))
                .property("height", integerSchema("custom house wall height in blocks (2..8)", 2, 8))
                .property("material", stringSchema("wall material palette: planks (default) / stone_like / glass"))
                .build(),
            (bot, args) -> {
                String bp = buildBlueprint(args);
                boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.Build(bp));
                return started ? ok("goal_assigned: build " + bp) : fail("goal_plan_failed");
            },
            (bot, args) -> {
                CompletableFuture<ToolDefinition.ToolResult> future = new CompletableFuture<>();
                CompletableFuture<io.github.zoyluo.aibot.goal.GoalResult> goalFuture = GoalExecutor.INSTANCE.whenComplete(bot);
                String bp = buildBlueprint(args);
                GoalExecutor.INSTANCE.submit(bot, new Goal.Build(bp));
                goalFuture.thenAccept(result ->
                    future.complete(result.status() == io.github.zoyluo.aibot.goal.GoalResult.Status.COMPLETED
                        ? ok("goal_completed: " + GoalExecutor.INSTANCE.resultSummary(result))
                        : fail(result.reason())));
                return future;
            });

        register("stockpile", "Obtain N of an item then store everything into a nearby chest. Auto-plans obtaining and depositing; do not decompose manually.", objectSchema()
                .property("item", stringSchema("item id to stockpile, e.g. minecraft:cobblestone"))
                .property("count", integerSchema("how many to obtain"))
                .required("item")
                .build(),
            (bot, args) -> {
                boolean started = GoalExecutor.INSTANCE.submit(bot,
                        new Goal.Stockpile(requiredItem(args, "item"), optionalInt(args, "count", 1)));
                return started ? ok("goal_assigned: stockpile") : fail("goal_plan_failed");
            },
            (bot, args) -> {
                CompletableFuture<ToolDefinition.ToolResult> future = new CompletableFuture<>();
                CompletableFuture<io.github.zoyluo.aibot.goal.GoalResult> goalFuture = GoalExecutor.INSTANCE.whenComplete(bot);
                GoalExecutor.INSTANCE.submit(bot,
                        new Goal.Stockpile(requiredItem(args, "item"), optionalInt(args, "count", 1)));
                goalFuture.thenAccept(result ->
                    future.complete(result.status() == io.github.zoyluo.aibot.goal.GoalResult.Status.COMPLETED
                        ? ok("goal_completed: " + GoalExecutor.INSTANCE.resultSummary(result))
                        : fail(result.reason())));
                return future;
            });

        register("find_container", "Find the nearest reachable inventory container such as a chest", objectSchema()
                .property("radius", integerSchema("search radius"))
                .build(), (bot, args) -> ContainerTask.nearestContainer(bot, optionalInt(args, "radius", 8))
                .map(pos -> ok("{\"x\":" + pos.getX() + ",\"y\":" + pos.getY() + ",\"z\":" + pos.getZ() + "}"))
                .orElseGet(() -> fail("no_container")));

        register("deposit", "Deposit items into a nearby or specified container.", objectSchema()
                .property("item", stringSchema("optional item id to deposit"))
                .property("count", integerSchema("optional item count"))
                .property("all_except_tools", booleanSchema("deposit all non-damageable items"))
                .property("chest_x", integerSchema("optional container x"))
                .property("chest_y", integerSchema("optional container y"))
                .property("chest_z", integerSchema("optional container z"))
                .build(),
            (bot, args) -> { Task task = ContainerTask.deposit(optionalBlockPos(args, "chest_x", "chest_y", "chest_z"), optionalItem(args, "item"), optionalInt(args, "count", 0), optionalBoolean(args, "all_except_tools", false)); assignLlm(bot, task); return ok("assigned: " + task.name()); },
            taskAsyncHandler(args -> ContainerTask.deposit(optionalBlockPos(args, "chest_x", "chest_y", "chest_z"), optionalItem(args, "item"), optionalInt(args, "count", 0), optionalBoolean(args, "all_except_tools", false))));

        register("withdraw", "Withdraw a specific item count from a nearby or specified container", objectSchema()
                .property("item", stringSchema("item id to withdraw"))
                .property("count", integerSchema("count to withdraw"))
                .property("chest_x", integerSchema("optional container x"))
                .property("chest_y", integerSchema("optional container y"))
                .property("chest_z", integerSchema("optional container z"))
                .required("item")
                .build(),
            (bot, args) -> { Task task = ContainerTask.withdraw(optionalBlockPos(args, "chest_x", "chest_y", "chest_z"), requiredItem(args, "item"), optionalInt(args, "count", 1)); assignLlm(bot, task); return ok("assigned: " + task.name()); },
            taskAsyncHandler(args -> ContainerTask.withdraw(optionalBlockPos(args, "chest_x", "chest_y", "chest_z"), requiredItem(args, "item"), optionalInt(args, "count", 1))));

        register("equip_armor", "Equip the best armor pieces from inventory and select the best weapon", objectSchema().build(), (bot, args) -> {
            int equipped = EquipAction.equipBestArmor(bot);
            EquipAction.equipBestWeapon(bot);
            return ok("equipped_armor_pieces: " + equipped);
        });

        register("attack", "Start a deterministic combat task against nearby entities of a type.", objectSchema()
                .property("entity_type", stringSchema("entity type, for example minecraft:zombie"))
                .property("count", integerSchema("number of kills"))
                .required("entity_type")
                .build(),
            (bot, args) -> { Task task = new CombatTask(requiredEntityType(args, "entity_type"), optionalInt(args, "count", 1), DangerPolicyStore.INSTANCE.resolve(bot).retreatHp()); assignLlm(bot, task); return ok("assigned: " + task.name()); },
            (bot, args) -> { Task task = new CombatTask(requiredEntityType(args, "entity_type"), optionalInt(args, "count", 1), DangerPolicyStore.INSTANCE.resolve(bot).retreatHp()); return taskAsyncHandler(ignored -> task).prepare(bot, args); });

        register("sleep", "Find or place a bed, sleep through night, and wake up in the morning", objectSchema().build(),
            (bot, args) -> { Task task = new SleepTask(); assignLlm(bot, task); return ok("assigned: " + task.name()); },
            taskAsyncHandler(args -> new SleepTask()));

        register("light_area", "Place torches around the bot where block light is below the configured threshold", objectSchema()
                .property("radius", integerSchema("scan radius"))
                .property("max_torches", integerSchema("maximum torches to place"))
                .build(),
            (bot, args) -> { Task task = new LightAreaTask(optionalInt(args, "radius", 8), optionalInt(args, "max_torches", 8)); assignLlm(bot, task); return ok("assigned: " + task.name()); },
            taskAsyncHandler(args -> new LightAreaTask(optionalInt(args, "radius", 8), optionalInt(args, "max_torches", 8))));

        register("follow", "Follow a player while keeping roughly 2-4 blocks of distance. Omit player_name to follow this bot's owner.", objectSchema()
                .property("player_name", stringSchema("optional player name; defaults to owner"))
                .build(),
            (bot, args) -> { Task task = new FollowTask(optionalString(args, "player_name", "")); assignLlm(bot, task); return ok("assigned: " + task.name()); },
            taskAsyncHandler(args -> new FollowTask(optionalString(args, "player_name", ""))));

        register("hold", "Hold the current position until another task is assigned. DangerWatcher can still interrupt for survival threats.", objectSchema().build(),
            (bot, args) -> { Task task = new HoldTask(); assignLlm(bot, task); return ok("assigned: " + task.name()); },
            taskAsyncHandler(args -> new HoldTask()));

        register("guard", "Guard the current point, a coordinate, or a named player. Hostiles near the guard point are fought inline, then the bot returns.", objectSchema()
                .property("player_name", stringSchema("optional player name to guard"))
                .property("x", integerSchema("optional guard x"))
                .property("y", integerSchema("optional guard y"))
                .property("z", integerSchema("optional guard z"))
                .build(),
            (bot, args) -> {
                String playerName = optionalString(args, "player_name", "");
                BlockPos point = optionalBlockPos(args, "x", "y", "z");
                Task task = playerName.isBlank() ? GuardTask.point(point == null ? bot.getBlockPos() : point) : GuardTask.player(playerName);
                assignLlm(bot, task);
                return ok("assigned: " + task.name());
            },
            (bot, args) -> {
                String playerName = optionalString(args, "player_name", "");
                BlockPos point = optionalBlockPos(args, "x", "y", "z");
                Task task = playerName.isBlank() ? GuardTask.point(point == null ? bot.getBlockPos() : point) : GuardTask.player(playerName);
                return taskAsyncHandler(ignored -> task).prepare(bot, args);
            });

        register("farm", "Till soil, plant crops, harvest mature crops. Supported crops: wheat, carrot, potato.", objectSchema()
                .property("x", integerSchema("area center x")).property("y", integerSchema("area center y")).property("z", integerSchema("area center z"))
                .property("radius", integerSchema("area radius")).property("crop", stringSchema("wheat, carrot, or potato"))
                .property("keep_tending", booleanSchema("keep surveying"))
                .required("x").required("y").required("z").required("crop")
                .build(),
            (bot, args) -> { FarmAction.CropSpec spec = FarmAction.cropSpec(requiredString(args, "crop")); Task task = new FarmTask(blockPos(args), optionalInt(args, "radius", 3), spec.seed(), spec.crop(), optionalBoolean(args, "keep_tending", false), false); assignLlm(bot, task); return ok("assigned: " + task.name()); },
            taskAsyncHandler(args -> { FarmAction.CropSpec spec = FarmAction.cropSpec(requiredString(args, "crop")); return new FarmTask(blockPos(args), optionalInt(args, "radius", 3), spec.seed(), spec.crop(), optionalBoolean(args, "keep_tending", false), false); }));

        register("harvest", "Harvest mature crops in an area without tilling. Supported crops: wheat, carrot, potato.", objectSchema()
                .property("x", integerSchema("area center x")).property("y", integerSchema("area center y")).property("z", integerSchema("area center z"))
                .property("radius", integerSchema("area radius")).property("crop", stringSchema("wheat, carrot, or potato"))
                .required("x").required("y").required("z").required("crop")
                .build(),
            (bot, args) -> { FarmAction.CropSpec spec = FarmAction.cropSpec(requiredString(args, "crop")); Task task = new FarmTask(blockPos(args), optionalInt(args, "radius", 3), spec.seed(), spec.crop(), false, true); assignLlm(bot, task); return ok("assigned: " + task.name()); },
            taskAsyncHandler(args -> { FarmAction.CropSpec spec = FarmAction.cropSpec(requiredString(args, "crop")); return new FarmTask(blockPos(args), optionalInt(args, "radius", 3), spec.seed(), spec.crop(), false, true); }));

        register("breed", "Feed two nearby adult animals of the requested type to breed them.", objectSchema()
                .property("entity_type", stringSchema("entity type, for example minecraft:cow"))
                .property("pairs", integerSchema("number of pairs to breed"))
                .required("entity_type")
                .build(),
            (bot, args) -> { Task task = new BreedTask(requiredEntityType(args, "entity_type"), optionalInt(args, "pairs", 1)); assignLlm(bot, task); return ok("assigned: " + task.name()); },
            taskAsyncHandler(args -> new BreedTask(requiredEntityType(args, "entity_type"), optionalInt(args, "pairs", 1))));

        register("attack_entity", "Attack a nearby entity by type", objectSchema()
                .property("entity_type", stringSchema("entity type, for example minecraft:cow"))
                .required("entity_type")
                .build(),
            (bot, args) -> {
                String entityType = requiredString(args, "entity_type");
                Identifier id = Identifier.of(entityType);
                CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN, "tool_attack_entity");
                Optional<Entity> target = bot.getServerWorld()
                        .getOtherEntities(bot, bot.getBoundingBox().expand(4.5D),
                                entity -> Registries.ENTITY_TYPE.getId(entity.getType()).equals(id)
                                        && ObservableWorldQuery.canObserveEntity(bot, entity))
                        .stream()
                        .min(Comparator.comparingDouble(bot::distanceTo));
                if (target.isEmpty()) return fail("no_nearby_entity: " + entityType);
                return result(InteractAction.attackEntity(bot, target.get()));
            },
            (bot, args) -> {
                String entityType = requiredString(args, "entity_type");
                Identifier id = Identifier.of(entityType);
                CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN, "tool_attack_entity");
                Optional<Entity> target = bot.getServerWorld()
                        .getOtherEntities(bot, bot.getBoundingBox().expand(4.5D),
                                entity -> Registries.ENTITY_TYPE.getId(entity.getType()).equals(id)
                                        && ObservableWorldQuery.canObserveEntity(bot, entity))
                        .stream()
                        .min(Comparator.comparingDouble(bot::distanceTo));
                if (target.isEmpty()) return CompletableFuture.completedFuture(fail("no_nearby_entity: " + entityType));
                io.github.zoyluo.aibot.action.ActionResult r = InteractAction.attackEntity(bot, target.get());
                return CompletableFuture.completedFuture(r.isSuccess() ? ok("attacked") : fail(r.reason()));
            }, ToolDefinition.Group.LOW_LEVEL);

        register("behavior_control", "Control this bot's behavior decisions. action (required): "
                + "stop = cancel the current mission but keep explicitly queued missions (use immediately before a replacement goal); "
                + "pause = pause the current mission without deleting it or its queue (safety actions may still run); "
                + "resume = resume a mission paused via pause, or continue work that was interrupted by danger or death"
                + " (interrupted work keeps its progress; danger-interrupted work also auto-resumes once the danger passes); "
                + "cancel_all = cancel the current mission and every queued mission; "
                + "set_policy = configure the autonomous danger-response policy (persisted to the world save; takes effect immediately); "
                + "get_policy = show the effective danger-response policy with the source of each value (policy override vs server config/default). "
                + "set_policy fields: mode = auto (default heuristic: fight when winnable, otherwise evade or shelter at night), "
                + "fight (engage whenever armed, the target is not a creeper, and hp is above retreat_hp; ignores the max_enemies cap; falls back to evade/shelter), "
                + "flee (never fight, only evade or shelter), off (disable all autonomous responses to monsters so the current task keeps running). "
                + "retreat_hp (1-20): hp at or below which combat is refused or retreated from. "
                + "max_enemies (0-20): max nearby hostiles the bot will engage in auto mode; 0 means never fight. "
                + "keep_survival (default true): false disables survival tripwires (drowning/on-fire/low-hp task aborts), the lava-escape task and emergency shelter — "
                + "the basic stuck-in-lava/drowning movement rescue and death respawn always stay on, but the bot may take damage or die. "
                + "mob_reactions: object mapping entity ids to the reaction when that monster is encountered, "
                + "e.g. {\"minecraft:creeper\": \"flee\", \"minecraft:zombie\": \"fight\", \"minecraft:enderman\": \"ignore\"}. "
                + "Reaction values: fight = engage this monster whenever armed and hp is above retreat_hp "
                + "(overrides mode, ignores the max_enemies cap and the creeper melee refusal — you asked for it); "
                + "flee = never fight this monster, only evade or shelter; "
                + "ignore = never autonomously react to this monster at all, the current task keeps running; "
                + "auto = remove the rule for this monster (falls back to mode). "
                + "reset_mob_reactions = true clears all per-monster rules. "
                + "Per-monster rules override mode for that monster, including mode=off. "
                + "Omitted fields keep their current values; action=set_policy with only mode=auto resets the base policy to server defaults.", objectSchema()
                .property("action", stringSchema("stop, pause, resume, cancel_all, set_policy, or get_policy"))
                .property("mode", stringSchema("auto, fight, flee, or off"))
                .property("retreat_hp", integerSchema("refuse or retreat from combat at or below this hp", 1, 20))
                .property("max_enemies", integerSchema("max hostiles to engage in auto mode; 0 never fights", 0, 20))
                .property("keep_survival", booleanSchema("keep survival tripwires, lava-escape task and emergency shelter"))
                .property("mob_reactions", mobReactionsSchema())
                .property("reset_mob_reactions", booleanSchema("clear all per-monster reaction rules"))
                .required("action")
                .build(), ToolRegistry::behaviorControl);

        register("post_job", "Post a shared job to the multi-bot task board. Idle bots whose role matches the job role can claim and execute it.", objectSchema()
                .property("kind", stringSchema("job kind, for example mine, build, craft, smelt, move, eat, or light_area"))
                .property("role", stringSchema("bot role that should claim it, for example miner or builder; blank means any role"))
                .property("params", objectSchema().build())
                .required("kind")
                .required("params")
                .build(), ToolDefinition.Group.COORDINATION, (bot, args) -> {
            Optional<UUID> ownerUuid = AIPlayerManager.INSTANCE.ownerOf(bot);
            if (ownerUuid.isEmpty()) {
                return fail("coordination_requires_owned_bot");
            }
            UUID id = TaskBoard.INSTANCE.postForOwner(ownerUuid.get(), requiredString(args, "kind"),
                    paramsObject(args, "params"), optionalString(args, "role", ""));
            io.github.zoyluo.aibot.persist.BotPersistence.INSTANCE.markDirty(bot.getServer());
            return ok("job_posted: " + id);
        });

        register("list_jobs", "List shared jobs on the multi-bot task board", objectSchema().build(), ToolDefinition.Group.COORDINATION, (bot, args) -> {
            Optional<UUID> ownerUuid = AIPlayerManager.INSTANCE.ownerOf(bot);
            if (ownerUuid.isEmpty()) {
                return fail("coordination_requires_owned_bot");
            }
            List<Job> jobs = TaskBoard.INSTANCE.snapshotForOwner(ownerUuid.get());
            if (jobs.isEmpty()) {
                return ok("[]");
            }
            StringBuilder builder = new StringBuilder("[");
            for (int index = 0; index < jobs.size(); index++) {
                Job job = jobs.get(index);
                if (index > 0) {
                    builder.append(", ");
                }
                builder.append("{id=").append(job.id())
                        .append(", kind=").append(job.kind())
                        .append(", role=").append(job.role())
                        .append(", status=").append(job.status())
                        .append(", reason=").append(job.failureReason())
                        .append("}");
            }
            builder.append("]");
            return ok(builder.toString());
        });

        register("tell_bot", "Send a message from this bot to another bot's brain, reusing the normal @bot chat pathway.", objectSchema()
                .property("target", stringSchema("target bot name"))
                .property("message", stringSchema("message text"))
                .required("target")
                .required("message")
                .build(), ToolDefinition.Group.COORDINATION, (bot, args) -> {
            String targetName = requiredString(args, "target");
            var target = AIPlayerManager.INSTANCE.getByName(targetName);
            if (target.isEmpty()) {
                return fail("target_unavailable");
            }
            if (!BotAuthorizationGate.INSTANCE.authorizeBot(
                    bot, target.get(), BotAuthorizationPolicy.Operation.COMMAND, "tool:tell_bot")) {
                return fail("target_unavailable");
            }
            boolean queued = BrainCoordinator.INSTANCE.handleMessage(target.get(), bot.getGameProfile().getName(), requiredString(args, "message"));
            return queued ? ok("message_sent") : fail("target_busy");
        });

        register("remember", "Store a persistent per-bot fact by key. Use for user preferences, named facts, or long-lived notes.", objectSchema()
                .property("key", stringSchema("memory key"))
                .property("value", stringSchema("memory value"))
                .required("key")
                .required("value")
                .build(), ToolDefinition.Group.MEMORY, (bot, args) -> {
            BotMemoryStore.INSTANCE.of(bot.getUuid()).remember(requiredString(args, "key"), requiredString(args, "value"));
            return ok("remembered");
        });

        register("recall", "Recall a persistent fact by key", objectSchema()
                .property("key", stringSchema("memory key"))
                .required("key")
                .build(), ToolDefinition.Group.MEMORY, (bot, args) -> BotMemoryStore.INSTANCE.of(bot.getUuid())
                .recall(requiredString(args, "key"))
                .map(ToolRegistry::ok)
                .orElseGet(() -> fail("missing_memory: " + requiredString(args, "key"))));

        register("forget", "Delete a persistent fact by key", objectSchema()
                .property("key", stringSchema("memory key"))
                .required("key")
                .build(), ToolDefinition.Group.MEMORY, (bot, args) -> {
            boolean removed = BotMemoryStore.INSTANCE.of(bot.getUuid()).forget(requiredString(args, "key"));
            return ok("forgotten: " + removed);
        });

        register("mark_place", "Remember the bot's current block position as a named place", objectSchema()
                .property("name", stringSchema("place name, for example home"))
                .required("name")
                .build(), ToolDefinition.Group.MEMORY, (bot, args) -> {
            BotMemoryStore.INSTANCE.of(bot.getUuid()).markPlace(requiredString(args, "name"), bot.getServerWorld(), bot.getBlockPos());
            return ok("marked_place: " + requiredString(args, "name") + " at " + bot.getBlockPos().toShortString());
        });

        register("goto_place", "Assign a move task to a remembered named place in the current dimension", objectSchema()
                .property("name", stringSchema("place name"))
                .required("name")
                .build(),
            (bot, args) -> {
                Optional<BotMemory.Place> place = BotMemoryStore.INSTANCE.of(bot.getUuid()).place(requiredString(args, "name"));
                if (place.isEmpty()) return fail("unknown_place: " + requiredString(args, "name"));
                if (!bot.getServerWorld().getRegistryKey().getValue().toString().equals(place.get().dimension())) return fail("place_in_other_dimension: " + place.get().dimension());
                Task task = new MoveTask(bot, place.get().pos()); assignLlm(bot, task); return ok("assigned: " + task.name());
            },
            (bot, args) -> {
                Optional<BotMemory.Place> place = BotMemoryStore.INSTANCE.of(bot.getUuid()).place(requiredString(args, "name"));
                if (place.isEmpty()) return CompletableFuture.completedFuture(fail("unknown_place: " + requiredString(args, "name")));
                if (!bot.getServerWorld().getRegistryKey().getValue().toString().equals(place.get().dimension())) return CompletableFuture.completedFuture(fail("place_in_other_dimension: " + place.get().dimension()));
                return taskAsyncHandler(ignored -> new MoveTask(bot, place.get().pos())).prepare(bot, args);
            }, ToolDefinition.Group.MEMORY);

        register("resume_mining", "Continue mining where the last mining session left off.", objectSchema()
                .property("count", integerSchema("how many more ore blocks to mine, default 8"))
                .build(),
            (bot, args) -> {
                var mem = BotMemoryStore.INSTANCE.of(bot.getUuid());
                var face = mem.place("mine_face");
                if (face.isEmpty()) return fail("no_mine_face");
                if (!bot.getServerWorld().getRegistryKey().getValue().toString().equals(face.get().dimension())) return fail("mine_face_in_other_dimension");
                java.util.Set<net.minecraft.block.Block> ores = resumeOres(mem);
                Task back = new MoveTask(bot, face.get().pos()); assignLlm(bot, back);
                GoalExecutor.INSTANCE.submit(bot, new Goal.MineOre(ores.isEmpty() ? java.util.Set.of(net.minecraft.block.Blocks.IRON_ORE) : ores, optionalInt(args, "count", 8)));
                return ok("resuming at " + face.get().pos().toShortString());
            },
            (bot, args) -> {
                var mem = BotMemoryStore.INSTANCE.of(bot.getUuid());
                var face = mem.place("mine_face");
                if (face.isEmpty()) return CompletableFuture.completedFuture(fail("no_mine_face"));
                if (!bot.getServerWorld().getRegistryKey().getValue().toString().equals(face.get().dimension())) return CompletableFuture.completedFuture(fail("mine_face_in_other_dimension"));
                java.util.Set<net.minecraft.block.Block> ores = resumeOres(mem);
                // 先走回作业面，走完后 goal 会自动接续
                Task back = new MoveTask(bot, face.get().pos()); assignLlm(bot, back);
                GoalExecutor.INSTANCE.submit(bot, new Goal.MineOre(ores.isEmpty() ? java.util.Set.of(net.minecraft.block.Blocks.IRON_ORE) : ores, optionalInt(args, "count", 8)));
                // 等待 goal 完成（move 完成后 goal 自动出队执行）
                CompletableFuture<ToolDefinition.ToolResult> future = new CompletableFuture<>();
                GoalExecutor.INSTANCE.whenComplete(bot).thenAccept(result ->
                    future.complete(result.status() == io.github.zoyluo.aibot.goal.GoalResult.Status.COMPLETED ? ok("resume_completed") : fail(result.reason())));
                return future;
            });

        register("mine_and_stockpile", "Mine ores then deposit the yield into a chest near the remembered base.", objectSchema()
                .property("ore", stringSchema("ore block id or raw item, e.g. minecraft:iron_ore"))
                .property("count", integerSchema("how many ore blocks to mine"))
                .required("ore")
                .build(),
            (bot, args) -> {
                var ores = oreTargetsFrom(requiredString(args, "ore"));
                int count = optionalInt(args, "count", 1);
                boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.MineOre(ores, count));
                if (!started) return fail("goal_plan_failed");
                Item yield = io.github.zoyluo.aibot.action.HarvestCore.expectedDropsFor(ores).stream().findFirst().orElse(null);
                if (yield != null) GoalExecutor.INSTANCE.submit(bot, new Goal.Stockpile(yield, count));
                return ok("goal_assigned: mine_ore + stockpile queued");
            },
            goalAsyncHandler((bot, args) -> {
                var ores = oreTargetsFrom(requiredString(args, "ore"));
                int count = optionalInt(args, "count", 1);
                Item yield = io.github.zoyluo.aibot.action.HarvestCore.expectedDropsFor(ores).stream().findFirst().orElse(null);
                if (yield != null) GoalExecutor.INSTANCE.submit(bot, new Goal.Stockpile(yield, count));
                return new Goal.MineOre(ores, count);
            }));

        register("recover_drops", "Run back to the most recent death location and pick up dropped items before they despawn (5 min)", objectSchema()
                .build(),
            (bot, args) -> {
                var deaths = io.github.zoyluo.aibot.memory.EpisodeLog.INSTANCE.recentOfType(bot.getUuid(), io.github.zoyluo.aibot.memory.EpisodeLog.Type.DEATH, 1);
                if (deaths.isEmpty()) return fail("no_recent_death");
                var death = deaths.get(0);
                Task task = new io.github.zoyluo.aibot.task.RecoverDropsTask(death.pos(), death.gameTick()); assignLlm(bot, task); return ok("assigned: recover_drops -> " + death.pos().toShortString());
            },
            (bot, args) -> {
                var deaths = io.github.zoyluo.aibot.memory.EpisodeLog.INSTANCE.recentOfType(bot.getUuid(), io.github.zoyluo.aibot.memory.EpisodeLog.Type.DEATH, 1);
                if (deaths.isEmpty()) return CompletableFuture.completedFuture(fail("no_recent_death"));
                var death = deaths.get(0);
                return taskAsyncHandler(ignored -> new io.github.zoyluo.aibot.task.RecoverDropsTask(death.pos(), death.gameTick())).prepare(bot, args);
            }, ToolDefinition.Group.MEMORY);

        register("set_goal", "Set a persistent long-term goal with ordered steps. Steps should be an array of short strings.", objectSchema()
                .property("title", stringSchema("goal title"))
                .property("steps", arrayOfStringsSchema("ordered goal steps"))
                .required("title")
                .required("steps")
                .build(), ToolDefinition.Group.MEMORY, (bot, args) -> {
            List<String> steps = stringArray(args, "steps");
            BotMemoryStore.INSTANCE.of(bot.getUuid()).setGoal(requiredString(args, "title"), steps);
            return ok(BotMemoryStore.INSTANCE.of(bot.getUuid()).goalStatus(""));
        });

        register("advance_goal", "Advance the current persistent long-term goal by one step", objectSchema()
                .property("result", stringSchema("short result of the completed step"))
                .build(), ToolDefinition.Group.MEMORY, (bot, args) -> ok(BotMemoryStore.INSTANCE.of(bot.getUuid()).advanceGoal(optionalString(args, "result", ""))));

        register("goal_status", "Get the current persistent long-term goal status", objectSchema().build(), ToolDefinition.Group.MEMORY, (bot, args) ->
                ok(BotMemoryStore.INSTANCE.of(bot.getUuid()).goalStatus("")));

        register("todo_add", "Add an item to your self-managed task queue (persisted with the bot; open items are always visible in your context). "
                + "New items start as pending. Use this queue to plan multi-step work and track what is left; you own its state, the system never mutates it for you.", objectSchema()
                .property("title", stringSchema("short description of the task"))
                .property("after_id", integerSchema("insert after this id; 0 = at the front; omit = append at the end", 0, Integer.MAX_VALUE))
                .required("title")
                .build(), ToolDefinition.Group.MEMORY, (bot, args) ->
                ok(BotMemoryStore.INSTANCE.of(bot.getUuid()).addTodo(requiredString(args, "title"), optionalInt(args, "after_id", -1))));

        register("todo_list", "List your task queue. Open items (pending/doing) are always returned; set include_closed to also see done/cancelled items.",
                objectSchema()
                        .property("include_closed", booleanSchema("include done/cancelled items"))
                        .build(), ToolDefinition.Group.MEMORY, (bot, args) -> {
            List<BotMemory.TodoItem> items = BotMemoryStore.INSTANCE.of(bot.getUuid()).todos();
            JsonObject root = new JsonObject();
            com.google.gson.JsonArray open = new com.google.gson.JsonArray();
            com.google.gson.JsonArray closed = new com.google.gson.JsonArray();
            int pending = 0;
            int doing = 0;
            int done = 0;
            int cancelled = 0;
            for (BotMemory.TodoItem item : items) {
                switch (item.status()) {
                    case PENDING -> pending++;
                    case DOING -> doing++;
                    case DONE -> done++;
                    case CANCELLED -> cancelled++;
                }
                JsonObject json = new JsonObject();
                json.addProperty("id", item.id());
                json.addProperty("status", item.status().label());
                json.addProperty("title", item.title());
                (item.status().open() ? open : closed).add(json);
            }
            root.add("open", open);
            JsonObject counts = new JsonObject();
            counts.addProperty("pending", pending);
            counts.addProperty("doing", doing);
            counts.addProperty("done", done);
            counts.addProperty("cancelled", cancelled);
            root.add("counts", counts);
            if (optionalBoolean(args, "include_closed", false)) {
                root.add("closed", closed);
            }
            return ok(root.toString());
        });

        register("todo_update", "Change the status and/or title of a task-queue item, or move it. "
                + "status: pending|doing|done|cancelled. Omitted fields keep their current values. "
                + "after_id: move the item right after that id, 0 = move to the front; omit = keep position. "
                + "Mark items done when you actually finished them and cancelled when you give up — the queue is only useful if you keep it honest.", objectSchema()
                .property("id", integerSchema("id of the item (the #N number)", 1, Integer.MAX_VALUE))
                .property("status", stringSchema("pending, doing, done, or cancelled"))
                .property("title", stringSchema("new title, replaces the old one"))
                .property("after_id", integerSchema("move right after this id; 0 = front; omit = keep position", 0, Integer.MAX_VALUE))
                .required("id")
                .build(), ToolDefinition.Group.MEMORY, (bot, args) -> {
            BotMemory memory = BotMemoryStore.INSTANCE.of(bot.getUuid());
            String rawStatus = optionalString(args, "status", "");
            BotMemory.TodoStatus status = rawStatus.isEmpty() ? null : BotMemory.TodoStatus.parse(rawStatus);
            String title = args.has("title") && args.get("title").isJsonPrimitive() ? args.get("title").getAsString() : null;
            boolean move = args.has("after_id") && args.get("after_id").isJsonPrimitive();
            return ok("updated: " + memory.updateTodo(optionalInt(args, "id", 0), status, title, optionalInt(args, "after_id", 0), move));
        });

        register("todo_remove", "Delete an item from the task queue entirely. Prefer todo_update with status=cancelled when the record should stay.", objectSchema()
                .property("id", integerSchema("id of the item (the #N number)", 1, Integer.MAX_VALUE))
                .required("id")
                .build(), ToolDefinition.Group.MEMORY, (bot, args) ->
                ok(BotMemoryStore.INSTANCE.of(bot.getUuid()).removeTodo(optionalInt(args, "id", 0))));

        register("todo_clear", "Prune done/cancelled items from the task queue in bulk. Without status both kinds are removed; cannot remove pending/doing items.", objectSchema()
                .property("status", stringSchema("done or cancelled; omit = both"))
                .build(), ToolDefinition.Group.MEMORY, (bot, args) -> {
            String rawStatus = optionalString(args, "status", "");
            BotMemory.TodoStatus status = rawStatus.isEmpty() ? null : BotMemory.TodoStatus.parse(rawStatus);
            return ok(BotMemoryStore.INSTANCE.of(bot.getUuid()).clearTodos(status));
        });

        register("assign_task", "Start a high-level deterministic task for the bot. Prefer dedicated tools (craft/eat/smelt/mine_ore/achieve_goal) when available. Supersedes any current task.", objectSchema()
                .property("task_type", stringSchema("move, gather, forage, irrigate, milk_cow, raid_crops, attack, mine, strip_mine, mine_vein, build, sleep, light_area, farm, harvest, fish, trade, breed, follow, hold, guard, deposit, stockpile, or withdraw"))
                .property("params", objectSchema().build())
                .required("task_type")
                .required("params")
                .build(),
            (bot, args) -> {
                String taskType = requiredString(args, "task_type");
                JsonObject params = args.getAsJsonObject("params");
                if ("mine_ore".equals(taskType)) {
                    if (!AIBotConfig.get().goal().autoToolFillEnabled()) { Task t = new OreDigTask(oreTargetsFrom(requiredString(params, "ore")), optionalInt(params, "count", 1)); assignLlm(bot, t); return ok("assigned: " + t.name()); }
                    boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.MineOre(oreTargetsFrom(requiredString(params, "ore")), optionalInt(params, "count", 1)));
                    return started ? ok("goal_assigned: mine_ore") : fail("goal_plan_failed");
                }
                if ("mine".equals(taskType) && OreScan.isOreBlock(blockWithAlias(params, "block", "block_type"))) {
                    Block block = blockWithAlias(params, "block", "block_type");
                    int count = optionalInt(params, "count", 1);
                    if (!AIBotConfig.get().goal().autoToolFillEnabled()) { Task t = new OreDigTask(OreScan.oreFamily(block), count); assignLlm(bot, t); return ok("assigned: " + t.name()); }
                    boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.MineOre(OreScan.oreFamily(block), count));
                    return started ? ok("goal_assigned: mine_ore") : fail("goal_plan_failed");
                }
                Task task = createTask(bot, taskType, params); assignLlm(bot, task); return ok("assigned: " + task.name());
            },
            (bot, args) -> {
                String taskType = requiredString(args, "task_type");
                JsonObject params = args.getAsJsonObject("params");
                if ("mine_ore".equals(taskType) || ("mine".equals(taskType) && OreScan.isOreBlock(blockWithAlias(params, "block", "block_type")))) {
                    // Goal 路径：等待 GoalExecutor 完成
                    CompletableFuture<ToolDefinition.ToolResult> future = new CompletableFuture<>();
                    CompletableFuture<io.github.zoyluo.aibot.goal.GoalResult> goalFuture = GoalExecutor.INSTANCE.whenComplete(bot);
                    Goal goal;
                    if ("mine_ore".equals(taskType)) {
                        goal = new Goal.MineOre(oreTargetsFrom(requiredString(params, "ore")), optionalInt(params, "count", 1));
                    } else {
                        goal = new Goal.MineOre(OreScan.oreFamily(blockWithAlias(params, "block", "block_type")), optionalInt(params, "count", 1));
                    }
                    GoalExecutor.INSTANCE.submit(bot, goal);
                    goalFuture.thenAccept(result -> future.complete(result.status() == io.github.zoyluo.aibot.goal.GoalResult.Status.COMPLETED ? ok("goal_completed") : fail(result.reason())));
                    return future;
                }
                // Task 路径：等待 TaskManager 完成
                return taskAsyncHandler(ignored -> createTask(bot, taskType, params)).prepare(bot, args);
            });

        register("get_task_status", "Get the current task status", objectSchema().build(), (bot, args) -> {
            // 优化3:有确定性目标在跑时不喂详细状态——断掉大脑反复轮询的正反馈(实测 get_task_status×19 耗尽轮次);
            // 目标完成/失败会主动唤醒大脑,期间无需查询。
            if (io.github.zoyluo.aibot.goal.GoalExecutor.INSTANCE.hasActivePlan(bot)) {
                return ok("{\"state\":\"goal_running\",\"note\":\"目标执行中,完成或失败时会通知你,期间不要重复查询\"}");
            }
            TaskStatus status = TaskManager.INSTANCE.status(bot);
            return ok("{\"name\":\"" + escape(status.name())
                    + "\",\"state\":\"" + status.state()
                    + "\",\"progress\":" + status.progress()
                    + ",\"description\":\"" + escape(status.description()) + "\"}");
        });

    }

    private static Task createTask(io.github.zoyluo.aibot.entity.AIPlayerEntity bot, String taskType, JsonObject params) {
        if (params == null) {
            throw new IllegalArgumentException("missing_or_bad_arg: params");
        }
        return switch (taskType) {
            case "move" -> new MoveTask(bot, new BlockPos(requiredInt(params, "x"), requiredInt(params, "y"), requiredInt(params, "z")));
            case "forage" -> new GatherQuotaTask(net.minecraft.item.Items.SWEET_BERRIES, optionalInt(params, "count", 4));
            case "attack" -> new CombatTask(
                    requiredEntityType(params, "entity_type"),
                    optionalInt(params, "count", 1),
                    DangerPolicyStore.INSTANCE.resolve(bot).retreatHp());
            case "mine" -> {
                Block block = blockWithAlias(params, "block", "block_type");
                int count = optionalInt(params, "count", 1);
                yield OreScan.isOreBlock(block) ? new OreDigTask(OreScan.oreFamily(block), count) : new MineTask(block, count);
            }
            case "mine_ore" -> new OreDigTask(oreTargetsFrom(requiredString(params, "ore")), optionalInt(params, "count", 1));
            case "gather" -> new GatherQuotaTask(requiredItem(params, "item"), optionalInt(params, "count", 1));
            case "irrigate" -> new io.github.zoyluo.aibot.task.IrrigateTask(
                    bot.getBlockPos().offset(bot.getHorizontalFacing(), 2).down()); // 身前 2 格 floor 层挖 2×2 无限水源
            case "milk_cow" -> new io.github.zoyluo.aibot.task.MilkCowTask(optionalInt(params, "count", 1)); // 挤 count 桶牛奶(需空桶)
            case "raid_crops" -> new io.github.zoyluo.aibot.task.RaidCropsTask(optionalInt(params, "count", 8)); // 收割附近(村庄/野外)成熟作物
            case "fish" -> new FishTask(optionalInt(params, "max_catches", 1), optionalInt(params, "max_ticks", 6000));
            case "trade" -> new TradeTask(optionalItem(params, "target_item"), optionalInt(params, "max_distance", 16));
            case "stockpile" -> new StockpileTask(optionalBoolean(params, "all_except_tools", true));
            case "sleep" -> new SleepTask();
            case "light_area" -> new LightAreaTask(optionalInt(params, "radius", 8), optionalInt(params, "max_torches", 8));
            case "follow" -> new FollowTask(optionalString(params, "player_name", ""));
            case "hold" -> new HoldTask();
            case "guard" -> {
                String playerName = optionalString(params, "player_name", "");
                BlockPos point = optionalBlockPos(params, "x", "y", "z");
                yield playerName.isBlank() ? GuardTask.point(point) : GuardTask.player(playerName);
            }
            case "farm" -> {
                FarmAction.CropSpec spec = FarmAction.cropSpec(requiredString(params, "crop"));
                yield new FarmTask(blockPos(params), optionalInt(params, "radius", 3), spec.seed(), spec.crop(),
                        optionalBoolean(params, "keep_tending", false), false);
            }
            case "harvest" -> {
                FarmAction.CropSpec spec = FarmAction.cropSpec(requiredString(params, "crop"));
                yield new FarmTask(blockPos(params), optionalInt(params, "radius", 3), spec.seed(), spec.crop(), false, true);
            }
            case "breed" -> new BreedTask(requiredEntityType(params, "entity_type"), optionalInt(params, "pairs", 1));
            case "strip_mine" -> new StripMineTask(
                    optionalDirection(params, "direction", Direction.NORTH),
                    optionalInt(params, "length", 16),
                    optionalInt(params, "spacing", 4),
                    optionalBlockPos(params, "depot_x", "depot_y", "depot_z"),
                    optionalBlocksCsv(params, "target_ores"));
            case "mine_vein" -> StripMineTask.mineNearbyVein(optionalBlocksCsv(params, "target_ores"));
            case "deposit" -> ContainerTask.deposit(
                    optionalBlockPos(params, "chest_x", "chest_y", "chest_z"),
                    optionalItem(params, "item"),
                    optionalInt(params, "count", 0),
                    optionalBoolean(params, "all_except_tools", false));
            case "withdraw" -> ContainerTask.withdraw(
                    optionalBlockPos(params, "chest_x", "chest_y", "chest_z"),
                    requiredItem(params, "item"),
                    optionalInt(params, "count", 1));
            case "build" -> {
                try {
                    boolean autoSite = optionalBoolean(params, "auto_site", false);
                    boolean flatten = optionalBoolean(params, "flatten", false);
                    BlockPos anchor = autoSite && !hasBlockPos(params, "anchor_x", "anchor_y", "anchor_z") && !hasBlockPos(params, "x", "y", "z")
                            ? null
                            : new BlockPos(
                                    intWithAlias(params, "anchor_x", "x"),
                                    intWithAlias(params, "anchor_y", "y"),
                                    intWithAlias(params, "anchor_z", "z"));
                    yield new BuildTask(
                            BlueprintLoader.load(requiredString(params, "blueprint")),
                            anchor,
                            autoSite,
                            flatten);
                } catch (java.io.IOException exception) {
                    throw new IllegalArgumentException(exception.getMessage(), exception);
                }
            }
            default -> throw new IllegalArgumentException("unknown_task_type: " + taskType);
        };
    }

    private void register(String name, String description, JsonObject schema, ToolDefinition.Handler handler) {
        tools.put(name, new ToolDefinition(name, description, schema, handler));
    }

    private void register(String name, String description, JsonObject schema, ToolDefinition.Group group, ToolDefinition.Handler handler) {
        tools.put(name, new ToolDefinition(name, description, schema, handler, group));
    }

    private void register(String name, String description, JsonObject schema,
                          ToolDefinition.Handler syncHandler, ToolDefinition.AsyncHandler asyncHandler) {
        tools.put(name, new ToolDefinition(name, description, schema, syncHandler, asyncHandler, ToolDefinition.Group.CORE));
    }

    private void register(String name, String description, JsonObject schema,
                          ToolDefinition.Handler syncHandler, ToolDefinition.AsyncHandler asyncHandler,
                          ToolDefinition.Group group) {
        tools.put(name, new ToolDefinition(name, description, schema, syncHandler, asyncHandler, group));
    }

    private static String craftPlanJson(CraftingHelper.CraftPlan plan) {
        JsonObject root = new JsonObject();
        root.addProperty("feasible", plan.success());
        root.addProperty("target", Registries.ITEM.getId(plan.target()).toString());
        root.addProperty("count", plan.targetCount());
        root.addProperty("needs_crafting_table", plan.needsCraftingTable());

        com.google.gson.JsonArray steps = new com.google.gson.JsonArray();
        for (CraftingHelper.CraftStep step : plan.steps()) {
            JsonObject json = new JsonObject();
            json.addProperty("output", Registries.ITEM.getId(step.recipe().output()).toString());
            json.addProperty("crafts", step.crafts());
            json.addProperty("output_count", step.outputCount());
            json.addProperty("needs_crafting_table", step.recipe().needsCraftingTable());
            com.google.gson.JsonArray ingredients = new com.google.gson.JsonArray();
            for (io.github.zoyluo.aibot.craft.RecipeRegistry.Ingredient ingredient : step.recipe().ingredients()) {
                JsonObject ingredientJson = new JsonObject();
                ingredientJson.addProperty("count", ingredient.count() * step.crafts());
                com.google.gson.JsonArray anyOf = new com.google.gson.JsonArray();
                for (Item item : ingredient.anyOf()) {
                    anyOf.add(Registries.ITEM.getId(item).toString());
                }
                ingredientJson.add("any_of", anyOf);
                ingredients.add(ingredientJson);
            }
            json.add("ingredients", ingredients);
            steps.add(json);
        }
        root.add("steps", steps);

        com.google.gson.JsonArray missing = new com.google.gson.JsonArray();
        for (CraftingHelper.Missing item : plan.missing()) {
            JsonObject json = new JsonObject();
            json.addProperty("item", Registries.ITEM.getId(item.item()).toString());
            json.addProperty("count", item.count());
            json.addProperty("source", AcquisitionHints.source(item.item()));
            missing.add(json);
        }
        root.add("missing", missing);
        return root.toString();
    }

    private static ToolDefinition.ToolResult result(io.github.zoyluo.aibot.action.ActionResult actionResult) {
        if (actionResult.isSuccess() || actionResult.isInProgress()) {
            return ok(actionResult.status().name().toLowerCase());
        }
        return fail(actionResult.reason());
    }

    private static ToolDefinition.ToolResult ok(String message) {
        return ToolDefinition.ToolResult.success(message);
    }

    // behavior_control 统一入口:任务控制(stop/pause/resume/cancel_all)与危险应对策略(set_policy/get_policy)。
    private static ToolDefinition.ToolResult behaviorControl(AIPlayerEntity bot, JsonObject args) {
        String action = requiredString(args, "action").toLowerCase(java.util.Locale.ROOT);
        return switch (action) {
            case "stop" -> {
                IntentControlTransaction.Outcome outcome = IntentController.INSTANCE.cancelCurrent(
                        bot, IntentController.ControlOrigin.LLM_TOOL, "tool_behavior_control_stop");
                yield ok(outcome.changed() ? "cancelled_current" : "already_idle");
            }
            case "pause" -> {
                boolean changed = IntentController.INSTANCE.pause(
                        bot, IntentController.ControlOrigin.LLM_TOOL, "tool_behavior_control_pause");
                yield ok(changed ? "mission_paused" : "already_paused");
            }
            case "resume" -> resumeWork(bot);
            case "cancel_all" -> {
                IntentControlTransaction.Outcome outcome = IntentController.INSTANCE.cancelAll(
                        bot, IntentController.ControlOrigin.LLM_TOOL, "tool_behavior_control_cancel_all");
                yield ok(outcome.changed() ? "cancelled_all" : "already_idle");
            }
            case "get_policy" -> ok(DangerPolicyStore.INSTANCE.describe(bot.getUuid()));
            case "set_policy" -> setDangerPolicy(bot, args);
            default -> throw new IllegalArgumentException(
                    "unknown_action: " + action + " (want stop|pause|resume|cancel_all|set_policy|get_policy)");
        };
    }

    /**
     * 继续被打断的工作。两条路径:
     * ① 用户暂停(userPaused 锁)→ 走 IntentController 解锁并弹栈;
     * ② 打断暂停(威胁/死亡压栈)→ 无活跃任务时显式弹栈继续;有活跃任务(多半是危险应对)时
     *    不抢——威胁暂停的工作本来就会自动接续,死亡打断的工作必须等危险应对结束后再 resume。
     */
    private static ToolDefinition.ToolResult resumeWork(AIPlayerEntity bot) {
        if (TaskManager.INSTANCE.isUserPaused(bot)) {
            boolean changed = IntentController.INSTANCE.resume(
                    bot, IntentController.ControlOrigin.LLM_TOOL, "tool_behavior_control_resume");
            return ok(changed ? "mission_resumed" : "not_paused");
        }
        if (TaskManager.INSTANCE.getActive(bot).isPresent()) {
            return fail("cannot_resume_now: 有任务正在执行(若是危险应对,被打断的工作会在其结束后自动继续;"
                    + "死亡打断的工作请在它结束后再调 resume,或现在用 stop 放弃被打断的工作)");
        }
        if (TaskManager.INSTANCE.hasPaused(bot)) {
            return TaskManager.INSTANCE.resumeExplicit(bot)
                    ? ok("mission_resumed: 被打断的工作已继续")
                    : ok("not_paused");
        }
        return ok("not_paused");
    }

    /** 任务类工具的统一结果映射:PAUSED(被打断)→ paused 状态 + 继续/放弃指引,不再是笼统的 failed。 */
    private static ToolDefinition.ToolResult fromTaskStatus(TaskStatus status) {
        if (status.state() == TaskState.COMPLETED) {
            return ok(status.description());
        }
        if (status.state() == TaskState.PAUSED) {
            return ToolDefinition.ToolResult.paused(interruptGuidance(status));
        }
        return fail(status.failureReason() != null && !status.failureReason().isBlank()
                ? status.failureReason() : "failed");
    }

    private static String interruptGuidance(TaskStatus status) {
        String reason = status.failureReason() == null ? "" : status.failureReason();
        if (reason.contains("bot_died")) {
            return "work_interrupted: bot died and respawned. \"" + status.name()
                    + "\" is paused with its progress kept. Call behavior_control action=resume to continue it"
                    + " (after any ongoing danger response finishes), or action=stop to abandon it.";
        }
        if (reason.startsWith("user_pause")) {
            return "work_paused_by_user: \"" + status.name()
                    + "\" is paused with its progress kept. Call behavior_control action=resume to continue it,"
                    + " or action=stop to abandon it.";
        }
        return "work_interrupted: " + reason + ". \"" + status.name()
                + "\" is paused with its progress kept and will auto-resume after the danger passes."
                + " You may also call behavior_control action=resume to continue it yourself,"
                + " or action=stop to abandon it.";
    }

    private static ToolDefinition.ToolResult setDangerPolicy(AIPlayerEntity bot, JsonObject args) {
        String rawMode = optionalString(args, "mode", "");
        DangerPolicy.Mode mode = rawMode.isEmpty() ? null : DangerPolicy.parseMode(rawMode);
        Integer retreatHp = args.has("retreat_hp") && args.get("retreat_hp").isJsonPrimitive()
                ? DangerPolicy.validateRetreatHp(args.get("retreat_hp").getAsInt()) : null;
        Integer maxEnemies = args.has("max_enemies") && args.get("max_enemies").isJsonPrimitive()
                ? DangerPolicy.validateMaxEnemies(args.get("max_enemies").getAsInt()) : null;
        Boolean keepSurvival = args.has("keep_survival") && args.get("keep_survival").isJsonPrimitive()
                ? args.get("keep_survival").getAsBoolean() : null;
        Map<String, String> mobReactions = mobReactionsDelta(args);
        boolean resetMobReactions = optionalBoolean(args, "reset_mob_reactions", false);
        // mode=auto 且其余全省略 = 显式重置回默认(删条目+存档键);否则增量合并
        DangerPolicy policy = (mode == DangerPolicy.Mode.AUTO && retreatHp == null && maxEnemies == null
                && keepSurvival == null && mobReactions == null && !resetMobReactions)
                ? DangerPolicyStore.INSTANCE.reset(bot.getUuid())
                : DangerPolicyStore.INSTANCE.update(bot.getUuid(), mode, retreatHp, maxEnemies, keepSurvival,
                        mobReactions, resetMobReactions);
        BotLog.danger(bot, "danger_policy_changed",
                "mode", policy.mode(),
                "retreat_hp", policy.retreatHp(),
                "max_enemies", policy.maxEnemies(),
                "keep_survival", policy.keepSurvival(),
                "mob_reactions", policy.mobReactions());
        String description = DangerPolicyStore.INSTANCE.describe(bot.getUuid());
        BrainCoordinator.INSTANCE.sendPanelChat(bot, "system",
                bot.getGameProfile().getName() + " 的危险应对策略已更新: " + description);
        return ok("danger_policy_set: " + description);
    }

    // 解析 mob_reactions 增量:实体 id 必须在注册表存在;反应值 fight/flee/ignore,或 "auto"=删除该怪规则。
    // 未传该字段返回 null(= 不动 per-怪规则)。
    private static Map<String, String> mobReactionsDelta(JsonObject args) {
        if (!args.has("mob_reactions") || !args.get("mob_reactions").isJsonObject()) {
            return null;
        }
        Map<String, String> delta = new LinkedHashMap<>();
        for (Map.Entry<String, com.google.gson.JsonElement> entry : args.getAsJsonObject("mob_reactions").entrySet()) {
            if (!entry.getValue().isJsonPrimitive()) {
                throw new IllegalArgumentException("missing_or_bad_arg: mob_reactions." + entry.getKey());
            }
            Identifier id = Identifier.of(entry.getKey().trim());
            if (Registries.ENTITY_TYPE.getOptionalValue(id).isEmpty()) {
                throw new IllegalArgumentException("unknown_entity_type: " + id);
            }
            String reaction = entry.getValue().getAsString().trim().toLowerCase(java.util.Locale.ROOT);
            if (!DangerPolicy.REACTION_REMOVE.equals(reaction)) {
                DangerPolicy.Reaction.parse(reaction); // 校验,非法值抛 unknown_mob_reaction
            }
            delta.put(id.toString(), reaction);
        }
        return delta;
    }

    private static ToolDefinition.ToolResult fail(String message) {
        return ToolDefinition.ToolResult.failure(message);
    }

    private static void assignLlm(AIPlayerEntity bot, Task task) {
        TaskManager.INSTANCE.assign(bot, task, TaskOrigin.of(TaskOrigin.Kind.LLM_TOOL, "llm_tool"));
    }

    /** 为任务类工具创建异步 handler：启动任务 → 等待完成 → 返回真实结果(被打断时返回 paused + 指引)。 */
    private static ToolDefinition.AsyncHandler taskAsyncHandler(java.util.function.Function<JsonObject, Task> taskFactory) {
        return (bot, args) -> {
            CompletableFuture<ToolDefinition.ToolResult> future = new CompletableFuture<>();
            CompletableFuture<TaskStatus> taskFuture = TaskManager.INSTANCE.whenComplete(bot);
            Task task = taskFactory.apply(args);
            assignLlm(bot, task);
            taskFuture.thenAccept(status -> future.complete(fromTaskStatus(status)));
            return future;
        };
    }

    /** 为动作类工具创建异步 handler：启动 ActionPack 动作 → 等待完成 → 返回真实结果。 */
    private static ToolDefinition.AsyncHandler actionAsyncHandler(java.util.function.BiConsumer<AIPlayerEntity, JsonObject> actionStarter) {
        return (bot, args) -> {
            CompletableFuture<ToolDefinition.ToolResult> future = new CompletableFuture<>();
            CompletableFuture<io.github.zoyluo.aibot.action.ActionResult> actionFuture = bot.getActionPack().whenActionComplete();
            actionStarter.accept(bot, args);
            actionFuture.thenAccept(result ->
                future.complete(result.isSuccess()
                    ? ok(result.status().name().toLowerCase())
                    : fail(result.reason())));
            return future;
        };
    }

    /** 为目标类工具创建异步 handler：提交目标 → 等待完成 → 返回真实结果。 */
    private static ToolDefinition.AsyncHandler goalAsyncHandler(java.util.function.BiFunction<AIPlayerEntity, JsonObject, Goal> goalFactory) {
        return (bot, args) -> {
            CompletableFuture<ToolDefinition.ToolResult> future = new CompletableFuture<>();
            CompletableFuture<io.github.zoyluo.aibot.goal.GoalResult> goalFuture = GoalExecutor.INSTANCE.whenComplete(bot);
            Goal goal = goalFactory.apply(bot, args);
            GoalExecutor.INSTANCE.submit(bot, goal);
            goalFuture.thenAccept(result ->
                future.complete(result.status() == io.github.zoyluo.aibot.goal.GoalResult.Status.COMPLETED
                    ? ok("goal_completed: " + GoalExecutor.INSTANCE.resultSummary(result))
                    : fail(result.reason())));
            return future;
        };
    }

    private static BlockPos blockPos(JsonObject args) {
        return new BlockPos(requiredInt(args, "x"), requiredInt(args, "y"), requiredInt(args, "z"));
    }

    private static int requiredInt(JsonObject args, String name) {
        if (!args.has(name) || !args.get(name).isJsonPrimitive()) {
            throw new IllegalArgumentException("missing_or_bad_arg: " + name);
        }
        return args.get(name).getAsInt();
    }

    private static int intWithAlias(JsonObject args, String primary, String alias) {
        if (args.has(primary) && args.get(primary).isJsonPrimitive()) {
            return args.get(primary).getAsInt();
        }
        if (args.has(alias) && args.get(alias).isJsonPrimitive()) {
            return args.get(alias).getAsInt();
        }
        throw new IllegalArgumentException("missing_or_bad_arg: " + primary);
    }

    private static String requiredString(JsonObject args, String name) {
        if (!args.has(name) || !args.get(name).isJsonPrimitive()) {
            throw new IllegalArgumentException("missing_or_bad_arg: " + name);
        }
        return args.get(name).getAsString();
    }

    private static int optionalInt(JsonObject args, String name, int defaultValue) {
        if (!args.has(name) || !args.get(name).isJsonPrimitive()) {
            return defaultValue;
        }
        return args.get(name).getAsInt();
    }

    private static boolean optionalBoolean(JsonObject args, String name, boolean defaultValue) {
        if (!args.has(name) || !args.get(name).isJsonPrimitive()) {
            return defaultValue;
        }
        return args.get(name).getAsBoolean();
    }

    private static String optionalString(JsonObject args, String name, String defaultValue) {
        if (!args.has(name) || !args.get(name).isJsonPrimitive()) {
            return defaultValue;
        }
        String value = args.get(name).getAsString();
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static List<String> stringArray(JsonObject args, String name) {
        if (!args.has(name) || !args.get(name).isJsonArray()) {
            throw new IllegalArgumentException("missing_or_bad_arg: " + name);
        }
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        for (com.google.gson.JsonElement element : args.getAsJsonArray(name)) {
            if (!element.isJsonPrimitive()) {
                throw new IllegalArgumentException("missing_or_bad_arg: " + name);
            }
            String value = element.getAsString();
            if (value != null && !value.isBlank()) {
                values.add(value.trim());
            }
        }
        if (values.isEmpty()) {
            throw new IllegalArgumentException("missing_or_bad_arg: " + name);
        }
        return values;
    }

    private static Map<String, String> paramsObject(JsonObject args, String name) {
        if (!args.has(name) || !args.get(name).isJsonObject()) {
            return Map.of();
        }
        Map<String, String> params = new LinkedHashMap<>();
        for (Map.Entry<String, com.google.gson.JsonElement> entry : args.getAsJsonObject(name).entrySet()) {
            if (entry.getValue().isJsonPrimitive()) {
                params.put(entry.getKey(), entry.getValue().getAsString());
            }
        }
        return params;
    }

    private static BlockPos optionalBlockPos(JsonObject args, String xName, String yName, String zName) {
        if (!args.has(xName) && !args.has(yName) && !args.has(zName)) {
            return null;
        }
        return new BlockPos(requiredInt(args, xName), requiredInt(args, yName), requiredInt(args, zName));
    }

    private static boolean hasBlockPos(JsonObject args, String xName, String yName, String zName) {
        return args.has(xName) && args.has(yName) && args.has(zName);
    }

    private static Block requiredBlock(JsonObject args, String name) {
        Identifier id = Identifier.of(requiredString(args, name));
        return Registries.BLOCK.getOptionalValue(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown_block: " + id));
    }

    private static Block blockWithAlias(JsonObject args, String primary, String alias) {
        if (args.has(primary) && args.get(primary).isJsonPrimitive()) {
            return requiredBlock(args, primary);
        }
        if (args.has(alias) && args.get(alias).isJsonPrimitive()) {
            return requiredBlock(args, alias);
        }
        throw new IllegalArgumentException("missing_or_bad_arg: " + primary);
    }

    private static Item requiredItem(JsonObject args, String name) {
        Identifier id = Identifier.of(requiredString(args, name));
        return Registries.ITEM.getOptionalValue(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown_item: " + id));
    }

    private static Item optionalItem(JsonObject args, String name) {
        if (!args.has(name) || !args.get(name).isJsonPrimitive() || args.get(name).getAsString().isBlank()) {
            return null;
        }
        return requiredItem(args, name);
    }

    private static EntityType<?> requiredEntityType(JsonObject args, String name) {
        Identifier id = Identifier.of(requiredString(args, name));
        return Registries.ENTITY_TYPE.getOptionalValue(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown_entity_type: " + id));
    }

    private static Direction optionalDirection(JsonObject args, String name, Direction defaultValue) {
        if (!args.has(name) || !args.get(name).isJsonPrimitive() || args.get(name).getAsString().isBlank()) {
            return defaultValue;
        }
        return switch (args.get(name).getAsString().toLowerCase(java.util.Locale.ROOT)) {
            case "north", "n" -> Direction.NORTH;
            case "south", "s" -> Direction.SOUTH;
            case "east", "e" -> Direction.EAST;
            case "west", "w" -> Direction.WEST;
            default -> throw new IllegalArgumentException("unknown_direction: " + args.get(name).getAsString());
        };
    }

    private static Set<Block> optionalBlocksCsv(JsonObject args, String name) {
        if (!args.has(name) || !args.get(name).isJsonPrimitive() || args.get(name).getAsString().isBlank()) {
            return Set.of();
        }
        Set<Block> blocks = new HashSet<>();
        for (String token : args.get(name).getAsString().split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Identifier id = Identifier.of(trimmed);
            blocks.add(Registries.BLOCK.getOptionalValue(id)
                    .orElseThrow(() -> new IllegalArgumentException("unknown_block: " + id)));
        }
        return blocks;
    }

    // 把"矿石方块 id"或"原矿物品(raw_iron/iron_ore 等)"解析成目标矿石家族(含深板岩变体)。
    private static java.util.Set<Block> oreTargetsFrom(String oreOrItem) {
        Identifier id = Identifier.of(oreOrItem.trim());
        Block block = Registries.BLOCK.getOptionalValue(id).orElse(null);
        if (block != null && OreScan.isOreBlock(block)) {
            return OreScan.oreFamily(block);
        }
        String path = id.getPath().replace("raw_", "");
        for (String cand : new String[]{"minecraft:" + path + "_ore", "minecraft:" + path}) {
            Block b = Registries.BLOCK.getOptionalValue(Identifier.of(cand)).orElse(null);
            if (b != null && OreScan.isOreBlock(b)) {
                return OreScan.oreFamily(b);
            }
        }
        return OreScan.COMMON_ORES;
    }

    private static java.util.Set<Block> resumeOres(BotMemory mem) {
        java.util.Set<Block> ores = new java.util.HashSet<>();
        mem.recall("mine_face_ores").ifPresent(csv -> {
            for (String id : csv.split(",")) {
                var block = net.minecraft.registry.Registries.BLOCK.get(net.minecraft.util.Identifier.of(id.trim()));
                if (block != net.minecraft.block.Blocks.AIR) ores.add(block);
            }
        });
        return ores;
    }

    private static String buildBlueprint(JsonObject args) {
        boolean custom = args != null && (args.has("width") || args.has("depth") || args.has("height") || args.has("material"));
        if (custom) {
            int w = optionalInt(args, "width", 5);
            int d = optionalInt(args, "depth", 5);
            int h = optionalInt(args, "height", 3);
            String material = optionalString(args, "material", "planks");
            return "custom:" + w + "x" + d + "x" + h + ":" + material;
        }
        return optionalString(args, "blueprint", "small_hut");
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void checkTag(net.minecraft.registry.entry.RegistryEntry<Biome> entry,
                                  net.minecraft.registry.tag.TagKey<Biome> tag,
                                  String name,
                                  StringBuilder out) {
        if (entry.isIn(tag)) {
            if (!out.isEmpty()) {
                out.append(",");
            }
            out.append("\"").append(name).append("\"");
        }
    }

    private static JsonObject xyzSchema() {
        return objectSchema()
                .property("x", integerSchema("block x"))
                .property("y", integerSchema("block y"))
                .property("z", integerSchema("block z"))
                .required("x")
                .required("y")
                .required("z")
                .build();
    }

    private static JsonObject stringSchema(String description) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "string");
        schema.addProperty("description", description);
        return schema;
    }

    private static JsonObject arrayOfStringsSchema(String description) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "array");
        schema.addProperty("description", description);
        JsonObject items = new JsonObject();
        items.addProperty("type", "string");
        schema.add("items", items);
        return schema;
    }

    private static JsonObject integerSchema(String description) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "integer");
        schema.addProperty("description", description);
        return schema;
    }

    private static JsonObject integerSchema(String description, int min, int max) {
        JsonObject schema = integerSchema(description);
        schema.addProperty("minimum", min);
        schema.addProperty("maximum", max);
        return schema;
    }

    private static JsonObject booleanSchema(String description) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "boolean");
        schema.addProperty("description", description);
        return schema;
    }

    private static JsonObject mobReactionsSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.addProperty("description",
                "map of entity id (e.g. minecraft:creeper) to reaction: fight, flee, ignore, or auto (removes the rule)");
        JsonObject additional = new JsonObject();
        additional.addProperty("type", "string");
        com.google.gson.JsonArray values = new com.google.gson.JsonArray();
        values.add("fight");
        values.add("flee");
        values.add("ignore");
        values.add("auto");
        additional.add("enum", values);
        schema.add("additionalProperties", additional);
        return schema;
    }

    private static ObjectSchemaBuilder objectSchema() {
        return new ObjectSchemaBuilder();
    }

    private static final class ObjectSchemaBuilder {
        private final JsonObject root = new JsonObject();
        private final JsonObject properties = new JsonObject();
        private final com.google.gson.JsonArray required = new com.google.gson.JsonArray();

        private ObjectSchemaBuilder() {
            root.addProperty("type", "object");
            root.add("properties", properties);
            root.add("required", required);
        }

        private ObjectSchemaBuilder property(String name, JsonObject schema) {
            properties.add(name, schema);
            return this;
        }

        private ObjectSchemaBuilder required(String name) {
            required.add(name);
            return this;
        }

        private JsonObject build() {
            return root;
        }
    }
}
