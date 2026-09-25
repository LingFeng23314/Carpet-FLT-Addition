package com.flt.carpetaddition.fakeplayer;

import com.flt.carpetaddition.FLTAdditionMod;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

/**
 * 动作结果的反馈出口：同时写日志与指令执行者的聊天栏。
 *
 * <p>把"往哪报告"从动作逻辑里剥离，动作只需关心报告内容。执行者可能已离线，发送失败一律忽略。
 */
final class ActionReporter {
    private final ServerCommandSource feedback;

    ActionReporter(ServerCommandSource feedback) {
        this.feedback = feedback;
    }

    void report(Text message) {
        FLTAdditionMod.LOGGER.info("[Tradefinder] {}", message.getString());
        if (this.feedback == null) {
            return;
        }
        try {
// IF >= fabric-1.20.1
//            this.feedback.sendFeedback(() -> message, false);
// ELSE
            this.feedback.sendFeedback(message, false);
// END IF
        } catch (Throwable ignored) {
            // 执行者已离线等情况，忽略
        }
    }

    /** 坐标友好文本：{@code (x, y, z)} */
    static String posText(BlockPos pos) {
        return "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
    }
}
