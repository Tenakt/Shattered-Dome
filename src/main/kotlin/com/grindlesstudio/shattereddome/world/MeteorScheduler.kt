package com.grindlesstudio.shattereddome.world

import com.grindlesstudio.shattereddome.entity.MeteorEntity
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3
import kotlin.random.Random

object MeteorScheduler {

    fun spawnSingleMeteor(level: ServerLevel, player: ServerPlayer) {
        val data = DomeWorldData.get(level)
        val startX = player.x + Random.nextDouble(-40.0, 40.0)
        val startY = 160.0
        val startZ = player.z + Random.nextDouble(-40.0, 40.0)

        val velocity = Vec3(Random.nextDouble(-0.3, 0.3), -1.4, Random.nextDouble(-0.3, 0.3))
        val meteor = MeteorEntity(
            level, startX, startY, startZ, velocity,
            MeteorEntity.MeteorType.NORMAL, data.meteorExplosionPower.toInt()
        )
        level.addFreshEntity(meteor)
    }
}