package com.flt.carpetaddition.mixin.vehicle;

//# [VERSION] 依赖 Leashable 接口（1.21+ 才有）→ 整个 Mixin 裁剪到 >= fabric-1.21
// IF >= fabric-1.21
import com.flt.carpetaddition.settings.FLTSettings;
import net.minecraft.entity.Leashable;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.vehicle.MinecartEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
// END IF

/**
 * 普通矿车拴绳交互修复（leashableMinecarts 配套，1.21+）。
 * 背景：MinecartEntity#interact 完全重写不调 super.interact()，Entity 基类的拴绳逻辑永远执行不到；
 * 其他矿车没 override 自动生效。
 * 方案：MinecartEntity#interact HEAD 拦截，规则开时若"已被该玩家拴着/手持拴绳"，先按
 * Entity#interact 拴绳逻辑处理并返回（创造 detachLeashWithoutDrop / 生存 detachLeash），否则放行。
 * [VERSION] 拴绳 API 五档：A 1.21 / B1 1.21.2~1.21.3（返回 SUCCESS 常量）/ B2 1.21.4~1.21.5 /
 * C1 1.21.6~1.21.8 / C2 1.21.9+（Entity 改 getEntityWorld）。
 */
// IF >= fabric-1.21
@Mixin(MinecartEntity.class)
public abstract class MinecartLeashInteractMixin {

