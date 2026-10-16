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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Set;

/**
 * 库存源持久化（组合拳二期）：把 {@link StockSource} 的坐标集合存到世界目录下的 JSON，**重启不丢**。
 *
 * <p>为什么要持久化：{@link StockSource} 是纯内存的 static Map，服务器一重启（或换存档）就清空，
 * 玩家每次开服都得重新登记一遍箱子。落盘后自动恢复。
 *
 * <p><b>文件位置</b>（2026-10-13 定稿）：{@code <游戏目录>/config/carpet-flt-addition/<世界名>/carpet-flt-stock.json}
 * —— <b>放在 world 目录之外</b>，这样"整体回档 world"的备份插件（PrimeBackup 等，{@code targets:["world"]}）
 * 就覆盖不到它，登记不会随世界回档一起丢。格式按维度分组，维度用注册名（支持 mod 维度）：
 * <pre>
 * { "minecraft:overworld": [[x,y,z], …], "minecraft:the_nether": […], "minecraft:the_end": […] }
 * </pre>
 *
 * <p><b>旧位置兼容</b>：依次尝试 {@code <world>/serverconfig/carpet-flt-addition/}（1.1.0 短暂用过）与
 * {@code <world>/carpet-flt-stock.json}（1.0.0）。加载时若当前位置不存在、旧位置存在 → 读旧文件并
 * <b>自动迁移</b>写回当前位置（旧文件保留不删，避免误删玩家数据）。
 *
 * <p><b>原子写</b>（2026-10-12）：先写 {@code .tmp} 再 {@code ATOMIC_MOVE} 替换 —— 避免"写到一半被强杀"
 * 留下截断的半个 JSON，导致下次启动 `load` 解析失败、整份登记丢失（旧实现是裸 {@code Files.writeString}）。
 *
 * <p>时机：服务器启动后由 FLTAdditionServer 首次 tick 加载一次；关闭时保存；命令改动后 / 容器被破坏后 /
 * 扫描自检清除后立即保存（这样即使服务器被强杀，登记结果也已经落盘）。
 */
public final class StockPersistence {
    private static final String FILE_NAME = "carpet-flt-stock.json";
    /** 数据目录名（= 模组 ID） */
    private static final String CONFIG_DIR = "carpet-flt-addition";
    /**
     * "钉住的登记"（潜影盒 / 预登记展示位）标记在 JSON 里的保留键。
     * 以 {@code "_"} 开头，与"namespace:path"形式的维度名天然区分（旧版本读到它会因"值不是数组"而跳过）。
     */
    private static final String PINNED_KEY = "_pinned";
    /**
     * 旧键名（2026-10-16 早先叫 "_portable"，只涵盖潜影盒）。读取时兼容，写入统一用 {@link #PINNED_KEY}。
     */
    private static final String LEGACY_PORTABLE_KEY = "_portable";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private StockPersistence() {
    }

    /**
     * 数据目录：{@code <游戏目录>/config/carpet-flt-addition/<世界名>/}。
     *
     * <p>⚠️ <b>为什么必须放在 world 目录之外</b>（2026-10-13 修正）：先前放在
     * {@code <world>/serverconfig/<modid>/}，但那仍在 world 里面 —— 用 PrimeBackup 之类
     * "整体回档 world 目录"的备份插件时，会把整个世界（含 serverconfig）替换成备份内容；
     * 而备份里若从来没有过该文件（旧版 mod 不写它），回档就等于<b>把登记表删掉</b>
     * （实测：回档后启动无任何"已加载库存源"日志 → 数据全丢）。放到 config 目录后，
     * 备份插件的 {@code targets:["world"]} 就覆盖不到它，登记不再随世界回档一起丢。
     *
     * <p>路径：
     * <ul>
     *   <li>专用服务器 → {@code <服务器目录>/config/carpet-flt-addition/<世界名>/}</li>
     *   <li>单机 → {@code .minecraft/config/carpet-flt-addition/<世界名>/}</li>
     * </ul>
     * 再用「世界名」分一层子目录，以便单机多存档时各存档互不串数据。
     */
    private static Path dirOf(MinecraftServer server) {
        Path configDir = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir();
        Path name = server.getWorldPath(LevelResource.ROOT).getFileName();
        return configDir.resolve(CONFIG_DIR).resolve(name != null ? name.toString() : "world");
    }

    /** 当前位置：{@code <游戏目录>/config/carpet-flt-addition/<世界名>/carpet-flt-stock.json} */
    private static Path fileOf(MinecraftServer server) {
        return dirOf(server).resolve(FILE_NAME);
    }

