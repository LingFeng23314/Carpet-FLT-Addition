package com.flt.carpetaddition.fakeplayer;

import carpet.patches.EntityPlayerMPFake;
import com.flt.carpetaddition.FLTAdditionMod;
import net.minecraft.server.MinecraftServer;

import java.util.HashMap;
import java.util.Iterator;
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
     */
    public static void tick(MinecraftServer server) {
        if (ACTIONS.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, AbstractFakePlayerAction>> iterator = ACTIONS.entrySet().iterator();
        while (iterator.hasNext()) {
            AbstractFakePlayerAction action = iterator.next().getValue();
            EntityPlayerMPFake fakePlayer = action.getFakePlayer();

// IF >= fabric-1.17.1
//            boolean keepRunning = !fakePlayer.isRemoved() && !fakePlayer.isDisconnected();
// ELSE
            boolean keepRunning = !fakePlayer.removed && !fakePlayer.isDisconnected();
// END IF
            if (keepRunning) {
                try {
                    keepRunning = action.tick();
                } catch (Throwable throwable) {
                    FLTAdditionMod.LOGGER.error("[Tradefinder] 假人 {} 执行动作时出错，已自动停止",
                            fakePlayer.getName().getString(), throwable);
                    keepRunning = false;
                }
            }

            if (!keepRunning) {
                action.onStop();
                iterator.remove();
            }
        }
    }

    /** 给假人安排一个新动作（先停掉它身上原有的动作） */
    public static void start(EntityPlayerMPFake fakePlayer, AbstractFakePlayerAction action) {
        stop(fakePlayer);
        ACTIONS.put(fakePlayer.getUuid(), action);
    }

    /** 停止假人当前的动作（没有则什么都不做） */
    public static void stop(EntityPlayerMPFake fakePlayer) {
        AbstractFakePlayerAction previous = ACTIONS.remove(fakePlayer.getUuid());
        if (previous != null) {
            previous.onStop();
        }
    }

    /** 假人当前是否有动作在执行 */
    public static boolean isRunning(EntityPlayerMPFake fakePlayer) {
        return ACTIONS.containsKey(fakePlayer.getUuid());
    }

    /** 服务器关闭时清空（避免跨存档残留） */
    public static void clear() {
        ACTIONS.clear();
    }
}
