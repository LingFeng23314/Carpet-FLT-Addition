package com.flt.carpetaddition.fakeplayer;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.item.ItemStack;

/**
 * 假人末影箱工具（备货容量治理，2026-09-28 新增）。
 *
 * <p><b>为什么需要它</b>：假人只有 36 格背包，一次大规模备货（多物品 × 大量）会塞满背包 ——
 * 塞满后既拿不到空潜影盒（装箱需要 1 格放盒），也取不动新货，备货直接卡死在
 * {@code BulkFetchRepackAction} 的 PACK 阶段。末影箱（27 格）正好可以作为溢出缓冲：
 * 把已装好的成品盒挪进去，腾出背包格继续干活。
 *
 * <p><b>为什么不用打开菜单</b>：{@link net.minecraft.world.entity.player.Player#getEnderChestInventory()}
 * 直接返回玩家自己的 {@link PlayerEnderChestContainer}，读写它<b>不需要方块、不需要开菜单、
 * 也不受距离限制</b>（{@code stillValid} 只在 {@code activeChest} 被设置时才做距离校验，而那只发生在
 * 真的打开末影箱方块时）。所以假人在仓库里也能随时存取。
 *
 * <p><b>内容会持久化吗</b>：会。反编译实证（26.1.2 minecraft-merged.jar）：
 * {@code Player.addAdditionalSaveData} 内调 {@code enderChestInventory.storeAsSlots(...)}
 * 把末影箱写进 playerdata；{@code PlayerList.remove(ServerPlayer)} 会调 {@code save}；
 * Carpet 侧没有 {@code deletePlayerData} 之类调用，且假人创建时走
 * {@code loadPlayerData → PlayerList.loadPlayerData} 读回。也就是说假人下线再上线，
 * 末影箱内容仍在（依名字生成的 UUID 稳定）。
 * <p>额外好处：假人死亡时<b>背包会掉落一地</b>，而末影箱不会 —— 放末影箱比放背包更安全。
 *
 * <p><b>玩家怎么取货</b>：靠 GCA（Gugle's Carpet Addition）的规则
 * {@code openFakePlayerEnderChest}（中文名「假人末影箱」，描述「允许玩家打开假人末影箱」）
 * —— 开启后玩家可自行打开假人的末影箱取走缓冲的货。本类不负责取出。
 *
 * <p>⚠️ <b>本类不能合并进 {@link FakePlayerUtils}</b>：那是 26.3 的版本专属适配文件，
 * 三版同步脚本会跳过它 → 公共工具放那里会漏同步。本类不在排除表，正常镜像。
 */
public final class EnderChestUtil {
    private EnderChestUtil() {
    }

    /** 取假人的末影箱容器（27 格，{@code PlayerEnderChestContainer extends SimpleContainer}） */
    public static PlayerEnderChestContainer container(ServerPlayer bot) {
        return bot == null ? null : bot.getEnderChestInventory();
    }

