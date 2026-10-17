package com.grindlesstudio.shattereddome.world

import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.saveddata.SavedData

class DomeWorldData : SavedData() {
    var borderRadius: Int = 100
    var isSkyBroken: Boolean = false
    var isStarfallActive: Boolean = false
    var meteorExplosionPower: Float = 3.0f

    override fun save(tag: CompoundTag, registries: HolderLookup.Provider): CompoundTag {
        tag.putInt("BorderRadius", borderRadius)
        tag.putBoolean("IsSkyBroken", isSkyBroken)
        tag.putBoolean("IsStarfallActive", isStarfallActive)
        tag.putFloat("MeteorExplosionPower", meteorExplosionPower)
        return tag
    }

    companion object {
        fun load(tag: CompoundTag, registries: HolderLookup.Provider): DomeWorldData {
            val data = DomeWorldData()
            data.borderRadius = if (tag.contains("BorderRadius")) tag.getInt("BorderRadius") else 100
            data.isSkyBroken = tag.getBoolean("IsSkyBroken")
            data.isStarfallActive = tag.getBoolean("IsStarfallActive")
            data.meteorExplosionPower = if (tag.contains("MeteorExplosionPower")) tag.getFloat("MeteorExplosionPower") else 3.0f
            return data
        }

        fun get(level: ServerLevel): DomeWorldData {
            return level.dataStorage.computeIfAbsent(
                Factory(::DomeWorldData, ::load),
                "shattered_dome_data"
            )
        }
    }
}