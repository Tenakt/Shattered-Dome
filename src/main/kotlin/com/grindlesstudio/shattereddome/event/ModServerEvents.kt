package com.grindlesstudio.shattereddome.event

import com.grindlesstudio.shattereddome.ShatteredDome
import com.grindlesstudio.shattereddome.network.SyncSkyBreakPayload
import com.grindlesstudio.shattereddome.server.SkyBreakServerManager
import com.grindlesstudio.shattereddome.world.DomeWorldData
import net.minecraft.server.level.ServerPlayer
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.tick.PlayerTickEvent
import net.neoforged.neoforge.network.PacketDistributor

@EventBusSubscriber(modid = ShatteredDome.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
object ModServerEvents {

    @SubscribeEvent
    @JvmStatic
    fun onPlayerLoggedIn(event: PlayerEvent.PlayerLoggedInEvent) {
        val player = event.entity as? ServerPlayer ?: return
        val level = player.serverLevel()
        val data = DomeWorldData.get(level)

        PacketDistributor.sendToPlayer(
            player,
            SyncSkyBreakPayload(data.isSkyBroken, SkyBreakServerManager.riftProgress)
        )
    }

    @SubscribeEvent
    @JvmStatic
    fun onPlayerTick(event: PlayerTickEvent.Post) {
        val player = event.entity as? ServerPlayer ?: return

        // Расстояние от центра мира (0, 0)
        val distanceSquared = player.x * player.x + player.z * player.z
        val deathDistance = 10000.0

        if (distanceSquared >= deathDistance * deathDistance) {
            player.kill()
        }
    }
}