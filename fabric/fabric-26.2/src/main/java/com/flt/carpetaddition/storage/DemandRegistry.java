package com.flt.carpetaddition.storage;

import net.minecraft.resources.Identifier;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 材料需求登记表（组合拳二期）。
 *
 * <p>保存"每个玩家当前投影需要哪些材料、各要多少"（由客户端 {@code MaterialDemandPayload} 上报，
 * 或由命令手动设置用于测试）。自动备货调度器据此算缺口。
 *
 * <p>键为玩家 UUID（不依赖在线状态，玩家离线后需求保留，假人继续为其备货）。
 * 线程安全：只在服务端主线程读写，用普通 HashMap。
 */
public final class DemandRegistry {
    /** 玩家 UUID → (物品ID → 所需总数) */
    private static final Map<UUID, Map<Identifier, Integer>> DEMANDS = new HashMap<>();

    /** 玩家 UUID → 玩家名（用于给专属备货假人命名） */
    private static final Map<UUID, String> NAMES = new HashMap<>();

    private DemandRegistry() {
    }

    /** 覆盖设置某玩家的材料需求（同时记下玩家名，用于给备货假人命名） */
    public static void set(UUID player, String playerName, Map<Identifier, Integer> demands) {
        DEMANDS.put(player, Collections.unmodifiableMap(new HashMap<>(demands)));
        NAMES.put(player, playerName);
    }

    /** 取玩家名；找不到则用 UUID 前 8 位兜底（保证假人名唯一可复现） */
    public static String nameOf(UUID player) {
        String name = NAMES.get(player);
        return name != null ? name : player.toString().substring(0, 8);
    }

    /** 取某玩家的材料需求（没有则空表） */
    public static Map<Identifier, Integer> get(UUID player) {
        return DEMANDS.getOrDefault(player, Map.of());
    }

    /** 当前有需求登记的玩家 */
    public static Set<UUID> players() {
        return new HashSet<>(DEMANDS.keySet());
    }

    public static void clear(UUID player) {
        DEMANDS.remove(player);
        NAMES.remove(player);
    }

    /** 服务器关闭时清空 */
    public static void clearAll() {
        DEMANDS.clear();
        NAMES.clear();
    }
}