    /** 末影箱已占用的格数（非空槽） */
    public static int occupiedSlots(ServerPlayer bot) {
        PlayerEnderChestContainer ec = container(bot);
        if (ec == null) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i < ec.getContainerSize(); i++) {
            if (!ec.getItem(i).isEmpty()) {
                n++;
            }
        }
        return n;
    }

    /** 末影箱剩余可放"新种类"的格数（已有部分堆叠的同类物品不算在内，仅供日志） */
    public static int freeSlots(ServerPlayer bot) {
        PlayerEnderChestContainer ec = container(bot);
        if (ec == null) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i < ec.getContainerSize(); i++) {
            if (ec.getItem(i).isEmpty()) {
                n++;
            }
        }
        return n;
    }

    /** 末影箱是否连一格新种类都放不进（仅作"能不能再腾一格"的粗判，真正判断看 {@link #tryStashSlot}） */
    public static boolean isFull(ServerPlayer bot) {
        return freeSlots(bot) <= 0;
    }

    /**
     * 尝试把一个物品堆<b>整堆</b>存入末影箱；能全部放下才存（入参<b>不被修改</b>）。
     *
     * <p>与 {@link #tryStashSlot} 同一套判断，区别只是这里拿到的是栈本身而不是背包槽位 ——
     * 用于"物品已经拿在手上（不在任何容器槽里）"的兜底场景，例如拆单盒后多出来的那组空盒。
     *
     * @return true = 已全部存入；false = 没存（空堆 / 末影箱放不下整堆）
     */
    public static boolean tryStash(ServerPlayer bot, ItemStack stack) {
        if (bot == null || stack == null || stack.isEmpty()) {
            return false;
        }
        PlayerEnderChestContainer ec = container(bot);
        if (ec == null || !ec.canAddItem(stack)) {
            return false;
        }
        // 🔴 [VERSION] 2026-09-30 自查修复（<b>这条会丢物资，务必保留</b>）：
        //   {@code canAddItem} 只保证"<b>某一格</b>还能再放进一些"，<b>不保证整栈放得下</b> ——
        //   字节码实证（26.1.2）：{@code SimpleContainer.canAddItem} 的判定就是
        //   "存在空槽 或 存在同(物品+组件)且未满的槽" → true。
        //   而 {@code SimpleContainer.addItem} 会把"没放下的剩余"作为返回值/剩余量抛出
        //   （本类直接丢弃返回值）。于是"末影箱没有空槽、只有一格同类但余量不足"时：
        //   canAddItem=true → addItem 只放下一部分、剩余被丢掉 → 调用方紧接着
        //   {@link #tryStashSlot} 把原槽清空 → <b>没放下的那部分凭空消失</b>。
        //   修法：先按"这种物品在末影箱里还能放进多少个"算总容量，够整栈才动手（全有或全无）。
        if (freeSpaceFor(ec, stack) < stack.getCount()) {
            return false;
        }
        ec.addItem(stack.copy());
        return true;
    }

    /**
     * 末影箱对 {@code stack} 这种物品（同物品 + 同组件）还<b>能容纳的总个数</b>。
     *
     * <p>用途：给「整栈全有或全无」的存入做容量预判（见 {@link #tryStash}）。
     * 空槽按<b>该物品自身的堆叠上限</b>计，不能写死 64 —— 雪球是 16、工具/盔甲是 1，
     * 写死 64 会高估容量，又会退回到"部分存入 → 清原槽 → 丢物资"。
     *
     * @return 还能放进的个数（0 = 一格余量都没有）
     */
    private static int freeSpaceFor(PlayerEnderChestContainer ec, ItemStack stack) {
        int max = Math.max(1, stack.getMaxStackSize());
        int free = 0;
        for (int i = 0; i < ec.getContainerSize(); i++) {
            ItemStack s = ec.getItem(i);
            if (s.isEmpty()) {
                free += max;   // 空槽最多能放该物品的一个满堆
            } else if (ItemStack.isSameItemSameComponents(s, stack)) {
                free += Math.max(0, s.getMaxStackSize() - s.getCount());
            }
        }
        return free;
    }

    /**
     * 把假人背包第 {@code invSlot} 格物品<b>整格</b>存入末影箱，成功则清空该格（＝腾出 1 格）。
     *
     * <p><b>⚠️ 为什么必须先 {@code canAddItem} 预判</b>：{@code SimpleContainer.addItem} 返回的是
     * "没放下的剩余"，它<b>可能只放下一部分</b>。若不预判就搬，只有两种写法，都是错的：
     * <ul>
     *   <li>清空原槽 → 没放下的那部分<b>凭空消失</b>；</li>
     *   <li>保留原槽 → 已放下的那部分<b>被复制</b>（背包和末影箱各一份）。</li>
     * </ul>
     * 所以只接受"能全部放下"的情形；放不下就原样返回 false，由调用方决定下一步。
     *
     * @param bot     假人
     * @param invSlot 背包槽位（0..{@code Inventory.INVENTORY_SIZE}-1 = 0..35，不含盔甲/副手）
     * @return true = 已整格存入并清空原槽；false = 没搬（槽为空 / 末影箱放不下）
     */
    public static boolean tryStashSlot(ServerPlayer bot, int invSlot) {
        if (bot == null) {
            return false;
        }
        var inv = bot.getInventory();
        // [VERSION] 2026-09-30 口径修正：上限改用 Inventory.INVENTORY_SIZE（36 = 主背包 27 + 快捷栏 9），
        // 而不是 getContainerSize()（＝41）—— 后者会放行装备槽索引，让盔甲被挪进末影箱。
        if (invSlot < 0 || invSlot >= Inventory.INVENTORY_SIZE) {
            return false;
        }
        ItemStack stack = inv.getItem(invSlot);
        if (stack.isEmpty() || !tryStash(bot, stack)) {
            return false;   // 空槽 / 末影箱放不下整格 → 不搬（见上文"为什么必须先预判"）
        }
        inv.setItem(invSlot, ItemStack.EMPTY);
        return true;
    }
}
