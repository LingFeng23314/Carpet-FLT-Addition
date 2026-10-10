package com.flt.carpetaddition.storage;

import com.flt.carpetaddition.FLTAdditionMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * 库存源持久化（组合拳二期）：把 {@link StockSource} 的坐标集合存到世界目录下的 JSON，**重启不丢**。
 *
 * <p>为什么要持久化：{@link StockSource} 是纯内存的 static Map，服务器一重启（或换存档）就清空，
 * 玩家每次开服都得重新登记一遍箱子。落盘后自动恢复。
 *
 * <p>文件：{@code <world>/carpet-flt-stock.json}，按维度分组，维度用注册名（支持 mod 维度）：
 * <pre>
 * { "minecraft:overworld": [[x,y,z], …], "minecraft:the_nether": […], "minecraft:the_end": […] }
 * </pre>
 *
 * <p>时机：服务器启动后由 FLTAdditionServer 首次 tick 加载一次；关闭时保存；命令改动后立即保存
 * （这样即使服务器被强杀，登记结果也已经落盘）。
 */
public final class StockPersistence {
    private static final String FILE_NAME = "carpet-flt-stock.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private StockPersistence() {
    }

    private static Path fileOf(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve(FILE_NAME);
    }

    /** 从世界目录加载库存源（服务器启动后调用一次；文件不存在则跳过） */
    public static void load(MinecraftServer server) {
        Path path = fileOf(server);
        if (!Files.exists(path)) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            int loaded = 0;
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                ResourceKey<Level> dimension = dimensionKey(entry.getKey());
                if (dimension == null || !entry.getValue().isJsonArray()) {
                    continue;
                }
                for (JsonElement element : entry.getValue().getAsJsonArray()) {
                    BlockPos pos = parsePos(element);
                    if (pos != null && StockSource.add(dimension, pos)) {
                        loaded++;
                    }
                }
            }
            FLTAdditionMod.LOGGER.info("[FLT] 已加载库存源 {} 个容器（{}）", loaded, FILE_NAME);
        } catch (Exception e) {
            FLTAdditionMod.LOGGER.warn("[FLT] 加载库存源失败，已忽略：{}", e.toString());
        }
    }

    /** 保存库存源到世界目录（关服时 + 命令改动后） */
    public static void save(MinecraftServer server) {
        JsonObject root = new JsonObject();
        for (Map.Entry<ResourceKey<Level>, Set<BlockPos>> entry : StockSource.all().entrySet()) {
            JsonArray positions = new JsonArray();
            for (BlockPos pos : entry.getValue()) {
                JsonArray one = new JsonArray();
                one.add(pos.getX());
                one.add(pos.getY());
                one.add(pos.getZ());
                positions.add(one);
            }
            // 26.x：ResourceKey 用 identifier()（不是 1.x 的 location()）
            root.add(entry.getKey().identifier().toString(), positions);
        }
        try {
            Files.writeString(fileOf(server), GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException e) {
            FLTAdditionMod.LOGGER.warn("[FLT] 保存库存源失败：{}", e.toString());
        }
    }

    /** 维度注册名 → ResourceKey（非法名字返回 null） */
    private static ResourceKey<Level> dimensionKey(String text) {
        Identifier id = Identifier.tryParse(text);
        return id == null ? null : ResourceKey.create(Registries.DIMENSION, id);
    }

    private static BlockPos parsePos(JsonElement element) {
        if (!element.isJsonArray()) {
            return null;
        }
        JsonArray array = element.getAsJsonArray();
        if (array.size() != 3) {
            return null;
        }
        try {
            return new BlockPos(array.get(0).getAsInt(), array.get(1).getAsInt(), array.get(2).getAsInt());
        } catch (Exception e) {
            return null;
        }
    }
}