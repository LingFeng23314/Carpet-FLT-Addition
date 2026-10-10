package com.flt.carpetaddition.mixin.world;

import com.flt.carpetaddition.settings.FLTSettings;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 命令反馈强制开启（forceCommandFeedback）—— 把原版游戏规则 {@code send_command_feedback}
 * 锁死为 true，任何关闭它的尝试都不生效。
 *
 * <p><b>为什么能只注入两个点就全覆盖</b>：26.x 的 GameRules 已重写为泛型
 * {@code GameRules.get(GameRule<T>)} / {@code GameRules.set(GameRule<T>,T,MinecraftServer)}，
 * javap 实证全部规则读写都收敛在这两个方法上 ——
 * 读侧 {@code CommandSourceStack / GameModeCommand / ServerPlayer / CommandBlock} 全走 {@code get()}，
 * 写侧 {@code /gamerule} 命令、客户端设置包（{@code ServerGamePacketListenerImpl}）、
 * JSON-RPC、以及存档载入（{@code setAll → setFromOther → set}）全走 {@code set()}。
 * 因此这里对 {@code get}/{@code set} 各注入一处即可。
 *
 * <p>两侧都判断 {@code rule == GameRules.SEND_COMMAND_FEEDBACK}，绝不误伤其它规则。
 * 读取恒返回 true 保证行为正确；写入直接拦下保证存储值也不会被改成 false。
 */
@Mixin(GameRules.class)
public abstract class CommandFeedbackMixin {

    /** 读取：该规则恒返回 true（命令反馈行为、/gamerule 查询、命令方块输出等都走这里）。 */
    @SuppressWarnings("unchecked")
    @Inject(method = "get", at = @At("HEAD"), cancellable = true)
    private <T> void flt$commandFeedbackGet(GameRule<T> rule, CallbackInfoReturnable<T> cir) {
        if (FLTSettings.forceCommandFeedback && rule == GameRules.SEND_COMMAND_FEEDBACK) {
            // GameRule<T> 在此分支必然是 GameRule<Boolean>（仅对 SEND_COMMAND_FEEDBACK 生效），
            // 泛型擦除后返回 Boolean.TRUE 即为调用方期望的 T。
            cir.setReturnValue((T) Boolean.TRUE);
        }
    }

    /** 写入：拦下该规则的任何修改（命令 / 网络包 / JSON-RPC / 存档载入），保持 true 不被改。 */
    @Inject(method = "set", at = @At("HEAD"), cancellable = true)
    private <T> void flt$commandFeedbackSet(GameRule<T> rule, T value, MinecraftServer server, CallbackInfo ci) {
        if (FLTSettings.forceCommandFeedback && rule == GameRules.SEND_COMMAND_FEEDBACK) {
            ci.cancel();
        }
    }
}
