
package com.grindlesstudio.shattereddome

import com.grindlesstudio.shattereddome.command.DomeCommands
import com.grindlesstudio.shattereddome.entity.MeteorEntity
import com.grindlesstudio.shattereddome.network.SyncScreenShakePayload
import com.grindlesstudio.shattereddome.network.SyncSkyBreakPayload
import com.grindlesstudio.shattereddome.network.SyncSkyFlashPayload
import com.grindlesstudio.shattereddome.network.SkyEventStage
import com.grindlesstudio.shattereddome.world.DomeWorldData
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.protocol.game.ClientboundSetTimePacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.level.GameRules
import net.minecraft.world.level.Level
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.Vec3
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.network.PacketDistributor
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

enum class SkyBreakPhase {
    INACTIVE,
    NIGHT_TRANSITION,
    EXPLOSION,
    RIFT_GROWTH,
    METEOR_STORM,
    RESTORING
}

object SkyBreakServerManager {

    var phase = SkyBreakPhase.INACTIVE
        private set

    var nightTimer = 0
        private set

    var riftProgress = 0f
        private set

    private const val TARGET_NIGHT_TIME = 18000L
    private const val NIGHT_TRANSITION_TICKS = 100

    private var starfallSpawnCooldown = 0
    private var meteorBurstTimer = 0
    private var meteorBurstSpawned = false
    private var finaleFlashSent = false
    private var finaleStartPoint: Vec3? = null
    private var finaleTargetPoint: Vec3? = null
    private var riftMeteorCooldown = 80
    private var stormAfterBurstTimer = 0
    private var restorationTimer = 0

    // 117 секунд до вспышки, затем 25 секунд на её рост и 3 секунды полёта.
    private const val FINALE_FLASH_TICK = 2340
    private const val FINALE_FLASH_DURATION = 500
    private const val FINALE_METEOR_TICK = FINALE_FLASH_TICK + FINALE_FLASH_DURATION
    private const val FINALE_FLIGHT_TICKS = 60
    private const val METEOR_STORM_RESTORATION_DELAY = 700
    private const val RESTORATION_DURATION = 600

    private const val METEOR_BURST_HEIGHT = 380.0
    private const val PLAYER_CLUSTER_RADIUS = 80.0

    fun startSkyBreak(level: ServerLevel) {
        phase = SkyBreakPhase.NIGHT_TRANSITION
        nightTimer = 0
        riftProgress = 0f
        starfallSpawnCooldown = 0
        meteorBurstTimer = 0
        meteorBurstSpawned = false
        finaleFlashSent = false
        finaleStartPoint = null
        finaleTargetPoint = null
        riftMeteorCooldown = 80
        stormAfterBurstTimer = 0
        restorationTimer = 0

        val data = DomeWorldData.get(level)
        data.isSkyBroken = true
        data.isStarfallActive = true
        data.setDirty()

        PacketDistributor.sendToAllPlayers(
            SyncSkyBreakPayload(
                isBroken = true,
                riftProgress = 0f,
                eventStage = SkyEventStage.AWAKENING
            )
        )
    }

    fun stopSkyBreak(level: ServerLevel) {
        phase = SkyBreakPhase.INACTIVE
        riftProgress = 0f
        nightTimer = 0
        starfallSpawnCooldown = 0
        meteorBurstTimer = 0
        meteorBurstSpawned = false
        finaleFlashSent = false
        finaleStartPoint = null
        finaleTargetPoint = null
        riftMeteorCooldown = 80
        stormAfterBurstTimer = 0
        restorationTimer = 0

        val data = DomeWorldData.get(level)
        data.isSkyBroken = false
        data.isStarfallActive = false
        data.setDirty()

        PacketDistributor.sendToAllPlayers(
            SyncSkyBreakPayload(
                isBroken = false,
                riftProgress = 0f,
                eventStage = SkyEventStage.INACTIVE
            )
        )
    }

