package com.flt.carpetaddition.fakeplayer;

import carpet.patches.EntityPlayerMPFake;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/**
 * 规则 A「原版刷取」：让假人反复拆/放讲台刷新图书管理员交易，直到刷出目标附魔书。
 * 链路完全等价真人手动操作（挖讲台 → 村民失去工作站点职业重置 + offers 置空 → 放回讲台
 * 重新认领 → getOffers() 懒加载生成新交易）。
 * 因此原版"必须从未交易过"的限制（villagerXp == 0）自然生效，不绕过。
 * 目标村民按「工作站点 == 绑定讲台」唯一锁定（见 FakePlayerUtils.findLibrarian），多假人互不串抢。
 * 判定/收尾委托 TradeOfferMatcher/TradeLockHelper，反馈走 ActionReporter。
 * <p>来源说明：动作流程的设计参考 Carpet-Org-Addition 的 LibrarianTradeFindAction
 * （MIT License，Copyright (c) 2024 fcsailboat）；本类为独立编写，未复制其代码。
 * 见项目根目录 THIRD-PARTY-NOTICES.md。
 */
public class LibrarianTradeFindAction extends AbstractFakePlayerAction {
    protected final BlockPos lecternPos;
    private final Holder.Reference<Enchantment> enchantment;
    /** 最低可接受等级（-1 已解析为该附魔最高等级） */
    private final int requiredLevel;
    /** 最高可接受价格（绿宝石数；-1 已解析为 Integer.MAX_VALUE） */
    private final int maxPrice;
    private final long startTick;
    private final ActionReporter reporter;

    protected final BlockExcavator excavator = new BlockExcavator(this.getFakePlayer());
    private final LecternPlacer placer;

    private boolean diggingBlock;
    protected int refreshCount;
    protected int missingVillagerTicks;
    private boolean reportedNoLectern;
    protected Villager lastVillager;
    private boolean outOfLectern;

    /** 附近找不到图书管理员多久后放弃（10 秒） */
    protected static final int MAX_MISSING_VILLAGER_TICKS = 200;

    public LibrarianTradeFindAction(EntityPlayerMPFake fakePlayer, BlockPos lecternPos,
                                    Holder.Reference<Enchantment> enchantment,
                                    int level, int price, CommandSourceStack feedback) {
        super(fakePlayer);
        this.lecternPos = lecternPos.immutable();
        this.enchantment = enchantment;
        this.requiredLevel = level == -1 ? enchantment.value().getMaxLevel() : level;
        this.maxPrice = price == -1 ? Integer.MAX_VALUE : price;
        this.startTick = this.server().getTickCount();
        this.reporter = new ActionReporter(feedback);
        this.placer = new LecternPlacer(this.getFakePlayer(), this.lecternPos);
    }