 // IF fabric-1.21
//    @Inject(method = "interact", at = @At("HEAD"), cancellable = true)
//    private void flt$leashInteractFirst(PlayerEntity player, Hand hand, CallbackInfoReturnable<ActionResult> cir) {
//        if (!FLTSettings.leashableMinecarts) {
//            return;
//        }
//        MinecartEntity self = (MinecartEntity) (Object) this;
//        Leashable leashable = (Leashable) (Object) this;
//        if (leashable.getLeashHolder() == player) {
//            if (self.getWorld() instanceof net.minecraft.server.world.ServerWorld) {
//                leashable.detachLeash(true, !player.isInCreativeMode());
//            }
//            cir.setReturnValue(ActionResult.success(self.getWorld().isClient));
//            return;
//        }
//        ItemStack heldItem = player.getStackInHand(hand);
//        if (heldItem.isOf(Items.LEAD) && leashable.canLeashAttachTo()) {
//            if (self.getWorld() instanceof net.minecraft.server.world.ServerWorld) {
//                leashable.attachLeash(player, true);
//            }
//            heldItem.decrement(1);
//            cir.setReturnValue(ActionResult.success(self.getWorld().isClient));
//        }
//    }
 // ELSE IF <= fabric-1.21.3
//    @Inject(method = "interact", at = @At("HEAD"), cancellable = true)
//    private void flt$leashInteractFirst(PlayerEntity player, Hand hand, CallbackInfoReturnable<ActionResult> cir) {
//        if (!FLTSettings.leashableMinecarts) {
//            return;
//        }
//        MinecartEntity self = (MinecartEntity) (Object) this;
//        Leashable leashable = (Leashable) (Object) this;
//        if (leashable.getLeashHolder() == player) {
//            if (self.getWorld() instanceof net.minecraft.server.world.ServerWorld) {
//                leashable.detachLeash(true, !player.isInCreativeMode());
//            }
//            cir.setReturnValue(ActionResult.SUCCESS);
//            return;
//        }
//        ItemStack heldItem = player.getStackInHand(hand);
//        if (heldItem.isOf(Items.LEAD) && leashable.canLeashAttachTo()) {
//            if (self.getWorld() instanceof net.minecraft.server.world.ServerWorld) {
//                leashable.attachLeash(player, true);
//            }
//            heldItem.decrement(1);
//            cir.setReturnValue(ActionResult.SUCCESS);
//        }
//    }
 // ELSE IF <= fabric-1.21.5
    @Inject(method = "interact", at = @At("HEAD"), cancellable = true)
    private void flt$leashInteractFirst(PlayerEntity player, Hand hand, CallbackInfoReturnable<ActionResult> cir) {
        if (!FLTSettings.leashableMinecarts) {
            return;
        }
        MinecartEntity self = (MinecartEntity) (Object) this;
        Leashable leashable = (Leashable) (Object) this;
        if (leashable.getLeashHolder() == player) {
            if (self.getWorld() instanceof net.minecraft.server.world.ServerWorld) {
                if (player.isInCreativeMode()) {
                    leashable.detachLeashWithoutDrop();
                } else {
                    leashable.detachLeash();
                }
            }
            cir.setReturnValue(ActionResult.SUCCESS.noIncrementStat());
            return;
        }
        ItemStack heldItem = player.getStackInHand(hand);
        if (heldItem.isOf(Items.LEAD) && leashable.canLeashAttachTo()) {
            if (self.getWorld() instanceof net.minecraft.server.world.ServerWorld) {
                leashable.attachLeash(player, true);
            }
            heldItem.decrement(1);
            cir.setReturnValue(ActionResult.SUCCESS);
        }
    }
 // ELSE IF <= fabric-1.21.8
//    @Inject(method = "interact", at = @At("HEAD"), cancellable = true)
//    private void flt$leashInteractFirst(PlayerEntity player, Hand hand, CallbackInfoReturnable<ActionResult> cir) {
//        if (!FLTSettings.leashableMinecarts) {
//            return;
//        }
//        MinecartEntity self = (MinecartEntity) (Object) this;
//        Leashable leashable = (Leashable) (Object) this;
//        if (leashable.getLeashHolder() == player) {
//            if (self.getWorld() instanceof net.minecraft.server.world.ServerWorld) {
//                if (player.isInCreativeMode()) {
//                    leashable.detachLeashWithoutDrop();
//                } else {
//                    leashable.detachLeash();
//                }
//            }
//            cir.setReturnValue(ActionResult.SUCCESS.noIncrementStat());
//            return;
//        }
//        ItemStack heldItem = player.getStackInHand(hand);
//        if (heldItem.isOf(Items.LEAD) && !(leashable.getLeashHolder() instanceof PlayerEntity)) {
//            if (!(self.getWorld() instanceof net.minecraft.server.world.ServerWorld)) {
//                cir.setReturnValue(ActionResult.CONSUME);
//                return;
//            }
//            if (leashable.canBeLeashedTo(player)) {
//                if (leashable.isLeashed()) {
//                    leashable.detachLeash();
//                }
//                leashable.attachLeash(player, true);
//                heldItem.decrement(1);
//                cir.setReturnValue(ActionResult.SUCCESS_SERVER);
//            }
//        }
//    }
 // ELSE
//    /** 1.21.9+ Entity 改 getEntityWorld */
//    @Inject(method = "interact", at = @At("HEAD"), cancellable = true)
//    private void flt$leashInteractFirst(PlayerEntity player, Hand hand, CallbackInfoReturnable<ActionResult> cir) {
//        if (!FLTSettings.leashableMinecarts) {
//            return;
//        }
//        MinecartEntity self = (MinecartEntity) (Object) this;
//        Leashable leashable = (Leashable) (Object) this;
//        if (leashable.getLeashHolder() == player) {
//            if (self.getEntityWorld() instanceof net.minecraft.server.world.ServerWorld) {
//                if (player.isInCreativeMode()) {
//                    leashable.detachLeashWithoutDrop();
//                } else {
//                    leashable.detachLeash();
//                }
//            }
//            cir.setReturnValue(ActionResult.SUCCESS.noIncrementStat());
//            return;
//        }
//        ItemStack heldItem = player.getStackInHand(hand);
//        if (heldItem.isOf(Items.LEAD) && !(leashable.getLeashHolder() instanceof PlayerEntity)) {
//            if (!(self.getEntityWorld() instanceof net.minecraft.server.world.ServerWorld)) {
//                cir.setReturnValue(ActionResult.CONSUME);
//                return;
//            }
//            if (leashable.canBeLeashedTo(player)) {
//                if (leashable.isLeashed()) {
//                    leashable.detachLeash();
//                }
//                leashable.attachLeash(player, true);
//                heldItem.decrement(1);
//                cir.setReturnValue(ActionResult.SUCCESS_SERVER);
//            }
//        }
//    }
 // END IF
}
// END IF