package com.flt.carpetaddition.fakeplayer;

import carpet.patches.EntityPlayerMPFake;
import com.flt.carpetaddition.FLTAdditionMod;
import com.flt.carpetaddition.storage.ContainerGroup;
import com.flt.carpetaddition.storage.StockSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 假人「一键备齐 + 装箱送回」动作（组合拳·投影「假人备货」按钮的服务端实现）。
 *
 * <p>用户从投影材料列表点「假人备货」后，服务端为这位玩家启动本动作，为假人执行完整流程：
 * <ol>
 *   <li><b>取齐（FETCH）</b>：逐物品算「需求 − 假人背包已有」缺口，跨箱取货到假人背包。
 *       复用 {@link MultiContainerWithdrawAction#collectTargets} 收集目标槽，再按
 *       传送 → 开箱 → 逐槽 {@code quickMoveStack} 的多刻状态机搬货。</li>
 *   <li><b>装箱（PACK）</b>：仓库找空潜影盒 → 让假人从仓库拿一个盒 → 打开盒 → 把假人背包的
 *       散装物品尽量填满盒，产出一个成品盒（每盒最多 27 格 × 该物品堆叠上限）。</li>
 *   <li><b>送回（DELIVER）</b>：假人背着成品盒（若有）与散装余量送到玩家面前。</li>
 * </ol>
 *
 * <p><b>为什么装箱</b>：大量散装物品（如一整盒石砖）占满假人 36 格背包；打包成一盒只占一格，
 * 一次就能把大量材料送到玩家面前（玩家右键假人背包取走成品盒，比逐格捡散装高效得多）。
 *
 * <p><b>空盒来源</b>：从已登记仓库里找「空潜影盒」（{@code StackCounter.count} 判定为"盒本身"
 * 即空盒）。仓库没有空盒 → 不装箱、散装送回（不额外造盒，绝不变出物品）。
 *
 * <p><b>状态机</b>（每 tick 由 {@link FakePlayerActionScheduler} 驱动，同一时刻只执行一个子阶段）：
 * <pre>
 *   FETCH:  for each 需求物品 in 排序后的缺口列表:
 *              传送(1t) → 开箱(1t) → 逐槽取货(直到该物品够需求或箱空)
 *   PACK:  找空盒(1t) → 去空盒箱取空盒(传送/开箱/取盒) → 打开盒(1t) → 填物(每t填若干格) → 完成
 *          所有散装填完 或 仓库没有空盒 → 结束装箱
 *   DELIVER: 传送假人到玩家面前，report，return false 结束
 * </pre>
 *
 * <p>【尽力而为】某物品仓库取光也不够需求 → 取到多少算多少；没空盒 → 散装送回。
 * 结束时理 {@code reporter} 汇报汇总。
 */
public class BulkFetchRepackAction extends AbstractFakePlayerAction {
    /** 玩家 UUID（送回目标） */
    private final UUID playerId;
    /** 玩家名（仅日志/命名用） */
    private final String playerName;

    /** 需求表（物品ID → 需要总数），键已排序 */
    private final List<Map.Entry<Identifier, Integer>> demands;
    /** 当前处理到第几个需求物品 */
    private int demandIndex = 0;

    /** 结束反馈 */
    private final Consumer<String> reporter;

    // ============ 汇总统计 ============
    private int fetchedTotalItems = 0;   // 累计取到的散装物品数（所有物品）
    /**
     * 本次任务各物品「已实际取到」的累计数量（跨多次派单）。
     *
     * <p>为什么不能只看背包：货一旦装进盒里，{@code countItemOnBot} 就数不到了（它只扫散装栈），
     * 而盒内物品稍后要交付给玩家 —— 若拿背包数算缺口，「取 27 格 → 装一盒 → 再算缺口」
     * 会得出"背包里还是 0"，于是同一物品被无限重取。所以缺口按
     * {@code 需求 − max(本任务已取, 背包现有)} 计算（见 {@link #advanceFetch}）。
     */
    private final Map<Identifier, Integer> fetchedSoFar = new LinkedHashMap<>();
    private int packedBoxes = 0;         // 产出的成品盒数
    private String message;

    // ============ 阶段码 ============
    private static final int PHASE_FETCH = 0;
    private static final int PHASE_PACK = 1;
    private static final int PHASE_DELIVER = 2;
    private int phase = PHASE_FETCH;

    // ---- 取货（FETCH）子状态：逐物品委托 MultiContainerWithdrawAction（跨箱取货做法参考 LMS，本实现独立编写） ---- //
    /** 当前正在执行「单个物品跨箱取货」的子动作；null 表示待对下一个需求物品新建 */
    private MultiContainerWithdrawAction currentFetcher;

    // ---- 装箱（PACK）子状态 ----
    private BlockPos emptyBoxSrcPos;   // 找到空盒的仓库容器坐标
    /**
     * 该容器所在世界（与 {@code emptyBoxSrcPos} 配对，供 {@link #locateEmptyBox} 优先复查用）。
     * 光有坐标无法在多个维度里定位容器。
     */
    private net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> emptyBoxSrcLevel;
    private int emptyBoxSrcSlot;       // 该容器里空盒的槽索引
    private AbstractContainerMenu boxSrcMenu;  // 去取空盒打开的箱菜单
    private BlockPos boxSrcOpenPos;
    private int boxSrcSize;
    private int packSub = 0;           // PACK 子阶段：0=判断还需装箱/1=找空盒/2=取空盒/3=装一盒

    // ---- 容量治理（[VERSION] 2026-09-28 新增：解决"背包塞满后既打不了包也取不动货"）----
    /**
     * true = 本轮 PACK 是「FETCH 途中的临时打包」，完成后回 FETCH 继续取货；
     * false = 需求已全部取完后的收尾打包，完成后直接进入交付。
     */
    private boolean packAfterFetch = false;
    /**
     * 本次备货已确认"装不了盒"（仓库没有空盒 / 背包满且末影箱也腾不出格）。
     * 置位后不再尝试中途打包 —— 否则会「背包满 → 转 PACK → 腾格失败 → 回 FETCH →
     * 立刻又满 → 又转 PACK」空转，要等 600 刻看门狗才救场。
     */
    private boolean packUnavailable = false;
    /**
     * 触发「取货途中就打包」的背包剩余空格阈值：剩余格数 &le; 此值且有散装时，先打包腾空间。
     *
     * <p>为什么是 4 而不是 1（[VERSION] 2026-09-28 调整）：只留 1 格等于"非等塞满才动手"，
     * 那时背包已无余量，腾格只能靠末影箱 —— 而末影箱一旦也满，备货就被迫截断。
     * 提前到剩 4 格就打包（装一盒净减 26 格），背包在塞满前即被压缩，大额备货往往
     * 全程都用不到末影箱：留 1 格取空盒、3 格给搬运波动。
     *
     * <p>[VERSION] 2026-09-29：同一个阈值也作为<b>取货子动作的容量闸门</b>
     * （{@code MultiContainerWithdrawAction.stopFreeSlots}）—— 取货途中一旦触及就暂停、交回本类
     * 装箱后再继续。此前只在本类（物品间隙）判容量，而取货子动作会一口气把某个物品的全部缺口
     * 搬回来，单个物品的量就可能塞满背包、让剩余目标槽全部搬不动（用户报障
     * "背包满了无法继续取货和装盒"）—— 原来注释里的"3 格给取货途中的波动"其实从未生效。
     */
    private static final int PACK_TRIGGER_FREE_SLOTS = 4;
    /**
     * <b>封盒的最小填充格数</b>：盒内填到这个格数才封盒，否则这些散装留作散装交付。
     *
     * <p>[VERSION] 2026-09-30 先按"半盒（14 格）"设过一版（用户报障「打包现在盒子利用率好低」），
     * 但<b>当天就发现按"格数"设阈值有个致命前提错误</b>：<b>一盒能填的格数上限 = 物品种类数</b>
     * （每种物品至少占 1 格，数量少也不会少占格）。用户的实际投影常常只有十来种物品，
     * 于是 14 格门槛<b>结构性永远达不到</b> → 表现成"总是不打包"，而他要的是材料整齐进盒
     * （2026-09-30 晚：需求 8 种 / 68 个 → 最多填 7 格 → 判"攒不够一盒" → 全散装送来）。
     *
     * <p>故改为 <b>1</b>：<b>只要背包里有散装就封盒</b>。配合 {@code fillBoxFromInventory} 的
     * "多物品填满优先 + 同种物品优先同盒"，利用率靠<b>把能装的都装进同一盒</b>来提升，
     * 而不是靠"不够多就不装"。
     *
     * <p>保留这个常量与判定分支（不删）是为了：① 语义留痕，说明"封盒门槛"这个概念存在；
     * ② 若将来想重新收紧（例如改成按<b>总量/容积占比</b>而非格数），直接在这里改。
     */
    private static final int MIN_BOX_FILL_SLOTS = 1;
    /**
     * 「腾格」的目标余量：把背包腾到至少这么多空格再继续取货（见 {@link #stashSomethingToEnderChest}）。
     *
     * <p>为什么不是只腾 1 格：每次只腾 1 格的话，"取 1 格 → 又满 → 再腾 1 格"要来回很多趟，
     * 而每趟都要跨箱传送 + 开箱 + 取货，极慢。取 8 格 = 两倍于装箱触发阈值，回来后能连取一段。
     */
    private static final int STASH_TARGET_FREE_SLOTS = 8;
    /** 本次备货存入假人末影箱的格数（交付提示用，区别于玩家自己早先放进去的东西） */
    private int stashedToEnderChest = 0;
    /**
     * true = 本次装箱是「背包装不下、且已无成品盒可挪走」而中止的（区别于"仓库没有空盒"）。
     * 置位后交付提示会明确告知玩家少备的原因，并把未完成的需求登记成「待续备货」——
     * 原先这种情况完全静默，玩家只看到"备货完成"，不知道还有需求没备。
     */
    private boolean stashFullAbort = false;
    /**
     * true = 本次"装不了盒"的直接原因是<b>取不到空盒</b>（仓库里没有，或背包放不下空盒）。
     *
     * <p>[VERSION] 2026-09-30 自查新增，只影响交付提示的措辞：{@code packedBoxes == 0} 并不只有
     * "仓库没有空盒"一种原因（还可能是"散装攒不够一盒"、"背包满且腾不出格"），
     * 一律写"仓库没有空盒"会把玩家指去翻一个其实是好的空盒源。
     */
    private boolean packNoBoxAbort = false;

    /**
     * true = 因看门狗判定卡死而提前交付（区别于容量不足）。
     * 卡死降级同样会把未处理的需求截断，所以也要登记续备并在提示里说清 ——
     * 否则玩家只看到"备货完成"，以为已备齐。
     */
    private boolean stalledAbort = false;
    /**
     * 因末影箱满而中止时，仍未取完的需求（物品 → 数量）。交给 {@link RestockScheduler}
     * 在玩家把这批货取走后自动续备；空 Map 表示无需分批。
     */
    private Map<Identifier, Integer> remainingDemands = Map.of();
    /**
     * true = 无论因何结束，都把「未取到的需求」登记成待续备货（多假人并行备货时置位）。
     *
     * <p><b>为什么需要这个开关</b>：{@link #remainingDemands} 只在两种 abort 路径上赋值
     * （容量塞满 {@code stashFullAbort} / 看门狗卡死 {@code stalledAbort}）。而并行备货里
     * 最常见的少取是<b>竞态</b>造成的：勘察时箱子确实有货、等你走到另一个假人已经取走了，
     * 此时 {@code advanceFetch} 因 {@code collectTargets} 返回空直接跳过 —— 不算 abort，
     * 于是缺口被静默丢掉（不复制、但也永远补不回来）。
     *
     * <p>置位后按"需求 − 实取"无条件登记缺口；多个假人各登记自己那一片，
     * {@code RestockScheduler.schedulePendingRestock} 负责合并累加。
     * 单假人路径保持 false —— 那条路径的缺口本来就由 abort 语义管着，不扩大改动面。
     */
    private boolean registerLeftoverAlways = false;

    /** 供 {@code RestockScheduler} 在并行备货时置位（见 {@link #registerLeftoverAlways}） */
    public void setRegisterLeftoverAlways(boolean value) {
        this.registerLeftoverAlways = value;
    }
    /**
     * 单次开箱最多取走多少个「空盒槽」（[VERSION] 2026-09-29 优化）。
     *
     * <p>为什么批量取：每装一盒都要「传送 → 开箱 → 取盒」跑一趟仓库，而空盒通常成堆放在同一箱里。
     * 一次把这箱的空盒备够（上限 6 槽），就能连着装好几盒只跑一趟。
     * <p>[VERSION] 2026-09-30 由 6 降到 3（用户报障「多拿了好多空盒」）：本上限只是"单次往返"的
     * 硬顶，真正该取多少由 {@link #estimateBoxesNeeded()} 按"这批还差几个盒"算。
     * 多取的空盒<b>不会随交付给玩家</b>（{@code prepareBotForNewTask} 把空盒当"工具"保留在假人身上），
     * 于是每轮任务多拿一点、假人背包被空盒越占越多。
     */
    private static final int MAX_BOXES_PER_TRIP = 3;
    /**
     * 假人身上<b>长期保留</b>的备用空盒<b>个数</b>（装箱工具），超出的会在装箱收尾时转入末影箱。
     *
     * <p>[VERSION] 2026-09-30 新增（修「多拿了好多空盒」）：空盒被
     * {@code prepareBotForNewTask} 当作"工具"跨任务保留，取盒时又可能一次拿进好几个，
     * 囤多了会挤占后续取货的背包空间。这里只留够一两次装箱的量，其余挪进末影箱（玩家可自行取回）。
     *
     * <p>⚠️ 单位是<b>个</b>不是格 —— 潜影盒可堆叠时 1 格就是 1 组（最多 64 个），
     * 按格算会把"该清理的量"放大 64 倍。见 {@link #relieveSurplusBoxes} 里的自查记录。
     */
    private static final int KEEP_SPARE_BOXES = 3;
    /** 末影箱占用达到此格数就在交付提示里预警，让玩家在撞上限前取货 */
    private static final int ENDER_CHEST_WARN_SLOTS = 22;

    // ---- 卡死看门狗 ----
    /** 连续多少刻没有任何实质进展（取到物/换箱/换阶段等）就强制降级，避免动作永挂 */
    private static final int MAX_STALL_TICKS = 600;   // 30 秒无进展即视为卡死
    private int stallTicks = 0;
    /** 上次推进时的"无进展判定依据"，用于日志对比 */
    private String stallMark = "";

    public BulkFetchRepackAction(EntityPlayerMPFake fakePlayer,
                                 UUID playerId, String playerName,
                                 Map<Identifier, Integer> demands,
                                 Consumer<String> reporter) {
        super(fakePlayer);
        this.playerId = playerId;
        this.playerName = playerName;
        this.demands = new ArrayList<>(demands.entrySet());
        this.demands.sort(Comparator.comparing(e -> e.getKey().toString()));
        this.reporter = reporter;
    }

    @Override
    public boolean tick() {
        try {
            boolean keep;
            String mark = progressMark();
            switch (phase) {
                case PHASE_FETCH -> { keep = tickFetch(); }
                case PHASE_PACK -> { keep = tickPack(); }
                default -> { return tickDeliver(); }
            }
            // 卡死看门狗：若本 tick 前后状态毫无变化（没取到物/没换槽/没换阶段），累计刻度，
            // 连续 MAX_STALL_TICKS 刻无进展则强制降级到交付（宁可送货上门也不永挂）。
            if (!mark.equals(stallMark)) {
                stallMark = mark;
                stallTicks = 0;
            } else if (++stallTicks >= MAX_STALL_TICKS) {
                FLTAdditionMod.LOGGER.warn("[FLT] 备货动作卡死（状态 {} 连续 {} 刻无进展），强制降级到交付",
                        mark, stallTicks);
                currentFetcher = null;
                if (boxSrcMenu != null) {
                    try { getFakePlayer().closeContainer(); } catch (Throwable ignored) {}
                    boxSrcMenu = null;
                }
                // 卡死会丢掉尚未处理的需求 → 一并登记续备（2026-09-28）
                stalledAbort = true;
                remainingDemands = unprocessedDemands();
                phase = PHASE_DELIVER;
                return true;
            }
            return keep;
        } catch (Throwable t) {
            FLTAdditionMod.LOGGER.error("[FLT] 备货打包动作异常，终止", t);
            message = "备货过程出错：" + t.getClass().getSimpleName();
            return false;
        }
    }

    /**
     * 生成当前"进展指纹"：阶段 + 当前物品 + 目标槽 + 装箱子阶段 + 背包散装总串。
     * 用作卡死看门狗判断「有无实质进展」的依据（同指纹连续多刻即视为卡住）。
     */
    private String progressMark() {
        StringBuilder sb = new StringBuilder(64);
        sb.append("p=").append(phase)
                .append(";demand=").append(demandIndex).append('/').append(demands.size());
        if (currentFetcher != null) {
            // 印出子动作的内部进度（idx/已取数/当前箱），这样即使取同一个物品，
            // 只要它在推进（传送/开箱/取槽），指纹就会变化 —— 看门狗不会误判卡死
            sb.append(";item=").append(currentFetchItemId)
                    .append('[').append(currentFetcher.progressSnapshot()).append(']');
        }
        // ⚠️ 指纹里<b>不放</b> packSub / emptyBoxSrcPos 这类"意图状态"（[VERSION] 2026-09-29 自查修复）：
        //    它们在"PACK 内部空转"时也会每刻变化（例：空盒所在方块打不开 →
        //    packSub 在 0/1/2/3 之间循环、box 反复被赋同一个值），看门狗据此判"一直在推进"
        //    → **永不触发**，假人静默卡死。改用<b>实际产出</b>（已装盒数）与散装槽数做指纹：
        //    只有真出了成果才算进展，空转时这两个数都不动 → 看门狗正常兜底。
        sb.append(";boxes=").append(packedBoxes)
                .append(";loose=").append(looseTotalOnBot());
        return sb.toString();
    }

    /**
     * 假人背包里散装（非潜影盒）物品的<b>槽数</b>，供进展指纹对比。
     *
     * <p>[VERSION] 2026-09-30 口径修正：循环上界改用 {@link Inventory#INVENTORY_SIZE}
     * （36 = 主背包 27 + 快捷栏 9），不再用 {@code getContainerSize()}（＝41，多算 5 个装备槽）——
     * 否则假人身上穿的盔甲会被当成"散装"计入指纹，也会被 {@code fillBoxFromInventory} 当材料装箱。
     */
    private int looseTotalOnBot() {
        var inv = getFakePlayer().getInventory();
        int n = 0;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack st = inv.getItem(i);
            if (!st.isEmpty() && !st.is(net.minecraft.tags.ItemTags.SHULKER_BOXES)) {
                n++;
            }
        }
        return n;
    }

    // =========================================================
    // 阶段1：FETCH —— 逐物品跨箱取齐
    // =========================================================

    private boolean tickFetch() {
        // 还有子动作在跑 → 继续驱动它
        if (currentFetcher != null) {
            boolean more = currentFetcher.tick();
            if (more) {
                return true;
            }
            // 该物品本轮结束（取够 / 无可取 / 因容量暂停）：累计实际取到的数量
            int got = currentFetcher.withdrawnCount();
            boolean paused = currentFetcher.pausedByCapacity();
            fetchedTotalItems += got;
            if (got > 0 && currentFetchItemId != null) {
                fetchedSoFar.merge(currentFetchItemId, got, Integer::sum);
            }
            FLTAdditionMod.LOGGER.info("[FLT] 备货 FETCH 物品 {} 取 {} 个（累计 {}）{}",
                    currentFetchItemId, got, fetchedTotalItems, paused ? "，背包将满已暂停" : "");
            currentFetcher = null;
            currentFetchItemId = null;
            // [VERSION] 2026-09-29：因容量暂停 → 需求下标回退一位，让该物品剩下的缺口在
            // 装箱腾出空间后继续取；否则 demandIndex 已前移，这批缺口会被永久跳过。
            if (paused) {
                demandIndex = Math.max(0, demandIndex - 1);
            }
        }
        // [VERSION] 2026-09-28：背包即将塞满 → 先打包腾空间，再取下一件。
        // 为什么放在"一个物品取完之后"而不是取货中途：打包要跑去仓库取空盒，
        // 而取货子动作正开着箱子菜单，中途插进去会打断它的状态机。物品间隙最安全。
        if (shouldPackBeforeFetchMore()) {
            FLTAdditionMod.LOGGER.info("[FLT] 备货 FETCH 背包仅剩 {} 格，先打包腾空间（还有 {} 项未取）",
                    freeSlotsOnBot(), demands.size() - demandIndex);
            packAfterFetch = true;
            phase = PHASE_PACK;
            return true;
        }
        return advanceFetch();
    }

    /**
     * 是否该在继续取货前先打包（FETCH 途中的容量治理）。
     *
     * <p>四个条件全满足才打包：本动作还没确认"装不了盒"、还有需求没取（否则收尾打包自会处理）、
     * 背包里有散装可装、背包剩余格数 &le; {@link #PACK_TRIGGER_FREE_SLOTS}。
     */
    private boolean shouldPackBeforeFetchMore() {
        // 🔴 背包一格不剩：哪怕"已知装不了盒"也必须转 PACK —— PACK 里还有
        // 「把成品盒挪进末影箱腾格」这条路，是继续取货的唯一出路。
        // （2026-09-29 调整判断顺序：原先把 packUnavailable 判在最前面，于是
        //   "无空盒 + 背包满"会一路空转到 600 刻看门狗才被救场。）
        if (freeSlotsOnBot() <= 0) {
            return true;
        }
        if (packUnavailable) {
            return false;   // 还没满、且已知打不了包 → 不必反复白跑
        }
        if (demandIndex >= demands.size()) {
            return false;   // 需求已取过一遍 → 交给收尾打包
        }
        if (!hasPackableLoose()) {
            return false;   // 还有空位、又没散装 → 打包无从下手
        }
        return freeSlotsOnBot() <= PACK_TRIGGER_FREE_SLOTS;
    }

    /**
     * 假人背包里<b>能放普通物品</b>的空槽数（主背包 27 + 快捷栏 9 = 36 格；不含盔甲 / 副手等装备槽）。
     *
     * <p>[VERSION] 2026-09-30 口径修正（本次故障的关键修复之一）：原实现遍历
     * {@code Inventory.getContainerSize()}（＝41，比 36 多出 5 个<b>装备槽</b>），把放不了潜影盒的
     * 装备槽也算成"可用余量"。这正是 PACK 阶段整段空转的直接推手 —— 实测日志
     * 「背包余 7 格」却一个空盒都放不进去（那 7 格里就有 5 格是装备槽），于是一遍遍跑去仓库取盒、
     * {@code quickMoveStack} 一遍遍搬不动，最后只能散装交付。
     *
     * <p>现统一委托 {@link RestockCapacity#freeInventorySlots}，全项目只保留一个口径。
     */
    private int freeSlotsOnBot() {
        return RestockCapacity.freeInventorySlots(getFakePlayer());
    }

    /**
     * 背包无空格时想办法腾出至少 1 格 —— 把一格东西挪进假人末影箱。
     *
     * <p>挑选顺序（尽量少干扰后续流程）：
     * <ol>
     *   <li><b>已装好的成品盒</b>：占 1 格、内容多，挪走最划算，而且它本来就是最终交付物；</li>
     *   <li>否则挑<b>数量最少</b>的一格散装：它对"凑满一盒"的贡献最小，损失最小。</li>
     * </ol>
     *
     * <p>⚠️ 为什么不做"部分搬运"：{@code SimpleContainer.addItem} 返回的是"没放下的剩余"，
     * 它可能只放下一部分；此时若清空原槽会丢物、若保留原槽会复制 —— 两种写法都是错的。
     * 详见 {@link EnderChestUtil#tryStashSlot}。这里只接受"整格存入"。
     *
     * <p>玩家取货方式：GCA 规则 {@code openFakePlayerEnderChest}（「假人末影箱」）开启后，
     * 玩家可自行打开假人末影箱取走这些暂存物；就算假人下线，末影箱内容也随 playerdata 保留。
     *
     * @return true = 已腾出至少 1 格
     */
    private boolean stashSomethingToEnderChest() {
        // 一次尽量多腾几格（见 STASH_TARGET_FREE_SLOTS）：每次只腾 1 格会来回很多趟。
        boolean any = false;
        while (freeSlotsOnBot() < STASH_TARGET_FREE_SLOTS) {
            if (!stashOneFinishedBox()) {
                break;   // 没有成品盒可挪 / 末影箱放不下 → 下面走散装兜底
            }
            any = true;
        }
        if (any) {
            return true;
        }
        // [VERSION] 2026-09-30 新增兜底：连一个成品盒都没有时（典型场景：首批取货就把背包塞满，
        //   还没来得及装盒 —— 正是 2026-09-29 报障的情形），旧实现直接放弃 → 背包满且装不了盒，
        //   只能截断交付。现改为把散装也挪进末影箱腾格：代价是玩家要开末影箱一格一格掏，
        //   但好过"备货断在半路"；腾出的位置随后会被 PACK 用来取空盒、装盒。
        //   注意只在"一格都不剩"时才动散装（比成品盒路径更保守），避免平时把散装推进末影箱。
        while (freeSlotsOnBot() <= 0) {
            if (!stashOneLooseStack()) {
                break;
            }
            any = true;
        }
        return any;
    }

    /**
     * 把一个<b>成品盒</b>（有内容的潜影盒）整格存入假人末影箱，腾出 1 格。
     *
     * <p>为什么只挪成品盒、<b>不</b>挪散装（[VERSION] 2026-09-29 调整）：散装是"原料"——
     * 塞进末影箱既没有压缩、又要玩家开末影箱一格一格掏，还会极快把它填满。正确做法是
     * 把这批货直接交付（散装送到玩家面前）并登记续备（见 {@code tickPack} case 0）。
     * 末影箱从此只承担"成品盒暂存"这一个职责 —— 语义单一，也不会被散装挤爆。
     *
     * <p>⚠️ 必须先 {@code canAddItem} 预判（见 {@link EnderChestUtil#tryStashSlot}）：
     * 部分存入会导致"清原槽=丢物 / 留原槽=复制"二选一的错误。
     *
     * @return true = 已腾出 1 格；false = 没有成品盒可挪 / 末影箱放不下整格
     */
    private boolean stashOneFinishedBox() {
        ServerPlayer bot = getFakePlayer();
        var inv = bot.getInventory();
        // [VERSION] 2026-09-30 口径修正：只扫主背包 + 快捷栏 —— 装备槽里不会有"成品盒"，
        // 用 getContainerSize() 会白扫 5 格；更重要的是索引语义要和 RestockCapacity 的判据一致。
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack st = inv.getItem(i);
            if (st.isEmpty() || !st.is(net.minecraft.tags.ItemTags.SHULKER_BOXES) || isEmptyShulker(st)) {
                continue;
            }
            if (EnderChestUtil.tryStashSlot(bot, i)) {
                stashedToEnderChest++;
                FLTAdditionMod.LOGGER.info("[FLT] 备货 PACK 背包已满：成品盒 {} ×{} 存入假人末影箱腾格（末影箱已用 {} 格）",
                        BuiltInRegistries.ITEM.getKey(st.getItem()), st.getCount(),
                        EnderChestUtil.occupiedSlots(bot));
                return true;
            }
        }
        return false;
    }

    /**
     * 把假人背包里「数量最少的一格散装」整格存入末影箱，腾出 1 格 —— <b>最后手段</b>。
     *
     * <p>什么时候才会走到这里：背包一格不剩、且<b>一格成品盒都没有</b>可挪。典型场景就是
     * "首批取货就把 36 格塞满、还没来得及装盒"（2026-09-29 报障的原样）。此时不腾格就彻底
     * 装不了盒，只能截断交付 —— 宁可把散装暂存末影箱、让玩家多开一次箱子。
     *
     * <p>为什么挑「数量最少」的那格：它对凑满一盒的贡献最小，挪走损失最小。
     *
     * <p>⚠️ 必须<b>整格</b>存入（见 {@link EnderChestUtil#tryStashSlot}）：部分存入会导致
     * "清原槽=丢物 / 留原槽=复制"二选一的错误。
     *
     * @return true = 已腾出 1 格；false = 无散装可挪 / 末影箱放不下整格
     */
    private boolean stashOneLooseStack() {
        ServerPlayer bot = getFakePlayer();
        var inv = bot.getInventory();
        int bestSlot = -1;
        int bestCount = Integer.MAX_VALUE;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack st = inv.getItem(i);
            if (st.isEmpty() || st.is(net.minecraft.tags.ItemTags.SHULKER_BOXES)) {
                continue;   // 空槽 / 潜影盒都不动（空盒是装箱工具，成品盒另有优先路径）
            }
            if (st.getCount() < bestCount) {
                bestCount = st.getCount();
                bestSlot = i;
            }
        }
        if (bestSlot < 0) {
            return false;
        }
        // 先留副本：tryStashSlot 成功后原槽会被清空，日志里就取不到物品名了
        ItemStack moving = inv.getItem(bestSlot).copy();
        if (!EnderChestUtil.tryStashSlot(bot, bestSlot)) {
            return false;   // 末影箱放不下整格 → 交调用方决定（通常是截断交付）
        }
        stashedToEnderChest++;
        FLTAdditionMod.LOGGER.info("[FLT] 备货 PACK 背包已满且无成品盒：散装 {} ×{} 存入假人末影箱腾格（末影箱已用 {} 格）",
                BuiltInRegistries.ITEM.getKey(moving.getItem()), moving.getCount(),
                EnderChestUtil.occupiedSlots(bot));
        return true;
    }

    /**
     * 收集「还没处理过的需求」——下标 {@code demandIndex} 起的项。
     *
     * <p>为什么从 {@code demandIndex} 起：FETCH 按顺序逐项取货，每项在派单前就把
     * {@code demandIndex} 前移，所以「下标 &lt; demandIndex」的都是已经动过的（取到多少算多少），
     * 而「下标 &ge; demandIndex」的还没碰过。
     *
     * <p>用途：因末影箱满而中止装箱时，把剩余需求登记成「待续备货」，玩家取走当前这批后
     * 自动接着备，而不是把需求静默丢掉。已交付的货在玩家手上、不在假人身上，所以下一批
     * 直接按完整需求取即可，不会重复。
     *
     * @return 剩余需求；没有则返回空 Map
     */
    private Map<Identifier, Integer> unprocessedDemands() {
        Map<Identifier, Integer> rest = new LinkedHashMap<>();
        for (Map.Entry<Identifier, Integer> e : demands) {
            // 缺口 = 需求 − 本任务已取到量 —— 已取到的那部分即将随本次交付交到玩家手上，
            // 续备只该补差额，用"从 demandIndex 起的完整需求"会让玩家收到重复的一批
            // （[VERSION] 2026-09-29 修正）。
            int left = e.getValue() - fetchedSoFar.getOrDefault(e.getKey(), 0);
            if (left > 0) {
                rest.put(e.getKey(), left);
            }
        }
        return rest;
    }

    /**
     * 结束装箱，决定下一步去向。
     *
     * <p>回 FETCH 继续取货的条件：本轮是「FETCH 途中的临时打包」（{@code packAfterFetch}），
     * <b>且背包确实腾出了空格</b>；否则（收尾打包 / 背包仍满）→ 进入交付。
     *
     * <p>⚠️ 为什么用"背包有没有空格"而不是"缺不缺空盒"判断：仓库缺空盒但背包还有空位时，
     * 继续取货仍有意义（散装取回来也能交付），不该提前收工；而背包真满时继续取只会处处失败，
     * 白跑一趟不如直接交付。这同时堵住了「背包满 → 转 PACK → 腾不出格 → 回 FETCH → 又满」的空转；
     * "缺盒时反复转 PACK" 则由 {@code packUnavailable} 挡住。
     */
    private boolean finishPack() {
        // [VERSION] 2026-09-30：只在"收尾打包"时清理多余的备用空盒（中途打包是为了腾格、
        //   接下来还要继续用盒，不能清）。见 KEEP_SPARE_BOXES。
        if (!packAfterFetch) {
            relieveSurplusBoxes();
        }
        boolean goFetch = packAfterFetch && freeSlotsOnBot() > 0;
        packAfterFetch = false;
        if (goFetch) {
            phase = PHASE_FETCH;
            FLTAdditionMod.LOGGER.info("[FLT] 备货 PACK 临时打包结束，回到 FETCH 继续取货");
        } else {
            phase = PHASE_DELIVER;
        }
        return true;
    }

    /**
     * 把假人身上<b>超出 {@link #KEEP_SPARE_BOXES} 的备用空盒</b>转入末影箱，腾出背包格。
     *
     * <p>[VERSION] 2026-09-30 新增（修「多拿了好多空盒」）：空盒是"工具"，
     * {@code prepareBotForNewTask} 会跨任务保留它、也不会随交付给玩家，所以一旦取多了就会
     * 一直占着背包，挤占后续取货的空间。这里在装箱收尾时把多余的挪进末影箱
     * （末影箱满或放不下就原样留着，绝不丢物）。
     */
    private void relieveSurplusBoxes() {
        ServerPlayer bot = getFakePlayer();
        var inv = bot.getInventory();
        int kept = 0;    // 已保留的"个数"（不是格数！见下面注释）
        int moved = 0;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack st = inv.getItem(i);
            if (!isEmptyShulker(st)) {
                continue;
            }
            int n = st.getCount();
            // ── 按【个数】保留，不能按格数 ──
            // ⚠️ 自查发现的坑：本方法最初写的是"数到第 KEEP_SPARE_BOXES 格就不转"，
            //    但潜影盒可堆叠时 1 格 = 1 组（最多 64 个）→ "保留 3 格"实际是保留最多 192 个，
            //    等于没清理。现在一律按个数算。
            if (kept + n <= KEEP_SPARE_BOXES) {
                kept += n;      // 这一格整个留着
                continue;
            }
            if (kept >= KEEP_SPARE_BOXES) {
                // 已经留够了 → 这一格整个转走
                if (EnderChestUtil.tryStashSlot(bot, i)) {
                    moved += n;
                }
                continue;
            }
            // 边界情形：这一格里"一部分要留、一部分要转"→ 拆出多余的那几个
            int surplus = kept + n - KEEP_SPARE_BOXES;
            ItemStack extra = st.split(surplus);
            kept = KEEP_SPARE_BOXES;
            if (!EnderChestUtil.tryStash(bot, extra)) {
                // ⚠️ 末影箱放不下 → 原样还回原槽（tryStash 不会改入参，所以 grow 回去是安全的）
                st.grow(extra.getCount());
            } else {
                moved += extra.getCount();
            }
            inv.setItem(i, st.isEmpty() ? ItemStack.EMPTY : st);
        }
        if (moved > 0) {
            FLTAdditionMod.LOGGER.info("[FLT] 备货 备用空盒多余，{} 个转入假人末影箱腾格（末影箱已用 {} 格）",
                    moved, EnderChestUtil.occupiedSlots(bot));
        }
    }

    /** 当前正在取的目标物品 ID（日志 / 缺口统计用） */
    private Identifier currentFetchItemId;

    private boolean advanceFetch() {
        for (;;) {
            if (demandIndex >= demands.size()) {
                FLTAdditionMod.LOGGER.info("[FLT] 备货 FETCH 完成，进入 PACK（共取 {} 个材料）", fetchedTotalItems);
                phase = PHASE_PACK;
                return true;   // 进入装箱
            }
            Map.Entry<Identifier, Integer> de = demands.get(demandIndex);
            Identifier itemId = de.getKey();
            int need = de.getValue();
            // 缺口 = 需求 − max(本任务已取到量, 背包现有散装)：见 fetchedSoFar 注释。
            // 取 max 是为了兼顾两种情况 —— 货还在背包里（两者相等）、货已被装进盒
            // （countItemOnBot 归零，只能靠 fetchedSoFar 记账）。
            int have = Math.max(fetchedSoFar.getOrDefault(itemId, 0), countItemOnBot(itemId));
            int remaining = need - have;
            demandIndex++;
            if (remaining <= 0) {
                continue;
            }
            Item item = BuiltInRegistries.ITEM.getValue(itemId);
            if (item == null) {
                continue;
            }
            // 复用已验证的跨箱取货动作（做法参考 LMS 的 getItemFromSlot 思路：逐目标槽、同箱连收、换箱分tick）——
            // 不再手写精简版状态机，避免"传送后位置未生效 / 菜单残留"等取 0 个的 bug（2026-09-23）。
            List<MultiContainerWithdrawAction.TargetSlot> targets =
                    MultiContainerWithdrawAction.collectTargets(server(), itemId, item, false, remaining);
            if (targets.isEmpty()) {
                FLTAdditionMod.LOGGER.info("[FLT] 备货 FETCH 仓库无 {}，跳过", itemId);
                continue;   // 仓库没货 → 跳过该物品
            }
            FLTAdditionMod.LOGGER.info("[FLT] 备货 FETCH 委托取货 {} 需求 {}，目标槽 {} 处",
                    itemId, remaining, targets.size());
            // 容量闸门：背包剩 4 格就暂停取货、交回本类去装箱（装一盒净减 26 格）。
            //
            // ⚠️ 只在「确实还能压缩」时才设闸门 —— 否则会形成死循环（2026-09-29 自查发现）：
            //    fetcher 一开跑就因格数不足暂停 → 回本类转 PACK → 但背包里没有散装可装盒
            //    → PACK 立即 finishPack 又回 FETCH → 再派单 → 再立即暂停……
            //    该循环每 tick 都跨 FETCH/PACK 两个阶段，看门狗的"进展指纹"（含 phase）
            //    随之每刻变化 → **永远不会触发**，假人静默卡死且不报错。
            // 两种"压缩不了"的情形都不设闸门（交回 FETCH 正常取货，满了走 PACK 的腾格/交付分支）：
            //    ① 已确认打不了包（仓库没有空盒）；② 背包里没有可装箱的散装（全是成品盒/空盒）。
            // [VERSION] 2026-09-30 判据修正（本次故障的关键修复之一）：
            //   原判据 `!packUnavailable && hasPackableLoose()` 看的是"**此刻**背包里有没有散装"，
            //   而每次任务开工时背包刚被清空 → hasPackableLoose() = false → 闸门被关掉，
            //   于是**第一件货全程无人管容量**，一路取到主背包 36 格全满；等轮到 PACK 时已无格放
            //   空盒（还要留 1 格给搬运），只能反复"去仓库取盒却搬不动"地空转，最后散装交付 ——
            //   这就是「假人背包满了、不能自动装盒」的根因。
            //
            //   正确判据：只要"背包里已经有散装"或"还有富余格数（> 4）"就设闸门 ——
            //   后者成立时，取到剩 4 格的那一刻背包里必然已装入本次的散装，PACK 能真的压缩。
            //   只有在"无散装 且 格数已 ≤ 4"（背包被成品盒/空盒占着）时才不设，以避开
            //   fetcher 一开跑就暂停 → PACK 无散装可压缩 → PACK/FETCH 每 tick 互跳、看门狗失效的老坑。
            boolean canCompress = !packUnavailable
                    && (hasPackableLoose() || freeSlotsOnBot() > PACK_TRIGGER_FREE_SLOTS);
            int stopFree = canCompress ? PACK_TRIGGER_FREE_SLOTS : 0;
            currentFetcher = new MultiContainerWithdrawAction(
                    getFakePlayer(), targets, item, itemId.toString(), remaining, false, null, stopFree);
            currentFetchItemId = itemId;
            return true;
        }
    }

    // =========================================================
    // 阶段2：PACK —— 反复取空盒装箱，直到散装清空或空盒用完
    // =========================================================

    private boolean tickPack() {
        switch (packSub) {
            case 0 -> {
                boolean full = freeSlotsOnBot() <= 0;
                // 既没散装可装、背包也不满 → 本轮装箱无事可做，收工
                if (!hasPackableLoose() && !full) {
                    FLTAdditionMod.LOGGER.debug("[FLT] 备货 PACK 无散装可装箱");
                    return finishPack();
                }
                // [VERSION] 2026-09-30 这条出口<b>必须</b>放在"就地装盒"之前（当天实测踩的坑）：
                //   它会拦两类情况 —— ① 取不到空盒（仓库没有 / 背包放不下）；② 收尾时散装攒不够
                //   一盒（见 MIN_BOX_FILL_SLOTS）。两者都已在别处置 packUnavailable。
                //   ⚠️ 若放在"背包里就有空盒 → packSub = 3"之后，就会死循环：背包里恰好还留着
                //   几个没用完的空盒 → 每 tick 都走 packSub = 3 → 每次装盒都被"攒不够一盒"挡回 →
                //   回到 case 0 又走 packSub = 3 …… 实测「攒不够一盒」日志刷了几百条、假人卡死在 PACK。
                if (packUnavailable) {
                    // 🔴 [VERSION] 2026-09-30 自查修复（这条出口会绕过"缺口登记"）：
                    //   本出口排在下面 `if (full)` 分支<b>之前</b>，而缺口登记（remainingDemands）
                    //   只写在那个分支里 —— 于是"仓库没有空盒 + 背包被塞满"这条最常见的提前收尾路径
                    //   会把尚未取到的需求<b>静默丢掉</b>（单假人路径没有 registerLeftoverAlways 兜底，
                    //   tickDeliver 也就无从登记）。这里补上同样的差额登记。
                    Map<Identifier, Integer> left = unprocessedDemands();
                    if (!left.isEmpty()) {
                        remainingDemands = left;
                    }
                    FLTAdditionMod.LOGGER.info(
                            "[FLT] 备货 PACK 已无可用空盒（packUnavailable），收尾交付（剩余 {} 项登记续备）",
                            left.size());
                    return finishPack();
                }
                // 背包里就有空盒 → 直接就地装一盒（装完是净减格），省一趟仓库往返
                if (hasPackableLoose() && hasEmptyShulkerInInventory()) {
                    packSub = 3;
                    return true;
                }
                // [VERSION] 2026-09-28：容量准备 —— 要有 1 格才拿得进空盒
                // （grabEmptyBox 靠 Inventory.add 把盒塞进背包；没格就塞不进，以前会卡死到看门狗）。
                // ⚠️ 这里<b>不</b>要求"有散装"：背包全是成品盒时同样要腾格，否则后续取货全塞不进来。
                if (full) {
                    if (!stashSomethingToEnderChest()) {
                        FLTAdditionMod.LOGGER.info("[FLT] 备货 PACK 背包已满且无成品盒可挪（末影箱已用 {} 格），改为交付当前批次",
                                EnderChestUtil.occupiedSlots(getFakePlayer()));
                        packUnavailable = true;
                        stashFullAbort = true;
                        // 背包满了又无处腾格 → 本次只能备到"背包容量"为止。
                        // 把还没处理的需求记下来，等玩家取走这批后自动续备下一批；
                        // 否则大额需求会在"背包满"处被永久截断（2026-09-28 修复）。
                        remainingDemands = unprocessedDemands();
                        return finishPack();
                    }
                    return true;   // 腾格完成；下一 tick 视情况去取盒或回 FETCH
                }
                packSub = 1;   // 有格、背包里没空盒 → 去仓库取一个
                return true;
            }
            case 1 -> {
                // 找空盒；没有 → 散装带回（并记住"装不了盒"，避免中途反复白跑）
                if (!locateEmptyBox()) {
                    FLTAdditionMod.LOGGER.info("[FLT] 备货 PACK 仓库无空盒，散装携带");
                    packUnavailable = true;
                    packNoBoxAbort = true;
                    return finishPack();
                }
                FLTAdditionMod.LOGGER.info("[FLT] 备货 PACK 找到空盒 {}/{}，开始取盒", emptyBoxSrcPos, emptyBoxSrcSlot);
                packSub = 2;
                return true;
            }
            case 2 -> {
                // 取空盒（多 tick）。完成后进入装盒
                if (!takeEmptyBoxOneTick()) {
                    packSub = 3;
                }
                return true;
            }
            default -> {
                // 3 = 用背包里第一个空盒装一盒，然后回到 0 判断是否还有散装
                packSub = 0;
                int before = looseTotalOnBot();
                int boxesBefore = packedBoxes;
                fillFirstEmptyBoxFromInventory();
                // [VERSION] 2026-09-30：只有真封了盒才打"装完一盒"。
                // 装不成的原因（攒不够一盒 / 取盒失败）各自已有更准确的日志，再补一句"装完一盒"
                // 会与它们自相矛盾 —— 旧实现就常出现「装完一盒（散装 36→36，累计 0 盒）」这种怪话。
                if (packedBoxes > boxesBefore) {
                    FLTAdditionMod.LOGGER.info("[FLT] 备货 PACK 装完一盒（散装 {}→{}，累计 {} 盒）",
                            before, looseTotalOnBot(), packedBoxes);
                }
                return true;
            }
        }
    }

    /**
     * 假人背包里是否已经有空潜影盒（可直接拿来装一盒）。
     *
     * <p>用途：背包满（或懒得跑一趟仓库）时，如果手上就有空盒，就地装一盒即可 ——
     * 装完是<b>净减格</b>，比"先去仓库取盒"省一次传送/开箱往返。
     */
    private boolean hasEmptyShulkerInInventory() {
        var inv = getFakePlayer().getInventory();
        // [VERSION] 2026-09-30：口径统一为主背包 + 快捷栏（装备槽里不可能出现空潜影盒）
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            if (isEmptyShulker(inv.getItem(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 假人背包是否还有"可装箱的散装物品"（非盒类的松散物品）。
     *
     * <p>[VERSION] 2026-09-30 口径修正：只扫主背包 + 快捷栏 —— 原先用
     * {@code getContainerSize()} 会把假人身上穿的<b>盔甲</b>误判成"可装箱散装"，
     * 进而被 {@code fillBoxFromInventory} 当材料装进盒里。
     */
    private boolean hasPackableLoose() {
        var inv = getFakePlayer().getInventory();
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack st = inv.getItem(i);
            if (!st.isEmpty() && !st.is(net.minecraft.tags.ItemTags.SHULKER_BOXES)) {
                return true;
            }
        }
        return false;
    }

    /** 扫假人背包第一个空潜影盒 → 用它装一盒散装；装完该盒留在背包（成品）。 */
    private void fillFirstEmptyBoxFromInventory() {
        var inv = getFakePlayer().getInventory();
        // 找第一个空盒槽（[VERSION] 2026-09-30：只扫主背包 + 快捷栏，装备槽里不会有空盒）
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack box = inv.getItem(i);
            if (!isEmptyShulker(box)) {
                continue;
            }
            int filled;
            if (box.getCount() > 1) {
                // ⚠️ 堆叠空盒（ORG「潜影盒可堆叠」让空盒 count>1）：绝不能把整组建一个 CONTAINER 填充，
                //    否则 64 个盒子一次性全带上内容外壳、而物品只按单盒扣 → 材料"复制成堆叠盒"（实测 bug）。
                //    用 split(1) 只拆出【单个】盒来装，剩余 count-1 个空盒留在背包槽，下次还能继续拆用。
                ItemStack single = box.split(1);
                filled = fillBoxFromInventory(single);
                // split(1) 后 box=原组剩余(count-1)、single=单个盒(可能已装料/可能空)
                if (single.isEmpty()) {
                    // 拆出来的盒没能装进物品（理论不该）：若原组也空了就清槽，否则放回一个空盒保持占位
                    if (box.isEmpty()) {
                        inv.setItem(i, ItemStack.EMPTY);
                    } else {
                        inv.setItem(i, box);   // 剩余空盒留槽
                    }
                } else {
                    // single 已带内容=成品盒，放回本槽；剩余空盒 box 若仍有则挤到别处
                    inv.setItem(i, single);
                    if (!box.isEmpty() && !inv.add(box)) {
                        // [VERSION] 2026-09-28 修复：以前只写 inv.add(box) 且忽略返回值 ——
                        // 背包满时 add 失败，这组剩余空盒就<b>凭空消失</b>了（既不在背包也不在槽里）。
                        // 现改为转存假人末影箱兜底；末影箱也放不下才记警告（至少不静默）。
                        if (!EnderChestUtil.tryStash(getFakePlayer(), box)) {
                            FLTAdditionMod.LOGGER.warn("[FLT] 备货 PACK 剩余空盒 {} ×{} 无处安放（背包与末影箱均满），已丢失",
                                    BuiltInRegistries.ITEM.getKey(box.getItem()), box.getCount());
                        }
                    }
                }
                if (filled > 0) {
                    packedBoxes++;
                }
                return;
            }
            // 本来就单个空盒：直接装
            filled = fillBoxFromInventory(box);
            if (filled > 0) {
                packedBoxes++;
            }
            return;
        }
    }

    /**
     * 扫描全部已登记容器，找第一个可拿的"空潜影盒"。
     * 找到后记录 {@code emptyBoxSrcPos / emptyBoxSrcSlot}。
     * @return true = 找到空盒来源；false = 仓库没有空盒
     */
    private boolean locateEmptyBox() {
        // [VERSION] 2026-09-29 优化：先复查**上次找到的那个容器**。空盒通常成堆放在同一个箱子里，
        // 而全量扫描要对每一个潜影盒展开内容判定（StackCounter.count），箱子多时开销可观。
        // 上一箱已经没盒了才回退到全扫。
        if (emptyBoxSrcPos != null && emptyBoxSrcLevel != null) {
            ServerLevel cached = server().getLevel(emptyBoxSrcLevel);
            if (cached != null && locateEmptyBoxAt(cached, emptyBoxSrcPos)) {
                return true;
            }
            emptyBoxSrcPos = null;   // 那箱已经空了 → 下面全扫
            emptyBoxSrcLevel = null;
        }
        for (Map.Entry<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>,
                Set<BlockPos>> entry : StockSource.all().entrySet()) {
            ServerLevel level = server().getLevel(entry.getKey());
            if (level == null) {
                continue;
            }
            Set<BlockPos> seen = new HashSet<>();
            for (BlockPos pos : entry.getValue()) {
                BlockPos canonical = ContainerGroup.canonical(level, pos);
                if (!seen.add(canonical)) {
                    continue;
                }
                if (locateEmptyBoxAt(level, canonical)) {
                    emptyBoxSrcLevel = entry.getKey();
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 在指定容器里找第一个空盒槽；找到就记入 {@code emptyBoxSrcPos/Slot} 并返回 true。
     *
     * @param level     容器所在世界
     * @param canonical 容器的规范坐标（双箱已归一）
     */
    private boolean locateEmptyBoxAt(ServerLevel level, BlockPos canonical) {
        Container c = ContainerGroup.fullContainerFor(level, canonical);
        if (c == null) {
            return false;
        }
        for (int i = 0; i < c.getContainerSize(); i++) {
            if (isEmptyShulker(c.getItem(i))) {
                emptyBoxSrcPos = canonical.immutable();
                emptyBoxSrcSlot = i;
                return true;
            }
        }
        return false;
    }

    /** 判断是否为"空潜影盒"（唯一实现见 {@link ShulkerBoxUtil#isEmptyShulker}） */
    private static boolean isEmptyShulker(ItemStack stack) {
        return ShulkerBoxUtil.isEmptyShulker(stack);
    }

    /**
     * 去空盒所在箱取空盒（传送 → 开箱 → quickMoveStack 取那格）。
     *
     * <p>与 {@code MultiContainerWithdrawAction.tick} 同构：三个阶段各自独占 tick，
     * 因为传送后位置要下一 tick 才生效，同 tick 开箱会失败。
     *
     * @return true = 还在进行；false = 已取到空盒（可能失败，失败只需继续走后续）
     */
    private boolean takeEmptyBoxOneTick() {
        // 阶段调度（换箱判断只看坐标，理由见 teleportToEmptyBox 注释）
        if (!emptyBoxSrcPos.equals(boxSrcOpenPos)) {
            return teleportToEmptyBox();
        }
        if (boxSrcMenu == null) {
            return openEmptyBoxSource();
        }
        return grabEmptyBox();
    }

    /**
     * 阶段①：传送到空盒所在箱（本 tick 只传送）。
     *
     * <p>⚠️ 换箱判断只靠坐标：不能写成 {@code boxSrcMenu == null || ...}——首次进入 menu 本就是 null，
     * 那样条件恒成立 → 每 tick 都重走"传送"、永远进不到 {@link #openEmptyBoxSource} 的开箱分支
     * （实测表现"找到空盒了却永远拿不到盒不装箱"，与 {@code MultiContainerWithdrawAction} 那个卡死同根）。
     *
     * @return 恒 true（下一 tick 开箱）
     */
    private boolean teleportToEmptyBox() {
        ServerPlayer bot = getFakePlayer();
        if (boxSrcMenu != null) {
            try {
                bot.closeContainer();
            } catch (Throwable ignored) {
            }
            boxSrcMenu = null;
        }
        boxSrcOpenPos = emptyBoxSrcPos.immutable();
        SafeSpotFinder.teleportToSafeSpot(bot, level(),
                emptyBoxSrcPos.getX() + 0.5D,
                emptyBoxSrcPos.getY() + 1.0D,   // 站箱顶上方一格；安全落点在其附近找（见 SafeSpotFinder）
                emptyBoxSrcPos.getZ() + 0.5D,
                SafeSpotFinder.CONTAINER_RANGE, "取空盒源 " + emptyBoxSrcPos);
        return true;
    }

    /**
     * 阶段②：打开空盒所在箱。
     *
     * @return true = 已开好（下一 tick 取盒）；false = 开箱失败，放弃这盒（回到 0 重来可能找到别处）
     */
    private boolean openEmptyBoxSource() {
        ServerPlayer bot = getFakePlayer();
        // ⚠️ 用方块状态的标准菜单入口（双箱合并成54格菜单），与 locateEmptyBox 用
        //    fullContainerFor 扫到的槽号一致；否则双箱后半(槽27~52)的空盒越界取不到。
        net.minecraft.world.MenuProvider provider =
                ContainerGroup.menuProviderFor(level(), emptyBoxSrcPos);
        if (provider == null) {
            return false;
        }
        OptionalInt id = bot.openMenu(provider);
        if (id.isEmpty() || bot.containerMenu == null) {
            return false;
        }
        boxSrcMenu = bot.containerMenu;
        Container full = ContainerGroup.fullContainerFor(level(), emptyBoxSrcPos);
        boxSrcSize = full != null ? full.getContainerSize() : 0;
        return true;
    }

    /**
     * 阶段③：从已开好的箱里取走那一格空盒，然后关箱。
     *
     * <p>堆叠空盒（ORG「潜影盒可堆叠」让空盒 count&gt;1）时一次只取【1 个】，剩余留在仓库槽，
     * 避免"整组被搬空"；配合 {@code fillFirstEmptyBoxFromInventory} 拆单盒填装。
     *
     * @return 恒 false（取盒流程结束）
     */
    private boolean grabEmptyBox() {
        ServerPlayer bot = getFakePlayer();
        if (boxSrcMenu == null) {
            return false;
        }
        // [VERSION] 2026-09-29 优化：同一次开箱里多取几个空盒，省掉每盒一趟「传送 + 开箱 + 槽位扫描」。
        // [VERSION] 2026-09-30 改为「按需取」：上限 = min(MAX_BOXES_PER_TRIP, estimateBoxesNeeded())，
        //   且堆叠空盒只取"还真差的那几个"—— 原来一律取满上限，在潜影盒可堆叠时一次就能搬走几组
        //   （实测：仅 1 次"4 槽"就够装 14 盒，余下的被跨任务保留、越囤越多）。
        // 用 quickMoveStack（原版转移规则）而不是手工 split/add：整组一起搬，堆叠空盒时
        // 一次就是几十个（只占 1 格），不可堆叠时每槽 1 个；背包没余量时它自然搬不动。
        int taken = 0;
        int failed = 0;   // 因背包放不下而搬不动的次数（用于判定"压缩彻底失败"）
        // [VERSION] 2026-09-30 按需取（修「多拿了好多空盒」）：
        //   ① 单次上限 = min(硬顶, 这批大约还差几个盒) —— 不再无脑取满；
        //   ② 堆叠空盒（ORG「潜影盒可堆叠」）时 quickMoveStack 会搬走【整组】，一组够装几十盒，
        //      所以取到第 1 槽就收手。
        final int takeLimit = Math.max(1, Math.min(MAX_BOXES_PER_TRIP, estimateBoxesNeeded()));
        for (int s = 0; s < boxSrcSize && s < boxSrcMenu.slots.size(); s++) {
            if (taken >= takeLimit) {
                break;
            }
            Slot slot = boxSrcMenu.slots.get(s);
            if (slot == null || !slot.hasItem() || !isEmptyShulker(slot.getItem())) {
                continue;   // 不是空盒（或已被别人拿走）
            }
            // [VERSION] 2026-09-30：余量判据改用"能放物品的槽"（主背包+快捷栏），
            // 并且不再用 `taken > 0 &&` 前置 —— 搬第一个盒子之前同样要确认放得下，
            // 否则第一格就搬不动时 taken 仍会 +1，日志虚报"取走 N 槽"。
            if (freeSlotsOnBot() <= 0) {
                failed++;
                break;      // 一个能放盒的槽都没有 → 这一箱别再试
            }
            ItemStack before = slot.getItem();
            int beforeCount = before == null ? 0 : before.getCount();
            int need = takeLimit - taken;   // 还差几个盒子

            // [VERSION] 2026-09-30 堆叠空盒「按需取」（这才是「多拿了好多空盒」的正解）：
            //   ORG「潜影盒可堆叠」下，一槽就是【一组】（可达 64 个），而 quickMoveStack 会整槽搬走。
            //   实测日志：仅"一次取走 4 槽空盒"就够装了 14 盒（4 槽 ≈ 几组 ≈ 上百个），
            //   用不完的空盒会被当"工具"跨任务保留、越囤越多。所以这里只取还差的那几个。
            if (beforeCount > need) {
                int part = SlotWithdrawUtil.takePartialFromSlot(boxSrcMenu, bot, s, need);
                if (part <= 0) {
                    failed++;
                    FLTAdditionMod.LOGGER.info("[FLT] 备货 PACK {} 槽 {} 的堆叠空盒搬不动（可放槽仅 {} 格），本箱放弃",
                            emptyBoxSrcPos, s, freeSlotsOnBot());
                    break;
                }
                taken += part;
                break;   // 已经凑够 takeLimit，收手
            }

            boxSrcMenu.quickMoveStack(bot, s);
            // ⚠️ 必须校验实际搬移量：原实现不看返回值就 taken++，日志会虚报"取走 6 槽空盒"，
            //    而背包里一个盒子都没有 → hasEmptyShulkerInInventory() 永远 false →
            //    下一轮又跑回来取盒 → PACK 阶段无限空转（2026-09-29 22:51 实测）。
            ItemStack after = slot.getItem();
            int afterCount = after == null ? 0 : after.getCount();
            if (beforeCount - afterCount <= 0) {
                failed++;
                FLTAdditionMod.LOGGER.info("[FLT] 备货 PACK {} 槽 {} 的空盒搬不动（可放槽仅 {} 格），本箱放弃",
                        emptyBoxSrcPos, s, freeSlotsOnBot());
                break;
            }
            taken++;
        }
        if (taken > 0) {
            // [VERSION] 2026-09-30 文案修正：taken 现在计的是【个数】而非槽数
            //   （堆叠空盒改为按需取之后，取 2 个可能只动到 1 个槽），写"槽"会误导排查。
            FLTAdditionMod.LOGGER.info("[FLT] 备货 PACK 从 {} 取走 {} 个空盒（背包余 {} 格）",
                    emptyBoxSrcPos, taken, freeSlotsOnBot());
        } else {
            FLTAdditionMod.LOGGER.info("[FLT] 备货 PACK {} 已无可取空盒（可能被别人拿走 / 背包放不下，失败 {} 次）",
                    emptyBoxSrcPos, failed);
        }
        try {
            bot.closeContainer();
        } catch (Throwable ignored) {
        }
        boxSrcMenu = null;
        // [VERSION] 2026-09-30：一格都没搬动 → 判定"压缩彻底失败"，置 packUnavailable。
        // 这是 PACK 死循环的直接出口 —— 不置位的话 tickPack case 0 会一直回来取盒。
        if (taken == 0 && failed > 0) {
            packUnavailable = true;
            packNoBoxAbort = true;
            FLTAdditionMod.LOGGER.info("[FLT] 备货 PACK 取空盒连续失败，标记为不可压缩（本次改为散装交付）");
        }
        return false;   // 完成取盒
    }

    /**
     * 这批货大约还需要几个空盒（向上取整，至少 2）。
     *
     * <p>[VERSION] 2026-09-30 新增（用户报障「多拿了好多空盒」）。取盒动作原本一律取满
     * {@link #MAX_BOXES_PER_TRIP} 槽，而"装完这批散装"根本用不了那么多；多出来的空盒会一直
     * 留在假人身上（{@code prepareBotForNewTask} 把空盒当"工具"保留、<b>不随交付给玩家</b>），
     * 于是每轮任务都多囤一点，背包被空盒越占越多。
     *
     * <p>估算口径：待装散装格数 ÷ 盒容量（27）向上取整，再 +1 作余量
     * （最后一盒可能因为 {@link #MIN_BOX_FILL_SLOTS} 门槛被拒，留一个兜底）。
     */
    private int estimateBoxesNeeded() {
        int per = ShulkerBoxBlockEntity.CONTAINER_SIZE;
        int looseSlots = looseTotalOnBot();
        return Math.max(1, (looseSlots + per - 1) / per) + 1;
    }

    /**
     * 把一个空盒<b>尽量填满</b>（每格 = 对应物品的堆叠上限，最多 {@code 27} 格）。
     *
     * <p>[VERSION] 2026-09-28：改为<b>「填满优先」的多物品装盒</b>。
     * 以前只挑"总数最多"的<b>一种</b>物品去装满 27 格 —— 该物品总量不足时（例如 100 个石头 = 2 格）
     * 剩下 25 格全部空着，而其余散装还得再占一个盒，盒数与背包格数双双被浪费
     * （用户报障："一种物品打包，不能充分利用空盒空间"）。
     * <p>现在按"散装总数从多到少"依次装：一种物品装满它的整数个堆后，若盒内还有空位就接着装下一种，
     * 直到 27 格填满或没有散装可取。每种物品内部仍按"整数堆"拆分，保证每格不超堆叠上限。
     *
     * <p>⚠️ 副作用（已知、可接受）：成品盒变成"混装盒"（盒内 ≥2 种物品）时，
     * {@code StackCounter.count} 会判其为混装并<b>不计入库存统计</b>（该口径是"宁可少算，
     * 保证统计到的 = 能取到的"）。但取货侧不受影响 —— {@code countTargetInsideBox} 对杂盒照样能拆；
     * 且本流程产出的盒是直接交给玩家的最终产物，一般不回存仓库。
     *
     * <p>装不满一整盒也能装（只要某类物品总数 ≥1）。从假人背包逐格抠这些物品写入盒内
     * （{@code ItemContainerContents.fromItems}），被抠光的槽置空；装完的盒（已带内容）留在假人背包作为成品。
     *
     * <p>⚠️ <b>每格上限必须取该物品自己的堆叠上限，不能写死 64</b>（[VERSION] 2026-09-24 修复）：
     * 石头是 64，但雪球是 16、工具/盔甲是 1。若按 64 造出 {@code count > maxStackSize} 的栈，
     * {@code fromItems → ItemStackTemplate.create() → validate()} 会走
     * {@code ItemStack.validateStrict}（判 {@code count > getMaxStackSize()}）→ 该格被
     * <b>替换成 {@code ItemStack.EMPTY}</b>，而且 {@code create()} 的失败分支只有一条
     * {@code LOGGER.warn}、调用方拿不到任何信号 → <b>物品静默凭空消失</b>。
     * （字节码实证：{@code ItemStackTemplate.create()V} 调 {@code validate()}；
     * {@code validate()} 调 {@code ItemStack.validateStrict}，失败即 {@code ItemStack.EMPTY}）
     *
     * @param emptyBox 假人背包里的一个空潜影盒（会被填充内容）
     * @return 装进盒的物品个数（0 = 没装成，理论上不该发生）
     */
    private int fillBoxFromInventory(ItemStack emptyBox) {
        if (emptyBox == null || emptyBox.isEmpty() || !isEmptyShulker(emptyBox)) {
            return 0;
        }
        // ⚠️ 防御：只能给【单个】空盒填内容。若误传堆叠盒(count>1)，整组一起改 CONTAINER = 复制 bug，
        //    直接拒绝（调用方 fillFirstEmptyBoxFromInventory 已保证传 single count==1 或本来就是单盒）。
        if (emptyBox.getCount() != 1) {
            return 0;
        }
        var inv = getFakePlayer().getInventory();

        // 按"散装总数从多到少"的优先级装盒。
        //
        // 【同种物品优先同盒】就靠这个循环次序保证：外层按物品降序、内层把该物品的<b>所有堆</b>
        // 连续塞进当前盒，装到盒满才换下一种物品。所以同一种物品绝不会"这一盒放几堆、那一盒再放几堆"，
        // 唯一会跨盒的理由是"这一盒确实装不下"。
        List<Map.Entry<Identifier, Integer>> order = looseTotalsDescending(inv);
        if (order.isEmpty()) {
            return 0;   // 没有可装箱的散装
        }

        int maxBoxSize = ShulkerBoxBlockEntity.CONTAINER_SIZE;

        // ---- 第一遍：只做规划，一个字节都不动背包 ----
        // 为什么必须先规划再落实：下面有"攒够一盒才封盒"的判断（见 MIN_BOX_FILL_SLOTS），
        // 若像旧实现那样边算边从背包扣，一旦判定"不够一盒"就再也回滚不了。
        List<ItemStack> filled = new ArrayList<>();               // 盒内 27 格
        Map<Integer, Integer> toDeduct = new LinkedHashMap<>();   // 背包槽索引 → 该槽要扣掉的数量
        for (Map.Entry<Identifier, Integer> entry : order) {
            if (filled.size() >= maxBoxSize) {
                break;   // 盒已满，后续物品这一盒装不下
            }
            Item pickItem = BuiltInRegistries.ITEM.getValue(entry.getKey());
            if (pickItem == null) {
                continue;
            }
            for (int i = 0; i < Inventory.INVENTORY_SIZE && filled.size() < maxBoxSize; i++) {
                ItemStack st = inv.getItem(i);
                if (st.isEmpty() || !st.is(pickItem)) {
                    continue;
                }
                int avail = st.getCount() - toDeduct.getOrDefault(i, 0);
                if (avail <= 0) {
                    continue;
                }
                // ⚠️ 每格容量取<b>实际栈</b>的堆叠上限：getMaxStackSize() 是 ItemInstance 的 default 方法
                //    （读 MAX_STACK_SIZE 数据组件），而 Item 自身只有 getDefaultMaxStackSize()，
                //    拿不到"数据包/模组改过堆叠上限"的语义 → 必须从 ItemStack 问。
                //    一个盒槽只放"一种物品的一个 ≤堆叠上限 小堆"。
                int limit = Math.max(1, st.getMaxStackSize());
                while (avail > 0 && filled.size() < maxBoxSize) {
                    int chunk = Math.min(avail, limit);
                    filled.add(st.copyWithCount(chunk));
                    avail -= chunk;
                    toDeduct.merge(i, chunk, Integer::sum);
                }
            }
        }

        if (filled.isEmpty()) {
            return 0;
        }

        // ---- 攒够一盒才封盒（[VERSION] 2026-09-30 新增）----
        // 见 MIN_BOX_FILL_SLOTS 的注释：旧行为"还有散装就再装一盒"会让最后一盒永远是零头，
        // 且零头盒把各种物品的剩余堆各收一点，同种物品因此被拆散到多个盒子。
        if (filled.size() < MIN_BOX_FILL_SLOTS) {
            // ⚠️ 中途打包（packAfterFetch）例外：那一趟的唯一目的是腾格子，一旦拒绝就再也腾不出空间，
            //    所以即使攒不够也照装。只有收尾打包（需求已全部取完）才允许判定"这盒不装"。
            if (!packAfterFetch) {
                FLTAdditionMod.LOGGER.info("[FLT] 备货 PACK 攒不够一盒（只能填 {} 格 < {} 格），散装留给交付",
                        filled.size(), MIN_BOX_FILL_SLOTS);
                // 置位以便 tickPack 走收尾出口，避免反复"取盒 → 填不满 → 再取盒"的空转
                packUnavailable = true;
                return 0;   // ★ 背包未被改动，散装原样留在假人身上随交付送走
            }
        }

        // ---- 第二遍：落实扣减（到这里才真正改背包）----
        for (Map.Entry<Integer, Integer> e : toDeduct.entrySet()) {
            int slot = e.getKey();
            int cut = e.getValue();
            ItemStack st = inv.getItem(slot);
            if (st.isEmpty()) {
                continue;
            }
            st.shrink(cut);
            inv.setItem(slot, st.isEmpty() ? ItemStack.EMPTY : st);
        }

        // ---- 写入盒内容 ----
        int got = 0;
        for (ItemStack s : filled) {
            got += s.getCount();
        }
        emptyBox.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(filled));
        return got;
    }

    /**
     * 列出假人背包里的散装物品及其总数，<b>按总数从多到少</b>排序（＝装盒的优先级顺序）。
     *
     * <p>只统计散装（跳过潜影盒本身：空盒要留着当容器、成品盒是已完成产出，都不再被拆开重装）。
     * 数量多的排前面 —— 装一盒时先装满大宗的，剩下的零碎物品填缝，最大化利用盒内空间。
     *
     * @return 物品 ID → 总数的降序列表；背包里没有散装物品时返回空列表
     */
    private List<Map.Entry<Identifier, Integer>> looseTotalsDescending(Inventory inv) {
        java.util.Map<Identifier, Integer> looseTotals = new java.util.LinkedHashMap<>();
        // [VERSION] 2026-09-30 口径修正：只统计主背包 + 快捷栏，避免把假人身上的盔甲当材料装进盒
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack st = inv.getItem(i);
            if (st.isEmpty() || st.is(net.minecraft.tags.ItemTags.SHULKER_BOXES)) {
                continue;
            }
            Identifier id = BuiltInRegistries.ITEM.getKey(st.getItem());
            looseTotals.merge(id, st.getCount(), Integer::sum);
        }
        List<Map.Entry<Identifier, Integer>> order = new ArrayList<>(looseTotals.entrySet());
        order.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        return order;
    }

    /**
     * 从背包某一格 {@code stack} 里分出若干个 ≤{@code slotLimit} 的小堆，追加到 {@code filled}
     * （直到该格被抠空、或 {@code filled} 达到 {@code maxBoxSize} 格）。
     *
     * <p>同类物品超过一个堆叠上限时会分成多格（例如 100 个石头 = 64 + 36 两格）。
     * 被抠的 {@code stack} 会被 {@code shrink} 成剩余数量（抠光即为空堆）。
     *
     * @param filled    盒内格列表（原地追加）
     * @param stack     被抠的背包格（原地扣减）
     * @param slotLimit 每格容量上限（该物品的堆叠上限，<b>不能写死 64</b>）
     * @param maxBoxSize 盒的格数上限（27）
     */
    /*
     * 旧的 fillOneBoxSlot(filled, stack, slotLimit, maxBoxSize) 已于 2026-09-30 删除。
     * 它"边算边从背包抠"的写法无法支持"攒不够一盒就不封盒"的回滚（抠完就还不回去了），
     * 其逻辑已内联进 {@link #fillBoxFromInventory} 的第一遍规划（记账在 toDeduct 里）。
     */

    // =========================================================
    // 阶段3：DELIVER —— 送回玩家
    // =========================================================

    private boolean tickDeliver() {
        boolean delivered = RestockScheduler.deliverToPlayer(server(), playerId, getFakePlayer());
        // [VERSION] 2026-09-28：背包塞不下时成品盒会暂存进假人末影箱 → 必须告诉玩家去哪里取，
        // 否则玩家只看到背包那点货，以为备少了。
        String stashHint = stashedToEnderChest > 0
                ? "，另有 " + stashedToEnderChest + " 格已暂存假人末影箱"
                        + "（需开启 GCA 规则「假人末影箱」/openFakePlayerEnderChest 才能打开取走）"
                : "";
        // [VERSION] 2026-09-28：末影箱相关的三段提示。
        // ① 因末影箱满而提前收尾 → 必须说清，否则玩家只看到"备货完成"，不知道还有需求没备；
        // ② 未满但接近满 → 提前预警，避免下次直接撞上限；
        // ③ 还有剩余需求 → 登记「待续备货」，玩家取走这批后自动继续。
        var ec = EnderChestUtil.container(getFakePlayer());
        int ecUsed = EnderChestUtil.occupiedSlots(getFakePlayer());
        int ecTotal = ec == null ? 27 : ec.getContainerSize();
        // [VERSION] 2026-09-30 措辞修正：stashFullAbort 现在是"连散装都塞不进末影箱"才置位
        //（原先只会挪成品盒，那时说"没有可挪走的成品盒"是对的；现在有了 stashOneLooseStack
        //  这条散装兜底，还能走到这里就说明末影箱已满/放不下），
        //  别再让玩家跑去清一个其实没满的箱子。
        String abortHint = stashFullAbort
                ? "。⚠️ 假人背包已满，备用缓冲（末影箱 " + ecUsed + "/" + ecTotal + "）也腾不出格子，本次只备到容量上限"
                : "";
        String ecWarn = !stashFullAbort && ecUsed >= ENDER_CHEST_WARN_SLOTS
                ? "。提示：假人末影箱已用 " + ecUsed + "/" + ecTotal + " 格，接近上限，记得取货"
                : "";
        String stalledHint = stalledAbort
                ? "。⚠️ 备货中途卡住（连续 30 秒无进展），已按当前进度提前交付"
                : "";
        // [VERSION] 2026-09-29 并行备货：无条件登记缺口（见 registerLeftoverAlways 的说明）。
        // 放在这里（交付时）而不是 onStop：此刻 fetchedSoFar 已定格，算出来的才是最终缺口。
        if (registerLeftoverAlways) {
            Map<Identifier, Integer> left = unprocessedDemands();
            if (!left.isEmpty()) {
                remainingDemands = left;
            }
        }
        String batchHint = "";
        if (!remainingDemands.isEmpty()) {
            RestockScheduler.schedulePendingRestock(playerId, playerName, remainingDemands);
            batchHint = "。剩余 " + remainingDemands.size()
                    + " 项需求已排队，你把这批取走后会自动接着备";
        }
        // [VERSION] 2026-09-30 新增：把「没取到 / 没取够」的材料**逐项列给玩家**。
        //   旧行为只在服务端日志打「仓库无 X，跳过」，玩家聊天里只看到"共取 N 个材料"，
        //   既不知道少了哪些、也不知道少多少（用户报障：「根本不说少哪些材料」）。
        //   口径与「待续备货」一致 = 需求 − 本任务已取到（见 unprocessedDemands），
        //   所以"仓库里根本没有"和"只够取一部分"都会列出来。
        Map<Identifier, Integer> shortfall = unprocessedDemands();
        String missHint = shortfall.isEmpty() ? ""
                : "。⚠️ 没取够 " + shortfall.size() + " 项：" + shortfallText(shortfall)
                        + "（仓库里没有或不够；补货后再点备货即可）";
        String msg;
        if (packedBoxes > 0) {
            msg = "备货完成：已打包 " + packedBoxes + " 盒，共取 " + fetchedTotalItems + " 个材料" + stashHint
                    + (delivered ? "，假人已送到你面前" : "（玩家离线，货在假人身上）");
        } else {
            // [VERSION] 2026-09-30 自查修复措辞：这里原本一律写"（仓库没有空盒，未装箱）"，
            //   但 packedBoxes==0 还有别的原因（散装攒不够一盒 / 背包满且腾不出格），
            //   会把玩家指去翻一个其实是好的空盒源。按真实原因分两种说法。
            String why = packNoBoxAbort ? "（仓库没有空盒，未装箱）" : "（未装箱，散装交付）";
            msg = "备货完成：共取 " + fetchedTotalItems + " 个材料" + stashHint
                    + (delivered ? "，假人已送到你面前" : "（玩家离线，货在假人身上）")
                    + why;
        }
        message = msg + abortHint + ecWarn + missHint + batchHint + stalledHint;
        return false;
    }

    /**
     * 把「没取够的材料」拼成一行短清单，给聊天消息用。
     *
     * <p>[VERSION] 2026-09-30 新增（用户报障：「根本不说少哪些材料」）。
     * 最多列 {@code MAX} 项，超出用"…等 N 项"收尾 —— 投影材料动辄几十种，
     * 全列会把聊天刷屏。名字用短名（{@code minecraft:} 前缀省略），非原版命名空间保留全名。
     */
    private static String shortfallText(Map<Identifier, Integer> shortfall) {
        final int MAX = 8;
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Map.Entry<Identifier, Integer> e : shortfall.entrySet()) {
            if (n >= MAX) {
                sb.append("…等 ").append(shortfall.size()).append(" 项");
                break;
            }
            if (n > 0) {
                sb.append('、');
            }
            Identifier id = e.getKey();
            sb.append("minecraft".equals(id.getNamespace()) ? id.getPath() : id.toString())
                    .append('×').append(e.getValue());
            n++;
        }
        return sb.toString();
    }

    // ============ ============ ============

    /**
     * 统计假人背包（主背包 + 快捷栏）里某物品的总数。
     *
     * <p>[VERSION] 2026-09-30 口径修正：只扫主背包 + 快捷栏，<b>不含盔甲 / 副手</b>。
     * 这条口径直接决定「还差多少」的缺口计算（{@code advanceFetch} 的 {@code need - have}）——
     * 用 {@code getContainerSize()} 时，假人装备栏里的同种物品会被算成"已经取到了"，导致少取。
     */
    private static int countItemOnBot(ServerPlayer bot, Item item) {
        var inventory = bot.getInventory();
        int total = 0;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private int countItemOnBot(Item item) {
        return countItemOnBot(getFakePlayer(), item);
    }

    private int countItemOnBot(Identifier itemId) {
        Item item = BuiltInRegistries.ITEM.getValue(itemId);
        return item == null ? 0 : countItemOnBot(item);
    }

    @Override
    public void onStop() {
        currentFetcher = null;
        if (getFakePlayer().containerMenu != null) {
            try {
                getFakePlayer().closeContainer();
            } catch (Throwable ignored) {
            }
        }
        if (reporter != null) {
            String reason = message != null ? message
                    : "备货已停止（" + (packedBoxes > 0 ? "装 " + packedBoxes + " 盒" : "未装箱")
                    + "，取 " + fetchedTotalItems + " 个）";
            try {
                reporter.accept(reason);
            } catch (Throwable ignored) {
            }
        }
    }
}