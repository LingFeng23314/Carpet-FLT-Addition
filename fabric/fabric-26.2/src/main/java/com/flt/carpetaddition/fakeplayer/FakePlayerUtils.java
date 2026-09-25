package com.flt.carpetaddition.fakeplayer;

import carpet.patches.EntityPlayerMPFake;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 假人操作工具箱。
 * 走玩家自己的处理入口（gameMode.useItemOn / lookAt / swing），而非直接 setBlock——
 * 这样服务端记录的行为与真人玩家一致，可录屏、可被其它插件当作正常玩家操作看待。
 * <p>来源说明：部分方法的实现思路参考 Carpet-Org-Addition 的 PlayerUtils / ServerUtils
 * （MIT License，Copyright (c) 2024 fcsailboat）；本类为独立编写，未复制其代码。
 * 见项目根目录 THIRD-PARTY-NOTICES.md。
 */
public final class FakePlayerUtils {
    private FakePlayerUtils() {
    }

    /** 按名字查找假人；玩家不存在或同名真玩家时返回 null */
    public static EntityPlayerMPFake findFakePlayer(MinecraftServer server, String name) {
        ServerPlayer player = server.getPlayerList().getPlayerByName(name);
        return player instanceof EntityPlayerMPFake fakePlayer ? fakePlayer : null;
    }

    /** 让实体视线转向某坐标 */
    public static void lookAt(Entity entity, Vec3 pos) {
        entity.lookAt(EntityAnchorArgument.Anchor.EYES, pos);
    }

    /** 主手挥动（假人传 false，不广播给客户端，避免无意义网络开销） */
    public static void swing(LivingEntity entity) {
        entity.swing(InteractionHand.MAIN_HAND, false);
    }

    /** 模拟"用主手物品右键方块"——与 ServerboundUseItemOnPacket 等价的真实处理入口 */
    public static void useItemOn(ServerPlayer player, InteractionHand hand, BlockHitResult hitResult) {
        player.gameMode.useItemOn(player, player.level(), player.getItemInHand(hand), hand, hitResult);
    }

    /** 该位置是不是讲台 */
    public static boolean isLectern(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).getBlock() instanceof LecternBlock;
    }

    /** 该位置是否"空得可以放方块"（空气或水） */
    public static boolean isReplaceableAir(ServerLevel level, BlockPos pos) {
        var state = level.getBlockState(pos);
        return state.isAir() || state.is(Blocks.WATER);
    }

    /**
     * 找<b>认领了该讲台</b>的图书管理员：村民工作站点记忆（JOB_SITE）指向该讲台坐标。
     * 用「工作站点唯一归属」匹配而非「距讲台最近」——多个假人同场时，各自的村民 JOB_SITE
     * 指向各自讲台，天然互不串抢（最近匹配会让相邻讲台互相抢同一个村民）。
     */
    public static Optional<Villager> findLibrarian(ServerLevel level, BlockPos lecternPos, double radius) {
        AABB searchBox = new AABB(
                lecternPos.getX() - radius, lecternPos.getY() - radius, lecternPos.getZ() - radius,
                lecternPos.getX() + 1 + radius, lecternPos.getY() + 1 + radius, lecternPos.getZ() + 1 + radius);
        List<Villager> villagers = level.getEntitiesOfClass(
                Villager.class,
                searchBox,
                villager -> villager.getVillagerData().profession().is(VillagerProfession.LIBRARIAN)
                        && villager.getBrain().getMemory(MemoryModuleType.JOB_SITE)
                        .map(jobSite -> jobSite.dimension().equals(level.dimension())
                                && jobSite.pos().equals(lecternPos))
                        .orElse(false));
        return villagers.stream().min(
                Comparator.comparingDouble(villager -> villager.distanceToSqr(Vec3.atCenterOf(lecternPos))));
    }
}