    private fun currentEventStage(): Int = when (phase) {
        SkyBreakPhase.INACTIVE -> SkyEventStage.INACTIVE
        SkyBreakPhase.NIGHT_TRANSITION -> SkyEventStage.AWAKENING
        SkyBreakPhase.EXPLOSION, SkyBreakPhase.RIFT_GROWTH -> SkyEventStage.RIFT_OPENING
        SkyBreakPhase.METEOR_STORM -> SkyEventStage.METEOR_STORM
        SkyBreakPhase.RESTORING -> SkyEventStage.RESTORING
    }

    private fun broadcastSkyState(level: ServerLevel, isBroken: Boolean = true) {
        PacketDistributor.sendToAllPlayers(
            SyncSkyBreakPayload(
                isBroken = isBroken,
                riftProgress = riftProgress,
                eventStage = currentEventStage()
            )
        )
    }

    private fun sendShake(level: ServerLevel, intensity: Float, duration: Int) {
        for (player in level.players()) {
            PacketDistributor.sendToPlayer(
                player,
                SyncScreenShakePayload(intensity = intensity, duration = duration)
            )
        }
    }

    /** Spawn a normal angled meteor from high above and off to one side of a player. */
    private fun spawnRiftMeteor(level: ServerLevel, player: ServerPlayer) {
        val random = level.random
        val angle = random.nextDouble() * Math.PI * 2.0
        val horizontalOffset = 75.0 + random.nextDouble() * (115.0 - 75.0)
        val startX = player.x + cos(angle) * horizontalOffset
        val startZ = player.z + sin(angle) * horizontalOffset
        val startY = player.y + 85.0 + random.nextDouble() * (125.0 - 85.0)

        val targetX = player.x - 24.0 + random.nextDouble() * 48.0
        val targetZ = player.z - 24.0 + random.nextDouble() * 48.0
        val targetY = level.getHeight(
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            floor(targetX).toInt(),
            floor(targetZ).toInt()
        ).toDouble() + 2.0

        val direction = Vec3(targetX - startX, targetY - startY, targetZ - startZ).normalize()
        val velocity = direction.scale(1.8 + random.nextDouble() * (2.7 - 1.8))
        val meteor = MeteorEntity(
            level = level,
            x = startX,
            y = startY,
            z = startZ,
            velocity = velocity,
            type = MeteorEntity.MeteorType.NORMAL,
            power = 2
        )
        level.addFreshEntity(meteor)
    }


    /**
     * Отрывает большой треугольный фрагмент космического неба.
     * Это отдельная визуальная сущность: она летит по диагонали и исчезает при ударе,
     * не разрушая блоки и не меняя механику сюжетного гигантского метеорита.
     */
    private fun spawnRiftSkyShard(level: ServerLevel, player: ServerPlayer) {
        val targetX = player.x + (Random.nextDouble() - 0.5) * 44.0
        val targetZ = player.z + (Random.nextDouble() - 0.5) * 44.0
        val targetY = level.getHeight(
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            floor(targetX).toInt(),
            floor(targetZ).toInt()
        ).toDouble() + 1.5

        val angle = Random.nextDouble() * Math.PI * 2.0
        // Сдвигаем старт в сторону, чтобы треугольник летел по диагонали, а не падал строго вертикально.
        val horizontalOffset = 55.0 + Random.nextDouble() * 32.0
        val startX = targetX + cos(angle) * horizontalOffset
        val startZ = targetZ + sin(angle) * horizontalOffset
        val startY = maxOf(player.y, targetY) + 105.0 + Random.nextDouble() * 45.0

        val direction = Vec3(
            targetX - startX,
            targetY - startY,
            targetZ - startZ
        ).normalize()
        val velocity = direction.scale(2.15 + Random.nextDouble() * 0.75)

        val shard = MeteorEntity(
            level = level,
            x = startX,
            y = startY,
            z = startZ,
            velocity = velocity,
            type = MeteorEntity.MeteorType.SKY_SHARD,
            power = 0
        )
        shard.configureSkyShardFlight(
            target = Vec3(targetX, targetY, targetZ),
            speed = velocity.length()
        )
        level.addFreshEntity(shard)
    }

