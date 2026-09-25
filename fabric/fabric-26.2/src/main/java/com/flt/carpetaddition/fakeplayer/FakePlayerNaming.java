package com.flt.carpetaddition.fakeplayer;

import com.flt.carpetaddition.settings.FLTSettings;

/**
 * 备货假人命名规则：全服一个共用的备货假人，名字 = FLT 自己的 carpet 规则
 * {@code fltFakePlayerPrefix} 的值 + 固定后缀 {@code carry}（<b>不含玩家名</b>）。
 *
 * <p>集中在一处的目的：保证「批量备货 / 单件取货 / 库存回推」三条链路拼出<b>同一个</b>名字，
 * 否则同一个人会被创建出两个假人。名字不含玩家名 → 单个服务器只存在一个备货假人
 * （多人同时备货会共用它，排到同一动作队列；当前适用单机/小服）。
 *
 * <p><b>为什么读 FLT 自己的规则而非 Carpet 全局 {@code fakePlayerNamePrefix}</b>
 * （2026-09-23 用户明确）：FLT 自己注册了一条 carpet 规则 {@code fltFakePlayerPrefix}，
 * 前缀只影响 FLT 的备货假人；不影响用户在 Carpet 全局 {@code fakePlayerNamePrefix} 里
 * 给其它普通假人配的前缀。默认值 {@code "FLT"} → 假人名默认为 {@code FLTcarry}。
 * （此前一度改为读 Carpet 全局前缀，因会影响全局其它假人而被用户否决、已回退。）
 *
 * <p>注意：本类<b>不能</b>并进 {@code FakePlayerUtils} —— 那是 26.3 的版本专属适配文件
 * （在 sync 排除表里），往里面加公共代码会导致 26.3 缺方法编译失败。
 */
public final class FakePlayerNaming {
    /** 原版玩家名长度上限：假人名超过它，进服广播 player_info_update 时服务端会编码失败 */
    public static final int MAX_PLAYER_NAME_LENGTH = 16;

    /** 固定后缀，拼在 FLT 前缀之后 */
    public static final String CARRY_SUFFIX = "carry";

    private FakePlayerNaming() {
    }

    /**
     * 拼出全服共用的备货假人名：{@code fltFakePlayerPrefix + "carry"}，总长 ≤ 16。
     *
     * <p><b>⚠️ 为什么必须截断（2026-09-23 实测断连）</b>：Minecraft 玩家名上限 16 字符。
     * 假人进服时服务端要广播 {@code ClientboundPlayerInfoUpdatePacket}，名字超长会使编码抛
     * {@code EncoderException: String too big (was 18 characters, max 16)} —— 玩家被踢下线，
     * 且报错表现为一个"原版包"（player_info_update）失败，很难联想到是假人名太长。
     * 实例：旧默认前缀(5) + 长玩家名(13) = 18 → 断连。
     *
     * <p>前缀超长时截前缀，保证尾部 {@code carry} 至少保留 1 个字符不变形。
     *
     * @param playerName 玩家名（仅供日志，不再参与拼名；可传 null）
     */
    public static String botNameFor(String playerName) {
        // 读 FLT 自己的 carpet 规则；现在默认 "FLT"，不是像 Carpet 那样的 #none 占位
        String prefix = FLTSettings.fltFakePlayerPrefix;
        if (prefix == null) {
            prefix = "";
        }
        prefix = prefix.trim();
        // 前缀自身超长时先截前缀，给 carry 留至少 1 个字符
        if (prefix.length() > MAX_PLAYER_NAME_LENGTH - CARRY_SUFFIX.length()) {
            prefix = prefix.substring(0, MAX_PLAYER_NAME_LENGTH - CARRY_SUFFIX.length());
        }
        String name = prefix + CARRY_SUFFIX;
        if (name.length() > MAX_PLAYER_NAME_LENGTH) {
            name = name.substring(0, MAX_PLAYER_NAME_LENGTH);
        }
        return name;
    }
}