package com.grindlesstudio.shattereddome.entity

import com.grindlesstudio.shattereddome.init.ModEntities
import com.grindlesstudio.shattereddome.network.SyncScreenShakePayload
import com.grindlesstudio.shattereddome.sound.ModSounds
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MoverType
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.network.PacketDistributor
import team.lodestar.lodestone.registry.common.particle.LodestoneParticleTypes
import team.lodestar.lodestone.systems.particle.builder.WorldParticleBuilder
import team.lodestar.lodestone.systems.particle.data.GenericParticleData
import team.lodestar.lodestone.systems.particle.data.color.ColorParticleData
import java.awt.Color
import java.util.UUID
import kotlin.math.sqrt
import kotlin.random.Random

class MeteorEntity : Entity {

    enum class MeteorType {
        NORMAL,
        STAR,
        GIANT,
        FRAGMENT,
        DECORATIVE_FRAGMENT
    }

    private var explosionPower: Int = 4
    private val playersAlreadyWarned = mutableSetOf<UUID>()

    var meteorType: MeteorType
        get() = MeteorType.entries[entityData.get(TYPE_ACCESSOR).toInt().coerceIn(0, 4)]
        set(value) {
            entityData.set(TYPE_ACCESSOR, value.ordinal.toByte())
        }

    /**
     * Настраивает управляемый полёт от точки вспышки к скоплению игроков на высоте Y = 415.
     */
    fun configureSkyfallFlight(target: Vec3, durationTicks: Int = 60) {
        meteorType = MeteorType.GIANT
        entityData.set(IS_SKYFALL, true)
        entityData.set(TARGET_X, target.x.toFloat())
        entityData.set(TARGET_Y, target.y.toFloat())
        entityData.set(TARGET_Z, target.z.toFloat())
        entityData.set(FLIGHT_DURATION, durationTicks.coerceAtLeast(1))
        entityData.set(CURRENT_FLIGHT_TICKS, 0)

        setNoGravity(true)

        val pos = position()
        val direction = Vec3(
            target.x - pos.x,
            target.y - pos.y,
            target.z - pos.z
        )

        deltaMovement = direction.scale(1.0 / durationTicks)
    }

    private fun tickSkyfallFlight() {
        val currentTicks = entityData.get(CURRENT_FLIGHT_TICKS) + 1
        val maxDuration = entityData.get(FLIGHT_DURATION)
        entityData.set(CURRENT_FLIGHT_TICKS, currentTicks)

        val target = Vec3(
            entityData.get(TARGET_X).toDouble(),
            entityData.get(TARGET_Y).toDouble(),
            entityData.get(TARGET_Z).toDouble()
        )

        val remainingTicks = (maxDuration - currentTicks + 1).coerceAtLeast(1)
        val pos = position()
        val direction = Vec3(
            target.x - pos.x,
            target.y - pos.y,
            target.z - pos.z
        )

        deltaMovement = direction.scale(1.0 / remainingTicks)
        move(MoverType.SELF, deltaMovement)

        // Подрыв при достижении высоты Y <= 415 или истечении времени полёта
        if (currentTicks >= maxDuration || y <= 415.0) {
            setPos(target.x, target.y, target.z)
            deltaMovement = Vec3.ZERO
            entityData.set(IS_SKYFALL, false)

            if (!level().isClientSide) {
                explodeIntoFragments()
            }
        }
    }

    constructor(
        entityType: EntityType<out MeteorEntity>,
        level: Level
    ) : super(entityType, level)

