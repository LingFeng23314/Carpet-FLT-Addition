package com.flt.carpetaddition.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * S2C：服务端把「仓库里一共有哪些物品、各多少」推给客户端（组合拳二期·仓储可见性）。
 *
 * <p>与 {@link FakePlayerStockPayload} 的区别（别混淆）：
 * <ul>
 *   <li>{@link FakePlayerStockPayload} = <b>假人身上</b>已备齐的计数 → 用于并入 Litematica
 *       材料列表的"已有数量"</li>
 *   <li>本类 = <b>仓库（已登记容器）里当前存了什么</b> → 用于告诉玩家"这些物品才取得到"，
 *       是 {@link com.flt.carpetaddition.storage.StockScanner#scanAll} 的结果</li>
 * </ul>
 *
 * <p>数据来源：{@code StockScanner.scanAll} / {@code scanAllBoxes} 实时遍历已登记容器聚合而成，
 * 潜影盒按 {@code StackCounter} 规则处理（混装盒不计入）。
 *
 * <p>两个字段：
 * <ul>
 *   <li>{@code contents} = 物品ID → <b>总量</b>（散装 + 单一内容盒内展开）</li>
 *   <li>{@code boxCounts} = 物品ID → <b>盒数</b>（仓库里有几盒"单一内容潜影盒"装该物品）</li>
 * </ul>
 * 客户端用 {@code contents} 显示库存、判断"仓库有没有"；用 {@code boxCounts} 判断「取整盒」是否可选。
 *
 * <p>编码与 {@link FakePlayerStockPayload} 同构：每个 map = VarInt 条目数 + 每项 (Identifier, VarInt)，
 * 依次写 contents、boxCounts。
 *
 * <p>[VERSION] 26.x: CustomPacketPayload.Type(Identifier) / FriendlyByteBuf.writeIdentifier。
 */
public record StockContentsPayload(Map<Identifier, Integer> contents,
                                   Map<Identifier, Integer> boxCounts) implements CustomPacketPayload {

    /** 通道 ID：carpet-flt-addition:stock_contents（客户端 flt-tools 需注册同名通道） */
    public static final Type<StockContentsPayload> ID =
            new Type<>(Identifier.fromNamespaceAndPath("carpet-flt-addition", "stock_contents"));

    public static final StreamCodec<FriendlyByteBuf, StockContentsPayload> CODEC =
            StreamCodec.of(StockContentsPayload::write, StockContentsPayload::read);

    public StockContentsPayload {
        contents = Collections.unmodifiableMap(new LinkedHashMap<>(contents));
        boxCounts = Collections.unmodifiableMap(new LinkedHashMap<>(boxCounts));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }

    private static void write(FriendlyByteBuf buf, StockContentsPayload payload) {
        writeMap(buf, payload.contents);
        writeMap(buf, payload.boxCounts);
    }

    private static StockContentsPayload read(FriendlyByteBuf buf) {
        return new StockContentsPayload(readMap(buf), readMap(buf));
    }

    private static void writeMap(FriendlyByteBuf buf, Map<Identifier, Integer> map) {
        buf.writeVarInt(map.size());
        for (Map.Entry<Identifier, Integer> entry : map.entrySet()) {
            buf.writeIdentifier(entry.getKey());
            buf.writeVarInt(entry.getValue());
        }
    }

    private static Map<Identifier, Integer> readMap(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        Map<Identifier, Integer> map = new LinkedHashMap<>(Math.max(16, size));
        for (int i = 0; i < size; i++) {
            map.put(buf.readIdentifier(), buf.readVarInt());
        }
        return map;
    }
}
