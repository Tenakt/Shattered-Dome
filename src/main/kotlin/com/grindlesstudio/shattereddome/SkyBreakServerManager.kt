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

    fun startSkyBreak(level: ServerLevel) {
        phase = SkyBreakPhase.NIGHT_TRANSITION
        nightTimer = 0
        riftProgress = 0f
        starfallSpawnCooldown = 0

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

    fun tick(level: ServerLevel) {
        val data = DomeWorldData.get(level)

        // Звездопад: одна звезда примерно раз в 1–3 секунды.
        if (data.isStarfallActive && level.players().isNotEmpty()) {
            if (starfallSpawnCooldown > 0) {
                starfallSpawnCooldown--
            } else {
                val player = level.players().random()

                // Создаём звёзды рядом с игроком по горизонтали.
                val starX = player.x + Random.nextDouble(-55.0, 55.0)
                val starZ = player.z + Random.nextDouble(-55.0, 55.0)

                // Высота относительно игрока.
                val starY = player.y + Random.nextDouble(35.0, 65.0)

                // Умеренная скорость по диагонали и вниз.
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
