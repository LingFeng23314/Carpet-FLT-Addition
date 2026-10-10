package com.flt.carpetaddition.mixin.villager;

import com.flt.carpetaddition.settings.FLTSettings;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * 村民吸引（villagersAttractedByEmeraldBlock）。
 * 注入 Villager.customServerAiStep TAIL：每 40 tick 扫 20 格内主/副手持绿宝石块的玩家，
 * 选最近者用 moveTo 导航过去。
 */
@Mixin(Villager.class)
public class VillagerEmeraldBlockMixin {

    @Unique
    private int flt$attractCooldown = 0;

    @Inject(method = "customServerAiStep", at = @At("TAIL"))
    private void flt$attractByEmeraldBlock(ServerLevel world, CallbackInfo ci) {
        if (!FLTSettings.villagersAttractedByEmeraldBlock) {
            return;
        }
        Villager self = (Villager) (Object) this;

        if (--flt$attractCooldown > 0) {
            return;
        }
        flt$attractCooldown = 40;

        AABB area = self.getBoundingBox().inflate(10);
        List<Player> players = world.getEntities(EntityTypeTest.forClass(Player.class), area,
                p -> holdsEmeraldBlock(p.getMainHandItem())
                        || holdsEmeraldBlock(p.getOffhandItem()));
        if (players.isEmpty()) {
            return;
        }

        Player nearest = players.get(0);
        double nearestDist = nearest.distanceToSqr(self);
        for (int i = 1; i < players.size(); i++) {
            double d = players.get(i).distanceToSqr(self);
            if (d < nearestDist) {
                nearestDist = d;
                nearest = players.get(i);
            }
        }

        self.getNavigation().stop();
        self.getNavigation().moveTo(nearest, 1.0);
    }

    /** 26.2 无 ItemStack.isOf，直接用 Item 对象 == 比较（与 1.21.11 isOf 行为一致） */
    private static boolean holdsEmeraldBlock(ItemStack stack) {
        return stack.getItem() == Items.EMERALD_BLOCK;
    }
}