    @Override
    public boolean tick() {
        this.excavator.tick();
        ServerLevel level = this.level();

        // 正在挖讲台
        if (this.diggingBlock) {
            FakePlayerUtils.lookAt(this.getFakePlayer(), Vec3.atCenterOf(this.lecternPos));
            if (this.excavator.mining(this.lecternPos, Direction.UP)) {
                this.diggingBlock = false;
            }
            return true;
        }

        // 讲台还在 → 找认领它的图书管理员并判定；没命中就挖掉刷新
        if (FakePlayerUtils.isLectern(level, this.lecternPos)) {
            Optional<Villager> librarian = FakePlayerUtils.findLibrarian(level, this.lecternPos, 8.0D);
            if (librarian.isEmpty()) {
                // 讲台在、但村民还没重新认领（拆/放后的重认领窗口）→ 等待，不要立刻再挖，
                // 否则村民永远来不及认领。超时仍找不到才放弃。
                if (++this.missingVillagerTicks >= MAX_MISSING_VILLAGER_TICKS) {
                    this.report(Component.literal("讲台附近找不到认领它的图书管理员村民，已停止")
                            .withStyle(ChatFormatting.RED));
                    return false;
                }
                return true;
            }
            this.missingVillagerTicks = 0;
            if (this.checkAndFinishIfFound(librarian.get())) {
                return false;
            }
            this.diggingBlock = true;
            return true;
        }

        // 讲台被挖掉 → 放回去，完成一轮刷新
        if (FakePlayerUtils.isReplaceableAir(level, this.lecternPos)) {
            String error = this.placer.readyOffhandLectern();
            if (error != null) {
                if (!this.reportedNoLectern) {
                    this.reportedNoLectern = true;
                    this.report(Component.literal("假人无法放置讲台：" + error + "，已停止")
                            .withStyle(ChatFormatting.RED));
                }
                this.outOfLectern = true;
                return false;
            }
            this.placer.place();
            if (FakePlayerUtils.isLectern(level, this.lecternPos)) {
                this.refreshCount++;
            }
            return true;
        }

        // 位置被别的方块占了
        this.report(Component.literal("目标位置被其它方块占用，已停止：").withStyle(ChatFormatting.RED));
        this.report(Component.literal("  " + ActionReporter.posText(this.lecternPos))
                .withStyle(ChatFormatting.GRAY));
        return false;
    }

    /** 读当前交易并判定是否命中；返回 true = 已命中/终止（动作结束），false = 未命中（继续挖刷新） */
    protected boolean checkAndFinishIfFound(Villager villager) {
        this.lastVillager = villager;

        // 原版限制：交易过的村民（villagerXp > 0）永远无法再刷新
        if (villager.getVillagerXp() != 0) {
            this.report(Component.literal("该村民已经交易过（villagerXp = " + villager.getVillagerXp()
                    + "），原版规则下无法再刷新，已停止").withStyle(ChatFormatting.RED));
            return true;
        }

        MerchantOffers offers = villager.getOffers();
        TradeOfferMatcher.Match match = TradeOfferMatcher.find(
                offers, this.enchantment.value(), this.requiredLevel, this.maxPrice);
        if (match == null) {
            return false;
        }
        this.finish(villager, match);
        return true;
    }

    /** 命中收尾：报告结果 + 交易一次把该村民的报价锁死 */
    protected void finish(Villager villager, TradeOfferMatcher.Match match) {
        boolean locked = TradeLockHelper.tryLock(this.getFakePlayer(), villager, match.index());
        long costTicks = this.server().getTickCount() - this.startTick;

        this.report(Component.literal("✔ 刷到了！").withStyle(ChatFormatting.GREEN)
                .append(Component.literal(" " + this.enchantmentName() + " " + levelText(match.level()))
                        .withStyle(ChatFormatting.AQUA)));
        this.report(Component.literal("  价格 " + match.price() + " 绿宝石  刷新 " + this.refreshCount
                + " 次  耗时 " + (costTicks / 20) + " 秒").withStyle(ChatFormatting.GRAY));
        this.report(Component.literal("  村民 " + ActionReporter.posText(villager.blockPosition())
                + "  讲台 " + ActionReporter.posText(this.lecternPos)).withStyle(ChatFormatting.GRAY));
        this.report(locked
                ? Component.literal("  已交易一次锁定该村民的交易").withStyle(ChatFormatting.GRAY)
                : Component.literal("  未锁定（假人背包缺少交易材料，请手动交易一次锁定）")
                        .withStyle(ChatFormatting.YELLOW));
    }

    private String enchantmentName() {
        return this.enchantment.value().description().getString();
    }

    private String levelText(int level) {
        return level <= 1 ? "" : String.valueOf(level);
    }

    /** 把消息同时写进日志和指令执行者的聊天栏 */
    protected void report(Component message) {
        this.reporter.report(message);
    }

    @Override
    public void onStop() {
        // 关掉可能还开着的交易界面，避免物品卡在界面里
        try {
            this.getFakePlayer().closeContainer();
        } catch (Throwable ignored) {
        }
    }
}