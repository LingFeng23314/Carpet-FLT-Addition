package com.flt.carpetaddition;

import com.flt.carpetaddition.fakeplayer.RestockScheduler;
import com.flt.carpetaddition.network.FakePlayerStockPayload;
import com.flt.carpetaddition.network.ItemFetchPayload;
import com.flt.carpetaddition.network.MaterialDemandPayload;
import com.flt.carpetaddition.network.RestockAllPayload;
import com.flt.carpetaddition.network.StockContentsPayload;
import com.flt.carpetaddition.network.StockQueryPayload;
import com.flt.carpetaddition.network.XaeroMapPayload;
import com.flt.carpetaddition.settings.FLTSettings;
import com.flt.carpetaddition.storage.DemandRegistry;
import com.flt.carpetaddition.storage.StockScanner;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.CRC32;

/**
 * FLT Carpet Addition 模组入口（26.x mojmap）。
 * 职责：初始化日志 + 注册 Xaero 多世界地图协议模拟（仅专用服务器）+ 把扩展注册给 Carpet。
 * [VERSION] Identifier.fromNamespaceAndPath（26.x）；PayloadTypeRegistry.clientboundPlay/serverboundPlay。
 */
public class FLTAdditionMod implements ModInitializer {
    public static final String MOD_ID = "carpet-flt-addition";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    /**
     * 仓储内容查询节流表：玩家 UUID → 上次查询时刻（毫秒）。
     *
     * <p>为什么需要：{@code StockScanner.scanAll} 要遍历全部已登记容器，是有成本的操作。
     * 正常使用（打开取货界面时查一次）远达不到阈值；这里只防异常/恶意客户端高频刷查询把服务端拖住。
     */
    private static final Map<UUID, Long> LAST_STOCK_QUERY_MS = new ConcurrentHashMap<>();

    /** 同一玩家仓储查询的最小间隔（毫秒）：间隔内的重复查询直接忽略，客户端稍后重试即可拿到 */
    private static final long STOCK_QUERY_COOLDOWN_MS = 1000L;

