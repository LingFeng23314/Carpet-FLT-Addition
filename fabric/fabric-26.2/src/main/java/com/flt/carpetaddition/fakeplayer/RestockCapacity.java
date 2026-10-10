package com.flt.carpetaddition.fakeplayer;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 备货假人的「取货缓存空间」计算与腾挪（2026-09-29 新增，多假人取货缓存）。
 *
 * <p><b>什么是"取货缓存空间"</b>：假人身上能用来堆放"待交付的货"的格子 —— 背包空槽，
 * 加上末影箱里腾得出的槽位（末影箱放的是压缩后的成品盒，把背包里的成品盒也挪进去，
 * 就反过来腾空了背包）。取货前必须保证缓存够装，否则假人跑到仓库、开了箱，
 * 才在搬货中途发现装不下 —— 白跑一趟，还容易静默少取。
 *
 * <p><b>为什么要"先腾空间再分配"</b>：假人身上经常压着上一批还没被玩家取走的成品盒
 * （玩家还没来取，或者还没来得及取）。那一批本来就在末影箱/背包里等着，把背包里的成品盒
 * 挪进末影箱，背包立刻多出几格，不需要玩家插手就能继续干活。腾不出来的（末影箱也满了）
 * 才需要通知玩家先取货 —— 见 {@code RestockScheduler} 的派单闸门。
 *
 * <p><b>只挪成品盒，不挪散装也不挪空盒</b>（沿用 2026-09-29 的容量治理纪律）：
 * 散装是"原料"，塞进末影箱既没有压缩、又要玩家一格一格掏，还会极快把它填满；
 * 空潜影盒属于"工具"（装箱要用），挪走会让后续装箱无盒可用。这两类一律不动。
 *
 * <p>⚠️ 本类不能合并进 {@link FakePlayerUtils}：那是 26.3 的版本专属适配文件，
 * 三版同步脚本会跳过它 → 公共工具放那里会漏同步。本类不在排除表，正常镜像。
 */
public final class RestockCapacity {
    /**
     * 派单前给取货留的余量格数。
     *
     * <p>为什么至少留 1 格：搬货过程中需要一格周转（原版开箱搬取时物品要在光标与背包之间过一手），
     * 一格不剩时连一次 {@code quickMoveStack} 都放不下。
     */
    public static final int SAFETY_SLOTS = 1;

    /** 单次腾挪的最大轮数（背包最多 36 格；取 64 纯属防御性上限，确保任何情况下都不会死循环） */
    private static final int MAX_STASH_ROUNDS = 64;

    private RestockCapacity() {
    }

    /**
     * 假人背包里<b>能放普通物品</b>的空槽数（＝主背包 27 + 快捷栏 9 = 36 格）。
     *
     * <p>[VERSION] 2026-09-30 修正口径（javap 26.1.2 / 26.2 / 26.3 三版实证）：
     * 必须用 {@code Inventory.getNonEquipmentItems()}（字节码就是 {@code return this.items}，
     * 即 {@code INVENTORY_SIZE} = 36 格），<b>不能</b>用 {@code getContainerSize()} ——
     * 后者等于 {@code items.size() + EQUIPMENT_SLOT_MAPPING.size()}，把盔甲 / 副手 /
     * 身体盔甲 / 鞍等 5 个<b>装备槽</b>也算了进来，而那些槽放不了潜影盒与普通物品。
     *
     * <p><b>为什么这个口径生死攸关</b>：{@code BulkFetchRepackAction} 靠它判断"还有没有余量去取空盒"。
     * 用 41 的口径时，假人装备栏空着就永远"还剩 5 格"，于是反复跑到仓库取空盒，
     * 而 {@code quickMoveStack} 一格也放不下 → PACK 阶段整段空转。
     * 实测（2026-09-29 22:51）日志连续几十轮「找到空盒 → 一次取走 6 槽空盒（背包余 7 格）→
     * 装完一盒（散装 36→36，累计 0 盒）」，余格数<b>恒定不变</b>，12 秒后只能散装交付 ——
     * 那"余 7 格"里就有 5 格是装备槽。
     */
    public static int freeInventorySlots(ServerPlayer bot) {
        if (bot == null) {
            return 0;
        }
        int n = 0;
        for (ItemStack stack : bot.getInventory().getNonEquipmentItems()) {
            if (stack.isEmpty()) {
                n++;
            }
        }
        return n;
    }

