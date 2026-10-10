package com.flt.carpetaddition.fakeplayer;

import com.flt.carpetaddition.FLTAdditionMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

/**
 * 安全落点查找：给假人的传送找一个「站得住、不被烤 / 扎 / 憋住」的位置。
 *
 * <p><b>为什么需要这个类（2026-09-28）</b>：取货链路里有 4 处硬传送 —— 3 处站到目标容器
 * 顶上方一格（{@code ContainerWithdrawAction} / {@code MultiContainerWithdrawAction} /
 * {@code BulkFetchRepackAction}），1 处送到玩家身边（{@code RestockScheduler.deliverToPlayer}）。
 * 落点都是固定公式、不做任何检查，实测三类事故：
 * <ol>
 *   <li><b>掉落</b>：落点脚下没有支撑（箱子悬空、玩家站悬崖边）→ 假人掉下去，可能落进岩浆或虚空</li>
 *   <li><b>卡住</b>：落点本身就是固体方块 → 假人被夹在方块里，开箱反复失败（表现为「取 0 个」）</li>
 *   <li><b>受伤</b>：落点是岩浆 / 火 / 仙人掌 / 甜浆果丛等 → 假人持续掉血</li>
 * </ol>
 * 假人是 {@code ServerPlayer}，会像真玩家一样受伤、烧死、掉虚空；死亡时背包掉一地 ——
 * 等于把刚取到的货撒在箱子旁边。
 *
 * <p><b>搜索半径必须分场景给</b>，因为两处的可容忍距离差很多（均已 javap 实证）：
 * <ul>
 *   <li><b>站到容器旁</b>：容器菜单 {@code stillValid} 走的是
 *       {@code blockInteractionRange}(4.5) + padding(4.0) = <b>8.5 格</b> → 见 {@link #CONTAINER_RANGE}</li>
 *   <li><b>送到玩家身边</b>：玩家要右键打开假人背包，走的是<b>实体</b>交互距离
 *       {@code entityInteractionRange} = <b>3.0 格</b>（比方块交互的 4.5 更短）
 *       → 见 {@link #DELIVER_RANGE}</li>
 * </ul>
 *
 * <p>⚠️ <b>跨版本 API 差异</b>：26.3 移除了 {@code BlockState.blocksMotion()}
 * （26.1.2 / 26.2 都有，javap 实证）→ 本类统一改用
 * {@code getCollisionShape(level, pos).isEmpty()} 判断「是否挡人」，
 * 语义等价（有碰撞箱 = 挡人）且三版通用。见 {@link #blocksMotion}。
 *
 * <p>⚠️ 本类<b>不能</b>并进 {@link FakePlayerUtils}：那是 26.3 的版本专属适配文件
 * （{@code swing} 签名差异），三版同步脚本 {@code sync_26.py} 的 EXCLUDE 表会跳过它 →
 * 公共工具放那里会漏同步。本类不在排除表，26.1.2 → 26.2 / 26.3 正常镜像。
 */
public final class SafeSpotFinder {

    /** 站到容器旁的推荐搜索半径：容器校验 8.5 格，留出余量取 6 */
    public static final double CONTAINER_RANGE = 6.0D;

    /** 送到玩家身边的推荐搜索半径：玩家右键假人只有 3.0 格，收紧到 2 保证够得着 */
    public static final double DELIVER_RANGE = 2.0D;

    private SafeSpotFinder() {
    }

    /**
     * 该方块是否会阻挡实体移动（= 有没有碰撞箱）。
     *
     * <p><b>为什么不直接用 {@code BlockState.blocksMotion()}</b>：那个方法在 26.3 已被移除
     * （26.1.2 / 26.2 有、26.3 没有，javap 实证），写了会导致 26.3 编译失败。
     * 这里直接问碰撞箱，三版通用，语义也一致 —— 有碰撞箱就会挡人。
     *
     * @param pos 该方块所在坐标（碰撞箱形状可能随位置变化，如栅栏、台阶）
     */
    private static boolean blocksMotion(ServerLevel level, BlockPos pos, BlockState state) {
        return !state.getCollisionShape(level, pos).isEmpty();
    }

    /**
     * 该方块是否会伤害「站在它上面或身处其中」的假人。
     *
     * <p>清单只列<b>会直接扣血</b>的方块；减速类（灵魂沙、蜂蜜块、冰）不算 —— 那些站得住。
     */
    private static boolean isDangerous(BlockState state) {
        return state.is(BlockTags.FIRE)              // 火 + 灵魂火
                || state.is(BlockTags.CAMPFIRES)     // 营火 + 灵魂营火
                || state.is(Blocks.MAGMA_BLOCK)      // 岩浆块
                || state.is(Blocks.CACTUS)           // 仙人掌
                || state.is(Blocks.SWEET_BERRY_BUSH) // 甜浆果丛
                || state.is(Blocks.POWDER_SNOW)      // 细雪（陷入 + 冻结）
                || state.is(Blocks.WITHER_ROSE)      // 凋灵玫瑰
                || state.getFluidState().is(FluidTags.LAVA);   // 岩浆（含流动岩浆）
    }

