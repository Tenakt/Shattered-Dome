package com.grindlesstudio.shattereddome.event

import com.grindlesstudio.shattereddome.ShatteredDome
import com.grindlesstudio.shattereddome.command.DomeCommands
import com.grindlesstudio.shattereddome.world.MeteorScheduler
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent

@EventBusSubscriber(modid = ShatteredDome.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
object ServerEvents {

    @SubscribeEvent
    @JvmStatic
    fun onRegisterCommands(event: RegisterCommandsEvent) {
        DomeCommands.register(event.dispatcher)
    }

    @SubscribeEvent
    @JvmStatic
    fun onServerTick(event: ServerTickEvent.Post) {
        val overworld = event.server.overworld()
        MeteorScheduler.tick(overworld)
    }
}