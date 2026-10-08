package com.grindlesstudio.shattereddome.init

import com.grindlesstudio.shattereddome.ShatteredDome
import com.grindlesstudio.shattereddome.entity.MeteorEntity
import net.minecraft.core.registries.Registries
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobCategory
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister

object ModEntities {
    val ENTITIES: DeferredRegister<EntityType<*>> =
        DeferredRegister.create(Registries.ENTITY_TYPE, ShatteredDome.MOD_ID)

    val METEOR: DeferredHolder<EntityType<*>, EntityType<MeteorEntity>> = ENTITIES.register("meteor") { ->
        EntityType.Builder.of(::MeteorEntity, MobCategory.MISC)
            .sized(2.0f, 2.0f)
            .clientTrackingRange(16)
            .updateInterval(1)
            .build("meteor")
    }
}