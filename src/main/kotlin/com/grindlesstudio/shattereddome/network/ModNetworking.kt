package com.grindlesstudio.shattereddome.network

import com.grindlesstudio.shattereddome.ShatteredDome
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent

@EventBusSubscriber(
    modid = ShatteredDome.MOD_ID,
    bus = EventBusSubscriber.Bus.MOD
)
object ModNetworking {

    @SubscribeEvent
    @JvmStatic
    fun registerPayloads(event: RegisterPayloadHandlersEvent) {
        val registrar = event.registrar("1.0.0")

        registrar.playToClient(
            SyncSkyBreakPayload.TYPE,
            SyncSkyBreakPayload.STREAM_CODEC,
            SyncSkyBreakPayload::handle
        )

        registrar.playToClient(
            SyncSkyFlashPayload.TYPE,
            SyncSkyFlashPayload.STREAM_CODEC,
            SyncSkyFlashPayload::handle
        )

        registrar.playToClient(
            SyncScreenShakePayload.TYPE,
            SyncScreenShakePayload.STREAM_CODEC,
            SyncScreenShakePayload::handle
        )

        // Синхронизация радиуса купола с клиентом
        registrar.playToClient(
            SyncDomeBorderPayload.TYPE,
            SyncDomeBorderPayload.STREAM_CODEC,
            SyncDomeBorderPayload::handle
        )
    }
}