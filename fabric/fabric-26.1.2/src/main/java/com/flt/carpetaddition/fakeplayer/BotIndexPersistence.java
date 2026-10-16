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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
 * <p><b>文件</b>（2026-10-13 定稿）：{@code <游戏目录>/config/carpet-flt-addition/<世界名>/carpet-flt-bots.json}
 * —— 放在 <b>world 目录之外</b>，避免"整体回档 world"的备份插件把它一起覆盖（与
 * {@link com.flt.carpetaddition.storage.StockPersistence} 同目录、同套做法）。
 * <p>[VERSION] 2026-09-29 多假人并行备货 —— 值由"单个序号"升级为"该玩家的槽位序号数组"
 * （槽位 0 = 主假人），形如
 * <pre>{ "d3f8a1e2-…": [1, 2, 3], "a1b2c3d4-…": [4] }</pre>
 * 读取时<b>兼容旧格式</b>（值是裸数字 {@code {"uuid": 1}}，即改造前的"一人一号"），
 * 按"只有槽位 0"处理 —— 旧存档升级后玩家仍然用原来的假人，不会凭空换号。
 * 与 {@link com.flt.carpetaddition.storage.StockPersistence}（库存源）是同目录、同套做法的姊妹文件；
 * 分开存是因为两者生命周期不同 —— 序号分配表一旦写就不常变，库存源则随玩家登记频繁变动。
 *
 * <p><b>旧位置兼容</b>：依次尝试 {@code <world>/serverconfig/carpet-flt-addition/}（1.1.0 短暂用过）与
 * {@code <world>/carpet-flt-bots.json}（更早）。加载时当前位置不存在、旧位置存在 → 读旧文件并自动迁移
 * 写回当前位置（旧文件保留不删）。
 *
 * <p><b>时机</b>：服务器启动后首次 tick 加载一次；之后每当有新序号被分配（{@link FakePlayerNaming#consumeDirty()}）
 * 立即落盘，这样即使服务器被强杀，已分配的序号也不会丢；关服时再保存一次兜底。
 */
public final class BotIndexPersistence {
    private static final String FILE_NAME = "carpet-flt-bots.json";
    /** 数据目录名（= 模组 ID，与库存源同目录） */
    private static final String CONFIG_DIR = "carpet-flt-addition";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private BotIndexPersistence() {
    }

    /**
     * 数据目录：{@code <游戏目录>/config/carpet-flt-addition/<世界名>/}。
     * ⚠️ 与 {@link com.flt.carpetaddition.storage.StockPersistence} 同理：放在 <b>world 目录之外</b>，
     * 避免"整体回档 world"的备份插件（PrimeBackup 等）把它一起覆盖掉（2026-10-13 修正）。
     */
    private static Path dirOf(MinecraftServer server) {
        Path configDir = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir();
        Path name = server.getWorldPath(LevelResource.ROOT).getFileName();
        return configDir.resolve(CONFIG_DIR).resolve(name != null ? name.toString() : "world");
    }

    /** 当前位置：{@code <游戏目录>/config/carpet-flt-addition/<世界名>/carpet-flt-bots.json} */
    private static Path fileOf(MinecraftServer server) {
        return dirOf(server).resolve(FILE_NAME);
    }

    /** 旧位置 2（1.1.0 短暂用过）：{@code <world>/serverconfig/carpet-flt-addition/} */
    private static Path legacyServerconfigFileOf(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("serverconfig")
                .resolve(CONFIG_DIR).resolve(FILE_NAME);
    }

    /** 旧位置 1（早期）：{@code <world>/carpet-flt-bots.json} */
    private static Path legacyRootFileOf(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve(FILE_NAME);
    }

    /** 返回第一个实际存在的路径；都不存在返回 null（保持传入顺序即优先级） */
    private static Path firstExisting(Path... candidates) {
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** 从磁盘加载序号分配表（服务器启动后调用一次；各处都不存在则跳过）。
     * 当前位置不存在时依次尝试旧位置（serverconfig → world 根），读到即自动迁移写回当前位置。 */
    public static void load(MinecraftServer server) {
        Path path = fileOf(server);
        boolean migratedFromLegacy = false;
        if (!Files.exists(path)) {
            Path legacy = firstExisting(legacyServerconfigFileOf(server), legacyRootFileOf(server));
            if (legacy == null) {
                return;
            }
            path = legacy;
            migratedFromLegacy = true;
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
            if (migratedFromLegacy) {
                FLTAdditionMod.LOGGER.info("[FLT] 检测到旧版假人序号文件（世界根 {}），迁移到 serverconfig/{}（旧文件保留）",
                        FILE_NAME, CONFIG_DIR);
                save(server);
            }
            FLTAdditionMod.LOGGER.info("[FLT] 已加载备货假人序号分配 {} 个玩家 / {} 个假人（{}）",
                    saved.size(), FakePlayerNaming.assignedCount(),
                    migratedFromLegacy ? "迁移自旧位置" : FILE_NAME);
        } catch (Exception e) {
            FLTAdditionMod.LOGGER.warn("[FLT] 加载备货假人序号分配失败，已忽略：{}", e.toString());
        }
    }

    /** 把当前序号分配表写入世界目录（新分配后 + 关服时）。原子写，避免截断（同 StockPersistence）。 */
    public static void save(MinecraftServer server) {
        JsonObject root = new JsonObject();
        for (Map.Entry<UUID, List<Integer>> entry : FakePlayerNaming.snapshot().entrySet()) {
            root.add(entry.getKey().toString(), GSON.toJsonTree(entry.getValue()));
        }
        Path path = fileOf(server);
        Path tmp = path.resolveSibling(FILE_NAME + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            FLTAdditionMod.LOGGER.warn("[FLT] 保存备货假人序号分配失败：{}", e.toString());
        }
    }
}
