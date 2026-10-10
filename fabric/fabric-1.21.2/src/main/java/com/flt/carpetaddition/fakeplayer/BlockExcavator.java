package com.flt.carpetaddition.fakeplayer;

import carpet.patches.EntityPlayerMPFake;
import net.minecraft.block.BlockState;
import net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket.Action;
import net.minecraft.server.network.ServerPlayerInteractionManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

/*
 * ---------------------------------------------------------------------------
 * 来源声明：本文件移植自 Carpet-Org-Addition 的 BlockExcavator。
 *
 *   原项目：Carpet-Org-Addition  https://github.com/fcsailboat/Carpet-Org-Addition
 *   版权  ：Copyright (c) 2024 fcsailboat
 *   许可  ：MIT License
 *
 * MIT License 要求「上述版权声明与许可声明须随本软件的所有副本或实质部分一并保留」，
 * 故此声明不可删除。
 *
 * 本项目所做的修改：变量改名（player -> fakePlayer）；改用本项目的 FakePlayerUtils 获取
 * 服务端世界；调用 yarn 名的 interactionManager.processBlockBreakingAction；移除上游未使用的
 * getPlayer / canBreak / computingRemainingMiningTime 三个方法及 mining(BlockPos) /
 * mining(BlockPos, boolean) 两个重载；重写中文注释。
 *
 * 完整的第三方来源清单见项目根目录 THIRD-PARTY-NOTICES.md。
 * ---------------------------------------------------------------------------
 */

/**
 * 假人方块挖掘器（逐刻累积挖掘进度）。
 * 假人不能像真人那样"按住左键"，必须自己模拟：每刻调 mining 累积进度，满 1 时发 STOP 完成。
 * 走 player.interactionManager.processBlockBreakingAction（= 服务端处理 PlayerActionC2SPacket 的方法），
 * 行为与原版玩家完全一致（含工具速度、耐久、领地保护）。
 */
public class BlockExcavator {
    private final EntityPlayerMPFake fakePlayer;
    /** 破坏后冷却（tick），模拟真人手感 */
    private int blockBreakingCooldown;
    /** 当前在挖的方块；null 表示没在挖 */
    private BlockPos currentBreakingPos;
    /** 当前方块的挖掘进度（0~1） */
    private float currentBreakingProgress;

    public BlockExcavator(EntityPlayerMPFake fakePlayer) {
        this.fakePlayer = fakePlayer;
    }

    public void tick() {
        if (this.blockBreakingCooldown > 0) {
            this.blockBreakingCooldown--;
        }
    }

    /** 尝试挖掘指定方块（受挖掘冷却限制） */
    public boolean mining(BlockPos blockPos, Direction direction) {
        return this.mining(blockPos, direction, true);
    }

    /**
     * 尝试挖掘指定方块。
     * @param respectCooldown 是否受"破坏后冷却"限制。持续挖同一格时传 false，避免冷却打断连续挖掘
     */
    public boolean mining(BlockPos blockPos, Direction direction, boolean respectCooldown) {
        if (respectCooldown && this.blockBreakingCooldown > 0) {
            return false;
        }

        ServerWorld level = FakePlayerUtils.worldOf(this.fakePlayer);
        ServerPlayerInteractionManager gameMode = this.fakePlayer.interactionManager;
        GameMode gameType = gameMode.getGameMode();

        // 权限类检查（与原版一致：禁止操作时不能挖）
        if (this.fakePlayer.isBlockBreakingRestricted(level, blockPos, gameType)) {
            return false;
        }
// IF >= fabric-1.21.5
//        if (!level.canEntityModifyAt(this.fakePlayer, blockPos)) {
// ELSE
        if (!level.canPlayerModifyAt(this.fakePlayer, blockPos)) {
// END IF
            return false;
        }

        // 上一格已变空气 → 挖完
        if (this.currentBreakingPos != null && level.getBlockState(this.currentBreakingPos).isAir()) {
            this.currentBreakingPos = null;
            return true;
        }

        BlockState blockState = level.getBlockState(blockPos);
        // 让假人看着要挖的方块（视觉自然 + 影响某些方块判定）
        Vec3d lookTarget = new Vec3d(blockPos.getX() + 0.5, blockPos.getY() + 0.5, blockPos.getZ() + 0.5);
        this.fakePlayer.lookAt(EntityAnchor.EYES, lookTarget);
        float delta = blockState.calcBlockBreakingDelta(this.fakePlayer, level, blockPos);

        boolean broken;
        if (this.fakePlayer.isCreative()) {
            // 创造模式：一下就没
            this.breakingAction(Action.START_DESTROY_BLOCK, blockPos, direction);
            this.blockBreakingCooldown = 5;
            broken = true;
        } else if (this.currentBreakingPos == null) {
            broken = this.startMining(blockPos, direction, delta);
        } else if (this.currentBreakingPos.equals(blockPos)) {
            broken = this.continueMining(blockPos, direction, delta);
        } else {
            // 换目标：先中断上一格再重新开始
            this.breakingAction(Action.ABORT_DESTROY_BLOCK, this.currentBreakingPos, direction);
            broken = this.startMining(blockPos, direction, delta);
        }

        this.fakePlayer.updateLastActionTime();
        this.fakePlayer.swingHand(Hand.MAIN_HAND, false);
        return broken;
    }

    private boolean startMining(BlockPos blockPos, Direction direction, float delta) {
        this.breakingAction(Action.START_DESTROY_BLOCK, blockPos, direction);
        if (delta >= 1F) {
            return true;
        }
        this.currentBreakingPos = blockPos;
        // 初始进度 2*delta：避免破坏前若干刻裂纹消失的视觉瑕疵
        this.currentBreakingProgress = 2 * delta;
        return false;
    }

    private boolean continueMining(BlockPos blockPos, Direction direction, float delta) {
        this.currentBreakingProgress += delta;
        if (this.currentBreakingProgress >= 1F) {
            this.breakingAction(Action.STOP_DESTROY_BLOCK, blockPos, direction);
            this.currentBreakingPos = null;
            this.blockBreakingCooldown = 5;
            this.currentBreakingProgress = 0F;
            return true;
        }
        return false;
    }

    /** 走真实玩家动作入口（= processBlockBreakingAction） */
    private void breakingAction(Action action, BlockPos blockPos, Direction direction) {
        ServerWorld level = FakePlayerUtils.worldOf(this.fakePlayer);
// IF >= fabric-1.21.2
        this.fakePlayer.interactionManager.processBlockBreakingAction(blockPos, action, direction, level.getTopYInclusive(), -1);
// ELSE IF >= fabric-1.19.4
//        this.fakePlayer.interactionManager.processBlockBreakingAction(blockPos, action, direction, level.getTopY(), -1);
// ELSE
//        this.fakePlayer.interactionManager.processBlockBreakingAction(blockPos, action, direction, level.getHeight() - 1);
// END IF
    }
}
