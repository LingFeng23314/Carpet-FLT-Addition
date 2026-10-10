package com.flt.carpetaddition.fakeplayer;

import com.flt.carpetaddition.FLTAdditionMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 备货假人「序号分配表」的持久化（2026-09-28）。
 *
 * <p><b>为什么需要它</b>：{@link FakePlayerNaming} 的序号分配表（玩家 UUID → 序号）原本只存在内存里，
 * 服务器一重启就清空、序号从 1 重新分配 —— 玩家可能换到别的序号。平时无碍，但<b>备货假人的末影箱
 * 是按假人身份（名字）存的</b>：换号之后，你之前暂存在 {@code flt_fetch1} 末影箱里的物资，
 * 会随着新玩家占用 1 号而"错位"，或留在没人再登录的旧名字下取不出来。落盘后编号保持稳定。
 *
 * <p><b>文件</b>：{@code <world>/carpet-flt-bots.json}。
 * <p>[VERSION] 2026-09-29 多假人并行备货 —— 值由"单个序号"升级为"该玩家的槽位序号数组"
 * （槽位 0 = 主假人），形如
 * <pre>{ "d3f8a1e2-…": [1, 2, 3], "a1b2c3d4-…": [4] }</pre>
 * 读取时<b>兼容旧格式</b>（值是裸数字 {@code {"uuid": 1}}，即改造前的"一人一号"），
 * 按"只有槽位 0"处理 —— 旧存档升级后玩家仍然用原来的假人，不会凭空换号。
 * 与 {@link com.flt.carpetaddition.storage.StockPersistence}（库存源）是同目录、同套做法的姊妹文件；
 * 分开存是因为两者生命周期不同 —— 序号分配表一旦写就不常变，库存源则随玩家登记频繁变动。
 *
 * <p><b>时机</b>：服务器启动后首次 tick 加载一次；之后每当有新序号被分配（{@link FakePlayerNaming#consumeDirty()}）
 * 立即落盘，这样即使服务器被强杀，已分配的序号也不会丢；关服时再保存一次兜底。
 */
public final class BotIndexPersistence {
    private static final String FILE_NAME = "carpet-flt-bots.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private BotIndexPersistence() {
    }

    private static Path fileOf(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve(FILE_NAME);
    }

    /** 从世界目录加载序号分配表（服务器启动后调用一次；文件不存在则跳过） */
    public static void load(MinecraftServer server) {
        Path path = fileOf(server);
        if (!Files.exists(path)) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            Map<UUID, List<Integer>> saved = new HashMap<>();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                try {
                    UUID id = UUID.fromString(entry.getKey());
                    List<Integer> indexes = new ArrayList<>();
                    JsonElement value = entry.getValue();
                    if (value != null && value.isJsonArray()) {
                        for (JsonElement element : value.getAsJsonArray()) {
                            indexes.add(element.getAsInt());
                        }
                    } else if (value != null && value.isJsonPrimitive()) {
                        indexes.add(value.getAsInt());   // 旧格式（改造前的一人一号）
                    }
                    if (!indexes.isEmpty()) {
                        saved.put(id, indexes);
                    }
                } catch (Exception ignored) {
                    // 单条损坏就跳过，不影响其余条目（例如手动改过文件）
                }
            }
            FakePlayerNaming.restore(saved);
            FLTAdditionMod.LOGGER.info("[FLT] 已加载备货假人序号分配 {} 个玩家 / {} 个假人（{}）",
                    saved.size(), FakePlayerNaming.assignedCount(), FILE_NAME);
        } catch (Exception e) {
            FLTAdditionMod.LOGGER.warn("[FLT] 加载备货假人序号分配失败，已忽略：{}", e.toString());
        }
    }

    /** 把当前序号分配表写入世界目录（新分配后 + 关服时） */
    public static void save(MinecraftServer server) {
        JsonObject root = new JsonObject();
        for (Map.Entry<UUID, List<Integer>> entry : FakePlayerNaming.snapshot().entrySet()) {
            root.add(entry.getKey().toString(), GSON.toJsonTree(entry.getValue()));
        }
        try {
            Files.writeString(fileOf(server), GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException e) {
            FLTAdditionMod.LOGGER.warn("[FLT] 保存备货假人序号分配失败：{}", e.toString());
        }
    }
}