    private fun beginRestoration(level: ServerLevel) {
        if (phase == SkyBreakPhase.RESTORING || phase == SkyBreakPhase.INACTIVE) return
        phase = SkyBreakPhase.RESTORING
        restorationTimer = 0
        val data = DomeWorldData.get(level)
        data.isStarfallActive = false
        data.setDirty()
        broadcastSkyState(level)
    }

    private fun finishRestoration(level: ServerLevel) {
        phase = SkyBreakPhase.INACTIVE
        riftProgress = 0f
        nightTimer = 0
        starfallSpawnCooldown = 0
        meteorBurstTimer = 0
        meteorBurstSpawned = false
        finaleFlashSent = false
        finaleStartPoint = null
        finaleTargetPoint = null
        riftMeteorCooldown = 80
        stormAfterBurstTimer = 0
        restorationTimer = 0

        val data = DomeWorldData.get(level)
        data.isSkyBroken = false
        data.isStarfallActive = false
        data.setDirty()
        broadcastSkyState(level, isBroken = false)
    }

    /**
     * Находит центр самого плотного скопления игроков.
     */
    private fun findDensestPlayerClusterCenter(
        level: ServerLevel
    ): Vec3? {
        val players = level.players()
        if (players.isEmpty()) return null

        var maxNearbyCount = -1
        var bestCenter: Vec3? = null

        for (player in players) {
            val nearby = players.filter {
                it.distanceToSqr(player) <=
                        PLAYER_CLUSTER_RADIUS * PLAYER_CLUSTER_RADIUS
            }

            if (nearby.size > maxNearbyCount) {
                maxNearbyCount = nearby.size

                val avgX = nearby.sumOf { it.x } / nearby.size
                val avgZ = nearby.sumOf { it.z } / nearby.size

                bestCenter = Vec3(avgX, 0.0, avgZ)
            }
        }

        return bestCenter
    }

    /**
     * Выбирает точку взрыва над самой плотной группой игроков и точку,
     * из которой начнётся полёт настоящей MeteorEntity.
     */
    private fun prepareFinaleTrajectory(level: ServerLevel): Boolean {
        val clusterCenter = findDensestPlayerClusterCenter(level) ?: return false

        val blockX = floor(clusterCenter.x).toInt()
        val blockZ = floor(clusterCenter.z).toInt()
        val surfaceY = level.getHeight(
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            blockX,
            blockZ
        )

        // Entity can fly above the build limit; the airburst stays 380 blocks above terrain.
        val targetY = surfaceY + METEOR_BURST_HEIGHT
        val target = Vec3(clusterCenter.x, targetY, clusterCenter.z)

        // Источник находится в верхней части неба, достаточно близко к игрокам,
        // чтобы сущность оставалась в загруженной области во время полёта.
        val source = target.add(-144.0, 170.0, 48.0)

        finaleStartPoint = source
        finaleTargetPoint = target
        return true
    }

    private fun sendFinaleFlash(level: ServerLevel): Boolean {
        if (finaleStartPoint == null || finaleTargetPoint == null) {
            if (!prepareFinaleTrajectory(level)) return false
        }

        val source = finaleStartPoint ?: return false
        val target = finaleTargetPoint ?: return false

        PacketDistributor.sendToAllPlayers(
            SyncSkyFlashPayload(
                sourceX = source.x,
                sourceY = source.y,
                sourceZ = source.z,
                targetX = target.x,
                targetY = target.y,
                targetZ = target.z
            )
        )
        finaleFlashSent = true
        return true
    }

    /**
     * Создаёт настоящий гигантский метеорит. Его сущность летит к target
     * и сама выполняет воздушный взрыв и разлёт осколков через 60 тиков.
     */
    private fun spawnFinaleMeteor(level: ServerLevel): Boolean {
        if (finaleStartPoint == null || finaleTargetPoint == null) {
            if (!prepareFinaleTrajectory(level)) return false
        }

        if (!finaleFlashSent && !sendFinaleFlash(level)) return false

        val source = finaleStartPoint ?: return false
        val target = finaleTargetPoint ?: return false

        val meteor = MeteorEntity(
            level = level,
            x = source.x,
            y = source.y,
            z = source.z,
            velocity = Vec3.ZERO,
            type = MeteorEntity.MeteorType.GIANT,
            power = 8
        )
        meteor.configureSkyfallFlight(target, FINALE_FLIGHT_TICKS)
        return level.addFreshEntity(meteor)
    }

