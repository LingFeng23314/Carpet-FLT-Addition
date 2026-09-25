package com.flt.carpetaddition.fakeplayer;

import carpet.patches.EntityPlayerMPFake;
import com.flt.carpetaddition.FLTAdditionMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 假人池 / 分步建档调度器。
 *
 * <p>背景：Carpet 的 {@link EntityPlayerMPFake#createFake} 只把"拉皮肤/档案"异步化了，
 * 真正的「实例化假人 + 读离线存档 + 进世界」仍被排进服务端主线程 tick 队列，
 * 且 createFake 是<b>立即返回 boolean</b>、对象要等生成完成后才能按名字查到。
 * 直接在同一 tick 里创建 + 使用会拿不到人，也会造成一次性卡顿。
 *
 * <p>本类做法（分步建档）：
 * <ol>
 *   <li>{@link #ensureOrCreate}：假人已存在 → 直接回调；不存在 → 调 createFake 并登记 pending。</li>
 *   <li>{@link #tick}：每刻检查 pending，等 {@code EntityPlayerMPFake.isSpawningPlayer(name)==false}
 *       表示建档完成 → 查到对象 → 执行回调（派活）。</li>
 * </ol>
 * 建档被摊到多刻、且每刻只推进一小步，避免"一建一卡"。
 *
 * <p>常驻复用：假人一旦生成就留在世界（不反复 createFake）。派活时只做
 * teleportTo + openMenu + quickMoveStack，符合"生成一次常住"的治本方案。
 *
 * <p>线程安全：全部在服务端主线程调用（命令 / onTick），用普通 HashMap 即可。
 */
public final class FakePlayerFactory {
    /** 正在建档的假人：名字 → 建档完成后要执行的回调 */
    private static final Map<String, Consumer<EntityPlayerMPFake>> PENDING = new HashMap<>();

    /**
     * 建档标记（{@code isSpawningPlayer}）消失后仍在等待实体进玩家列表的刻数。
     *
     * <p>为什么需要：{@code isSpawningPlayer} 变 false 与「实体出现在
     * {@code PlayerList}」不是同一刻 —— 实测前者先变 false，实体要晚 1 刻左右才查到
     * （2026-09-23 日志："建档结束但查不到实体，已跳过"，下一行假人才进服）。
     * 若此刻直接放弃，回调会被丢掉（假人其实建好了，只是查不到）。
     */
    private static final Map<String, Integer> WAIT_TICKS = new HashMap<>();

    /**
     * 建档标记消失后最多再等多少刻（6 秒）；超时才判定真失败。
     *
     * <p>为什么是 6 秒而不是 1 秒（2026-09-23 实测）：
     * {@code EntityPlayerMPFake.createFake} 建名时 Carpet 会先查 Mojang 档
     * （{@code findProfileByName} 发 HTTP，日志 WARN "Couldn't find profile with name: <bot>"），
     * 加上 AMS/GCA 等附加的 useOfflinePlayerUUID mixin 又包一层，整个建档常要 ~1.5~2 秒
     * 实体才真正进玩家列表。之前 20 刻(1秒) 太短，会把"其实快生成了"的人误判成失败、
     * 记下 10 秒冷却，导致假人刚上线 FLT 却已放弃、活接不上。放宽到 120 刻(6 秒) 兜住这个延迟。
     */
    private static final int MAX_WAIT_TICKS = 120;

    /**
     * 建档失败冷却表：名字 → 上次 createFake 返回 false / 建档放弃的时间戳（毫秒）。
     *
     * <p>为什么需要（2026-09-23 实测断连 + NPE）：当装了大量 Carpet 附加（TIS / ORG / AMS 等）
     * 时，它们会在 {@code placeNewPlayer} 里对假人做 deduplicate / fakePlayerRejoin，
     * 叠加起来可能让 createFake 传送到 {@code DistanceManager.removePlayer} 时抛
     * {@code NullPointerException: chunkPlayers is null}—— 假人建不起来。
     * 若没有冷却，{@code tickPending}/{@code restockAllBoxed} 每刻都会重试 createFake，
     * 造成"20 刻内同一个名字连建 4 个实体"的刷屏与竞态（日志 115/117/118/119）。
     * 加冷却后：失败即等 {@link #CREATE_RETRY_COOLDOWN_MS} 再允许重试，显著降低对
     * 多 Carpet 附加的扰动，也给用户留出修 mods 环境的时间。
     */
    private static final Map<String, Long> RETRY_COOLDOWN = new HashMap<>();

    /** createFake 失败后至少等多久才允许对同一名字再次建档（毫秒，默认 10 秒） */
    private static final long CREATE_RETRY_COOLDOWN_MS = 10_000L;

    private FakePlayerFactory() {
    }

    /**
     * 确保假人存在，并在可用时调用 {@code onReady}。
     * <ul>
     *   <li>已存在（含真玩家同名则视为失败，不回调）→ 立即回调</li>
     *   <li>不存在 → 异步建档，登记 pending，{@link #tick} 推进完成后回调</li>
     *   <li>已在建档中 → 拒绝（避免重复 createFake）</li>
     * </ul>
     * @return true = 已就绪（回调已执行）；false = 正在建档（回调将在后续 tick 触发）
     */
    public static boolean ensureOrCreate(MinecraftServer server, String name, ServerLevel level, Vec3 spawnPos,
                                         Consumer<EntityPlayerMPFake> onReady) {
        EntityPlayerMPFake existing = FakePlayerUtils.findFakePlayer(server, name);
        if (existing != null) {
            onReady.accept(existing);
            return true;
        }
        if (PENDING.containsKey(name)) {
            return false;   // 已在建档，勿重复
        }
        // 冷却检查：刚失败过（多 Carpet 附加 NPE 等）就再等一会儿，避免每 tick 疯狂重试 createFake
        Long lastFail = RETRY_COOLDOWN.get(name);
        if (lastFail != null && System.currentTimeMillis() - lastFail < CREATE_RETRY_COOLDOWN_MS) {
            return false;   // 冷却中，调用方（tickPending/restockAllBoxed）下个周期再看
        }
        PENDING.put(name, onReady);
        boolean accepted = EntityPlayerMPFake.createFake(
                name, server, spawnPos, 0.0D, 0.0D, level.dimension(), GameType.SURVIVAL, false);
        if (!accepted) {
            // createFake 拒绝（名字非法 / 已存在等）→ 撤销登记 + 记冷却，防止立刻无限重试
            PENDING.remove(name);
            WAIT_TICKS.remove(name);
            RETRY_COOLDOWN.put(name, System.currentTimeMillis());
            FLTAdditionMod.LOGGER.warn("[FLT] 假人 {} 建档被拒（名字非法或已存在），{} 秒内不再重试",
                    name, CREATE_RETRY_COOLDOWN_MS / 1000);
        }
        return false;
    }

    /**
     * 每刻推进所有 pending 建档。由 FLTAdditionServer.onTick 调用。
     * 建档完成（{@code isSpawningPlayer} 变 false 且能查到人）→ 执行回调并移除。
     *
     * <p>标记消失但实体尚未进玩家列表时不立即放弃，而是再等最多 {@link #MAX_WAIT_TICKS} 刻
     * （见 {@link #WAIT_TICKS} 的说明）。
     */
    public static void tick(MinecraftServer server) {
        if (PENDING.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<String, Consumer<EntityPlayerMPFake>>> it = PENDING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Consumer<EntityPlayerMPFake>> entry = it.next();
            String name = entry.getKey();
            if (EntityPlayerMPFake.isSpawningPlayer(name)) {
                continue;   // 还在建档，下一 tick 再看
            }
            EntityPlayerMPFake fake = FakePlayerUtils.findFakePlayer(server, name);
            if (fake == null) {
                // 标记已消失但实体还没进玩家列表 → 再等几刻（实测会滞后 1 刻左右）
                int waited = WAIT_TICKS.merge(name, 1, Integer::sum);
                if (waited < MAX_WAIT_TICKS) {
                    continue;
                }
                it.remove();
                WAIT_TICKS.remove(name);
                RETRY_COOLDOWN.put(name, System.currentTimeMillis());
                FLTAdditionMod.LOGGER.warn("[FLT] 假人 {} 建档后 {} 刻仍查不到实体，放弃（{} 秒内不再重试）",
                        name, waited, CREATE_RETRY_COOLDOWN_MS / 1000);
                continue;
            }
            it.remove();
            WAIT_TICKS.remove(name);
            try {
                entry.getValue().accept(fake);
            } catch (Throwable t) {
                FLTAdditionMod.LOGGER.error("[FLT] 假人 {} 建档完成回调出错", name, t);
            }
        }
    }

    /** 该名字是否正在建档 */
    public static boolean isPending(String name) {
        return PENDING.containsKey(name);
    }

    /** 服务器关闭时清空（跨存档不留残留） */
    public static void clear() {
        PENDING.clear();
        WAIT_TICKS.clear();
        RETRY_COOLDOWN.clear();
    }
}