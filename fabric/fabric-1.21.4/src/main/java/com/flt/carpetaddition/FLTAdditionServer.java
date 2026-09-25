package com.flt.carpetaddition;

import carpet.CarpetExtension;
import carpet.CarpetServer;
import com.flt.carpetaddition.settings.FLTSettings;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.flt.carpetaddition.command.TradefinderCommand;
import com.flt.carpetaddition.fakeplayer.FakePlayerActionScheduler;
import com.mojang.brigadier.CommandDispatcher;
// IF >= fabric-1.19.4
import net.minecraft.command.CommandRegistryAccess;
// END IF
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

/**
 * FLT 的 Carpet 扩展入口。
 * 职责：让 Carpet 解析 @Rule + 注册 /Tradefinder + 驱动假人动作调度器（onTick，均 1.21+）。
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

    /** 每游戏刻驱动假人动作调度器。用 Carpet onTick 而非 fabric-api ServerTickEvents，少一层依赖差异。 */
    @Override
    public void onTick(MinecraftServer server) {
        FakePlayerActionScheduler.tick(server);
    }
//
    /** 注册 /Tradefinder 命令（carpet ≥1.19.4 为双参；≤1.18.2 为单参） */
// IF >= fabric-1.19.4
    @Override
    public void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess buildContext) {
        TradefinderCommand.register(dispatcher, buildContext);
    }
// ELSE
//    @Override
//    public void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
//        TradefinderCommand.register(dispatcher);
//    }
// END IF
//
    /** 服务器关闭时清空假人任务，避免残留状态影响下一个存档 */
    @Override
    public void onServerClosed(MinecraftServer server) {
        FakePlayerActionScheduler.clear();
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