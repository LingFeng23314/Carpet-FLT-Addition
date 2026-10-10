package com.flt.carpetaddition.fakeplayer;

import com.flt.carpetaddition.settings.FLTSettings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 备货假人命名规则：名字 = FLT 自己的 carpet 规则 {@code itemFetcherPrefix}
 * + 固定词 {@code fetch} + 序号 —— 例如 {@code flt_fetch1} / {@code flt_fetch2}（默认前缀 {@code flt_}）。
 *
 * <p><b>为什么是"每个玩家一组专属假人"</b>：
 * <ul>
 *   <li>2026-09-28：从"全服共用一个"改成"每人一个"。以前拼出的名字不含玩家名（即 {@code flt_fetch}），
 *       全服只有一个备货假人 —— 多人同时备货会互相抢：甲在备货时乙会被"假人正忙"挡住；
 *       更糟的是甲的交付回收逻辑可能在乙刚接手时把假人下线（见 {@link RestockScheduler#tickRecycle}），
 *       把乙的任务顶掉。改成一号一人后各自独立。</li>
 *   <li>2026-09-29：从"每人一个"扩展为"每人一组（多槽位）"—— 一份需求清单可以按数量切给
 *       多个假人并行取货（见 {@link #botNamesFor}）。<b>槽位 0 就是原来那个人</b>，
 *       所以并行关闭（{@code itemFetcherParallel = 1}）时行为与改造前完全一致。</li>
 * </ul>
 *
 * <p><b>为什么用序号而不是玩家名</b>（2026-09-28 用户选定）：Minecraft 玩家名上限 16 字符，
 * 而假人名同样受此限制（见 {@link #botNameFor(UUID, int)}）。把玩家名拼进去会挤掉前缀，
 * 且玩家名超长时截断还可能撞名（两个人截出同一个假人名 → 又变成抢一个假人）。
 * 序号短（1、2、3…）、天然唯一，在玩家列表里也容易辨认。
 *
 * <p>序号分配是<b>会话内稳定</b>的：同一玩家 + 同一槽位始终拿到同一个序号（{@link #BOT_SLOTS}
 * 记住映射），玩家离线也<b>不</b>回收序号，避免他再上线时凭空多出一个假人。
 * ⚠️ 服务器重启后映射由落盘文件恢复（见 {@link BotIndexPersistence}）；文件丢了才会重新分配。
 * 若你在备货假人的末影箱里暂存过物资（见 {@link EnderChestUtil}），换号后那些物资会留在<b>旧名字</b>
 * 的假人数据下（即假人也叫旧名才能取到）。
 *
 * <p><b>为什么读 FLT 自己的规则而非 Carpet 全局 {@code fakePlayerNamePrefix}</b>
 * （2026-09-23 用户明确）：FLT 自己注册了一条 carpet 规则 {@code itemFetcherPrefix}，
 * 前缀只影响 FLT 的备货假人；不影响用户在 Carpet 全局 {@code fakePlayerNamePrefix} 里
 * 给其它普通假人配的前缀。
 *
 * <p>注意：本类<b>不能</b>并进 {@code FakePlayerUtils} —— 那是 26.3 的版本专属适配文件
 * （在 sync 排除表里），往里面加公共代码会导致 26.3 缺方法编译失败。
 */
public final class FakePlayerNaming {
    /** 原版玩家名长度上限：假人名超过它，进服广播 player_info_update 时服务端会编码失败 */
    public static final int MAX_PLAYER_NAME_LENGTH = 16;

    /** 固定词，拼在前缀之后、序号之前；词根与命令 /Itemfetcher 一致 */
    public static final String FETCH_SUFFIX = "fetch";

    /**
     * 玩家 UUID → 该玩家各槽位（slot）的假人序号。列表下标即槽位号，0 = 主假人。
     * 只在服务端主线程访问，普通 HashMap 即可，无需并发容器。
     */
    private static final Map<UUID, List<Integer>> BOT_SLOTS = new HashMap<>();

    /**
     * 是否有「新分配的序号」尚未落盘。
     * 由 {@code FLTAdditionServer.onTick} 每刻检查，发现就立即保存（见 {@link BotIndexPersistence}）——
     * 这样即使服务器被强杀，已分配的序号也不会丢，玩家重启后仍是同一个假人。
     */
    private static boolean dirty = false;

    private FakePlayerNaming() {
    }

    /**
     * 取（或分配）该玩家第 {@code slot} 个备货假人的序号，从 1 开始。
     *
     * <p>分配规则：该槽位已分配过就直接复用；否则取<b>当前全局最小的未被占用</b>序号
     * （"未被占用" = 不在任何玩家的任何槽位里，保证全服假人名不撞）。玩家离线<b>不</b>回收序号
     * —— 这样他下次上线还是同一个假人，不会凭空多出一个。
     *
     * @param playerId 玩家 UUID；为 null 时退化为 1（仅防御，正常调用不会走到）
     * @param slot     槽位号（≥0）；0 = 主假人，负值按 0 处理
     */
    public static int indexFor(UUID playerId, int slot) {
        if (playerId == null) {
            return 1;
        }
        int want = Math.max(0, slot);
        List<Integer> slots = BOT_SLOTS.computeIfAbsent(playerId, k -> new ArrayList<>());
        while (slots.size() <= want) {
            slots.add(nextFreeIndex());
            dirty = true;   // 新增分配 → 标记待落盘
        }
        return slots.get(want);
    }

    /** 便捷方法：主假人（槽位 0）的序号 —— 等价于改造前的 {@code indexFor(UUID)} */
    public static int indexFor(UUID playerId) {
        return indexFor(playerId, 0);
    }

    /** 当前全局最小的未被占用序号 */
    private static int nextFreeIndex() {
        Set<Integer> used = new HashSet<>();
        for (List<Integer> list : BOT_SLOTS.values()) {
            used.addAll(list);
        }
        int index = 1;
        while (used.contains(index)) {
            index++;
        }
        return index;
    }

    /**
     * 确保该玩家前 {@code count} 个槽位都已分配序号，返回它们的假人名（顺序 = 槽位 0..count-1）。
     *
     * <p>用途：多假人并行备货前一次性把 N 个假人的名字都定下来。副作用是<b>会分配</b>尚未使用的序号
     * —— 这是有意的：名字一旦分配就落盘，避免"这批用了 3 号、下批 3 号被别的玩家拿走"。
     */
    public static List<String> botNamesFor(UUID playerId, int count) {
        int n = Math.max(0, count);
        List<String> names = new ArrayList<>(n);
        for (int slot = 0; slot < n; slot++) {
            names.add(botNameFor(playerId, slot));
        }
        return names;
    }

    /** 该玩家当前已分配几个槽位（0 = 还从没建过备货假人） */
    public static int slotCount(UUID playerId) {
        List<Integer> slots = playerId == null ? null : BOT_SLOTS.get(playerId);
        return slots == null ? 0 : slots.size();
    }

    /** 清空序号分配表（服务器关闭时调用，避免跨存档残留） */
    public static void clear() {
        BOT_SLOTS.clear();
        dirty = false;
    }

    /** 取出并清除「待落盘」标记；true = 序号表有新变化需要保存 */
    public static boolean consumeDirty() {
        if (!dirty) {
            return false;
        }
        dirty = false;
        return true;
    }

    /** 导出当前分配表的副本（持久化用；外部改动不会影响内部状态） */
    public static Map<UUID, List<Integer>> snapshot() {
        Map<UUID, List<Integer>> copy = new HashMap<>();
        for (Map.Entry<UUID, List<Integer>> entry : BOT_SLOTS.entrySet()) {
            copy.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return copy;
    }

    /**
     * 从持久化数据恢复分配表（服务器启动时调用一次）。
     *
     * <p>防御式读取：非法序号（null / ≤0）或与已恢复项<b>冲突</b>的条目直接跳过 ——
     * 它们会在下次请求时重新分配，不会因为文件里一条坏数据把整张表搞乱。
     */
    public static void restore(Map<UUID, List<Integer>> saved) {
        BOT_SLOTS.clear();
        dirty = false;
        if (saved == null || saved.isEmpty()) {
            return;
        }
        Set<Integer> used = new HashSet<>();
        for (Map.Entry<UUID, List<Integer>> entry : saved.entrySet()) {
            UUID id = entry.getKey();
            List<Integer> raw = entry.getValue();
            if (id == null || raw == null || raw.isEmpty()) {
                continue;
            }
            List<Integer> clean = new ArrayList<>(raw.size());
            for (Integer index : raw) {
                if (index == null || index <= 0 || used.contains(index)) {
                    continue;   // 非法 / 撞号 → 跳过该槽位（只影响它自己的下标，不污染全局）
                }
                clean.add(index);
                used.add(index);
            }
            if (!clean.isEmpty()) {
                BOT_SLOTS.put(id, clean);
            }
        }
    }

    /** 当前全服已分配出去多少个假人序号（日志/调试用） */
    public static int assignedCount() {
        int total = 0;
        for (List<Integer> list : BOT_SLOTS.values()) {
            total += list.size();
        }
        return total;
    }

    /**
     * 拼出该玩家第 {@code slot} 个备货假人的名字：{@code itemFetcherPrefix + "fetch" + 序号}，总长 ≤ 16。
     *
     * <p><b>⚠️ 为什么必须截断（2026-09-23 实测断连）</b>：Minecraft 玩家名上限 16 字符。
     * 假人进服时服务端要广播 {@code ClientboundPlayerInfoUpdatePacket}，名字超长会使编码抛
     * {@code EncoderException: String too big (was 18 characters, max 16)} —— 玩家被踢下线，
     * 且报错表现为一个"原版包"（player_info_update）失败，很难联想到是假人名太长。
     * 实例：早期设计（前缀 + 玩家名）下 5 + 13 = 18 → 断连。
     *
     * <p>截断时<b>先砍前缀</b>：序号是区分不同假人的关键，{@code fetch} 是识别用途的词根，
     * 两者都优先保住；前缀是用户可配置项，最该让位。
     *
     * @param playerId 玩家 UUID（分配序号用）
     * @param slot     槽位号（0 = 主假人）
     */
    public static String botNameFor(UUID playerId, int slot) {
        String tail = FETCH_SUFFIX + indexFor(playerId, slot);
        int room = MAX_PLAYER_NAME_LENGTH - tail.length();
        if (room <= 0) {
            // 极端情况：序号位数多到连 fetch 都放不下（16 字符内序号最多 7 位，正常到不了）。
            // 硬截 tail 兜底 —— 宁可名字难看，也不能让服务端编码失败把客户端踢下线。
            return tail.substring(0, Math.min(tail.length(), MAX_PLAYER_NAME_LENGTH));
        }
        String prefix = FLTSettings.itemFetcherPrefix;
        if (prefix == null) {
            prefix = "";
        }
        prefix = prefix.trim();
        if (prefix.length() > room) {
            prefix = prefix.substring(0, room);
        }
        return prefix + tail;
    }

    /**
     * 便捷方法：该玩家主假人（槽位 0）的名字 —— 等价于改造前的 {@code botNameFor(UUID)}。
     * 单假人链路（自动备货、单件取货、分批续备）全部走这个名字。
     */
    public static String botNameFor(UUID playerId) {
        return botNameFor(playerId, 0);
    }
}
