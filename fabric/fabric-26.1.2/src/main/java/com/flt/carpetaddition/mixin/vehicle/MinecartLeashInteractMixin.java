package com.flt.carpetaddition.mixin.vehicle;

import com.flt.carpetaddition.settings.FLTSettings;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 普通矿车拴绳交互修复（leashableMinecarts 配套）。
 * 背景：Minecart#interact 不调 super.interact()，所以 Entity 基类的拴绳逻辑永远执行不到；
 * 其他矿车没 override，自动生效。
 * 方案：Minecart#interact HEAD 拦截，规则开时若"已被该玩家拴着/手持拴绳"，先按 Entity#interact
 * 拴绳逻辑处理并返回（创造 removeLeash，生存 dropLeash），否则放行原逻辑。
 */
@Mixin(Minecart.class)
public abstract class MinecartLeashInteractMixin {

    @Inject(method = "interact", at = @At("HEAD"), cancellable = true)
    private void flt$leashInteractFirst(Player player, InteractionHand hand, Vec3 location,
                                        CallbackInfoReturnable<InteractionResult> cir) {
        if (!FLTSettings.leashableMinecarts) {
            return;
        }
        Minecart self = (Minecart) (Object) this;
        Leashable leashable = (Leashable) (Object) this; // mixin 运行时给矿车实现了 Leashable
        if (leashable.getLeashHolder() == player) {
            // 已被该玩家拴着 → 右键解开
            if (!self.level().isClientSide()) {
                if (player.hasInfiniteMaterials()) {
                    leashable.removeLeash();
                } else {
                    leashable.dropLeash();
                }
                self.playSound(SoundEvents.LEAD_UNTIED);
            }
            cir.setReturnValue(InteractionResult.SUCCESS.withoutItem());
            return;
        }
        ItemStack heldItem = player.getItemInHand(hand);
        if (heldItem.is(Items.LEAD) && !(leashable.getLeashHolder() instanceof Player)) {
            if (self.level().isClientSide()) {
                // 客户端只表示"接受这次交互"，实际拴绳由服务端执行（原版约定）
                cir.setReturnValue(InteractionResult.CONSUME);
                return;
            }
            if (leashable.canHaveALeashAttachedTo(player)) {
                if (leashable.isLeashed()) {
                    leashable.dropLeash();
                }
                leashable.setLeashedTo(player, true);
                self.playSound(SoundEvents.LEAD_TIED);
                heldItem.shrink(1);
                cir.setReturnValue(InteractionResult.SUCCESS_SERVER);
            }
        }
    }
}