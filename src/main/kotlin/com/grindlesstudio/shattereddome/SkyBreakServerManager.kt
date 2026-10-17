package com.grindlesstudio.shattereddome.server

import com.grindlesstudio.shattereddome.ShatteredDome
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
import net.minecraft.world.phys.Vec3
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.network.PacketDistributor
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

    fun startSkyBreak(level: ServerLevel) {
        phase = SkyBreakPhase.NIGHT_TRANSITION
        nightTimer = 0
        riftProgress = 0f

        val data = DomeWorldData.get(level)
        data.isSkyBroken = true
        data.isStarfallActive = true
        data.setDirty()

        PacketDistributor.sendToAllPlayers(SyncSkyBreakPayload(isBroken = true, riftProgress = 0f))
    }

    fun stopSkyBreak(level: ServerLevel) {
        phase = SkyBreakPhase.INACTIVE
        riftProgress = 0f

        val data = DomeWorldData.get(level)
        data.isSkyBroken = false
        data.isStarfallActive = false
        data.setDirty()

        PacketDistributor.sendToAllPlayers(SyncSkyBreakPayload(isBroken = false, riftProgress = 0f))
    }

    // Запуск Огромного Гигантского Метеорита
    fun spawnGiantMeteor(level: ServerLevel, player: ServerPlayer) {
        // Спавн высоко над тучами (Y = 360) и за пределом видимости
        val startX = player.x + Random.nextDouble(-350.0, 350.0)
        val startY = 360.0
        val startZ = player.z - 450.0

        // Направление полёта с комфортной ускоренной скоростью (scale = 0.75)
        val dir = Vec3(player.x - startX, -50.0, player.z + 450.0 - startZ).normalize().scale(0.75)

        val giant = MeteorEntity(
            level = level,
            x = startX,
            y = startY,
            z = startZ,
            velocity = dir,
            type = MeteorEntity.MeteorType.GIANT,
            power = 15
        )
        level.addFreshEntity(giant)
    }

    fun tick(level: ServerLevel) {
        val data = DomeWorldData.get(level)

        // --- Звездопад: Светящиеся полосы на высоте Y = 350..420 ---
        if (data.isStarfallActive && level.players().isNotEmpty()) {
            if (level.random.nextInt(2) == 0) {
                val player = level.players().random()
                val starX = player.x + Random.nextDouble(-250.0, 250.0)
                val starY = Random.nextDouble(350.0, 420.0) // Очень высоко в небе
                val starZ = player.z + Random.nextDouble(-250.0, 250.0)

                // Высокая гиперзвуковая скорость пролёта полосы света
                val starVel = Vec3(
                    Random.nextDouble(-10.0, 10.0),
                    Random.nextDouble(-1.5, -0.2),
                    Random.nextDouble(-10.0, 10.0)
                )

                val star = MeteorEntity(
                    level = level,
                    x = starX,
                    y = starY,
                    z = starZ,
                    velocity = starVel,
                    type = MeteorEntity.MeteorType.STAR,
                    power = 0
                )
                level.addFreshEntity(star)
            }
        }

        when (phase) {
            SkyBreakPhase.NIGHT_TRANSITION -> {
                nightTimer++
                val currentDayTime = level.dayTime % 24000L
                val diff = (TARGET_NIGHT_TIME - currentDayTime + 24000L) % 24000L
                val step = diff / (NIGHT_TRANSITION_TICKS - nightTimer + 1)

                level.setDayTime(level.dayTime + step)

                val timePacket = ClientboundSetTimePacket(
                    level.gameTime,
                    level.dayTime,
                    level.gameRules.getBoolean(GameRules.RULE_DAYLIGHT)
                )
                for (p in level.players()) p.connection.send(timePacket)

                if (nightTimer >= NIGHT_TRANSITION_TICKS) {
                    level.setDayTime(TARGET_NIGHT_TIME)
                    phase = SkyBreakPhase.EXPLOSION
                }
            }

            SkyBreakPhase.EXPLOSION -> {
                for (p in level.players()) {
                    level.playSound(null, p.blockPosition(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.WEATHER, 5.0f, 0.6f)
                    level.playSound(null, p.blockPosition(), SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, 3.0f, 0.8f)
                    level.sendParticles(p, ParticleTypes.EXPLOSION_EMITTER, true, p.x, p.y + 40.0, p.z, 5, 2.0, 2.0, 2.0, 0.1)
                }
                phase = SkyBreakPhase.RIFT_GROWTH
                riftProgress = 0.01f
                PacketDistributor.sendToAllPlayers(SyncSkyBreakPayload(isBroken = true, riftProgress = riftProgress))
            }

            SkyBreakPhase.RIFT_GROWTH -> {
                if (riftProgress < 1.0f) {
                    riftProgress += 0.0012f
                    if (riftProgress > 1.0f) riftProgress = 1.0f
                    PacketDistributor.sendToAllPlayers(SyncSkyBreakPayload(isBroken = true, riftProgress = riftProgress))
                }
            }

            SkyBreakPhase.INACTIVE -> {}
        }
    }
}

@EventBusSubscriber(modid = ShatteredDome.MOD_ID)
object SkyBreakServerEvents {

    @SubscribeEvent
    @JvmStatic
    fun onServerTick(event: ServerTickEvent.Post) {
        val server = event.server
        val overworld = server.getLevel(net.minecraft.world.level.Level.OVERWORLD) ?: return
        SkyBreakServerManager.tick(overworld)
    }

    @SubscribeEvent
    @JvmStatic
    fun onPlayerJoin(event: PlayerEvent.PlayerLoggedInEvent) {
        val player = event.entity as? ServerPlayer ?: return
        val isBroken = SkyBreakServerManager.phase != SkyBreakPhase.INACTIVE
        PacketDistributor.sendToPlayer(player, SyncSkyBreakPayload(isBroken, SkyBreakServerManager.riftProgress))
    }

    @SubscribeEvent
    @JvmStatic
    fun onRegisterCommands(event: RegisterCommandsEvent) {
        DomeCommands.register(event.dispatcher)
    }
}