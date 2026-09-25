package com.flt.carpetaddition.fakeplayer;

import com.flt.carpetaddition.FLTAdditionMod;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * 动作结果的反馈出口：同时写日志与指令执行者的聊天栏。
 *
 * <p>把"往哪报告"从动作逻辑里剥离，动作只需关心报告内容。执行者可能已离线，发送失败一律忽略。
 */
final class ActionReporter {
    private final CommandSourceStack feedback;

    ActionReporter(CommandSourceStack feedback) {
        this.feedback = feedback;
    }

    void report(Component message) {
        FLTAdditionMod.LOGGER.info("[Tradefinder] {}", message.getString());
        if (this.feedback == null) {
            return;
        }
        try {
            this.feedback.sendSuccess(() -> message, false);
        } catch (Throwable ignored) {
            // 执行者已离线等情况，忽略
        }
    }

    /** 坐标友好文本：{@code (x, y, z)} */
    static String posText(BlockPos pos) {
        return "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
    }
}
