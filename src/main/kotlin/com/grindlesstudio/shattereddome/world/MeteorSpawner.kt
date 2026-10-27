package com.grindlesstudio.shattereddome.world

import com.grindlesstudio.shattereddome.entity.MeteorEntity
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object MeteorSpawner {

    private const val METEOR_RENDER_DISTANCE = 50.0

    fun spawnMeteorNearPlayer(level: ServerLevel, player: ServerPlayer) {
        val data = DomeWorldData.get(level)
        val borderRadius = data.borderRadius.toDouble()

        // Проверяем, находится ли игрок внутри купола.
        val playerDistanceSquared = player.x * player.x + player.z * player.z

        if (playerDistanceSquared > borderRadius * borderRadius) {
            return
        }

        val random = level.random

        // Вычисляем точку падения обычного метеорита рядом с игроком.
        val angle = random.nextDouble() * Math.PI * 2.0
        val distance = sqrt(random.nextDouble()) * METEOR_RENDER_DISTANCE

        val targetX = player.x + cos(angle) * distance
        val targetZ = player.z + sin(angle) * distance

        // Не допускаем спавн за границей купола.
        val targetDistanceSquared = targetX * targetX + targetZ * targetZ

        if (targetDistanceSquared > borderRadius * borderRadius) {
            return
        }

        val surfacePos = level.getHeightmapPos(
            Heightmap.Types.WORLD_SURFACE,
            BlockPos(targetX.toInt(), 0, targetZ.toInt())
        )

        val targetY = surfacePos.y.toDouble()

        // Точка появления выше и в стороне от цели.
        val spawnAngle = random.nextDouble() * Math.PI * 2.0
        val spawnDist = 60.0 + random.nextDouble() * 30.0

        val spawnX = targetX + cos(spawnAngle) * spawnDist
        val spawnZ = targetZ + sin(spawnAngle) * spawnDist

        val spawnY = (targetY + 110.0)
            .coerceAtMost((level.maxBuildHeight - 10).toDouble())

        // Направление полёта под наклоном.
        val direction = Vec3(
            targetX - spawnX,
            targetY - spawnY,
            targetZ - spawnZ
        ).normalize()

        val velocity = direction.scale(2.0)
        val power = data.meteorExplosionPower.toInt()

        val meteor = MeteorEntity(
            level = level,
            x = spawnX,
            y = spawnY,
            z = spawnZ,
            velocity = velocity,
            type = MeteorEntity.MeteorType.NORMAL,
            power = power
        )

        level.addFreshEntity(meteor)
    }

    fun triggerRandomMeteorShower(level: ServerLevel) {
        val players = level.players()

        if (players.isEmpty()) return

        for (player in players) {
            spawnMeteorNearPlayer(level, player)
        }
    }

    /**
     * Создаёт большой сюжетный метеорит.
     *
     * @param level Серверный мир.
     * @param targetPlayer Игрок, над которым должна быть точка взрыва.
     * @param skyRiftPos Координаты источника метеорита.
     * @param flightTicks Продолжительность полёта в тиках.
     */
    fun spawnGiantSkyfallMeteor(
        level: ServerLevel,
        targetPlayer: ServerPlayer,
        skyRiftPos: Vec3,
        flightTicks: Int = 60
    ) {
        val targetPos = Vec3(
            targetPlayer.x,
            375.0,
            targetPlayer.z
        )

        val giantMeteor = MeteorEntity(
            level = level,
            x = skyRiftPos.x,
            y = skyRiftPos.y,
            z = skyRiftPos.z,
            velocity = Vec3.ZERO,
            type = MeteorEntity.MeteorType.GIANT,
            power = 10
        )

        giantMeteor.configureSkyfallFlight(
            target = targetPos,
            durationTicks = flightTicks
        )

        level.addFreshEntity(giantMeteor)
    }
}