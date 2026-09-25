package com.flt.carpetaddition.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * C2S：客户端请求假人立即取"单个物品"（组合拳二期·投影中键取货）。
 *
 * <p>与批量材料需求 {@link MaterialDemandPayload} 的区别：本包只带一种物品 + 数量 + 打包标志，
 * 不带整张需求表。客户端在投影上「组合键 + 中键选取」某投影方块后发送本包，服务端让该玩家的
 * 专属备货假人自寻库存源箱子取该物品。属于批量自动备货之外的"即时单点补货"通道。
 *
 * <p>【编码（与客户端 flt-tools 严格一致，勿改）】单物品、无 size 前缀：
 * Identifier 物品ID + VarInt 数量 + 1 字节 box。
 *
 * <p>[VERSION] 2026-09-24 语义变更：{@code box} 由「整盒搬取」改为<b>打包</b> ——
 * true = 取 {@code count} 个物品（单位是<b>个</b>，不再是盒）后用空潜影盒装好再交付；
 * false = 散装原样取 {@code count} 个。字段名与字节格式<b>保持不变</b>，故与旧客户端二进制兼容。
 *
 * <p>【向后兼容】box 字段是<b>末尾追加</b>。旧客户端（未升级 flt-tools）发送的包<b>没有</b>这个字节；
 * 服务端 {@link #read} 用 {@code buf.readableBytes() > 0 && buf.readBoolean()} 判读：
 * 旧客户端包在 itemId+count 后无剩余字节 → 自动按 false（散装）处理，不解析出错。
 * 这保证「新服务端 + 旧客户端」联机仍正常，无需新增独立通道。
 *
 * <p>[VERSION] 26.x: CustomPacketPayload.Type(Identifier) / FriendlyByteBuf.writeIdentifier/writeBoolean。
 */
public record ItemFetchPayload(Identifier itemId, int count, boolean box) implements CustomPacketPayload {

    /** 通道 ID：carpet-flt-addition:item_fetch（客户端 flt-tools 需注册同名通道） */
    public static final Type<ItemFetchPayload> ID =
            new Type<>(Identifier.fromNamespaceAndPath("carpet-flt-addition", "item_fetch"));

    public static final StreamCodec<FriendlyByteBuf, ItemFetchPayload> CODEC =
            StreamCodec.of(ItemFetchPayload::write, ItemFetchPayload::read);

    /** 旧版二参构造（兼容：老调用处直接发 {itemId,count}，box 默认 false） */
    public ItemFetchPayload(Identifier itemId, int count) {
        this(itemId, count, false);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }

    private static void write(FriendlyByteBuf buf, ItemFetchPayload payload) {
        buf.writeIdentifier(payload.itemId());
        buf.writeVarInt(payload.count());
        buf.writeBoolean(payload.box());
    }

    private static ItemFetchPayload read(FriendlyByteBuf buf) {
        Identifier itemId = buf.readIdentifier();
        int count = buf.readVarInt();
        // 向后兼容旧客户端：旧包在 count 后无剩余字节 → 视为非整盒取
        boolean box = buf.readableBytes() > 0 && buf.readBoolean();
        return new ItemFetchPayload(itemId, count, box);
    }
}
