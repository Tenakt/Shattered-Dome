package com.grindlesstudio.shattereddome.entity

import com.grindlesstudio.shattereddome.init.ModEntities
import com.grindlesstudio.shattereddome.world.MeteorChunkTickets
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
import kotlin.math.cos
import kotlin.math.sin
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
    private val skyfallForcedChunks = mutableSetOf<Pair<Int, Int>>()

    var meteorType: MeteorType
        get() = MeteorType.entries[entityData.get(TYPE_ACCESSOR).toInt().coerceIn(0, 4)]
        set(value) {
            entityData.set(TYPE_ACCESSOR, value.ordinal.toByte())
        }

    /** Настраивает полёт большого метеорита к точке над группой игроков. */
    fun configureSkyfallFlight(target: Vec3, durationTicks: Int = 60) {
        // Если полёт переинициализировали, сначала освобождаем старые билеты.
        releaseSkyfallChunkTickets()

        meteorType = MeteorType.GIANT
        entityData.set(IS_SKYFALL, true)
        entityData.set(TARGET_X, target.x.toFloat())
        entityData.set(TARGET_Y, target.y.toFloat())
        entityData.set(TARGET_Z, target.z.toFloat())
        entityData.set(FLIGHT_DURATION, durationTicks.coerceAtLeast(1))
        entityData.set(CURRENT_FLIGHT_TICKS, 0)

        setNoGravity(true)

        // Принудительно загружаем и тикаем чанки всего маршрута. Это действует
        // только для сюжетного метеорита, обычные метеориты не затрагиваются.
        val serverLevel = level() as? ServerLevel
        if (serverLevel != null) {
            skyfallForcedChunks.addAll(
                MeteorChunkTickets.forceFlightPath(
                    level = serverLevel,
                    owner = uuid,
                    start = position(),
                    target = target
                )
            )
        }

        val duration = durationTicks.coerceAtLeast(1).toDouble()
        deltaMovement = Vec3(
            (target.x - x) / duration,
            (target.y - y) / duration,
            (target.z - z) / duration
        )
    }

    private fun releaseSkyfallChunkTickets() {
        if (skyfallForcedChunks.isEmpty()) return

        val serverLevel = level() as? ServerLevel ?: return
        MeteorChunkTickets.releaseFlightPath(
            level = serverLevel,
            owner = uuid,
            chunks = skyfallForcedChunks
        )
        skyfallForcedChunks.clear()
    }

    override fun remove(reason: Entity.RemovalReason) {
        // Не оставляем принудительно загруженные чанки после удаления сущности.
        releaseSkyfallChunkTickets()
        super.remove(reason)
    }

    private fun tickSkyfallFlight() {
        val currentTicks = entityData.get(CURRENT_FLIGHT_TICKS) + 1
        val maxDuration = entityData.get(FLIGHT_DURATION).coerceAtLeast(1)
        entityData.set(CURRENT_FLIGHT_TICKS, currentTicks)

        val target = Vec3(
            entityData.get(TARGET_X).toDouble(),
            entityData.get(TARGET_Y).toDouble(),
            entityData.get(TARGET_Z).toDouble()
        )

        if (currentTicks >= maxDuration) {
            setPos(target.x, target.y, target.z)
            deltaMovement = Vec3.ZERO

            // Только сервер взрывает сущность. Клиент ждёт серверную синхронизацию,
            // поэтому полёт не вызывает локальный повторный взрыв или дубли осколков.
            if (!level().isClientSide) {
                entityData.set(IS_SKYFALL, false)
                explodeIntoFragments()
            }
            return
        }

        val remainingTicks = (maxDuration - currentTicks + 1).coerceAtLeast(1)
        val pos = position()
        deltaMovement = Vec3(
            target.x - pos.x,
            target.y - pos.y,
            target.z - pos.z
        ).scale(1.0 / remainingTicks)

        move(MoverType.SELF, deltaMovement)
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
            if (isRemoved) return
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

            if (tickCount >= 60) discard()
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
            if (onGround() || horizontalCollision || verticalCollision) onImpact()
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
            val intensity = (2.4f * (1.0 - distance / effectRadius).toFloat()).coerceIn(0.25f, 2.4f)
            player.playNotifySound(
                SoundEvents.ELYTRA_FLYING,
                SoundSource.AMBIENT,
                2.0f,
                0.65f + Random.nextFloat() * 0.45f
            )
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
        if (onGround() || horizontalCollision || verticalCollision) onImpact()
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
            return
        }

        val serverLevel = level() as ServerLevel
        if (tickCount % 5 == 0) {
            serverLevel.playSound(
                null,
                blockPosition(),
                SoundEvents.FIREWORK_ROCKET_BLAST,
                SoundSource.HOSTILE,
                10.0f,
                0.25f
            )
        }
        spawnServerTrailParticles(Color(255, 110, 10), Color(180, 0, 220), 12.0f)

        // Пока метеорит летит по заданной траектории, тряску на сервере не спамим:
        // сила тряски плавно растёт на клиенте ещё во время вспышки.
        if (entityData.get(IS_SKYFALL)) return

        if (y <= 415.0 || onGround() || horizontalCollision || verticalCollision) {
            explodeIntoFragments()
            return
        }

        for (player in serverLevel.players()) {
            if (player.distanceToSqr(this) < 300.0 * 300.0) {
                PacketDistributor.sendToPlayer(
                    player,
                    SyncScreenShakePayload(intensity = 2.0f, duration = 15)
                )
            }
        }
    }

    private fun explodeIntoFragments() {
        if (level().isClientSide || isRemoved) return

        val serverLevel = level() as ServerLevel
        val pos = position()

        serverLevel.explode(
            this,
            pos.x,
            pos.y,
            pos.z,
            8.0f,
            Level.ExplosionInteraction.NONE
        )

        serverLevel.sendParticles(
            ParticleTypes.EXPLOSION_EMITTER,
            pos.x, pos.y, pos.z,
            1,
            0.0, 0.0, 0.0,
            0.0
        )
        serverLevel.sendParticles(
            ParticleTypes.FLAME,
            pos.x, pos.y, pos.z,
            100,
            12.0, 8.0, 12.0,
            0.12
        )
        serverLevel.sendParticles(
            ParticleTypes.LAVA,
            pos.x, pos.y, pos.z,
            35,
            10.0, 6.0, 10.0,
            0.05
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

        // Восемь физических и двадцать два декоративных осколка.
        val physicalFragmentCount = 8
        val totalFragmentCount = 30
        repeat(totalFragmentCount) { index ->
            val angle = Random.nextDouble(0.0, Math.PI * 2.0)
            val horizontalSpeed = Random.nextDouble(0.6, 2.5)
            val velocity = Vec3(
                cos(angle) * horizontalSpeed,
                Random.nextDouble(-1.0, 1.4),
                sin(angle) * horizontalSpeed
            )
            val isPhysical = index < physicalFragmentCount
            val fragment = MeteorEntity(
                level = serverLevel,
                x = pos.x + Random.nextDouble(-12.0, 12.0),
                y = pos.y + Random.nextDouble(-3.0, 3.0),
                z = pos.z + Random.nextDouble(-12.0, 12.0),
                velocity = velocity,
                type = if (isPhysical) MeteorType.FRAGMENT else MeteorType.DECORATIVE_FRAGMENT,
                power = if (isPhysical) 3 else 0
            )
            serverLevel.addFreshEntity(fragment)
        }

        // Сильная финальная тряска только один раз, от настоящего места взрыва.
        val shakeRadius = 250.0
        for (player in serverLevel.players()) {
            val distanceSquared = player.distanceToSqr(pos.x, pos.y, pos.z)
            if (distanceSquared > shakeRadius * shakeRadius) continue
            val distance = sqrt(distanceSquared)
            val intensity = (8.0 * (1.0 - distance / shakeRadius))
                .toFloat()
                .coerceAtLeast(0.5f)
            PacketDistributor.sendToPlayer(
                player,
                SyncScreenShakePayload(intensity = intensity, duration = 45)
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

    private fun spawnServerTrailParticles(startColor: Color, endColor: Color, scale: Float) {
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

    override fun shouldRenderAtSqrDistance(distance: Double): Boolean = true

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
