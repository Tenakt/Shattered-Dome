package com.grindlesstudio.shattereddome.event

import com.grindlesstudio.shattereddome.ShatteredDome
import com.grindlesstudio.shattereddome.SkyBreakServerManager
import com.grindlesstudio.shattereddome.network.SyncDomeBorderPayload
import com.grindlesstudio.shattereddome.network.SyncSkyBreakPayload
import com.grindlesstudio.shattereddome.world.DomeWorldData
import net.minecraft.server.level.ServerPlayer
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.tick.PlayerTickEvent
import net.neoforged.neoforge.network.PacketDistributor

@EventBusSubscriber(
    modid = ShatteredDome.MOD_ID,
    bus = EventBusSubscriber.Bus.GAME
)
object ModServerEvents {

    @SubscribeEvent
    @JvmStatic
    fun onPlayerLoggedIn(event: PlayerEvent.PlayerLoggedInEvent) {
        val player = event.entity as? ServerPlayer ?: return
        val data = DomeWorldData.get(player.serverLevel())

        PacketDistributor.sendToPlayer(
            player,
            SyncSkyBreakPayload(
                data.isSkyBroken,
                SkyBreakServerManager.riftProgress
            )
        )

        PacketDistributor.sendToPlayer(
            player,
            SyncDomeBorderPayload(data.borderRadius)
        )
    }

    @SubscribeEvent
    @JvmStatic
    fun onPlayerTick(event: PlayerTickEvent.Post) {
        val player = event.entity as? ServerPlayer ?: return
        val data = DomeWorldData.get(player.serverLevel())

        val radius = data.borderRadius.coerceAtLeast(1).toDouble()

        if (
            player.x > radius ||
            player.x < -radius ||
            player.z > radius ||
            player.z < -radius
        ) {
            player.kill()
        }
    }
}