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

    private var starfallSpawnCooldown = 0
    private var meteorBurstTimer = 0
    private var meteorBurstSpawned = false

    private const val METEOR_BURST_TICKS = 2400
    private const val PHYSICAL_FRAGMENT_COUNT = 8
    private const val DECORATIVE_FRAGMENT_COUNT = 22

    // Высота начала полёта метеорита со стороны вспышки и точка взрыва
    private const val METEOR_SPAWN_HEIGHT = 750.0
    private const val METEOR_TARGET_HEIGHT = 415.0

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
            SyncSkyBreakPayload(isBroken = true, riftProgress = 0f)
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
            SyncSkyBreakPayload(isBroken = false, riftProgress = 0f)
        )
    }

    /**
     * Находит центральную точку самого людного места на сервере.
     */
    private fun findDensestPlayerClusterCenter(level: ServerLevel, searchRadius: Double = 80.0): Vec3 {
        val players = level.players()
        if (players.isEmpty()) return Vec3.ZERO

        var maxNearbyCount = -1
        var bestCenter = players.first().position()

        for (player in players) {
            val nearby = players.filter { it.distanceToSqr(player) <= searchRadius * searchRadius }

            if (nearby.size > maxNearbyCount) {
                maxNearbyCount = nearby.size

                var avgX = 0.0
                var avgZ = 0.0
                for (member in nearby) {
                    avgX += member.x
                    avgZ += member.z
                }
                avgX /= nearby.size
                avgZ /= nearby.size

                bestCenter = Vec3(avgX, METEOR_TARGET_HEIGHT, avgZ)
            }
        }

        return bestCenter
    }

    /**
     * Создает метеорит, летящий строго со стороны небесного разлома к скоплению игроков на высоту Y = 415.
     */
    fun spawnSkyfallMeteor(level: ServerLevel) {
        val targetPos = findDensestPlayerClusterCenter(level)
        if (targetPos == Vec3.ZERO) return

        // Смещение (-250, -300) строго соответствует направлению фиолетового разлома на небе
        val startPos = Vec3(
            targetPos.x - 250.0,
            METEOR_SPAWN_HEIGHT,
            targetPos.z - 300.0
        )

        val meteor = MeteorEntity(
            level = level,
            x = startPos.x,
            y = startPos.y,
            z = startPos.z,
            velocity = Vec3.ZERO,
            type = MeteorEntity.MeteorType.GIANT,
            power = 10
        )

        // 60 тиков (3 секунды) строгого полёта со стороны разлома
        meteor.configureSkyfallFlight(
            target = targetPos,
            durationTicks = 60
        )

        level.addFreshEntity(meteor)
    }

    fun spawnMeteorFragmentBurst(level: ServerLevel, player: ServerPlayer) {
        val totalFragments = PHYSICAL_FRAGMENT_COUNT + DECORATIVE_FRAGMENT_COUNT

        repeat(totalFragments) { index ->
            val fragmentX = player.x + Random.nextDouble(-25.0, 25.0)
            val fragmentZ = player.z + Random.nextDouble(-25.0, 25.0)

            val angle = Random.nextDouble(0.0, Math.PI * 2.0)
            val horizontalSpeed = Random.nextDouble(0.6, 2.5)

            val velocity = Vec3(
                cos(angle) * horizontalSpeed,
                -Random.nextDouble(1.5, 3.0),
                sin(angle) * horizontalSpeed
            )

            val isPhysical = index < PHYSICAL_FRAGMENT_COUNT

            val fragment = MeteorEntity(
                level = level,
                x = fragmentX,
                y = METEOR_TARGET_HEIGHT,
                z = fragmentZ,
                velocity = velocity,
                type = if (isPhysical) MeteorEntity.MeteorType.FRAGMENT else MeteorEntity.MeteorType.DECORATIVE_FRAGMENT,
                power = if (isPhysical) 3 else 0
            )

            level.addFreshEntity(fragment)
        }

        level.sendParticles(
            ParticleTypes.EXPLOSION_EMITTER,
            player.x, METEOR_TARGET_HEIGHT, player.z,
            15, 15.0, 5.0, 15.0, 0.1
        )

        level.playSound(
            null, player.blockPosition(),
            SoundEvents.GENERIC_EXPLODE.value(),
            SoundSource.WEATHER, 10.0f, 0.4f
        )
    }

    fun tick(level: ServerLevel) {
        val data = DomeWorldData.get(level)

        if (phase != SkyBreakPhase.INACTIVE && !meteorBurstSpawned && level.players().isNotEmpty()) {
            meteorBurstTimer++

            if (meteorBurstTimer >= METEOR_BURST_TICKS) {
                spawnSkyfallMeteor(level)
                meteorBurstSpawned = true
            }
        }

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
                    x = starX, y = starY, z = starZ,
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
                val diff = (TARGET_NIGHT_TIME - currentDayTime + 24000L) % 24000L
                val remainingTicks = (NIGHT_TRANSITION_TICKS - nightTimer + 1).coerceAtLeast(1)

                val step = diff / remainingTicks
                level.setDayTime(level.dayTime + step)

                val timePacket = ClientboundSetTimePacket(
                    level.gameTime, level.dayTime,
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
                    level.playSound(null, player.blockPosition(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.WEATHER, 5.0f, 0.6f)
                    level.playSound(null, player.blockPosition(), SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, 3.0f, 0.8f)
                    level.sendParticles(player, ParticleTypes.EXPLOSION_EMITTER, true, player.x, player.y + 40.0, player.z, 5, 2.0, 2.0, 2.0, 0.1)
                }

                phase = SkyBreakPhase.RIFT_GROWTH
                riftProgress = 0.01f

                PacketDistributor.sendToAllPlayers(
                    SyncSkyBreakPayload(isBroken = true, riftProgress = riftProgress)
                )
            }

            SkyBreakPhase.RIFT_GROWTH -> {
                if (riftProgress < 1.0f) {
                    riftProgress = (riftProgress + 0.0012f).coerceAtMost(1.0f)

                    PacketDistributor.sendToAllPlayers(
                        SyncSkyBreakPayload(isBroken = true, riftProgress = riftProgress)
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
        val isBroken = SkyBreakServerManager.phase != SkyBreakPhase.INACTIVE

        PacketDistributor.sendToPlayer(
            player,
            SyncSkyBreakPayload(isBroken, SkyBreakServerManager.riftProgress)
        )
    }

    @SubscribeEvent
    @JvmStatic
    fun onRegisterCommands(event: RegisterCommandsEvent) {
        DomeCommands.register(event.dispatcher)
    }
}