    constructor(
        level: Level,
        x: Double,
        y: Double,
        z: Double,
        velocity: Vec3 = Vec3(0.2, -1.5, 0.2),
        type: MeteorType = MeteorType.NORMAL,
        power: Int = 4
    ) : this(ModEntities.METEOR.get(), level) {
        setPos(x, y, z)
        meteorType = type
        explosionPower = power
        deltaMovement = velocity
    }

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        builder.define(TYPE_ACCESSOR, MeteorType.NORMAL.ordinal.toByte())
        builder.define(IS_SKYFALL, false)
        builder.define(TARGET_X, 0f)
        builder.define(TARGET_Y, 0f)
        builder.define(TARGET_Z, 0f)
        builder.define(FLIGHT_DURATION, 60)
        builder.define(CURRENT_FLIGHT_TICKS, 0)
    }

    override fun tick() {
        super.tick()

        val isSkyfall = entityData.get(IS_SKYFALL)

        if (isSkyfall) {
            tickSkyfallFlight()
        } else {
            move(MoverType.SELF, deltaMovement)
        }

        when (meteorType) {
            MeteorType.STAR -> tickStar()
            MeteorType.NORMAL -> tickNormal()
            MeteorType.GIANT -> tickGiant()
            MeteorType.FRAGMENT -> tickFragment()
            MeteorType.DECORATIVE_FRAGMENT -> tickDecorativeFragment()
        }

        if (y < level().minBuildHeight) {
            discard()
        }
    }

    // ------------------------------------------------------------
    // ЗВЕЗДОПАД
    // ------------------------------------------------------------

    private fun tickStar() {
        if (level().isClientSide) {
            val clientLevel = level() as ClientLevel
            val pos = position()
            val velocity = deltaMovement

            val steps = 20
            val trailLength = 12.0

            for (i in 0..steps) {
                val factor = i.toDouble() / steps
                val px = pos.x - velocity.x * factor * trailLength
                val py = pos.y - velocity.y * factor * trailLength
                val pz = pos.z - velocity.z * factor * trailLength

                clientLevel.addParticle(ParticleTypes.END_ROD, true, px, py, pz, 0.0, 0.0, 0.0)

                if (i % 2 == 0) {
                    clientLevel.addParticle(ParticleTypes.INSTANT_EFFECT, true, px, py, pz, 0.0, 0.0, 0.0)
                }
            }

            repeat(4) {
                clientLevel.addParticle(
                    ParticleTypes.FLAME, true,
                    pos.x + (random.nextDouble() - 0.5) * 0.2,
                    pos.y + (random.nextDouble() - 0.5) * 0.2,
                    pos.z + (random.nextDouble() - 0.5) * 0.2,
                    0.0, 0.0, 0.0
                )
            }
        } else {
            val serverLevel = level() as ServerLevel
            WorldParticleBuilder.create(LodestoneParticleTypes.WISP_PARTICLE)
                .setColorData(ColorParticleData.create(Color(180, 220, 255), Color(120, 70, 255)).build())
                .setScaleData(GenericParticleData.create(2.5f, 0f).build())
                .setTransparencyData(GenericParticleData.create(0.9f, 0f).build())
                .setLifetime(12)
                .spawn(serverLevel, x, y, z)

            if (tickCount >= 60) {
                discard()
            }
        }
    }

    // ------------------------------------------------------------
    // ОБЫЧНЫЙ МЕТЕОРИТ
    // ------------------------------------------------------------

    private fun tickNormal() {
        if (level().isClientSide) {
            spawnTrailParticles()
        } else {
            if (tickCount % 4 == 0) {
                level().playSound(null, blockPosition(), SoundEvents.ELYTRA_FLYING, SoundSource.HOSTILE, 2.0f, 0.6f)
            }

            spawnServerTrailParticles(Color(220, 80, 255), Color(80, 0, 160), 2.5f)

            if (onGround() || horizontalCollision || verticalCollision) {
                onImpact()
            }
        }
    }

    // ------------------------------------------------------------
    // ОСКОЛКИ
    // ------------------------------------------------------------

    private fun tickFragment() {
        if (level().isClientSide) {
            spawnTrailParticles()
            return
        }

        val serverLevel = level() as ServerLevel

        for (player in serverLevel.players()) {
            val distanceSquared = player.distanceToSqr(this)
            val effectRadius = 75.0

            if (distanceSquared >= effectRadius * effectRadius) continue
            if (!playersAlreadyWarned.add(player.uuid)) continue

            val distance = sqrt(distanceSquared)
            val intensity = (2.4f * (1.0 - (distance / effectRadius)).toFloat()).coerceIn(0.25f, 2.4f)

            player.playNotifySound(SoundEvents.ELYTRA_FLYING, SoundSource.AMBIENT, 2.0f, 0.65f + Random.nextFloat() * 0.45f)

            PacketDistributor.sendToPlayer(
                player,
                SyncScreenShakePayload(intensity = intensity, duration = 8 + (intensity * 5).toInt())
            )
        }

        spawnServerTrailParticles(Color(255, 120, 30), Color(180, 20, 10), 2.0f)

        if (tickCount > 400) {
            discard()
            return
        }

        if (onGround() || horizontalCollision || verticalCollision) {
            onImpact()
        }
    }

    private fun tickDecorativeFragment() {
        if (level().isClientSide) {
            spawnTrailParticles()
            return
        }

        if (tickCount >= 360 || onGround() || horizontalCollision || verticalCollision) {
            discard()
            return
        }

        spawnServerTrailParticles(Color(255, 150, 45), Color(190, 35, 15), 2.0f)
    }

    // ------------------------------------------------------------
    // ГИГАНТСКИЙ МЕТЕОРИТ
    // ------------------------------------------------------------

    private fun tickGiant() {
        if (level().isClientSide) {
            spawnGiantTrailParticles()
        } else {
            val serverLevel = level() as ServerLevel

            if (tickCount % 5 == 0) {
                serverLevel.playSound(null, blockPosition(), SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.HOSTILE, 10.0f, 0.25f)
            }

            spawnServerTrailParticles(Color(255, 110, 10), Color(180, 0, 220), 12.0f)

            val isSkyfall = entityData.get(IS_SKYFALL)

            if (!isSkyfall && (y <= 415.0 || onGround() || horizontalCollision || verticalCollision)) {
                explodeIntoFragments()
                return
            }

            for (player in serverLevel.players()) {
                val distanceSquared = player.distanceToSqr(this)
                if (distanceSquared < 300.0 * 300.0) {
                    PacketDistributor.sendToPlayer(
                        player,
                        SyncScreenShakePayload(intensity = 2.0f, duration = 15)
                    )
                }
            }
        }
    }

    private fun explodeIntoFragments() {
        if (level().isClientSide) return

        val serverLevel = level() as ServerLevel
        val pos = position()

        serverLevel.explode(
            this,
            pos.x, pos.y, pos.z,
            8.0f,
            Level.ExplosionInteraction.NONE
        )

        serverLevel.playSound(
            null,
            blockPosition(),
            SoundEvents.GENERIC_EXPLODE.value(),
            SoundSource.HOSTILE,
            15.0f,
            0.3f
        )

        serverLevel.playSound(
            null,
            blockPosition(),
            SoundEvents.LIGHTNING_BOLT_THUNDER,
            SoundSource.WEATHER,
            12.0f,
            0.4f
        )

        val fragmentCount = 20
        for (i in 0 until fragmentCount) {
            val vx = (random.nextDouble() - 0.5) * 2.2
            val vy = -1.2 - random.nextDouble() * 0.9
            val vz = (random.nextDouble() - 0.5) * 2.2

            val isDecorative = random.nextBoolean()
            val fragmentType = if (isDecorative) MeteorType.DECORATIVE_FRAGMENT else MeteorType.FRAGMENT

            val fragment = MeteorEntity(
                level = serverLevel,
                x = pos.x + (random.nextDouble() - 0.5) * 6.0,
                y = pos.y + (random.nextDouble() - 0.5) * 2.0,
                z = pos.z + (random.nextDouble() - 0.5) * 6.0,
                velocity = Vec3(vx, vy, vz),
                type = fragmentType,
                power = 3
            )
            serverLevel.addFreshEntity(fragment)
        }

        for (player in serverLevel.players()) {
            PacketDistributor.sendToPlayer(
                player,
                SyncScreenShakePayload(intensity = 6.0f, duration = 35)
            )
        }

        discard()
    }

    // ------------------------------------------------------------
    // ЧАСТИЦЫ И ШЛЕЙФ
    // ------------------------------------------------------------

    private fun spawnTrailParticles() {
        val pos = position()

        repeat(5) {
            val ox = (random.nextDouble() - 0.5) * 1.5
            val oy = (random.nextDouble() - 0.5) * 1.5
            val oz = (random.nextDouble() - 0.5) * 1.5

            level().addParticle(ParticleTypes.FLAME, pos.x + ox, pos.y + oy, pos.z + oz, 0.0, 0.05, 0.0)
            level().addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, pos.x + ox, pos.y + oy, pos.z + oz, 0.0, 0.1, 0.0)
        }
    }

    private fun spawnGiantTrailParticles() {
        val pos = position()
        val velocity = deltaMovement
        val speed = velocity.length()

        val dir = if (speed > 0.001) velocity.normalize() else Vec3(0.0, -1.0, 0.0)

        val trailSteps = 50
        for (i in 0..trailSteps) {
            val backOffset = i * 1.5
            val px = pos.x - dir.x * backOffset + (random.nextDouble() - 0.5) * 4.0
            val py = pos.y - dir.y * backOffset + (random.nextDouble() - 0.5) * 4.0
            val pz = pos.z - dir.z * backOffset + (random.nextDouble() - 0.5) * 4.0

            level().addParticle(ParticleTypes.FLAME, true, px, py, pz, 0.0, 0.0, 0.0)

            if (i % 2 == 0) {
                level().addParticle(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, true, px, py, pz, 0.0, 0.05, 0.0)
                level().addParticle(ParticleTypes.LAVA, true, px, py, pz, 0.0, 0.0, 0.0)
            }
        }
    }

    private fun spawnServerTrailParticles(
        startColor: Color,
        endColor: Color,
        scale: Float
    ) {
        val serverLevel = level() as? ServerLevel ?: return

        WorldParticleBuilder.create(LodestoneParticleTypes.WISP_PARTICLE)
            .setColorData(ColorParticleData.create(startColor, endColor).build())
            .setScaleData(GenericParticleData.create(scale, 0f).build())
            .setTransparencyData(GenericParticleData.create(0.85f, 0f).build())
            .setLifetime(25)
            .spawn(serverLevel, x, y, z)
    }

    private fun onImpact() {
        if (level().isClientSide) return

        val serverLevel = level() as ServerLevel

        serverLevel.explode(
            this,
            x, y, z,
            explosionPower.toFloat(),
            Level.ExplosionInteraction.BLOCK
        )

        var centerGround = blockPosition()
        while (serverLevel.isEmptyBlock(centerGround) && centerGround.y > serverLevel.minBuildHeight) {
            centerGround = centerGround.below()
        }

        val radius = 2
        for (xo in -radius..radius) {
            for (yo in -radius..radius) {
                for (zo in -radius..radius) {
                    if (xo * xo + yo * yo + zo * zo <= radius * radius) {
                        serverLevel.setBlockAndUpdate(
                            centerGround.offset(xo, yo + 1, zo),
                            Blocks.MAGMA_BLOCK.defaultBlockState()
                        )
                    }
                }
            }
        }

        serverLevel.playSound(null, centerGround, ModSounds.METEOR_IMPACT.get(), SoundSource.BLOCKS, 5.0f, 0.9f)
        serverLevel.playSound(null, centerGround, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, 4.0f, 0.7f)

        for (player in serverLevel.players()) {
            val distanceSquared = player.distanceToSqr(this)
            if (distanceSquared < 120.0 * 120.0) {
                val factor = (1.0 - (sqrt(distanceSquared) / 120.0)).toFloat().coerceIn(0.1f, 1.0f)
                PacketDistributor.sendToPlayer(
                    player,
                    SyncScreenShakePayload(intensity = 4.5f * factor, duration = 25)
                )
            }
        }

        discard()
    }

    override fun getBoundingBoxForCulling(): AABB {
        return if (meteorType == MeteorType.GIANT) {
            super.getBoundingBoxForCulling().inflate(500.0)
        } else {
            super.getBoundingBoxForCulling().inflate(2.0)
        }
    }

    override fun shouldRenderAtSqrDistance(distance: Double): Boolean {
        return true
    }

    override fun readAdditionalSaveData(compound: CompoundTag) {
        explosionPower = compound.getInt("ExplosionPower")
        meteorType = MeteorType.entries[compound.getInt("MeteorType").coerceIn(0, 4)]
    }

    override fun addAdditionalSaveData(compound: CompoundTag) {
        compound.putInt("ExplosionPower", explosionPower)
        compound.putInt("MeteorType", meteorType.ordinal)
    }

    companion object {
        private val TYPE_ACCESSOR: EntityDataAccessor<Byte> =
            SynchedEntityData.defineId(MeteorEntity::class.java, EntityDataSerializers.BYTE)

        private val IS_SKYFALL: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(MeteorEntity::class.java, EntityDataSerializers.BOOLEAN)

        private val TARGET_X: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(MeteorEntity::class.java, EntityDataSerializers.FLOAT)

        private val TARGET_Y: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(MeteorEntity::class.java, EntityDataSerializers.FLOAT)

        private val TARGET_Z: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(MeteorEntity::class.java, EntityDataSerializers.FLOAT)

        private val FLIGHT_DURATION: EntityDataAccessor<Int> =
            SynchedEntityData.defineId(MeteorEntity::class.java, EntityDataSerializers.INT)

        private val CURRENT_FLIGHT_TICKS: EntityDataAccessor<Int> =
            SynchedEntityData.defineId(MeteorEntity::class.java, EntityDataSerializers.INT)
    }
}