package com.flt.carpetaddition.fakeplayer;

import carpet.patches.EntityPlayerMPFake;
import net.minecraft.block.Blocks;
import net.minecraft.block.LecternBlock;
import net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.MutableText;
// IF <= fabric-1.18.2
import net.minecraft.text.LiteralText;
// END IF
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
// IF >= fabric-1.19.4
//import net.minecraft.util.math.GlobalPos;
// ELSE
import net.minecraft.util.dynamic.GlobalPos;
// END IF
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.village.VillagerProfession;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 假人操作工具箱。
 * 走玩家自己的处理入口（interactionManager.interactBlock / lookAt / swingHand），而非直接 setBlock——
 * 这样服务端记录的行为与真人玩家一致，可录屏、可被其它插件当作正常玩家操作看待。
 * 参考 ORG 的 PlayerUtils / ServerUtils（MIT）；FLT 独立实现。
 */
public final class FakePlayerUtils {
    private FakePlayerUtils() {
    }

    /** 按名字查找假人；玩家不存在或同名真玩家时返回 null */
    public static EntityPlayerMPFake findFakePlayer(MinecraftServer server, String name) {
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(name);
        return player instanceof EntityPlayerMPFake fakePlayer ? fakePlayer : null;
    }

    /**
     * 实体所在世界。[VERSION] 1.21.9+ Entity 改名 getEntityWorld；≤1.21.8 为 getWorld。
     * 统一在此收口，避免每个调用点重复写条件块。
     */
    public static ServerWorld worldOf(Entity entity) {
// IF >= fabric-1.21.9
//        return (ServerWorld) entity.getEntityWorld();
// ELSE IF >= fabric-1.19.4
//        return (ServerWorld) entity.getWorld();
// ELSE
        return (ServerWorld) entity.getEntityWorld();
// END IF
    }

    /** 文本字面量。[VERSION] 1.19.4+ 用 Text.literal；≤1.18.2 为 new LiteralText */
    public static MutableText literal(String text) {
// IF >= fabric-1.19.4
//        return Text.literal(text);
// ELSE
        return new LiteralText(text);
// END IF
    }

    /** 玩家背包。[VERSION] 1.16.5 为公有字段 inventory；≥1.17.1 为 getInventory() */
    public static PlayerInventory invOf(PlayerEntity player) {
// IF >= fabric-1.17.1
//        return player.getInventory();
// ELSE
        return player.inventory;
// END IF
    }

    /** 堆是否为指定物品。[VERSION] ≥1.17.1 用 isOf；1.16.5 用 getItem() == */
    public static boolean isItem(ItemStack stack, Item item) {
// IF >= fabric-1.17.1
//        return stack.isOf(item);
// ELSE
        return stack.getItem() == item;
// END IF
    }

    /** 让实体视线转向某坐标 */
    public static void lookAt(Entity entity, Vec3d pos) {
        entity.lookAt(EntityAnchor.EYES, pos);
    }

    /** 主手挥动（假人传 false，不广播给客户端，避免无意义网络开销） */
    public static void swing(LivingEntity entity) {
        entity.swingHand(Hand.MAIN_HAND, false);
    }

    /** 模拟"用主手物品右键方块"——与 PlayerInteractBlockC2SPacket 等价的真实处理入口 */
    public static void useItemOn(ServerPlayerEntity player, Hand hand, BlockHitResult hitResult) {
        player.interactionManager.interactBlock(player, worldOf(player), player.getStackInHand(hand), hand, hitResult);
    }

    /** 该位置是不是讲台 */
    public static boolean isLectern(ServerWorld level, BlockPos pos) {
        return level.getBlockState(pos).getBlock() instanceof LecternBlock;
    }

    /** 该位置是否"空得可以放方块"（空气或水） */
    public static boolean isReplaceableAir(ServerWorld level, BlockPos pos) {
        var state = level.getBlockState(pos);
        return state.isAir() || state.isOf(Blocks.WATER);
    }

    /**
     * 找<b>认领了该讲台</b>的图书管理员：村民工作站点记忆（JOB_SITE）指向该讲台坐标。
     * 用「工作站点唯一归属」匹配而非「距讲台最近」——多个假人同场时，各自的村民 JOB_SITE
     * 指向各自讲台，天然互不串抢（最近匹配会让相邻讲台互相抢同一个村民）。
     */
    public static Optional<VillagerEntity> findLibrarian(ServerWorld level, BlockPos lecternPos, double radius) {
        Box searchBox = new Box(
                lecternPos.getX() - radius, lecternPos.getY() - radius, lecternPos.getZ() - radius,
                lecternPos.getX() + 1 + radius, lecternPos.getY() + 1 + radius, lecternPos.getZ() + 1 + radius);
        List<VillagerEntity> villagers = level.getEntitiesByClass(
                VillagerEntity.class,
                searchBox,
// IF >= fabric-1.21.5
//                villager -> villager.getVillagerData().profession().matchesKey(VillagerProfession.LIBRARIAN)
// ELSE
                villager -> villager.getVillagerData().getProfession() == VillagerProfession.LIBRARIAN
// END IF
// IF >= fabric-1.19.4
//                        && villager.getBrain().getOptionalRegisteredMemory(MemoryModuleType.JOB_SITE)
// ELSE
                        && villager.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE)
// END IF
// IF >= fabric-1.21
//                        .map(jobSite -> jobSite.dimension().equals(level.getRegistryKey())
//                                && jobSite.pos().equals(lecternPos))
// ELSE
                        .map(jobSite -> jobSite.getDimension().equals(level.getRegistryKey())
                                && jobSite.getPos().equals(lecternPos))
// END IF
                        .orElse(false));
        return villagers.stream().min(
                Comparator.comparingDouble(villager -> villager.squaredDistanceTo(Vec3d.ofCenter(lecternPos))));
    }
}
