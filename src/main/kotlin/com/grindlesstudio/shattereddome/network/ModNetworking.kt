package com.grindlesstudio.shattereddome.network

import com.grindlesstudio.shattereddome.ShatteredDome
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent

@EventBusSubscriber(modid = ShatteredDome.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
object ModNetworking {

    @SubscribeEvent
    @JvmStatic
    fun register(event: RegisterPayloadHandlersEvent) {
        val registrar = event.registrar("1.0.0")
        registrar.playToClient(
            SyncSkyBreakPayload.TYPE,
            SyncSkyBreakPayload.STREAM_CODEC,
            SyncSkyBreakPayload::handle
        )
    }
}