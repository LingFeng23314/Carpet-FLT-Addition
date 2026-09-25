package com.flt.carpetaddition;

import com.flt.carpetaddition.network.XaeroMapPayload;
import com.flt.carpetaddition.settings.FLTSettings;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Identifier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
// IF >= fabric-1.20.5
//import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
// ELSE IF <= fabric-1.20.4
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.network.PacketByteBuf;
// ELSE
//#    （理论不可达）
// END IF

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * FLT Carpet Addition 模组入口。
 * 职责：初始化日志 + 注册 Xaero 多世界地图协议模拟（仅专用服务器）+ 把扩展注册给 Carpet。
 * [VERSION] 1.20.5+ CustomPayload/PayloadTypeRegistry；1.20.4- 老式 Identifier+buf；
 * Identifier.of 仅 1.19.4+。
 * Xaero 注册只在 SERVER：xaeroworldmap:main 是 Xaero 客户端自注册 channel，
 * 客户端注册同一 channel 会与 Xaero 冲突崩溃。
 */
public class FLTAdditionMod implements ModInitializer {
    public static final String MOD_ID = "carpet-flt-addition";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("FLT Carpet Addition initializing...");
        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER) {
            registerXaeroProtocol();
        }
        FLTAdditionServer.init();
    }

    /**
     * 注册 Xaero 服务端协议模拟（xaeroworldmap:main 双向 + xaerolib:main 仅 S2C）。
     * 主发送链路在 XaeroMapPlayerManagerMixin（进世界时主动发），这里负责 C2S 握手响应。
     */
    private static void registerXaeroProtocol() {
// IF >= fabric-1.20.5
//        PayloadTypeRegistry.playS2C().register(XaeroMapPayload.WORLD_MAP_ID, XaeroMapPayload.CODEC);
//        PayloadTypeRegistry.playC2S().register(XaeroMapPayload.WORLD_MAP_ID, XaeroMapPayload.CODEC);
//        PayloadTypeRegistry.playS2C().register(XaeroMapPayload.LIB_ID, XaeroMapPayload.CODEC);
//
//        ServerPlayNetworking.registerGlobalReceiver(XaeroMapPayload.WORLD_MAP_ID, (payload, context) -> {
//            if (FLTSettings.xaeroMapName.equals("#none")) {
//                return;
//            }
//            if (payload.messageId() == XaeroMapPayload.MSG_HANDSHAKE) {
//                LOGGER.info("[XAERO] 收到客户端握手 (networkVersion={}), 规则={}，回握手 + 下发 levelId",
//                        payload.value(), FLTSettings.xaeroMapName);
//                context.responseSender().sendPacket(XaeroMapPayload.handshake());
//                context.responseSender().sendPacket(
//                        XaeroMapPayload.levelProperties(crc32(FLTSettings.xaeroMapName)));
//            }
//        });
// ELSE IF <= fabric-1.20.4
        // 老式 API：直接注册 channel（Identifier），收到包时手动解析 buf
        ServerPlayNetworking.registerGlobalReceiver(XaeroMapPayload.WORLD_MAP_ID, (server, player, handler, buf, responseSender) -> {
            if (FLTSettings.xaeroMapName.equals("#none")) {
                return;
            }
            int messageId = buf.readByte();
            int value = buf.readInt();
            if (messageId == XaeroMapPayload.MSG_HANDSHAKE) {
                LOGGER.info("[XAERO] 收到客户端握手 (networkVersion={}), 规则={}，回握手 + 下发 levelId",
                        value, FLTSettings.xaeroMapName);
                PacketByteBuf handshakeBuf = PacketByteBufs.create();
                XaeroMapPayload.writeHandshake(handshakeBuf);
                ServerPlayNetworking.send(player, XaeroMapPayload.WORLD_MAP_ID, handshakeBuf);
                PacketByteBuf propsBuf = PacketByteBufs.create();
                XaeroMapPayload.writeLevelProperties(propsBuf, crc32(FLTSettings.xaeroMapName));
                ServerPlayNetworking.send(player, XaeroMapPayload.WORLD_MAP_ID, propsBuf);
            }
        });
// ELSE
//#    （理论不可达）
// END IF
    }

    /** 世界名 → CRC32（Xaero 的 levelId；确定性：同名同 ID） */
    public static int crc32(String text) {
        CRC32 crc = new CRC32();
        crc.update(text.getBytes(StandardCharsets.UTF_8));
        return (int) crc.getValue();
    }

    /** [VERSION] Identifier.of 仅 1.19.4+；1.18.2- 用 new Identifier */
    public static Identifier id(String path) {
// IF >= fabric-1.19.4
        return Identifier.of(MOD_ID, path);
// ELSE IF <= fabric-1.18.2
//return new Identifier(MOD_ID, path);
// ELSE
//#    （理论不可达）
// END IF
    }

    /** [VERSION] 同 id() */
    public static Identifier makeId(String namespace, String path) {
// IF >= fabric-1.19.4
        return Identifier.of(namespace, path);
// ELSE IF <= fabric-1.18.2
//return new Identifier(namespace, path);
// ELSE
//#    （理论不可达）
// END IF
    }
}