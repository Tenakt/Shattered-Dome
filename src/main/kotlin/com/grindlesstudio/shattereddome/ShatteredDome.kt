package com.grindlesstudio.shattereddome

import com.grindlesstudio.shattereddome.init.ModBlocks
import com.grindlesstudio.shattereddome.init.ModEntities
import com.grindlesstudio.shattereddome.sound.ModSounds
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.common.Mod

@Mod(ShatteredDome.MOD_ID)
class ShatteredDome(modEventBus: IEventBus) {

    companion object {
        const val MOD_ID = "shattereddome"
    }

    init {
        ModSounds.SOUND_EVENTS.register(modEventBus)
        ModEntities.ENTITIES.register(modEventBus)
        ModBlocks.BLOCKS.register(modEventBus)
    }
}