    /**
     * 假人能否安全站在 {@code feet} 这一格。
     *
     * <p>三个条件全满足才算安全：
     * <ol>
     *   <li><b>脚下有支撑</b>：{@code feet.below()} 有碰撞箱（实体地面）且不危险</li>
     *   <li><b>身体放得下</b>：{@code feet} 与 {@code feet.above()} 都没有碰撞箱</li>
     *   <li><b>身体不泡在危险物里</b>：这两格都不是危险方块、且都没有液体
     *       （假人落进水里会下沉溺水，位置也就不准了）</li>
     * </ol>
     *
     * @param feet 脚所在的方块坐标（不是头部）
     */
    public static boolean canStandAt(ServerLevel level, BlockPos feet) {
        BlockPos head = feet.above();
        if (level.isOutsideBuildHeight(feet) || level.isOutsideBuildHeight(head)) {
            return false;   // 虚空 / 超出世界高度
        }

        BlockState below = level.getBlockState(feet.below());
        BlockState at = level.getBlockState(feet);
        BlockState above = level.getBlockState(head);

        // ① 脚下要能承载，且不能是危险方块（岩浆块、仙人掌…）
        if (!blocksMotion(level, feet.below(), below) || isDangerous(below)) {
            return false;
        }
        // ② 身体两格必须能容身（有碰撞箱就会把假人卡住窒息）
        if (blocksMotion(level, feet, at) || blocksMotion(level, head, above)) {
            return false;
        }
        // ③ 身体两格不能是危险方块，也不能有液体
        if (isDangerous(at) || isDangerous(above)) {
            return false;
        }
        return at.getFluidState().isEmpty() && above.getFluidState().isEmpty();
    }

    /**
     * 就近找一个安全落点，供传送使用。
     *
     * <p>搜索顺序（先近后远，找到即返回）：以原始落点为中心逐圈向外扩（半径 0、1、2 …），
     * 每圈内先试原高度、再依次往下一格试；候选点到原始落点的直线距离不得超过 {@code maxDistance}。
     *
     * <p>「往下一格试」是为了覆盖最常见的悬空情况：落点本身在半空中，但正下方一两格就是实地。
     *
     * @param x           原始落点 X
     * @param y           原始落点 Y（脚位高度）
     * @param z           原始落点 Z
     * @param maxDistance 允许偏离原始落点的最大距离（格）。开箱用 {@link #CONTAINER_RANGE}、
     *                    送货用 {@link #DELIVER_RANGE}，理由见类注释
     * @return 安全落脚点（方块中心坐标）；{@code null} = 附近找不到，由调用方决定回退行为
     */
    public static Vec3 findSafeSpot(ServerLevel level, double x, double y, double z, double maxDistance) {
        BlockPos origin = BlockPos.containing(x, y, z);
        int maxR = (int) Math.ceil(maxDistance);

        for (int r = 0; r <= maxR; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;   // 只处理当前这一圈，更内圈上一轮已经试过
                    }
                    for (int dy = 0; dy >= -maxR; dy--) {
                        BlockPos feet = new BlockPos(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                        double ddx = feet.getX() + 0.5D - x;
                        double ddy = feet.getY() - y;
                        double ddz = feet.getZ() + 0.5D - z;
                        double dist = Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz);
                        if (dist > maxDistance) {
                            continue;
                        }
                        if (canStandAt(level, feet)) {
                            return new Vec3(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D);
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * 把假人传送到 {@code (x, y, z)} 附近的<b>安全落点</b>，朝向沿用假人当前朝向。
     *
     * @param scene 日志用的场景名（如「取货开箱」「送货到玩家身边」）
     */
    public static void teleportToSafeSpot(ServerPlayer bot, ServerLevel level,
                                          double x, double y, double z,
                                          double maxDistance, String scene) {
        teleportToSafeSpot(bot, level, x, y, z, maxDistance, bot.getYRot(), bot.getXRot(), scene);
    }

    /**
     * 把假人传送到 {@code (x, y, z)} 附近的<b>安全落点</b>，并指定朝向。
     *
     * <p><b>找不到安全落点时的回退</b>：按原始坐标传送（保持改造前的行为）+ 记一条 WARN。
     * 之所以不中止取货，是因为安全判定一旦误杀，中止会让原本能用的场景彻底失效；
     * 退回原行为最坏也只是回到「没有这层保护」的旧状态。
     */
    public static void teleportToSafeSpot(ServerPlayer bot, ServerLevel level,
                                          double x, double y, double z,
                                          double maxDistance, float yRot, float xRot, String scene) {
        Vec3 spot = findSafeSpot(level, x, y, z, maxDistance);
        if (spot == null) {
            FLTAdditionMod.LOGGER.warn("[FLT] {} 找不到安全落点（目标 {} / {} / {}，{} 格内），按原位传送",
                    scene, String.format("%.1f", x), String.format("%.1f", y), String.format("%.1f", z),
                    String.format("%.1f", maxDistance));
            spot = new Vec3(x, y, z);
        }
        bot.teleportTo(level, spot.x, spot.y, spot.z, Set.<Relative>of(), yRot, xRot, false);
    }
}
