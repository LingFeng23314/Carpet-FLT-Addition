package com.flt.carpetaddition.fakeplayer;

import carpet.patches.EntityPlayerMPFake;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

import java.util.Optional;

/**
 * 规则 B「类 TC 直掷」：不拆讲台，直接 setOffers(null) + getOffers() 懒加载重掷交易表。
 * 与规则 A 区别：A 是物理拆/放讲台（慢但 100% 原版），B 是服务端直掷（快几个数量级）。
 * 门槛与规则 A 一致：villagerXp == 0（已交易过的村民直接拒绝，严格保留原版限制）。
 * 参考 Trade Cycling（ARR）的 onCycleTrades 内核（仅供研究，不复制代码）；
 * FLT 仅采用"setOffers(null) + getOffers()"这一原版 API 组合思路。
 */
public class LibrarianTradeForceAction extends LibrarianTradeFindAction {
    /**
     * @param fakePlayer 执行动作的假人（定位村民 + 命中后交易锁死）
     * @param lecternPos 讲台位置（保证找到的村民是图书管理员）
     * @param enchantment 目标附魔
     * @param level 最低等级；-1 = 该附魔最高等级
     * @param price 最高价格（绿宝石数）；-1 = 不限
     * @param feedback 结果反馈（指令执行者）；可为 null
     */
    public LibrarianTradeForceAction(EntityPlayerMPFake fakePlayer, BlockPos lecternPos,
                                     Enchantment enchantment,
                                     int level, int price, ServerCommandSource feedback) {
        super(fakePlayer, lecternPos, enchantment, level, price, feedback);
    }

    @Override
    public boolean tick() {
        // force 模式不拆讲台，父类 excavator 闲置
        ServerWorld level = this.level();

        Optional<VillagerEntity> optional = FakePlayerUtils.findLibrarian(level, this.lecternPos, 8.0D);
        if (optional.isEmpty()) {
            this.missingVillagerTicks++;
            if (this.missingVillagerTicks >= MAX_MISSING_VILLAGER_TICKS) {
                this.report(FakePlayerUtils.literal("讲台附近找不到图书管理员村民，已停止").formatted(Formatting.RED));
                return true;
            }
            return false;
        }
        this.missingVillagerTicks = 0;

        VillagerEntity villager = optional.get();
        this.lastVillager = villager;

        if (villager.getExperience() != 0) {
            this.report(FakePlayerUtils.literal("该村民已经交易过（villagerXp = " + villager.getExperience()
                    + "），无法再刷新，已停止").formatted(Formatting.RED));
            return true;
        }

        // 直掷：清空交易 → getOffers() 懒加载重新生成
        villager.setOffers(null);
        villager.getOffers();
        this.refreshCount++;

        return !this.checkAndFinishIfFound(villager);
    }
}
