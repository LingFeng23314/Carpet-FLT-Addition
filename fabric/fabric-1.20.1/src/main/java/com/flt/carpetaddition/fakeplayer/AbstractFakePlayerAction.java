package com.flt.carpetaddition.fakeplayer;

import carpet.patches.EntityPlayerMPFake;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;

/** 假人动作基类。描述"一个假人正在做的事"，由 FakePlayerActionScheduler 每刻驱动。
 *  只保留"每刻执行一次 + 结束回调"，不做 ORG 的序列化/持久化/多动作管理树。
 *  参考 ORG 的 AbstractPlayerAction（MIT）；FLT 独立实现。
 *  //# [VERSION] 本包仅 1.21+ 生成（假人刷交易功能）；1.20.1- 无此目录。 */
public abstract class AbstractFakePlayerAction {
    private final EntityPlayerMPFake fakePlayer;

    protected AbstractFakePlayerAction(EntityPlayerMPFake fakePlayer) {
        this.fakePlayer = fakePlayer;
    }

    public final EntityPlayerMPFake getFakePlayer() {
        return this.fakePlayer;
    }

    /** 假人所在的服务端世界 */
    public final ServerWorld level() {
        return FakePlayerUtils.worldOf(this.fakePlayer);
    }

    /** 假人所在的服务器 */
    public final MinecraftServer server() {
        return this.level().getServer();
    }

    /**
     * 每游戏刻执行一次（由调度器在服务端主线程调用）。
     * @return true = 继续执行；false = 动作结束（调度器会把它移除）
     */
    public abstract boolean tick();

    /** 动作结束时回调，默认空实现 */
    public void onStop() {
    }
}
