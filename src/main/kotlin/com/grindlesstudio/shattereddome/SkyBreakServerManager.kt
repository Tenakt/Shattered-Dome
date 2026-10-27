package com.grindlesstudio.shattereddome

import com.grindlesstudio.shattereddome.command.DomeCommands
import com.grindlesstudio.shattereddome.entity.MeteorEntity
import com.grindlesstudio.shattereddome.network.SyncScreenShakePayload
import com.grindlesstudio.shattereddome.network.SyncSkyBreakPayload
import com.grindlesstudio.shattereddome.network.SyncSkyFlashPayload
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
    RIFT_GROWTH
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

    // 117 секунд до вспышки, затем 25 секунд на её рост и 3 секунды полёта.
    private const val FINALE_FLASH_TICK = 2340
    private const val FINALE_FLASH_DURATION = 500
    private const val FINALE_METEOR_TICK =
        FINALE_FLASH_TICK + FINALE_FLASH_DURATION
    private const val FINALE_FLIGHT_TICKS = 60

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

        val data = DomeWorldData.get(level)
        data.isSkyBroken = true
        data.isStarfallActive = true
        data.setDirty()

        PacketDistributor.sendToAllPlayers(
            SyncSkyBreakPayload(
                isBroken = true,
                riftProgress = 0f
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

        val data = DomeWorldData.get(level)
        data.isSkyBroken = false
        data.isStarfallActive = false
        data.setDirty()

        PacketDistributor.sendToAllPlayers(
            SyncSkyBreakPayload(
                isBroken = false,
                riftProgress = 0f
            )
        )
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
     * Вычисляет точку взрыва и точку, из которой начнётся полёт метеорита.
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

        // Сущность может лететь выше предела строительства мира.
        // Поэтому сохраняем необходимую высоту воздушного взрыва.
        val targetY = surfaceY + METEOR_BURST_HEIGHT
        val target = Vec3(clusterCenter.x, targetY, clusterCenter.z)

        // Точка появления находится в верхней части неба и достаточно близко,
        // чтобы метеорит мог двигаться к цели во время загруженных чанков.
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
     * Создаёт большой метеорит как реальную сущность.
     * Взрыв и разлёт осколков выполняются самой MeteorEntity.
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

        // Финальная последовательность работает только при наличии игроков.
        if (
            phase != SkyBreakPhase.INACTIVE &&
            !meteorBurstSpawned &&
            level.players().isNotEmpty()
        ) {
            meteorBurstTimer++

            if (
                meteorBurstTimer >= FINALE_FLASH_TICK &&
                !finaleFlashSent
            ) {
                sendFinaleFlash(level)
            }

            // После успешного появления сущности таймер останавливается.
            // Метеорит сам завершит полёт и взорвётся через 60 тиков.
            if (
                meteorBurstTimer >= FINALE_METEOR_TICK &&
                !meteorBurstSpawned
            ) {
                meteorBurstSpawned = spawnFinaleMeteor(level)
            }
        }

        // Обычный звёздопад сохраняется.
        if (data.isStarfallActive && level.players().isNotEmpty()) {
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

                val star = MeteorEntity(
                    level = level,
                    x = starX,
                    y = starY,
                    z = starZ,
                    velocity = starVelocity,
                    type = MeteorEntity.MeteorType.STAR,
                    power = 0
                )

                level.addFreshEntity(star)
                starfallSpawnCooldown = Random.nextInt(20, 61)
            }
        }

        when (phase) {
            SkyBreakPhase.NIGHT_TRANSITION -> {
                nightTimer++

                val currentDayTime = level.dayTime % 24000L
                val diff =
                    (TARGET_NIGHT_TIME - currentDayTime + 24000L) % 24000L

                val remainingTicks =
                    (NIGHT_TRANSITION_TICKS - nightTimer + 1)
                        .coerceAtLeast(1)

                val step = diff / remainingTicks
                level.setDayTime(level.dayTime + step)

                val timePacket = ClientboundSetTimePacket(
                    level.gameTime,
                    level.dayTime,
                    level.gameRules.getBoolean(GameRules.RULE_DAYLIGHT)
                )

                for (player in level.players()) {
                    player.connection.send(timePacket)
                }

                if (nightTimer >= NIGHT_TRANSITION_TICKS) {
                    level.setDayTime(TARGET_NIGHT_TIME)
                    phase = SkyBreakPhase.EXPLOSION
                }
            }

            SkyBreakPhase.EXPLOSION -> {
                for (player in level.players()) {
                    level.playSound(
                        null,
                        player.blockPosition(),
                        SoundEvents.GENERIC_EXPLODE.value(),
                        SoundSource.WEATHER,
                        5.0f,
                        0.6f
                    )

                    level.playSound(
                        null,
                        player.blockPosition(),
                        SoundEvents.LIGHTNING_BOLT_THUNDER,
                        SoundSource.WEATHER,
                        3.0f,
                        0.8f
                    )

                    level.sendParticles(
                        player,
                        ParticleTypes.EXPLOSION_EMITTER,
                        true,
                        player.x,
                        player.y + 40.0,
                        player.z,
                        5,
                        2.0,
                        2.0,
                        2.0,
                        0.1
                    )
                }

                phase = SkyBreakPhase.RIFT_GROWTH
                riftProgress = 0.01f

                PacketDistributor.sendToAllPlayers(
                    SyncSkyBreakPayload(
                        isBroken = true,
                        riftProgress = riftProgress
                    )
                )
            }

            SkyBreakPhase.RIFT_GROWTH -> {
                if (riftProgress < 1.0f) {
                    riftProgress =
                        (riftProgress + 0.0012f).coerceAtMost(1.0f)

                    PacketDistributor.sendToAllPlayers(
                        SyncSkyBreakPayload(
                            isBroken = true,
                            riftProgress = riftProgress
                        )
                    )
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
                SkyBreakServerManager.riftProgress
            )
        )
    }

    @SubscribeEvent
    @JvmStatic
    fun onRegisterCommands(event: RegisterCommandsEvent) {
        DomeCommands.register(event.dispatcher)
    }
}