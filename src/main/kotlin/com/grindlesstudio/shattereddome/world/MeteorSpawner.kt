package com.grindlesstudio.shattereddome.world

import com.grindlesstudio.shattereddome.entity.MeteorEntity
import com.grindlesstudio.shattereddome.network.SyncSkyFlashPayload
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.network.PacketDistributor
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object MeteorSpawner {

    private const val METEOR_RENDER_DISTANCE = 50.0

    fun spawnMeteorNearPlayer(level: ServerLevel, player: ServerPlayer) {
        val data = DomeWorldData.get(level)
        val borderRadius = data.borderRadius.toDouble()

        // Расстояние игрока от центра купола (0, 0)
        val playerDistanceSquared = player.x * player.x + player.z * player.z

        // Игрок находится за пределами зоны купола
        if (playerDistanceSquared > borderRadius * borderRadius) {
            return
        }

        val random = level.random

        // 1. Вычисляем целевую точку падения возле игрока
        val angle = random.nextDouble() * Math.PI * 2.0
        val distance = sqrt(random.nextDouble()) * METEOR_RENDER_DISTANCE

        val targetX = player.x + cos(angle) * distance
        val targetZ = player.z + sin(angle) * distance

        // Не даём метеориту приземлиться за границей купола
        val targetDistanceSquared = targetX * targetX + targetZ * targetZ
        if (targetDistanceSquared > borderRadius * borderRadius) {
            return
        }

        val surfacePos = level.getHeightmapPos(
            Heightmap.Types.WORLD_SURFACE,
            BlockPos(targetX.toInt(), 0, targetZ.toInt())
        )
        val targetY = surfacePos.y.toDouble()

        // 2. Рассчитываем спавн под наклоном
        val spawnAngle = random.nextDouble() * Math.PI * 2.0
        val spawnDist = 60.0 + random.nextDouble() * 30.0
        val spawnX = targetX + cos(spawnAngle) * spawnDist
        val spawnZ = targetZ + sin(spawnAngle) * spawnDist

        val spawnY = (targetY + 110.0).coerceAtMost((level.maxBuildHeight - 10).toDouble())

        // Вектор направления падения под наклоном
        val dir = Vec3(targetX - spawnX, targetY - spawnY, targetZ - spawnZ).normalize()
        val speed = 2.0
        val velocity = dir.scale(speed)

        val power = data.meteorExplosionPower.toInt()

        // 3. Создаем сущность метеорита с явным указанием типа NORMAL
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

        // 4. Озарение неба при прорыве метеора сквозь купол
        PacketDistributor.sendToPlayersInDimension(level, SyncSkyFlashPayload)
    }

    fun triggerRandomMeteorShower(level: ServerLevel) {
        val players = level.players()
        if (players.isEmpty()) return

        for (player in players) {
            spawnMeteorNearPlayer(level, player)
        }
    }

    /**
     * Запускает гигантский сюжетный метеорит из точки вспышки (skyRiftPos)
     * по направлению к игроку с детонацией на высоте Y = 375.
     *
     * @param level Серверный мир
     * @param targetPlayer Игрок-цель
     * @param skyRiftPos Координаты вспышки/разлома на небе (например, Y = 700..900)
     * @param flightTicks Время полёта в тиках до точки взрыва (например, 60 тиков = 3 секунды)
     */
    fun spawnGiantSkyfallMeteor(
        level: ServerLevel,
        targetPlayer: ServerPlayer,
        skyRiftPos: Vec3,
        flightTicks: Int = 60
    ) {
        // Точка назначения на высоте Y = 375 над игроком
        val targetPosAt375 = Vec3(targetPlayer.x, 375.0, targetPlayer.z)

        val giantMeteor = MeteorEntity(
            level = level,
            x = skyRiftPos.x,
            y = skyRiftPos.y,
            z = skyRiftPos.z,
            velocity = Vec3.ZERO,
            type = MeteorEntity.MeteorType.GIANT,
            power = 10
        )

        // Настраиваем вектор полёта от точки вспышки к Y = 375 над игроком
        giantMeteor.configureSkyfallFlight(targetPosAt375, durationTicks = flightTicks)

        level.addFreshEntity(giantMeteor)
    }
}