    fun tick(level: ServerLevel) {
        val data = DomeWorldData.get(level)
        val hasPlayers = level.players().isNotEmpty()

        if (phase != SkyBreakPhase.INACTIVE && hasPlayers && !meteorBurstSpawned) {
            meteorBurstTimer++

            if (meteorBurstTimer >= FINALE_FLASH_TICK && !finaleFlashSent) {
                sendFinaleFlash(level)
            }

            if (meteorBurstTimer >= FINALE_METEOR_TICK && !meteorBurstSpawned) {
                meteorBurstSpawned = spawnFinaleMeteor(level)
            }
        }

        // A few regular stars remain before the full rifts open. They stop during healing.
        if (data.isStarfallActive && hasPlayers && phase != SkyBreakPhase.RESTORING) {
            if (starfallSpawnCooldown > 0) {
                starfallSpawnCooldown--
            } else {
                val player = level.players().random()
                val starX = player.x + Random.nextDouble(-60.0, 60.0)
                val starZ = player.z + Random.nextDouble(-60.0, 60.0)
                val starY = player.y + Random.nextDouble(40.0, 70.0)
                val angle = Random.nextDouble(0.0, Math.PI * 2.0)
                val speed = Random.nextDouble(0.9, 1.5)
                val starVelocity = Vec3(
                    cos(angle) * speed,
                    Random.nextDouble(-0.6, -0.3),
                    sin(angle) * speed
                )
                level.addFreshEntity(
                    MeteorEntity(
                        level = level,
                        x = starX,
                        y = starY,
                        z = starZ,
                        velocity = starVelocity,
                        type = MeteorEntity.MeteorType.STAR,
                        power = 0
                    )
                )
                starfallSpawnCooldown = Random.nextInt(20, 61)
            }
        }

        // While rifts are opening and during the storm, send angled meteors down from above.
        if (phase == SkyBreakPhase.METEOR_STORM && hasPlayers) {
            if (riftMeteorCooldown > 0) {
                riftMeteorCooldown--
            } else {
                val targetPlayer = level.players().random()
                if (Random.nextFloat() < 0.42f) {
                    // Иногда с неба отрывается целый треугольный фрагмент космоса.
                    spawnRiftSkyShard(level, targetPlayer)
                } else {
                    spawnRiftMeteor(level, targetPlayer)
                }
                // События редкие, чтобы сохранить масштаб и не перегружать сервер.
                riftMeteorCooldown = Random.nextInt(160, 281)
            }
        }

        when (phase) {
            SkyBreakPhase.NIGHT_TRANSITION -> {
                nightTimer++

                // A warning tremor ramps up before the first visible flash.
                if (
                    nightTimer in (NIGHT_TRANSITION_TICKS - 35) until NIGHT_TRANSITION_TICKS &&
                    nightTimer % 5 == 0
                ) {
                    val ramp = ((nightTimer - (NIGHT_TRANSITION_TICKS - 35)).toFloat() / 35f)
                        .coerceIn(0f, 1f)
                    sendShake(level, intensity = 0.12f + ramp * 0.85f, duration = 8)
                }

                val currentDayTime = level.dayTime % 24000L
                val diff = (TARGET_NIGHT_TIME - currentDayTime + 24000L) % 24000L
                val remainingTicks = (NIGHT_TRANSITION_TICKS - nightTimer + 1).coerceAtLeast(1)
                val step = diff / remainingTicks
                level.setDayTime(level.dayTime + step)

                val timePacket = ClientboundSetTimePacket(
                    level.gameTime,
                    level.dayTime,
                    level.gameRules.getBoolean(GameRules.RULE_DAYLIGHT)
                )
                for (player in level.players()) player.connection.send(timePacket)

                if (nightTimer >= NIGHT_TRANSITION_TICKS) {
                    level.setDayTime(TARGET_NIGHT_TIME)
                    phase = SkyBreakPhase.EXPLOSION
                }
            }

            SkyBreakPhase.EXPLOSION -> {
                // Sudden crack-opening flash and a stronger impact shake.
                sendShake(level, intensity = 2.4f, duration = 38)
                for (player in level.players()) {
                    level.playSound(
                        null, player.blockPosition(), SoundEvents.GENERIC_EXPLODE.value(),
                        SoundSource.WEATHER, 5.0f, 0.6f
                    )
                    level.playSound(
                        null, player.blockPosition(), SoundEvents.LIGHTNING_BOLT_THUNDER,
                        SoundSource.WEATHER, 3.0f, 0.8f
                    )
                    level.sendParticles(
                        player, ParticleTypes.EXPLOSION_EMITTER, true,
                        player.x, player.y + 40.0, player.z,
                        5, 2.0, 2.0, 2.0, 0.1
                    )
                }

                phase = SkyBreakPhase.RIFT_GROWTH
                riftProgress = 0.01f
                broadcastSkyState(level)
            }

            SkyBreakPhase.RIFT_GROWTH -> {
                if (riftProgress < 1.0f) {
                    riftProgress = (riftProgress + 0.0012f).coerceAtMost(1.0f)
                    broadcastSkyState(level)
                }

                if (riftProgress >= 1.0f) {
                    phase = SkyBreakPhase.METEOR_STORM
                    broadcastSkyState(level)
                }
            }

            SkyBreakPhase.METEOR_STORM -> {
                // Keep the rifts open through the meteor storm and the existing giant meteor finale.
                if (meteorBurstSpawned) {
                    stormAfterBurstTimer++
                    if (stormAfterBurstTimer >= METEOR_STORM_RESTORATION_DELAY) {
                        beginRestoration(level)
                    }
                }
            }

            SkyBreakPhase.RESTORING -> {
                restorationTimer++
                riftProgress = (riftProgress - 1.0f / RESTORATION_DURATION).coerceAtLeast(0f)

                // Gradually close the rifts. Packets are still sent every tick so clients animate together.
                broadcastSkyState(level)

                if (riftProgress <= 0f || restorationTimer >= RESTORATION_DURATION) {
                    finishRestoration(level)
                }
            }

            SkyBreakPhase.INACTIVE -> Unit
        }
    }
}

