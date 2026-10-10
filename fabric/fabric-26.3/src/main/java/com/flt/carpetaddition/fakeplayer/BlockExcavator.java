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
 * 本项目所做的修改：变量改名（player -> fakePlayer）；适配 26.x API
 * （BlockPos.getCenter() 在该版本已移除，改用手写中心坐标）；移除上游未使用的 getPlayer /
 * canBreak / computingRemainingMiningTime 三个方法及 mining(BlockPos) / mining(BlockPos, boolean)
 * 两个重载；重写中文注释。
 *
 * 完整的第三方来源清单见项目根目录 THIRD-PARTY-NOTICES.md。
 * ---------------------------------------------------------------------------
 */

package com.flt.carpetaddition.fakeplayer;

import carpet.patches.EntityPlayerMPFake;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 假人方块挖掘器（逐刻累积挖掘进度）。
 * 假人不能像真人那样"按住左键"，必须自己模拟：每刻调 mining 累积进度，满 1 时发 STOP 完成。
 * 走 player.gameMode.handleBlockBreakAction（= 服务端处理 ServerboundPlayerActionPacket 的方法），
 * 行为与原版玩家完全一致（含工具速度、耐久、领地保护）。
 * <p>来源与许可：本文件移植自 Carpet-Org-Addition，具体版权声明见<b>本文件顶部的署名块</b>
 * （MIT License，Copyright (c) 2024 fcsailboat），不可删除。
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

        ServerLevel level = (ServerLevel) this.fakePlayer.level();
        ServerPlayerGameMode gameMode = this.fakePlayer.gameMode;
        GameType gameType = gameMode.getGameModeForPlayer();

        // 权限类检查（与原版一致：禁止操作时不能挖）
        if (this.fakePlayer.blockActionRestricted(level, blockPos, gameType)) {
            return false;
        }
        if (!level.mayInteract(this.fakePlayer, blockPos)) {
            return false;
        }

        // 上一格已变空气 → 挖完
        if (this.currentBreakingPos != null && level.getBlockState(this.currentBreakingPos).isAir()) {
            this.currentBreakingPos = null;
            return true;
        }

        BlockState blockState = level.getBlockState(blockPos);
        // 让假人看着要挖的方块（视觉自然 + 影响某些方块判定）
        // 26.x 无 BlockPos.getCenter()，统一用 Vec3 中心坐标
        Vec3 lookTarget = new Vec3(blockPos.getX() + 0.5, blockPos.getY() + 0.5, blockPos.getZ() + 0.5);
        this.fakePlayer.lookAt(EntityAnchorArgument.Anchor.EYES, lookTarget);
        float delta = blockState.getDestroyProgress(this.fakePlayer, level, blockPos);

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

        this.fakePlayer.resetLastActionTime();
        this.fakePlayer.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
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

    /** 走真实玩家动作入口（= handleBlockBreakAction） */
    private void breakingAction(Action action, BlockPos blockPos, Direction direction) {
        ServerLevel level = (ServerLevel) this.fakePlayer.level();
        this.fakePlayer.gameMode.handleBlockBreakAction(blockPos, action, direction, level.getMaxY(), -1);
    }
}