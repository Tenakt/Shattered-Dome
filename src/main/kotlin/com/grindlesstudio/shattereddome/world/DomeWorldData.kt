package com.grindlesstudio.shattereddome.world

import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.saveddata.SavedData

class DomeWorldData : SavedData() {
    var borderRadius: Int = 100
    var isSkyBroken: Boolean = false
    var isStarfallActive: Boolean = false
    var meteorEnabled: Boolean = true
    var meteorExplosionPower: Float = 3.0f
    var meteorMinIntervalMinutes: Int = 5
    var meteorMaxIntervalMinutes: Int = 15

    // Настройки частоты метеоритов во время SkyBreak (в секундах)
    var skyBreakMinIntervalSeconds: Int = 10
    var skyBreakMaxIntervalSeconds: Int = 20

    override fun save(tag: CompoundTag, registries: HolderLookup.Provider): CompoundTag {
        tag.putInt("BorderRadius", borderRadius)
        tag.putBoolean("IsSkyBroken", isSkyBroken)
        tag.putBoolean("IsStarfallActive", isStarfallActive)
        tag.putBoolean("MeteorEnabled", meteorEnabled)
        tag.putFloat("MeteorExplosionPower", meteorExplosionPower)
        tag.putInt("MeteorMinInterval", meteorMinIntervalMinutes)
        tag.putInt("MeteorMaxInterval", meteorMaxIntervalMinutes)
        tag.putInt("SkyBreakMinInterval", skyBreakMinIntervalSeconds)
        tag.putInt("SkyBreakMaxInterval", skyBreakMaxIntervalSeconds)
        return tag
    }

    companion object {
        fun load(tag: CompoundTag, registries: HolderLookup.Provider): DomeWorldData {
            val data = DomeWorldData()
            data.borderRadius = if (tag.contains("BorderRadius")) tag.getInt("BorderRadius") else 100
            data.isSkyBroken = tag.getBoolean("IsSkyBroken")
            data.isStarfallActive = tag.getBoolean("IsStarfallActive")
            data.meteorEnabled = if (tag.contains("MeteorEnabled")) tag.getBoolean("MeteorEnabled") else true
            data.meteorExplosionPower = if (tag.contains("MeteorExplosionPower")) tag.getFloat("MeteorExplosionPower") else 3.0f
            data.meteorMinIntervalMinutes = if (tag.contains("MeteorMinInterval")) tag.getInt("MeteorMinInterval") else 5
            data.meteorMaxIntervalMinutes = if (tag.contains("MeteorMaxInterval")) tag.getInt("MeteorMaxInterval") else 15
            data.skyBreakMinIntervalSeconds = if (tag.contains("SkyBreakMinInterval")) tag.getInt("SkyBreakMinInterval") else 10
            data.skyBreakMaxIntervalSeconds = if (tag.contains("SkyBreakMaxInterval")) tag.getInt("SkyBreakMaxInterval") else 20
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