@EventBusSubscriber(modid = ShatteredDome.MOD_ID)
object SkyBreakServerEvents {

    @SubscribeEvent
    @JvmStatic
    fun onServerTick(event: ServerTickEvent.Post) {
        val overworld = event.server.getLevel(Level.OVERWORLD) ?: return
        SkyBreakServerManager.tick(overworld)
    }

    @SubscribeEvent
    @JvmStatic
    fun onPlayerJoin(event: PlayerEvent.PlayerLoggedInEvent) {
        val player = event.entity as? ServerPlayer ?: return

        val isBroken =
            SkyBreakServerManager.phase != SkyBreakPhase.INACTIVE

        PacketDistributor.sendToPlayer(
            player,
            SyncSkyBreakPayload(
                isBroken,
                SkyBreakServerManager.riftProgress,
                when (SkyBreakServerManager.phase) {
                    SkyBreakPhase.INACTIVE -> SkyEventStage.INACTIVE
                    SkyBreakPhase.NIGHT_TRANSITION -> SkyEventStage.AWAKENING
                    SkyBreakPhase.EXPLOSION, SkyBreakPhase.RIFT_GROWTH -> SkyEventStage.RIFT_OPENING
                    SkyBreakPhase.METEOR_STORM -> SkyEventStage.METEOR_STORM
                    SkyBreakPhase.RESTORING -> SkyEventStage.RESTORING
                }
            )
        )
    }

    @SubscribeEvent
    @JvmStatic
    fun onRegisterCommands(event: RegisterCommandsEvent) {
        DomeCommands.register(event.dispatcher)
    }
}