    /** 假人末影箱空槽数 */
    public static int freeEnderSlots(ServerPlayer bot) {
        return EnderChestUtil.freeSlots(bot);
    }

    /**
     * 该假人的总缓存空间 = 背包空槽 + 末影箱空槽。
     *
     * <p>为什么把末影箱算进来：背包里的成品盒随时能挪进末影箱，等价于"背包的延伸"。
     * 但它只是<b>潜在</b>可用（要先腾挪），所以这个值只用于"该挑哪个假人来干活"这种粗判；
     * 真正派单前的闸门看的是 {@link #freeInventorySlots}（腾挪后的实际背包空位）。
     */
    public static int cacheSlots(ServerPlayer bot) {
        return freeInventorySlots(bot) + freeEnderSlots(bot);
    }

    /** 取 {@code count} 个该物品至少要占几格（按该物品的堆叠上限向上取整） */
    public static int neededSlots(Identifier itemId, int count) {
        if (itemId == null || count <= 0) {
            return 0;
        }
        int stackSize = 64;
        Item item = BuiltInRegistries.ITEM.getValue(itemId);
        if (item != null && item != Items.AIR) {
            // 只用默认堆叠上限做"够不够装"的估算，不参与任何物品转移，所以不必展开数据组件
            stackSize = Math.max(1, item.getDefaultMaxStackSize());
        }
        return (int) ((count + (long) stackSize - 1L) / stackSize);
    }

    /** 该格是不是"可腾挪的成品盒"（有内容的潜影盒；空盒是装箱工具，不算） */
    public static boolean isFinishedBox(ItemStack stack) {
        return stack != null
                && !stack.isEmpty()
                && stack.is(ItemTags.SHULKER_BOXES)
                && !ShulkerBoxUtil.isEmptyShulker(stack);
    }

    /**
     * 把一个成品盒<b>整格</b>挪进假人末影箱，腾出 1 格背包。
     *
     * <p>⚠️ 必须先 {@code canAddItem} 预判（见 {@link EnderChestUtil#tryStashSlot}）：部分存入会导致
     * "清原槽 = 丢物 / 留原槽 = 复制"二选一的错误，所以只接受整格存入。
     *
     * @return true = 已腾出 1 格；false = 没有成品盒可挪 / 末影箱放不下整格
     */
    public static boolean stashOneFinishedBox(ServerPlayer bot) {
        if (bot == null) {
            return false;
        }
        var inv = bot.getInventory();
        // [VERSION] 2026-09-30 口径修正：只扫主背包 + 快捷栏（与 freeInventorySlots 同一口径）。
        // 用 getContainerSize() 会把装备槽一并扫进来 —— 装备槽里若有潜影盒会被误挪进末影箱。
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            if (isFinishedBox(inv.getItem(i)) && EnderChestUtil.tryStashSlot(bot, i)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 保证背包至少有 {@code needSlots} 个空格：不够就逐个把成品盒挪进末影箱，直到够用或挪不动。
     *
     * <p>为什么目标就是 {@code needSlots}（而不是像批量备货那样腾到固定 8 格）：
     * 那边的调用方还要接着连取一大段，多腾几格少跑几趟；这里的调用方只需要"装得下这一批"，
     * 多腾无益（一件单物品取货通常只要 1~2 格）。
     *
     * @return 腾挪后背包的<b>实际</b>空格数（调用方据此判断够不够，而不是假设一定腾成功）
     */
    public static int makeRoom(ServerPlayer bot, int needSlots) {
        if (bot == null) {
            return 0;
        }
        int rounds = 0;
        while (freeInventorySlots(bot) < needSlots && rounds++ < MAX_STASH_ROUNDS) {
            if (!stashOneFinishedBox(bot)) {
                break;   // 没有成品盒可挪 / 末影箱放不下 → 交调用方决定（通常是提示玩家取货）
            }
        }
        return freeInventorySlots(bot);
    }
}
