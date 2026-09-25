package com.flt.carpetaddition.mixin.vehicle;

import com.flt.carpetaddition.settings.FLTSettings;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 拴绳拴矿车（leashableMinecarts）：给 AbstractMinecart 加 Leashable 实现。
 * 结构参照 Minecraft 原版 AbstractBoat（原版类，非第三方 mod）：
 * LeashData 字段 + getLeashOffset + 存档读写 TAIL 注入 +
 * tick TAIL 手动调 Leashable.tickLeash（矿车 tick 重写不走 super.tick()，需手动补）。
 */
@Mixin(AbstractMinecart.class)
public abstract class MinecartLeashMixin implements Leashable {

    @Unique
    private Leashable.LeashData fltLeashData;

    @Override
    public Leashable.LeashData getLeashData() {
        return this.fltLeashData;
    }

    @Override
    public void setLeashData(Leashable.LeashData leashData) {
        this.fltLeashData = leashData;
    }

    /** 拴绳挂点：矿车顶部偏上 */
    @Override
    public Vec3 getLeashOffset() {
        return new Vec3(0.0, 0.75 * ((AbstractMinecart) (Object) this).getBbHeight(), 0.0);
    }

    /** 规则开关 = 是否允许被新拴上；已拴住的矿车不受规则关闭影响 */
    @Override
    public boolean canBeLeashed() {
        return FLTSettings.leashableMinecarts;
    }

    /** 矿车 tick() 重写不走 super.tick() → 手动补 Leashable.tickLeash */
    @Inject(method = "tick", at = @At("TAIL"))
    private void flt$tickLeash(CallbackInfo ci) {
        if (((AbstractMinecart) (Object) this).level() instanceof ServerLevel serverLevel) {
            // (Entity & Leashable) 交叉 cast：编译期 this 不满足（mixin 运行时加接口），运行时一定满足
            Leashable.tickLeash(serverLevel, (Entity & Leashable) (Object) this);
        }
    }

    /** 存档：拴绳数据写入矿车 NBT */
    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void flt$saveLeash(ValueOutput output, CallbackInfo ci) {
        this.writeLeashData(output, this.fltLeashData);
    }

    /** 读档：恢复拴绳数据（UUID / 栅栏坐标，下一 tick 由 restoreLeashFromSave 重新绑定） */
    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void flt$loadLeash(ValueInput input, CallbackInfo ci) {
        this.readLeashData(input);
    }
}