package com.grindlesstudio.shattereddome

import net.minecraft.world.entity.player.Player
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.entity.player.CanPlayerSleepEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent

@EventBusSubscriber(
    modid = ShatteredDome.MOD_ID,
    bus = EventBusSubscriber.Bus.GAME
)
object SkyBreakSleepHandler {

    @SubscribeEvent
    @JvmStatic
    fun onCanPlayerSleep(event: CanPlayerSleepEvent) {
        if (SkyBreakServerManager.phase != SkyBreakPhase.INACTIVE) {
            event.problem = Player.BedSleepingProblem.OTHER_PROBLEM
        }
    }

    @SubscribeEvent
    @JvmStatic
    fun onServerTick(event: ServerTickEvent.Post) {
        if (SkyBreakServerManager.phase == SkyBreakPhase.INACTIVE) return

        for (player in event.server.playerList.players) {
            if (player.isSleeping) {
                player.stopSleepInBed(true, true)
            }
        }
    }
}
