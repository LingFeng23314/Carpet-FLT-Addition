package com.flt.carpetaddition.mixin.villager;

import com.flt.carpetaddition.settings.FLTSettings;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * 村民吸引（villagersAttractedByEmeraldBlock）。
 * 注入 VillagerEntity.mobTick TAIL：每 40 tick 扫 20 格内主/副手持绿宝石块的玩家，
 * 选最近者用 startMovingTo 导航过去。
 */
@Mixin(VillagerEntity.class)
public class VillagerEmeraldBlockMixin {

    @Unique
    private int flt$attractCooldown = 0;

    @Inject(method = "mobTick", at = @At("TAIL"))
    private void flt$attractByEmeraldBlock(ServerWorld world, CallbackInfo ci) {
        if (!FLTSettings.villagersAttractedByEmeraldBlock) {
            return;
        }
        VillagerEntity self = (VillagerEntity) (Object) this;

        if (--flt$attractCooldown > 0) {
            return;
        }
        flt$attractCooldown = 40;

        Box area = self.getBoundingBox().expand(10);
        List<PlayerEntity> players = world.getEntitiesByClass(PlayerEntity.class, area,
                p -> holdsEmeraldBlock(p.getMainHandStack())
                        || holdsEmeraldBlock(p.getOffHandStack()));
        if (players.isEmpty()) {
            return;
        }

        PlayerEntity nearest = players.get(0);
        double nearestDist = nearest.squaredDistanceTo(self);
        for (int i = 1; i < players.size(); i++) {
            double d = players.get(i).squaredDistanceTo(self);
            if (d < nearestDist) {
                nearestDist = d;
                nearest = players.get(i);
            }
        }

        self.getNavigation().stop();
        self.getNavigation().startMovingTo(nearest, 1.0);
    }

    /** [VERSION] isOf 分界 fabric-1.17.1；1.16.5 用 Item == 比较（行为一致） */
    private static boolean holdsEmeraldBlock(ItemStack stack) {
// IF >= fabric-1.17.1
        return stack.isOf(Items.EMERALD_BLOCK);
// ELSE
//        return stack.getItem() == Items.EMERALD_BLOCK;
// END IF
    }
}