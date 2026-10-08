package com.grindlesstudio.shattereddome.sound

import com.grindlesstudio.shattereddome.ShatteredDome
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.sounds.SoundEvent
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister
import java.util.function.Supplier

object ModSounds {
    val SOUND_EVENTS: DeferredRegister<SoundEvent> =
        DeferredRegister.create(Registries.SOUND_EVENT, ShatteredDome.MOD_ID)

    val SIREN_WARNING: DeferredHolder<SoundEvent, SoundEvent> = SOUND_EVENTS.register("siren_warning", Supplier {
        SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(ShatteredDome.MOD_ID, "siren_warning"))
    })

    val METEOR_IMPACT: DeferredHolder<SoundEvent, SoundEvent> = SOUND_EVENTS.register("meteor_impact", Supplier {
        SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(ShatteredDome.MOD_ID, "meteor_impact"))
    })
}