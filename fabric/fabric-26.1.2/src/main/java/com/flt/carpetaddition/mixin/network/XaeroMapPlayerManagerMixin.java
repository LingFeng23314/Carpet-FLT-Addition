package com.flt.carpetaddition.mixin.network;

import com.flt.carpetaddition.FLTAdditionMod;
import com.flt.carpetaddition.network.XaeroMapPayload;
import com.flt.carpetaddition.settings.FLTSettings;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Xaero 地图世界名（xaeroMapName）。注入 PlayerList.sendLevelInfo RETURN，
 * 在专用服务器向客户端发 lib 维度握手 + worldmap 握手 + LevelMapProperties(levelId)，
 * 让 Xaero 按 worldId 分离地图实例。
 */
@Mixin(PlayerList.class)
public class XaeroMapPlayerManagerMixin {

    @Inject(method = "sendLevelInfo", at = @At("RETURN"))
    private void flt$sendXaeroWorldInfo(ServerPlayer player, ServerLevel world, CallbackInfo ci) {
        if (FLTSettings.xaeroMapName.equals("#none")) {
            return;
        }
        if (FabricLoader.getInstance().getEnvironmentType() != EnvType.SERVER) {
            return;
        }
        int levelId = FLTAdditionMod.crc32(FLTSettings.xaeroMapName);
        ServerPlayNetworking.send(player, XaeroMapPayload.libDimensionHandshake());
        ServerPlayNetworking.send(player, XaeroMapPayload.handshake());
        ServerPlayNetworking.send(player, XaeroMapPayload.levelProperties(levelId));
    }
}