    @Override
    public void onInitialize() {
        LOGGER.info("FLT Carpet Addition initializing...");
        // Xaero 相关只在专用服务器执行：xaeroworldmap:main 是 Xaero 客户端自己注册的 channel，
        // 客户端进程注册同一 channel 会与 Xaero 冲突导致崩溃。
        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER) {
            registerXaeroProtocol();
        }
        // 组合拳协议：单人 / 联机都必须注册。
        // ⚠️ 绝不能挪进上面的 EnvType.SERVER 判断：单人游戏里本进程既是客户端又是集成服务端，
        //    EnvType 是 CLIENT —— 一旦跳过注册，集成服务端就不会向客户端声明 item_fetch 等通道，
        //    客户端 ClientPlayNetworking.canSend() 恒为 false，表现为
        //    「发送失败：未连接到支持该通道的服务器」。
        registerFltProtocol();
        FLTAdditionServer.init();
    }

    /**
     * 注册 Xaero 服务端协议模拟（xaeroworldmap:main 双向 + xaerolib:main 仅 S2C）。
     * 主发送链路在 XaeroMapPlayerManagerMixin（进世界时主动发），这里负责 C2S 握手响应。
     */
    private static void registerXaeroProtocol() {
        PayloadTypeRegistry.clientboundPlay().register(XaeroMapPayload.WORLD_MAP_ID, XaeroMapPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(XaeroMapPayload.WORLD_MAP_ID, XaeroMapPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(XaeroMapPayload.LIB_ID, XaeroMapPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(XaeroMapPayload.WORLD_MAP_ID, (payload, context) -> {
            if (FLTSettings.xaeroMapName.equals("#none")) {
                return;
            }
            if (payload.messageId() == XaeroMapPayload.MSG_HANDSHAKE) {
                LOGGER.info("[XAERO] 收到客户端握手 (networkVersion={}), 规则={}，回握手 + 下发 levelId",
                        payload.value(), FLTSettings.xaeroMapName);
                context.responseSender().sendPacket(XaeroMapPayload.handshake());
                context.responseSender().sendPacket(
                        XaeroMapPayload.levelProperties(crc32(FLTSettings.xaeroMapName)));
            }
        });
    }

    /**
     * 注册 FLT 组合拳二期协议：
     * C2S 收客户端上报的材料需求（material_demand）+ 单物品即刻取货（item_fetch）；
     * S2C 推假人身上的备货计数（fakeplayer_stock）。
     *
     * <p>单人 / 联机都要注册（见 {@link #onInitialize()} 的说明）。客户端侧由 flt-tools 注册同名通道。
     */
    private static void registerFltProtocol() {
        // ⚠️ 类型注册必须排在 registerGlobalReceiver 之前：fabric 的 GlobalReceiverRegistry
        //    会先 assertPayloadType（类型没注册就抛 IllegalArgumentException）。
        registerPayloadType(() ->
                PayloadTypeRegistry.serverboundPlay().register(MaterialDemandPayload.ID, MaterialDemandPayload.CODEC));
        registerPayloadType(() ->
                PayloadTypeRegistry.clientboundPlay().register(FakePlayerStockPayload.ID, FakePlayerStockPayload.CODEC));
        // 单物品取货（C2S）：投影上「组合键 + 中键」选取 → 假人自寻箱子取指定物品指定数量
        registerPayloadType(() ->
                PayloadTypeRegistry.serverboundPlay().register(ItemFetchPayload.ID, ItemFetchPayload.CODEC));
        // 仓储内容查询（C2S，空载荷）：客户端打开取货界面时问一次「仓库里有什么」
        registerPayloadType(() ->
                PayloadTypeRegistry.serverboundPlay().register(StockQueryPayload.ID, StockQueryPayload.CODEC));
        // 仓储内容回推（S2C）：实时扫描仓库后把「物品ID → 总数」发回该玩家
        registerPayloadType(() ->
                PayloadTypeRegistry.clientboundPlay().register(StockContentsPayload.ID, StockContentsPayload.CODEC));
        // 假人备货（C2S，空载荷）：投影材料列表点「假人备货」→ 请求一键备齐+装箱送回
        registerPayloadType(() ->
                PayloadTypeRegistry.serverboundPlay().register(RestockAllPayload.ID, RestockAllPayload.CODEC));

        // 收到客户端上报的投影材料需求 → 存起来，供自动备货调度器算缺口；并立刻回推一次当前假人库存
        ServerPlayNetworking.registerGlobalReceiver(MaterialDemandPayload.ID, (payload, context) -> {
            ServerPlayer player = context.player();
            DemandRegistry.set(player.getUUID(), player.getName().getString(), payload.demands());
            LOGGER.info("[FLT] 玩家 {} 上报材料需求 {} 种", player.getName().getString(), payload.demands().size());
            RestockScheduler.pushCurrentStock(player.level().getServer(), player.getUUID());
        });

        // 玩家进服时，若服务端已有其需求，推一次当前假人库存，让材料列表立刻显示"已备多少"
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                RestockScheduler.pushCurrentStock(server, handler.player.getUUID()));

        // 单物品取货请求 → 派该玩家的专属备货假人取货
        ServerPlayNetworking.registerGlobalReceiver(ItemFetchPayload.ID, (payload, context) -> {
            ServerPlayer player = context.player();
            boolean dispatched = RestockScheduler.fetchSingle(
                    player.level().getServer(),
                    player.getUUID(),
                    player.getName().getString(),
                    payload.itemId(),
                    payload.count(),
                    payload.box());
            // [VERSION] 2026-09-24：box 语义已由「整盒搬取」改为「打包」，日志文案同步
            LOGGER.info("[FLT] 玩家 {} 请求假人取 {} x{}{} → {}",
                    player.getName().getString(), payload.itemId(), payload.count(),
                    payload.box() ? "(打包)" : "",
                    dispatched ? "已派单" : "未派单(假人忙/无货/建档中)");
        });

        // 仓储内容查询 → 现扫一次仓库，把「物品ID → 总数」回推给该玩家
        ServerPlayNetworking.registerGlobalReceiver(StockQueryPayload.ID, (payload, context) -> {
            ServerPlayer player = context.player();

            // 节流：同一玩家 1 秒内只响应一次（scanAll 有成本，防高频刷）
            long now = System.currentTimeMillis();
            Long last = LAST_STOCK_QUERY_MS.get(player.getUUID());
            if (last != null && now - last < STOCK_QUERY_COOLDOWN_MS) {
                LOGGER.debug("[FLT] 玩家 {} 的仓储查询被节流（{} ms 冷却中）",
                        player.getName().getString(), STOCK_QUERY_COOLDOWN_MS);
                return;
            }
            LAST_STOCK_QUERY_MS.put(player.getUUID(), now);

            // 实时扫描：拿到的是"现在这一刻"的仓库内容（玩家手动搬动箱子后也立即准确）
            var server = player.level().getServer();
            Map<Identifier, Integer> contents = StockScanner.scanAll(server);
            Map<Identifier, Integer> boxCounts = StockScanner.scanAllBoxes(server);
            ServerPlayNetworking.send(player, new StockContentsPayload(contents, boxCounts));
            LOGGER.info("[FLT] 玩家 {} 查询仓库内容 → 回推 {} 种物品（{} 种有盒装）",
                    player.getName().getString(), contents.size(), boxCounts.size());
        });

        // 投影「假人备货」按钮 → 一键备齐 + 装箱送回
        ServerPlayNetworking.registerGlobalReceiver(RestockAllPayload.ID, (payload, context) -> {
            ServerPlayer player = context.player();
            MinecraftServer server = player.level().getServer();
            // 先主动上报一次当前投影需求（按钮点击前客户端会先 sendDemand，这里兜底保证有需求）
            boolean ok = RestockScheduler.restockAllBoxed(server, player.getUUID(), player.getName().getString());
            LOGGER.info("[FLT] 玩家 {} 点击「假人备货」 → {}",
                    player.getName().getString(),
                    ok ? "已启动备货装箱" : "未启动（假人忙/未建档/无需求）");
        });
    }

    /**
     * 注册一个 payload 类型；已被其它半边注册过则跳过。
     *
     * <p>为什么需要：单人游戏里本 mod（服务端半边）与 flt-tools（客户端半边）同处一个 JVM，
     * 两侧都会注册同一批通道，而 fabric 的 {@code PayloadTypeRegistry.register()}
     * 对重复注册会抛 {@code IllegalArgumentException("Packet type ... is already registered!")}。
     * 类型只需注册一次、谁先注册都行 → 把重复视为"已完成"。
     */
    private static void registerPayloadType(Runnable registerCall) {
        try {
            registerCall.run();
        } catch (IllegalArgumentException alreadyRegistered) {
            LOGGER.info("[FLT] payload 类型已由同 JVM 的另一半注册，跳过：{}", alreadyRegistered.getMessage());
        }
    }

    /** 世界名 → CRC32（Xaero 的 levelId；确定性：同名同 ID） */
    public static int crc32(String text) {
        CRC32 crc = new CRC32();
        crc.update(text.getBytes(StandardCharsets.UTF_8));
        return (int) crc.getValue();
    }

    /** 生成 carpet-flt-addition:xxx 形式的 Identifier */
    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    /** 通用 Identifier 工厂（自定义命名空间用，如 flt 附魔标签） */
    public static Identifier makeId(String namespace, String path) {
        return Identifier.fromNamespaceAndPath(namespace, path);
    }
}