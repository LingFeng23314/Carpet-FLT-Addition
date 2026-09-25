package com.flt.carpetaddition;

import carpet.CarpetExtension;
import carpet.CarpetServer;
import com.flt.carpetaddition.command.DemandCommand;
import com.flt.carpetaddition.command.EndGatewayCommand;
import com.flt.carpetaddition.command.FetchCommand;
import com.flt.carpetaddition.command.StockCommand;
import com.flt.carpetaddition.command.TradefinderCommand;
import com.flt.carpetaddition.fakeplayer.FakePlayerActionScheduler;
import com.flt.carpetaddition.fakeplayer.FakePlayerFactory;
import com.flt.carpetaddition.fakeplayer.RestockScheduler;
import com.flt.carpetaddition.settings.FLTSettings;
import com.flt.carpetaddition.storage.DemandRegistry;
import com.flt.carpetaddition.storage.StockPersistence;
import com.flt.carpetaddition.storage.StockSource;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

/**
 * FLT 的 Carpet 扩展入口。
 * 职责：让 Carpet 解析 @Rule + 注册 /Tradefinder + 驱动假人动作调度器（onTick）。
 * 翻译文件通过 canHasTranslations 交给 Carpet。
 * 参考 AMS 的 CarpetAMSAdditionServer（manageExtension 注册方式）。
 */
public class FLTAdditionServer implements CarpetExtension {
    private static final FLTAdditionServer INSTANCE = new FLTAdditionServer();

    /** Gson + 翻译表类型提为静态常量，避免每次回调重建（匿名 TypeToken 会触发类加载） */
    private static final Gson GSON = new Gson();
    private static final Type LANG_TYPE = new TypeToken<Map<String, String>>() {}.getType();

    public static void init() {
        CarpetServer.manageExtension(INSTANCE);
    }

    @Override
    public void onGameStarted() {
        FLTAdditionMod.LOGGER.info("FLT Carpet extension loaded!");
        CarpetServer.settingsManager.parseSettingsClass(FLTSettings.class);
    }

    /** 库存源是否已在本次运行中加载过（服务器启动后首次 tick 加载一次） */
    private static boolean stockLoaded = false;

    /** 每刻驱动假人动作调度器 + 推进假人分步建档 + 自动备货调度。用 Carpet onTick 而非 fabric-api ServerTickEvents，少一层依赖差异。 */
    @Override
    public void onTick(MinecraftServer server) {
        // 首次 tick 时世界目录已就绪 → 加载持久化的库存源（懒加载，不依赖特定回调时机）
        if (!stockLoaded) {
            StockPersistence.load(server);
            stockLoaded = true;
        }
        FakePlayerActionScheduler.tick(server);
        FakePlayerFactory.tick(server);
        RestockScheduler.tick(server);
        // 假人回收：交付完成、玩家把货取空后让假人下线。刻意放在 autoRestock 判断之外——
        // 无论自动备货开不开，"取完货的假人"都必须能被回收，否则会永久占着玩家的物资。
        RestockScheduler.tickRecycle(server);
    }

    /** 注册 /Tradefinder、/Itemfetcher（stock / demand / fetch）、/Gatewayfixer 命令 */
    @Override
    public void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext) {
        TradefinderCommand.register(dispatcher, buildContext);
        EndGatewayCommand.register(dispatcher, buildContext);
        FetchCommand.register(dispatcher, buildContext);
        StockCommand.register(dispatcher, buildContext);
        DemandCommand.register(dispatcher, buildContext);
    }

    /** 服务器关闭时：先保存库存源（落盘），再清空假人任务 / 建档队列 / 备货状态，避免残留影响下一个存档 */
    @Override
    public void onServerClosed(MinecraftServer server) {
        StockPersistence.save(server);   // 必须在 StockSource.clear() 之前
        stockLoaded = false;
        FakePlayerActionScheduler.clear();
        FakePlayerFactory.clear();
        RestockScheduler.clear();
        RestockScheduler.clearPending();   // 待派单队列（未发出的手动取货请求）
        DemandRegistry.clearAll();
        StockSource.clear();
    }

    /** 把 assets/carpet-flt-addition/lang/&lt;lang&gt;.json 交给 Carpet 显示规则名称/描述 */
    @Override
    public Map<String, String> canHasTranslations(String lang) {
        InputStream langFile = FLTAdditionServer.class.getClassLoader()
                .getResourceAsStream("assets/carpet-flt-addition/lang/" + lang + ".json");
        if (langFile == null) {
            return Collections.emptyMap();
        }
        try {
            String jsonData = new String(langFile.readAllBytes(), StandardCharsets.UTF_8);
            return GSON.fromJson(jsonData, LANG_TYPE);
        } catch (IOException e) {
            return Collections.emptyMap();
        }
    }
}