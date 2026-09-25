package com.flt.carpetaddition.mixin.network;

import com.flt.carpetaddition.FLTAdditionMod;
import com.flt.carpetaddition.network.XaeroMapPayload;
import com.flt.carpetaddition.settings.FLTSettings;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
// IF <= fabric-1.20.4
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.network.PacketByteBuf;
// END IF

/**
 * Xaero 地图世界名（xaeroMapName）。
 注入 PlayerManager.sendWorldInfo RETURN：在专用服务器向客户端发 lib 维度握手 + worldmap 握手 +
 LevelMapProperties(levelId)，让 Xaero 按 worldId 分离地图实例。
 * [VERSION] 1.20.5+ CustomPayload 发送；1.20.4- 老式 Identifier+buf 发送。
 * 仅在专用服务器发送（payload codec 只在 EnvType.SERVER 注册）。
 */
@Mixin(PlayerManager.class)
public class XaeroMapPlayerManagerMixin {

    @Inject(method = "sendWorldInfo", at = @At("RETURN"))
    private void flt$sendXaeroWorldInfo(ServerPlayerEntity player, ServerWorld world, CallbackInfo ci) {
        if (FLTSettings.xaeroMapName.equals("#none")) {
            return;
        }
        if (FabricLoader.getInstance().getEnvironmentType() != EnvType.SERVER) {
            return;
        }
        int levelId = FLTAdditionMod.crc32(FLTSettings.xaeroMapName);
// IF >= fabric-1.20.5
//        ServerPlayNetworking.send(player, XaeroMapPayload.libDimensionHandshake());
//        ServerPlayNetworking.send(player, XaeroMapPayload.handshake());
//        ServerPlayNetworking.send(player, XaeroMapPayload.levelProperties(levelId));
// ELSE IF <= fabric-1.20.4
        PacketByteBuf dimBuf = PacketByteBufs.create();
        XaeroMapPayload.writeLibDimensionHandshake(dimBuf);
        ServerPlayNetworking.send(player, XaeroMapPayload.LIB_ID, dimBuf);
        PacketByteBuf hsBuf = PacketByteBufs.create();
        XaeroMapPayload.writeHandshake(hsBuf);
        ServerPlayNetworking.send(player, XaeroMapPayload.WORLD_MAP_ID, hsBuf);
        PacketByteBuf propsBuf = PacketByteBufs.create();
        XaeroMapPayload.writeLevelProperties(propsBuf, levelId);
        ServerPlayNetworking.send(player, XaeroMapPayload.WORLD_MAP_ID, propsBuf);
// ELSE
//#    （理论不可达）
// END IF
    }
}