package com.flt.carpetaddition.storage;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 大容器（双箱 / 陷阱大箱）组识别（组合拳·多箱取货）。
 *
 * <p><b>背景</b>：原版大箱子本质是<b>两个相邻方块拼成一个 54 格容器</b>，打开时才由
 * {@code ChestBlock.getContainer} 合并。两个 half 各是一个 {@code ChestBlockEntity}，
 * 各自只存自己那 27 格（左 0-26、右 27-53）。若玩家只登记了一个 half，统计就缺一半，
 * 表现为"取货缺一半 / 数量不对"。
 *
 * <p><b>本类职责</b>：
 * <ol>
 *   <li>{@link #groupOf}：给定任一半坐标，返回整组全部 half 坐标（登记用，把两个 half 都 add）</li>
 *   <li>{@link #canonical}：整组的"规范坐标"（字典序最小），保证同组无论从哪个 half 进都得到同一 key，
 *       用于扫描/取货<b>去重</b>（整组只处理一次）</li>
 *   <li>{@link #fullContainerFor}：返回能<b>完整读取整组 54 格</b>的容器——双箱用
 *       {@code ChestBlock.getContainer} 拿合并容器；普通容器直接取 BlockEntity</li>
 * </ol>
 *
 * <p>当前支持<b>原版双箱 / 陷阱大箱</b>。另支持 <b>TIS 大木桶（largeBarrel）</b>：
 * TIS Carpet Addition（modid {@code carpettisaddition}）是<b>可选依赖</b>，本类通过<b>反射</b>
 * 调用 {@code LargeBarrelHelper} —— 装了就生效（同双箱那样归一 + 整组统计），没装不影响原功能。
 */
public final class ContainerGroup {
    private ContainerGroup() {
    }

    // =========================================================
    // TIS 大木桶（largeBarrel）可选支持（反射接入）
    // =========================================================

    /** TIS 的 Helper 类全名（反射用，不编译期依赖） */
    private static final String TIS_LARGE_BARREL_HELPER =
            "carpettisaddition.helpers.rule.largeBarrel.LargeBarrelHelper";

    /** 服务端是否已加载 TIS mod（缓存一次判定结果，避免每容器反复查） */
    private static Boolean tisLoaded = null;

    /** 反射缓存：LargeBarrelHelper 的三个静态方法 */
    private static Method tisIsLargeBarrel;
    private static Method tisGetOtherPos;

    /**
     * 判定当前环境是否装了 TIS Carpet Addition（一次性惰性探测并缓存）。
     *
     * <p>探测方式：试着 {@code Class.forName(LargeBarrelHelper)}；能找到说明装了。
     * 不依赖 FabricLoader，避免不必要的模块加载。
     */
    private static boolean tisAvailable() {
        if (tisLoaded == null) {
            try {
                Class.forName(TIS_LARGE_BARREL_HELPER);
                tisLoaded = Boolean.TRUE;
            } catch (Throwable notLoaded) {
                tisLoaded = Boolean.FALSE;
            }
        }
        return tisLoaded;
    }

    /**
     * 反射取 TIS 的 {@code LargeBarrelHelper.getOtherPos} / {@code isLargeBarrel}。
     * 方法签名在同一 mod 里稳定；首调用后缓存 Method 对象。
     */
    private static void initTisMethods() {
        if (tisIsLargeBarrel != null || !tisAvailable()) {
            return;
        }
        try {
            Class<?> helper = Class.forName(TIS_LARGE_BARREL_HELPER);
            tisIsLargeBarrel = helper.getMethod("isLargeBarrel",
                    BlockState.class, Level.class, BlockPos.class);
            tisGetOtherPos = helper.getMethod("getOtherPos",
                    BlockState.class, Level.class, BlockPos.class);
        } catch (Throwable t) {
            // 方法签名变化 → 作废（后续每次调用都会重试，但不崩）
            tisIsLargeBarrel = null;
            tisGetOtherPos = null;
        }
    }

    /** TIS 大木桶的另一半坐标；pos 不是 TIS 大木桶时返回 null。 */
    private static BlockPos tisOtherPos(ServerLevel level, BlockPos pos, BlockState state) {
        try {
            initTisMethods();
            if (tisGetOtherPos == null) {
                return null;
            }
            Object other = tisGetOtherPos.invoke(null, state, level, pos);
            return other instanceof BlockPos bp ? bp.immutable() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 给定 pos 若属于 TIS 大木桶，返回整组（两 half）；否则 null。
     * 只处理大木桶，普通容器返回 null（由调用方继续走原版分支）。
     */
    private static boolean isTisBarrel(ServerLevel level, BlockPos pos, BlockState state) {
        try {
            initTisMethods();
            if (tisIsLargeBarrel == null) {
                return false;
            }
            Object r = tisIsLargeBarrel.invoke(null, state, level, pos);
            return Boolean.TRUE.equals(r);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 若 pos 属于双格容器，返回整组全部 half 坐标（含自身）；否则返回只含自身。
     * 支持原版双箱（ChestBlock）与 TIS 大木桶（BarrelBlock，反射检测）。
     * 供 {@code StockCommand.add} 把整组都登记。
     */
    public static List<BlockPos> groupOf(ServerLevel level, BlockPos pos) {
        List<BlockPos> result = new ArrayList<>();
        result.add(pos.immutable());
        BlockState state = level.getBlockState(pos);

        // TIS 大木桶：两 half 面对面（facing 互为反向），由 LargeBarrelHelper 判定并给另一半
        if (state.getBlock() instanceof BarrelBlock) {
            BlockPos other = tisOtherPos(level, pos, state);
            if (other != null) {
                result.add(other);
            }
            return result;
        }

        if (!(state.getBlock() instanceof ChestBlock)) {
            return result;
        }
        try {
            if (state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                BlockPos other = ChestBlock.getConnectedBlockPos(pos, state);
                BlockState otherState = level.getBlockState(other);
                if (otherState.getBlock() instanceof ChestBlock
                        && otherState.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                    result.add(other.immutable());
                }
            }
        } catch (Exception ignored) {
            // 状态解析异常（理论上不发生）→ 退化为自身一组
        }
        return result;
    }

    /** 整组"规范坐标"：组内 (x,y,z) 字典序最小。同组无论从哪个 half 进都返回同一个值。 */
    public static BlockPos canonical(ServerLevel level, BlockPos pos) {
        List<BlockPos> group = groupOf(level, pos);
        BlockPos best = null;
        for (BlockPos p : group) {
            if (best == null || compare(p, best) < 0) {
                best = p;
            }
        }
        return best != null ? best : pos.immutable();
    }

    /** 判断 pos 是否双格大容器的一部分（分组才有意义）。支持原版双箱与 TIS 大木桶。 */
    public static boolean isPartOfDouble(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof BarrelBlock) {
            return isTisBarrel(level, pos, state);
        }
        return state.getBlock() instanceof ChestBlock
                && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE;
    }

    /**
     * 返回能<b>完整读取整组容器内容</b>的 {@link Container}。
     * 双箱返回 {@link ChestBlock#getContainer} 的合并 54 格；TIS 大木桶返回其合并容器；
     * 否则返回 pos 的 BlockEntity 容器。
     *
     * @return 完整容器；pos 不是容器时返回 null
     */
    public static Container fullContainerFor(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);

        // TIS 大木桶：getInventory(state, level, pos) 用 DoubleBlockCombiner 合并返回 CompoundContainer
        if (state.getBlock() instanceof BarrelBlock) {
            Container tis = tisFullContainer(level, pos, state);
            if (tis != null) {
                return tis;
            }
        }

        if (state.getBlock() instanceof ChestBlock) {
            try {
                return ChestBlock.getContainer(
                        (ChestBlock) state.getBlock(), state, level, pos, false);
            } catch (Exception ignored) {
                // 合并失败（无右半等）→ 退到读 BlockEntity
            }
        }
        if (level.getBlockEntity(pos) instanceof Container container) {
            return container;
        }
        return null;
    }

    /**
     * 反射调 TIS {@code LargeBarrelHelper.getInventory(BlockState, Level, BlockPos)}，
     * 返回合并后的完整 {@link Container}（大木桶 54 格 / 单桶 27 格）。
     * 失败（TIS 未装 / 方法签名变化）返回 null。
     */
    private static Container tisFullContainer(ServerLevel level, BlockPos pos, BlockState state) {
        try {
            if (!tisAvailable()) {
                return null;
            }
            Method getInventory = Class.forName(TIS_LARGE_BARREL_HELPER)
                    .getMethod("getInventory", BlockState.class, Level.class, BlockPos.class);
            Object container = getInventory.invoke(null, state, level, pos);
            return container instanceof Container c ? c : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 返回与 {@link #fullContainerFor} 槽位<b>完全一致</b>的 {@link MenuProvider}（用于假人 openMenu 取货/装箱）。
     *
     * <p><b>为什么必须有它</b>（2026-09-23 双箱 bug 实证）：{@code fullContainerFor} 通过
     * {@code ChestBlock.getContainer} 返回<b>合并后的 54 格双箱容器</b>，{@code collectTargets} 据此记录
     * 槽 27~52；但假人取货若用裸 {@code blockEntity}（单个 half 的 ChestBlockEntity）打开菜单，它的
     * {@code createMenu} 里只调用 {@code ChestMenu.threeRows} 建<b>27 格菜单</b> → 槽 27~52 全部越界、
     * 取 0 个。玩家右键大箱子之所以显示 54 格，是走了 {@code BlockState.getMenuProvider}
     * （{@code MENU_PROVIDER_COMBINER} 合并成 CombinedMenuProvider）。所以取货开箱必须用<b>方块状态</b>
     * 的标准菜单入口，与 {@code fullContainerFor} 的合并容器槽位对齐。
     *
     * @return 合并容器对应的 MenuProvider（双箱/TIS 大桶→合并 54/27 格，单箱/普通容器→自身）；
     *         pos 不是可打开容器时返回 null
     */
    public static MenuProvider menuProviderFor(ServerLevel level, BlockPos pos) {
        // 优先用方块状态的公开菜单入口（MC 内部对双箱/TIS 大桶用 DoubleBlockCombiner 合并），
        // 保证打开的菜单位置与 fullContainerFor 读到的完整容器一致。
        MenuProvider fromState = level.getBlockState(pos).getMenuProvider(level, pos);
        if (fromState != null) {
            return fromState;
        }
        // 回退：普通的可打开 blockEntity（如桶、漏斗容器等）
        if (level.getBlockEntity(pos) instanceof MenuProvider be) {
            return be;
        }
        return null;
    }

    /** 字典序比较（Y 优先，NET 习惯；仅内部去重 key 用） */
    private static int compare(BlockPos a, BlockPos b) {
        if (a.getY() != b.getY()) return a.getY() - b.getY();
        if (a.getX() != b.getX()) return a.getX() - b.getX();
        return a.getZ() - b.getZ();
    }
}