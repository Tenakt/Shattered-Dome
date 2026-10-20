package com.grindlesstudio.shattereddome

import com.grindlesstudio.shattereddome.command.DomeCommands
import com.grindlesstudio.shattereddome.entity.MeteorEntity
import com.grindlesstudio.shattereddome.network.SyncSkyBreakPayload
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
    var nightTimer = 0
    var riftProgress = 0f

    private const val TARGET_NIGHT_TIME = 18000L
    private const val NIGHT_TRANSITION_TICKS = 100

    // Интервал между падающими звёздами.
    private var starfallSpawnCooldown = 0

    // Таймер подлёта большого метеорита.
    // Должен совпадать с METEOR_DURATION в SkyRiftRenderer.
    private var meteorBurstTimer = 0
    private var meteorBurstSpawned = false

    private const val METEOR_BURST_TICKS = 2400
    private const val PHYSICAL_FRAGMENT_COUNT = 8
    private const val DECORATIVE_FRAGMENT_COUNT = 22
    private const val METEOR_BURST_HEIGHT = 600

    fun startSkyBreak(level: ServerLevel) {
        phase = SkyBreakPhase.NIGHT_TRANSITION
        nightTimer = 0
        riftProgress = 0f
        starfallSpawnCooldown = 0
        meteorBurstTimer = 0
        meteorBurstSpawned = false

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
     * Создаёт осколки только после того, как большой метеорит взорвался.
     * Первые PHYSICAL_FRAGMENT_COUNT осколков физические,
     * остальные — декоративные.
     */
    fun spawnMeteorFragmentBurst(
        level: ServerLevel,
        player: ServerPlayer
    ) {
        val totalFragments =
            PHYSICAL_FRAGMENT_COUNT + DECORATIVE_FRAGMENT_COUNT

        repeat(totalFragments) { index ->
            val fragmentX = player.x + Random.nextDouble(-18.0, 18.0)
            val fragmentZ = player.z + Random.nextDouble(-18.0, 18.0)

            val groundY = level.getHeight(
                Heightmap.Types.MOTION_BLOCKING,
                fragmentX.toInt(),
                fragmentZ.toInt()
            )

            val spawnY = groundY + METEOR_BURST_HEIGHT

            val angle = Random.nextDouble(0.0, Math.PI * 2.0)
            val horizontalSpeed = Random.nextDouble(0.4, 2.0)

            val velocity = Vec3(
                cos(angle) * horizontalSpeed,
                -Random.nextDouble(1.8, 3.2),
                sin(angle) * horizontalSpeed
            )

            val isPhysical = index < PHYSICAL_FRAGMENT_COUNT

            val fragment = MeteorEntity(
                level = level,
                x = fragmentX,
                y = spawnY.toDouble(),
                z = fragmentZ,
                velocity = velocity,
                type = if (isPhysical) {
                    MeteorEntity.MeteorType.FRAGMENT
                } else {
                    MeteorEntity.MeteorType.DECORATIVE_FRAGMENT
                },
                power = if (isPhysical) 3 else 0
            )

            level.addFreshEntity(fragment)
        }

        // Вспышка в момент распада большой модели.
        level.sendParticles(
            ParticleTypes.EXPLOSION_EMITTER,
            player.x,
            player.y + 40.0,
            player.z,
            12,
            12.0,
            8.0,
            12.0,
            0.05
        )

        level.playSound(
            null,
            player.blockPosition(),
            SoundEvents.GENERIC_EXPLODE.value(),
            SoundSource.WEATHER,
            8.0f,
            0.45f
        )
    }

    fun tick(level: ServerLevel) {
        val data = DomeWorldData.get(level)

        // В момент окончания небесной анимации появляется сама 3D-модель.
        // Взрыв и осколки здесь не запускаются: они срабатывают при
        // достижении цели сущностью MeteorEntity.
        if (
            phase != SkyBreakPhase.INACTIVE &&
            !meteorBurstSpawned &&
            level.players().isNotEmpty()
        ) {
            meteorBurstTimer++

            if (meteorBurstTimer >= METEOR_BURST_TICKS) {
                val targetPlayer = level.players().random()
                spawnSkyfallMeteor(level, targetPlayer)
                meteorBurstSpawned = true
            }
        }

        // Звездопад: одна звезда примерно раз в 1–3 секунды.
        if (data.isStarfallActive && level.players().isNotEmpty()) {
            if (starfallSpawnCooldown > 0) {
                starfallSpawnCooldown--
            } else {
                val player = level.players().random()

                val starX = player.x + Random.nextDouble(-55.0, 55.0)
                val starZ = player.z + Random.nextDouble(-55.0, 55.0)
                val starY = player.y + Random.nextDouble(35.0, 65.0)

                val angle = Random.nextDouble(0.0, Math.PI * 2.0)
                val speed = Random.nextDouble(0.8, 1.4)

                val starVelocity = Vec3(
                    cos(angle) * speed,
                    Random.nextDouble(-0.55, -0.25),
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
                // Это начальная вспышка при раскрытии разлома.
                // Она не заменяет финальный взрыв метеорита.
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

    private fun spawnSkyfallMeteor(
        level: ServerLevel,
        player: ServerPlayer
    ) {
        // Модель появляется сбоку и выше игрока, чтобы был виден полёт.
        val start = Vec3(
            player.x + 25.0,
            player.y + 38.0,
            player.z + 25.0
        )

        // Точка взрыва находится над игроком, а не внутри поверхности.
        val target = Vec3(
            player.x,
            player.y + 18.0,
            player.z
        )

        val meteor = MeteorEntity(
            level = level,
            x = start.x,
            y = start.y,
            z = start.z,
            velocity = Vec3.ZERO,
            type = MeteorEntity.MeteorType.GIANT,
            power = 4
        )

        // Этот метод должен быть добавлен в MeteorEntity.kt.
        meteor.configureSkyfallFlight(
            target = target,
            durationTicks = 55
        )

        level.addFreshEntity(meteor)
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
