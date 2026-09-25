package com.flt.carpetaddition.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;
// IF >= fabric-1.20.5
//import net.minecraft.network.codec.PacketCodec;
//import net.minecraft.network.packet.CustomPayload;
// END IF

/**
 * Xaero 地图生态服务端协议模拟的自定义网络包（独立实现）。
 * 协议格式通过分析 Xaero 客户端字节码得出（两 channel 都用"信封"格式）：
 *   xaerolib:main      → ID 1 = 维度握手：writeByte(1)
 *   xaeroworldmap:main → ID 0 = LevelMapProperties：writeInt(levelId)；ID 1 = 握手：writeInt(3)
 * 合规声明：独立编写，不含 Xaero World Map / XaeroLib（ARR）的任何代码或资源；
 * 网络协议格式通过分析其客户端网络行为得出，仅用于与该模组客户端互操作。
 * [VERSION] 1.20.5+ CustomPayload + PacketCodec + PayloadTypeRegistry；1.20.4- 老式 Identifier+buf 直写。
 */
// IF >= fabric-1.20.5
//public class XaeroMapPayload implements CustomPayload {
// ELSE IF <= fabric-1.20.4
public class XaeroMapPayload {
// ELSE
//#    （理论不可达）
// END IF

// IF >= fabric-1.20.5
//    /** Xaero 世界地图 channel（客户端监听，levelId 走这里） */
//    public static final CustomPayload.Id<XaeroMapPayload> WORLD_MAP_ID =
//            new CustomPayload.Id<>(flt$id("xaeroworldmap", "main"));
//
//    /** XaeroLib 基础 channel（客户端靠这里的维度握手启用 Server 模式） */
//    public static final CustomPayload.Id<XaeroMapPayload> LIB_ID =
//            new CustomPayload.Id<>(flt$id("xaerolib", "main"));
// ELSE IF <= fabric-1.20.4
/** Xaero 世界地图 channel（客户端监听，levelId 走这里） */
public static final Identifier WORLD_MAP_ID = flt$id("xaeroworldmap", "main");

/** XaeroLib 基础 channel（客户端靠这里的维度握手启用 Server 模式） */
public static final Identifier LIB_ID = flt$id("xaerolib", "main");
// ELSE
//#    （理论不可达）
// END IF

    /** [VERSION] Identifier.of 仅 1.19.4+；1.18.2- 用 new Identifier */
    private static Identifier flt$id(String namespace, String path) {
// IF >= fabric-1.19.4
//        return Identifier.of(namespace, path);
// ELSE IF <= fabric-1.18.2
return new Identifier(namespace, path);
// ELSE
//#    （理论不可达）
// END IF
    }

    // ---- xaeroworldmap:main 消息 ID ----
    public static final int MSG_LEVEL_MAP_PROPERTIES = 0;
    public static final int MSG_HANDSHAKE = 1;

    // ---- xaerolib:main 消息 ID ----
    public static final int MSG_LIB_DIMENSION_HANDSHAKE = 1;

    /** 服务端握手版本（字节码实锤：WorldMap HandshakePacket 构造 = 3） */
    public static final int HANDSHAKE_SERVER_VERSION = 3;

    /** XaeroLib 维度握手内容（字节码实锤：writeByte(1)） */
    public static final int LIB_HANDSHAKE_VALUE = 1;

// IF >= fabric-1.20.5
//#    /** 编解码器：信封 = writeByte(消息ID) + 消息体。
//     *  内容按类型：worldmap 两类 = writeInt；xaerolib 维度握手 = writeByte（不能用 messageId
//     *  分发——worldmap 与 xaerolib 的 ID 数值撞车都是 1） */
//    public static final PacketCodec<PacketByteBuf, XaeroMapPayload> CODEC =
//            PacketCodec.ofStatic(XaeroMapPayload::write, XaeroMapPayload::read);
//
//    private final Id<XaeroMapPayload> channelId;
//    private final int messageId;
//    private final int value;
//
//    public XaeroMapPayload(Id<XaeroMapPayload> channelId, int messageId, int value) {
//        this.channelId = channelId;
//        this.messageId = messageId;
//        this.value = value;
//    }
//
//    public int messageId() {
//        return messageId;
//    }
//
//    public int value() {
//        return value;
//    }
//
//    @Override
//    public Id<? extends CustomPayload> getId() {
//        return channelId;
//    }
//
//    /** xaeroworldmap:main · 服务端握手响应包（ID 1 + 版本 3） */
//    public static XaeroMapPayload handshake() {
//        return new XaeroMapPayload(WORLD_MAP_ID, MSG_HANDSHAKE, HANDSHAKE_SERVER_VERSION);
//    }
//
//    /** xaeroworldmap:main · 世界属性包（ID 0 + levelId），分图关键 */
//    public static XaeroMapPayload levelProperties(int levelId) {
//        return new XaeroMapPayload(WORLD_MAP_ID, MSG_LEVEL_MAP_PROPERTIES, levelId);
//    }
//
//    /** xaerolib:main · 维度握手包（ID 1 + 值 1），触发客户端 serverHasMod=true */
//    public static XaeroMapPayload libDimensionHandshake() {
//        return new XaeroMapPayload(LIB_ID, MSG_LIB_DIMENSION_HANDSHAKE, LIB_HANDSHAKE_VALUE);
//    }
//
//    private static void write(PacketByteBuf buf, XaeroMapPayload payload) {
//        buf.writeByte(payload.messageId);
//        if (payload.channelId == LIB_ID) {
//            buf.writeByte(payload.value);
//        } else {
//            buf.writeInt(payload.value);
//        }
//    }
//
//    private static XaeroMapPayload read(PacketByteBuf buf) {
//        int id = buf.readByte();
//        int value = buf.readInt();
//        return new XaeroMapPayload(WORLD_MAP_ID, id, value);
//    }
// ELSE IF <= fabric-1.20.4
/** 1.20.4- 旧网络 API：直接把"信封"写入发送方提供的 buf */

/** xaeroworldmap:main · 服务端握手响应包（ID 1 + 版本 3） */
public static void writeHandshake(PacketByteBuf buf) {
buf.writeByte(MSG_HANDSHAKE);
buf.writeInt(HANDSHAKE_SERVER_VERSION);
}

/** xaeroworldmap:main · 世界属性包（ID 0 + levelId），分图关键 */
public static void writeLevelProperties(PacketByteBuf buf, int levelId) {
buf.writeByte(MSG_LEVEL_MAP_PROPERTIES);
buf.writeInt(levelId);
}

/** xaerolib:main · 维度握手包（ID 1 + 值 1），触发客户端 serverHasMod=true */
public static void writeLibDimensionHandshake(PacketByteBuf buf) {
buf.writeByte(MSG_LIB_DIMENSION_HANDSHAKE);
buf.writeByte(LIB_HANDSHAKE_VALUE);
}
// ELSE
//#    （理论不可达）
// END IF
}