    /** 旧位置 2（1.1.0 短暂用过）：{@code <world>/serverconfig/carpet-flt-addition/}，仅用于一次性迁移 */
    private static Path legacyServerconfigFileOf(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("serverconfig")
                .resolve(CONFIG_DIR).resolve(FILE_NAME);
    }

    /** 旧位置 1（1.0.0/早期）：{@code <world>/carpet-flt-stock.json}，仅用于一次性迁移 */
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

    /**
     * 从磁盘加载库存源（服务器启动后调用一次；各处都不存在则跳过）。
     * 当前位置不存在时，依次尝试旧位置（serverconfig → world 根），读到即自动迁移写回当前位置。
     */
    public static void load(MinecraftServer server) {
        Path path = fileOf(server);
        boolean migratedFromLegacy = false;
        if (!Files.exists(path)) {
            Path legacy = firstExisting(legacyServerconfigFileOf(server), legacyRootFileOf(server));
            if (legacy == null) {
                return;   // 全部都不存在 → 首次使用，无需加载
            }
            path = legacy;
            migratedFromLegacy = true;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            // 先读"钉住的登记"（潜影盒 / 预登记展示位）标记 —— 旧文件没有该键，跳过即可（不影响普通登记）。
            // 兼容：新键 "_pinned" 优先；读不到再回退旧键 "_portable"（2026-10-16 早先的名字）。
            int pinnedLoaded = 0;
            JsonElement pinnedEl = root.get(PINNED_KEY);
            if (pinnedEl == null) {
                pinnedEl = root.get(LEGACY_PORTABLE_KEY);
            }
            if (pinnedEl != null && pinnedEl.isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : pinnedEl.getAsJsonObject().entrySet()) {
                    ResourceKey<Level> dimension = dimensionKey(entry.getKey());
                    if (dimension == null || !entry.getValue().isJsonArray()) {
                        continue;
                    }
                    for (JsonElement element : entry.getValue().getAsJsonArray()) {
                        BlockPos pos = parsePos(element);
                        if (pos != null) {
                            StockSource.setPinned(dimension, pos, true);
                            pinnedLoaded++;
                        }
                    }
                }
            }
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
            if (migratedFromLegacy) {
                // 读到旧位置数据 → 立即写回新位置；旧文件保留不删（宁可留冗余，不可误删玩家数据）
                FLTAdditionMod.LOGGER.info("[FLT] 检测到旧版库存源文件（世界根 {}），迁移到 serverconfig/{}（旧文件保留）",
                        FILE_NAME, CONFIG_DIR);
                save(server);
            }
            FLTAdditionMod.LOGGER.info("[FLT] 已加载库存源 {} 个位置（其中 {} 个钉住：潜影盒/展示位，{}）",
                    loaded, pinnedLoaded, migratedFromLegacy ? "迁移自旧位置" : FILE_NAME);
        } catch (Exception e) {
            FLTAdditionMod.LOGGER.warn("[FLT] 加载库存源失败，已忽略：{}", e.toString());
        }
    }

    /**
     * 保存库存源（关服时 + 命令改动后 + 破坏/自检清除后）。
     *
     * <p><b>原子写</b>：先写同目录 {@code .tmp}，再 {@code ATOMIC_MOVE} 覆盖目标文件。
     * 这样任何时刻磁盘上的正本要么是"旧的完整文件"、要么是"新的完整文件"，绝不会是写了一半的截断内容
     * （旧实现裸 {@code Files.writeString} 直写正本：写入过程中被强杀 → 留下半个 JSON → 下次启动解析失败、
     * 整份登记丢失）。
     */
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
        // "钉住的登记"（潜影盒 / 预登记展示位）标记：另存到保留键下（顶层以 "_" 开头，与"namespace:path"维度名区分）。
        // 必须持久化：否则重启后标记丢失，失效自检又会把"挖起待放回的潜影盒"、"有货才摆出来的展示位"清掉。
        JsonObject pinned = new JsonObject();
        for (Map.Entry<ResourceKey<Level>, Set<BlockPos>> entry : StockSource.pinnedAll().entrySet()) {
            JsonArray positions = new JsonArray();
            for (BlockPos pos : entry.getValue()) {
                JsonArray one = new JsonArray();
                one.add(pos.getX());
                one.add(pos.getY());
                one.add(pos.getZ());
                positions.add(one);
            }
            pinned.add(entry.getKey().identifier().toString(), positions);
        }
        root.add(PINNED_KEY, pinned);
        Path path = fileOf(server);
        Path tmp = path.resolveSibling(FILE_NAME + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                // 少数文件系统不支持原子移动 → 退化为普通替换（仍有"旧正本 → 新正本"的完整性保证）
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
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