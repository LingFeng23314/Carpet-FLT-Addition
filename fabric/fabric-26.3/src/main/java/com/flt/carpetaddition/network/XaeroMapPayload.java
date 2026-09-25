package com.flt.carpetaddition.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Xaero 地图生态服务端协议模拟的自定义网络包（26.x mojmap）。
 * 协议格式通过分析 Xaero 客户端字节码得出（两个 channel 都用"信封"格式）：
 *   xaerolib:main      → ID 1 = 维度握手：writeByte(1)
 *   xaeroworldmap:main → ID 0 = LevelMapProperties：writeInt(levelId)；ID 1 = 握手：writeInt(3)
 * 合规声明：独立编写，不含 Xaero World Map / XaeroLib（ARR）的任何代码或资源；
 * 网络协议格式通过分析其客户端网络行为得出，仅用于与该模组客户端互操作。
 * [VERSION] 26.x: CustomPacketPayload / Type<...>(Identifier) / StreamCodec.of / FriendlyByteBuf。
 */
public class XaeroMapPayload implements CustomPacketPayload {

    /** Xaero 世界地图 channel（客户端监听，levelId 走这里） */
    public static final Type<XaeroMapPayload> WORLD_MAP_ID =
            new Type<>(Identifier.fromNamespaceAndPath("xaeroworldmap", "main"));

    /** XaeroLib 基础 channel（客户端靠这里的维度握手启用 Server 模式） */
    public static final Type<XaeroMapPayload> LIB_ID =
            new Type<>(Identifier.fromNamespaceAndPath("xaerolib", "main"));

    // ---- xaeroworldmap:main 消息 ID ----
    public static final int MSG_LEVEL_MAP_PROPERTIES = 0;
    public static final int MSG_HANDSHAKE = 1;

    // ---- xaerolib:main 消息 ID ----
    public static final int MSG_LIB_DIMENSION_HANDSHAKE = 1;

    /** 服务端握手版本（字节码实锤：WorldMap HandshakePacket 构造 = 3） */
    public static final int HANDSHAKE_SERVER_VERSION = 3;

    /** XaeroLib 维度握手内容（字节码实锤：writeByte(1)） */
    public static final int LIB_HANDSHAKE_VALUE = 1;

    /** 编解码器：信封 = writeByte(消息ID) + 消息体（两 channel 通用） */
    public static final StreamCodec<FriendlyByteBuf, XaeroMapPayload> CODEC =
            StreamCodec.of(XaeroMapPayload::write, XaeroMapPayload::read);

    private final Type<XaeroMapPayload> channelId;
    private final int messageId;
    private final int value;

    public XaeroMapPayload(Type<XaeroMapPayload> channelId, int messageId, int value) {
        this.channelId = channelId;
        this.messageId = messageId;
        this.value = value;
    }

    public int messageId() {
        return messageId;
    }

    public int value() {
        return value;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return channelId;
    }

    /** xaeroworldmap:main · 服务端握手响应包（ID 1 + 版本 3） */
    public static XaeroMapPayload handshake() {
        return new XaeroMapPayload(WORLD_MAP_ID, MSG_HANDSHAKE, HANDSHAKE_SERVER_VERSION);
    }

    /** xaeroworldmap:main · 世界属性包（ID 0 + levelId），分图关键 */
    public static XaeroMapPayload levelProperties(int levelId) {
        return new XaeroMapPayload(WORLD_MAP_ID, MSG_LEVEL_MAP_PROPERTIES, levelId);
    }

    /** xaerolib:main · 维度握手包（ID 1 + 值 1），触发客户端 serverHasMod=true */
    public static XaeroMapPayload libDimensionHandshake() {
        return new XaeroMapPayload(LIB_ID, MSG_LIB_DIMENSION_HANDSHAKE, LIB_HANDSHAKE_VALUE);
    }

    /** 信封消息 ID 用 writeByte；内容按 channel 分发（不能用 messageId——worldmap 与 xaerolib 的 ID 数值撞车）：
     *  xaerolib 维度握手 = writeByte(1)；worldmap 两类内容 = writeInt */
    private static void write(FriendlyByteBuf buf, XaeroMapPayload payload) {
        buf.writeByte(payload.messageId);
        if (payload.channelId == LIB_ID) {
            buf.writeByte(payload.value);
        } else {
            buf.writeInt(payload.value);
        }
    }

    /** 解码（C2S 只收 worldmap channel 的客户端 HandshakePacket，内容为 writeInt） */
    private static XaeroMapPayload read(FriendlyByteBuf buf) {
        int id = buf.readByte();
        int value = buf.readInt();
        return new XaeroMapPayload(WORLD_MAP_ID, id, value);
    }
}