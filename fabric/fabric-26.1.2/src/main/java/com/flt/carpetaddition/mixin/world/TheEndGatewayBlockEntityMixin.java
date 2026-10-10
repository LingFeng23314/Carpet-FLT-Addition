package com.flt.carpetaddition.mixin.world;

import com.flt.carpetaddition.settings.FLTSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 末地折跃门配对出口的半径与造岛高度（endGatewayExitSearchDistance / endGatewayIslandHeight）。
 *
 * <p><b>一、出口半径</b>（javap 反汇编 26.x 的 {@code findExitPortalXZPosTentative} 实证）——
 * 原版逻辑分四步：
 * <ol>
 *   <li>候选点 = 门相对原点的方向归一化后 × 1024 —— 只有这一步真的在半径 1024 的圆上；</li>
 *   <li>若候选点落在<b>非空区块</b>（已经有岛），沿同一条射线每次<b>后退 16 格</b>，退到虚空为止；</li>
 *   <li>再沿射线每次<b>前进 16 格</b>（最多 16 次 = 256 格），找第一个非空区块；</li>
 *   <li>在该区块找落脚点；找不到就<b>当场生成一座小岛</b>（Y=75），门落在该处上方 10 格。</li>
 * </ol>
 * 也就是说原版的 1024 <b>只是个搜索起点</b>，最终落点由「这条射线上最近的那座岛」决定 ——
 * 这正是「把规则改成 768、门却生成在 900 开外」的原因。
 *
 * <p>本 Mixin 在规则 &gt;0 时<b>直接短路原方法</b>，返回「门的方向 × 规则值」这个精确坐标，
 * 完全跳过找岛搜索；随后原版 {@code findOrCreateValidTeleportPos} 会照常接手 ——
 * 该区块已有岛就落在岛上，是虚空则就地生成一座小岛，于是配对门<b>精确落在指定半径上</b>
 * （仍受区块粒度与 findTallestBlock 影响，实际偏差约在 ±30 格内）。
 *
 * <p><b>二、造岛高度</b>（endGatewayIslandHeight）—— 只在上面的第 4 步「找不到落脚点、
 * 需要当场造岛」时才生效。原版把生成基准写死为 {@code Y=75.0}
 * （javap 实证：{@code BlockPos.containing(x + 0.5d, 75.0d, z + 0.5d)}，三版位置一致）。
 * 那座岛由 {@code EndFeatures.END_ISLAND}（{@code EndIslandFeature}）生成，
 * 它以该基准点为<b>最高层</b>向下逐层收缩（半径 4~6 的圆盘）→ 所以基准高度≈岛顶高度，
 * 门再落在此高度 +10（{@code getPortalPosition} 里的 {@code above(10)}）→ 岛和门一起随规则移动。
 *
 * <p><b>⚠️ 回调必须是 static</b>：两个目标方法在 26.x 都是 {@code private static}
 * （javap 实证 26.1.2 / 26.2 / 26.3 三版签名一致），因此回调也是 static、签名里没有「外围 this」。
 *
 * <p><b>⚠️ 规则 =0 时完全交还原版</b>：两个回调都直接放行，不做任何干预。
 */
@Mixin(TheEndGatewayBlockEntity.class)
public abstract class TheEndGatewayBlockEntityMixin {

    /** 出口半径：规则 &gt;0 → 候选点直接取「门方向 × 规则值」，跳过原版的 ±16 格找岛搜索 */
    @Inject(method = "findExitPortalXZPosTentative", at = @At("HEAD"), cancellable = true)
    private static void flt$exactExitRadius(ServerLevel level, BlockPos pos, CallbackInfoReturnable<Vec3> cir) {
        int radius = FLTSettings.endGatewayExitSearchDistance;
        if (radius <= 0) {
            return;   // 0 = 原版行为（径向 1024 格起找最近的岛），不干预
        }
        // 与原生第一步同式：以「门相对原点」的方向归一化后乘以半径
        cir.setReturnValue(new Vec3(pos.getX(), 0.0D, pos.getZ()).normalize().scale(radius));
    }

    /** 造岛基准高度：把原版写死的 75.0 换成规则值（&le;0 走原版） */
    @ModifyConstant(method = "findOrCreateValidTeleportPos", constant = @Constant(doubleValue = 75.0))
    private static double flt$islandBaseHeight(double original) {
        int height = FLTSettings.endGatewayIslandHeight;
        return height > 0 ? (double) height : original;
    }
}
