package com.flt.carpetaddition.fakeplayer;

import carpet.patches.EntityPlayerMPFake;
import com.flt.carpetaddition.FLTAdditionMod;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 假人动作调度器（FLT 极简版）。
 * 持有"每个假人当前正在执行的动作"，由 FLTAdditionServer.onTick 每刻驱动。
 * 一个假人同一时间只允许一个动作。
 * 不依赖 fabric-api：CarpetExtension.onTick 已提供每刻回调，少一层依赖差异。
 * 线程安全：所有方法都在服务端主线程调用，用普通 HashMap 即可。
 */
public final class FakePlayerActionScheduler {
    /** key = 假人 UUID */
    private static final Map<UUID, AbstractFakePlayerAction> ACTIONS = new HashMap<>();

    private FakePlayerActionScheduler() {
    }

    /**
     * 每游戏刻驱动所有动作一次。移除条件：返回 false / 假人掉线 / 抛异常（记录日志后终止）。
     *
     * <p>⚠️ <b>迭代中只改 map，绝不调 {@code onStop()}</b>（[VERSION] 2026-09-29 修复）：
     * {@code onStop()} 可能启动新动作 —— 例如 {@code RestockScheduler.flushOne} 会把待派队列里的
     * 下一件派给**同一个假人**（内部先 {@code ACTIONS.remove} 再 {@code put}），map 随之被改动，
     * 紧接着的 {@code iterator.remove()} 就抛 {@code ConcurrentModificationException}。
     * （Java 实测复现：当前顺序必抛，改成"先移除、迭代结束后统一回调"则正常。）
     * <p>该异常的后果被放大得很严重：它会冒泡到 {@code FLTAdditionServer.onTick}（那里本 tick
     * 排在第一位且当时无 try 保护）→ 建档 / 备货 / 回收三个子系统该刻全部不执行。
     * <p>所以：迭代内只把「已结束的动作」从 map 摘掉并收集起来，迭代<b>结束之后</b>再统一回调。
     */
    public static void tick(MinecraftServer server) {
        if (ACTIONS.isEmpty()) {
            return;
        }
        List<AbstractFakePlayerAction> finished = new ArrayList<>();
        Iterator<Map.Entry<UUID, AbstractFakePlayerAction>> iterator = ACTIONS.entrySet().iterator();
        while (iterator.hasNext()) {
            AbstractFakePlayerAction action = iterator.next().getValue();
            EntityPlayerMPFake fakePlayer = action.getFakePlayer();

            boolean keepRunning = !fakePlayer.isRemoved() && !fakePlayer.hasDisconnected();
            if (keepRunning) {
                try {
                    keepRunning = action.tick();
                } catch (Throwable throwable) {
                    FLTAdditionMod.LOGGER.error("[Tradefinder] 假人 {} 执行动作时出错，已自动停止",
                            fakePlayer.getGameProfile().name(), throwable);
                    keepRunning = false;
                }
            }

            if (!keepRunning) {
                iterator.remove();
                finished.add(action);
            }
        }

        // 迭代结束后才回调：此刻启动新动作（派队列里的下一件）不会再有并发修改问题。
        // onStop 内部出错也不能连累其他动作的收尾 —— 逐个 try。
        for (AbstractFakePlayerAction action : finished) {
            try {
                action.onStop();
            } catch (Throwable throwable) {
                FLTAdditionMod.LOGGER.error("[Tradefinder] 假人动作结束时回调出错", throwable);
            }
        }
    }

    /** 给假人安排一个新动作（先停掉它身上原有的动作） */
    public static void start(EntityPlayerMPFake fakePlayer, AbstractFakePlayerAction action) {
        stop(fakePlayer);
        ACTIONS.put(fakePlayer.getUUID(), action);
    }

    /** 停止假人当前的动作（没有则什么都不做） */
    public static void stop(EntityPlayerMPFake fakePlayer) {
        AbstractFakePlayerAction previous = ACTIONS.remove(fakePlayer.getUUID());
        if (previous != null) {
            previous.onStop();
        }
    }

    /** 假人当前是否有动作在执行 */
    public static boolean isRunning(EntityPlayerMPFake fakePlayer) {
        return ACTIONS.containsKey(fakePlayer.getUUID());
    }

    /** 服务器关闭时清空（避免跨存档残留） */
    public static void clear() {
        ACTIONS.clear();
    }
}