package com.grindlesstudio.shattereddome.world

import com.grindlesstudio.shattereddome.entity.MeteorEntity
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.levelgen.Heightmap
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object MeteorSpawner {

    private const val METEOR_RENDER_DISTANCE = 60.0

    fun spawnMeteorNearPlayer(level: ServerLevel, player: ServerPlayer) {

        val data = DomeWorldData.get(level)
        val borderRadius = data.borderRadius.toDouble()

        // Расстояние игрока от центра купола (0, 0)
        val playerDistanceSquared =
            player.x * player.x +
                    player.z * player.z

        // Игрок находится за пределами зоны метеоритов
        if (playerDistanceSquared > borderRadius * borderRadius) {
            return
        }

        val random = level.random

        // Случайная точка рядом с игроком
        val angle = random.nextDouble() * Math.PI * 2.0
        val distance = sqrt(random.nextDouble()) * METEOR_RENDER_DISTANCE

        val targetX = player.x + cos(angle) * distance
        val targetZ = player.z + sin(angle) * distance

        // Не даём метеориту попасть за границу купола
        val targetDistanceSquared =
            targetX * targetX +
                    targetZ * targetZ

        if (targetDistanceSquared > borderRadius * borderRadius) {
            return
        }

        val surfacePos = level.getHeightmapPos(
            Heightmap.Types.WORLD_SURFACE,
            BlockPos(targetX.toInt(), 0, targetZ.toInt())
        )

        val surfaceY = surfacePos.y.toDouble()

        val power = data.meteorExplosionPower.toInt()

        val spawnY = (surfaceY + 320.0).coerceAtMost(
            (level.maxBuildHeight - 10).toDouble()
        )

        val meteor = MeteorEntity(
            level,
            targetX,
            spawnY,
            targetZ,
            power
        )

        level.addFreshEntity(meteor)
    }

    fun triggerRandomMeteorShower(level: ServerLevel) {

        val players = level.players()

        if (players.isEmpty()) {
            return
        }

        // Метеориты падают рядом со ВСЕМИ игроками,
        // которые находятся внутри границы.
        for (player in players) {
            spawnMeteorNearPlayer(level, player)
        }
    }
}