package com.flt.carpetaddition.mixin.vehicle;

//# [VERSION] Leashable 接口与 1.21+ 拴绳体系 1.20.4- 不存在
// IF >= fabric-1.21
//import com.flt.carpetaddition.settings.FLTSettings;
//import net.minecraft.entity.Entity;
//import net.minecraft.entity.Leashable;
//import net.minecraft.entity.vehicle.AbstractMinecartEntity;
//import net.minecraft.nbt.NbtCompound;
//import net.minecraft.server.world.ServerWorld;
// END IF
// IF >= fabric-1.21.6
//import net.minecraft.storage.ReadView;
//import net.minecraft.storage.WriteView;
// END IF
// IF >= fabric-1.21
//import org.spongepowered.asm.mixin.Mixin;
//import org.spongepowered.asm.mixin.Unique;
//import org.spongepowered.asm.mixin.injection.At;
//import org.spongepowered.asm.mixin.injection.Inject;
//import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
// END IF

/**
 * 拴绳拴矿车（leashableMinecarts，1.21+）。
 * 给 AbstractMinecartEntity 加 Leashable 实现（LeashData + 存档读写 + tick TAIL 手动补调，
 * 矿车 tick 重写不走 super.tick()）。结构照搬对应版本船。
 * [VERSION] tickLeash 三形态：1.21 单参 / 1.21.2~1.21.8 双参 + getWorld / 1.21.9+ 双参 + getEntityWorld。
 * 拴绳存档四形态：1.21~1.21.3 read 返回 LeashData / 1.21.4~1.21.5 read void / 1.21.6+ ReadView/WriteView。
 */
// IF >= fabric-1.21
//@Mixin(AbstractMinecartEntity.class)
//public abstract class MinecartLeashMixin implements Leashable {
//
//    @Unique
//    private Leashable.LeashData fltLeashData;
//
//    @Override
//    public Leashable.LeashData getLeashData() {
//        return this.fltLeashData;
//    }
//
//    @Override
//    public void setLeashData(Leashable.LeashData leashData) {
//        this.fltLeashData = leashData;
//    }
//
//    /** 规则开关 = 是否允许被新拴上；已拴住的矿车不受规则关闭影响 */
//    @Override
//    public boolean canBeLeashed() {
//        return FLTSettings.leashableMinecarts;
//    }
//
// // IF fabric-1.21
//    /** 1.21 单参 tickLeash */
//    @Inject(method = "tick", at = @At("TAIL"))
//    private void flt$tickLeash(CallbackInfo ci) {
//        if (((AbstractMinecartEntity) (Object) this).getWorld() instanceof ServerWorld) {
//            Leashable.tickLeash((Entity & Leashable) (Object) this);
//        }
//    }
// // ELSE IF <= fabric-1.21.8
//    /** 1.21.2~1.21.8 双参 + getWorld */
//    @Inject(method = "tick", at = @At("TAIL"))
//    private void flt$tickLeash(CallbackInfo ci) {
//        if (((AbstractMinecartEntity) (Object) this).getWorld() instanceof ServerWorld serverWorld) {
//            Leashable.tickLeash(serverWorld, (Entity & Leashable) (Object) this);
//        }
//    }
// // ELSE
//    /** 1.21.9+ Entity 改名 getEntityWorld */
//    @Inject(method = "tick", at = @At("TAIL"))
//    private void flt$tickLeash(CallbackInfo ci) {
//        if (((AbstractMinecartEntity) (Object) this).getEntityWorld() instanceof ServerWorld serverWorld) {
//            Leashable.tickLeash(serverWorld, (Entity & Leashable) (Object) this);
//        }
//    }
// // END IF
//
// // IF <= fabric-1.21.3
//    /** 1.21~1.21.3：read 返回 LeashData（船 1.21:691 同款）→ 自己赋值 */
//    @Inject(method = "writeCustomDataToNbt", at = @At("TAIL"))
//    private void flt$saveLeash(NbtCompound nbt, CallbackInfo ci) {
//        this.writeLeashDataToNbt(nbt, this.fltLeashData);
//    }
//
//    @Inject(method = "readCustomDataFromNbt", at = @At("TAIL"))
//    private void flt$loadLeash(NbtCompound nbt, CallbackInfo ci) {
//        this.fltLeashData = this.readLeashDataFromNbt(nbt);
//    }
// // ELSE IF <= fabric-1.21.5
//    /** 1.21.4~1.21.5：read void（船 1.21.5:617 同款）→ 内部自己 set */
//    @Inject(method = "writeCustomDataToNbt", at = @At("TAIL"))
//    private void flt$saveLeash(NbtCompound nbt, CallbackInfo ci) {
//        this.writeLeashDataToNbt(nbt, this.fltLeashData);
//    }
//
//    @Inject(method = "readCustomDataFromNbt", at = @At("TAIL"))
//    private void flt$loadLeash(NbtCompound nbt, CallbackInfo ci) {
//        this.readLeashDataFromNbt(nbt);
//    }
// // ELSE
//    /** 1.21.6+：换 ReadView/WriteView 体系（实体存档方法 writeCustomData/readCustomData） */
//    @Inject(method = "writeCustomData", at = @At("TAIL"))
//    private void flt$saveLeash(WriteView view, CallbackInfo ci) {
//        this.writeLeashData(view, this.fltLeashData);
//    }
//
//    @Inject(method = "readCustomData", at = @At("TAIL"))
//    private void flt$loadLeash(ReadView view, CallbackInfo ci) {
//        this.readLeashData(view);
//    }
// // END IF
//}
// END IF