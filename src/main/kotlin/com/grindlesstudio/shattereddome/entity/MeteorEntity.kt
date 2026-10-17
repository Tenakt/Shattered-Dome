package com.grindlesstudio.shattereddome.entity

import com.grindlesstudio.shattereddome.init.ModEntities
import com.grindlesstudio.shattereddome.network.SyncScreenShakePayload
import com.grindlesstudio.shattereddome.sound.ModSounds
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
import kotlin.random.Random

class MeteorEntity : Entity {

    enum class MeteorType {
        NORMAL,  // Обычный метеорит
        STAR,    // Полоса света высоко в небе (без модели)
        GIANT    // Огромный гигантский метеорит
    }

    private var explosionPower: Int = 4

    var meteorType: MeteorType
        get() = MeteorType.entries[entityData.get(TYPE_ACCESOR).toInt().coerceIn(0, 2)]
        set(value) = entityData.set(TYPE_ACCESOR, value.ordinal.toByte())

    constructor(entityType: EntityType<out MeteorEntity>, level: Level) : super(entityType, level)

    constructor(
        level: Level,
        x: Double,
        y: Double,
        z: Double,
        velocity: Vec3 = Vec3(0.2, -1.5, 0.2),
        type: MeteorType = MeteorType.NORMAL,
        power: Int = 4
    ) : this(ModEntities.METEOR.get() as EntityType<out MeteorEntity>, level) {
        setPos(x, y, z)
        this.meteorType = type
        this.explosionPower = power
        this.deltaMovement = velocity
    }

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        builder.define(TYPE_ACCESOR, MeteorType.NORMAL.ordinal.toByte())
    }

    override fun tick() {
        super.tick()
        move(MoverType.SELF, deltaMovement)

        when (meteorType) {
            MeteorType.STAR -> tickStar()
            MeteorType.NORMAL -> tickNormal()
            MeteorType.GIANT -> tickGiant()
        }

        if (y < level().minBuildHeight) {
            discard()
        }
    }

    // --- Светящаяся полоса света на высоте 350-400+ (Звездопад) ---
    private fun tickStar() {
        if (level().isClientSide) {
            val pos = position()
            val vel = deltaMovement

            // Рисуем сплошную яркую линию света вдоль движения
            val steps = 14
            for (i in 0..steps) {
                val factor = i.toDouble() / steps
                val px = pos.x - vel.x * factor * 2.5
                val py = pos.y - vel.y * factor * 2.5
                val pz = pos.z - vel.z * factor * 2.5

                level().addParticle(ParticleTypes.END_ROD, px, py, pz, 0.0, 0.0, 0.0)
                level().addParticle(ParticleTypes.INSTANT_EFFECT, px, py, pz, 0.0, 0.0, 0.0)
            }
        } else {
            val serverLevel = level() as ServerLevel

            // Яркий плазменный шлейф света
            WorldParticleBuilder.create(LodestoneParticleTypes.WISP_PARTICLE)
                .setColorData(ColorParticleData.create(Color(255, 255, 220), Color(255, 170, 40)).build())
                .setScaleData(GenericParticleData.create(5.0f, 0f).build())
                .setTransparencyData(GenericParticleData.create(0.95f, 0f).build())
                .setLifetime(8)
                .spawn(serverLevel, x, y, z)

            // Быстро сгорает за 12 тиков (~0.6 сек)
            if (tickCount >= 12) {
                discard()
            }
        }
    }

    // --- Обычный Метеорит ---
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

    // --- Медленный Огромный Метеорит ---
    private fun tickGiant() {
        if (level().isClientSide) {
            spawnGiantTrailParticles()
        } else {
            val serverLevel = level() as ServerLevel

            // Глубокий раскатистый гул
            if (tickCount % 10 == 0) {
                serverLevel.playSound(null, blockPosition(), SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.HOSTILE, 8.0f, 0.25f)
            }

            // Огромный плазменный хвост
            spawnServerTrailParticles(Color(255, 110, 10), Color(180, 0, 220), 12.0f)

            // РЕДКИЙ отлёт осколков (раз в 70 тиков ≈ 3.5 секунды)
            if (tickCount % 70 == 0) {
                val sideX = Random.nextDouble(-1.2, 1.2)
                val sideZ = Random.nextDouble(-1.2, 1.2)

                val fragmentVel = Vec3(
                    deltaMovement.x + sideX,
                    -1.0 - Random.nextDouble(0.5),
                    deltaMovement.z + sideZ
                )

                val fragment = MeteorEntity(
                    level = serverLevel,
                    x = x + sideX * 4.0,
                    y = y - 3.0,
                    z = z + sideZ * 4.0,
                    velocity = fragmentVel,
                    type = MeteorType.NORMAL,
                    power = 3
                )
                serverLevel.addFreshEntity(fragment)
            }

            // Тряска земли при пролёте
            for (player in serverLevel.players()) {
                val distSq = player.distanceToSqr(this)
                if (distSq < 300.0 * 300.0) {
                    PacketDistributor.sendToPlayer(player, SyncScreenShakePayload(intensity = 2.0f, duration = 15))
                }
            }

            if (onGround() || horizontalCollision || verticalCollision) {
                onGiantImpact()
            }
        }
    }

    private fun spawnTrailParticles() {
        val pos = position()
        for (i in 0 until 5) {
            val ox = (random.nextDouble() - 0.5) * 1.5
            val oy = (random.nextDouble() - 0.5) * 1.5
            val oz = (random.nextDouble() - 0.5) * 1.5
            level().addParticle(ParticleTypes.FLAME, pos.x + ox, pos.y + oy, pos.z + oz, 0.0, 0.05, 0.0)
            level().addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, pos.x + ox, pos.y + oy, pos.z + oz, 0.0, 0.1, 0.0)
        }
    }

    private fun spawnGiantTrailParticles() {
        val pos = position()
        for (i in 0 until 25) {
            val ox = (random.nextDouble() - 0.5) * 8.0
            val oy = (random.nextDouble() - 0.5) * 8.0
            val oz = (random.nextDouble() - 0.5) * 8.0
            level().addParticle(ParticleTypes.EXPLOSION, pos.x + ox, pos.y + oy, pos.z + oz, 0.0, 0.0, 0.0)
            level().addParticle(ParticleTypes.LAVA, pos.x + ox, pos.y + oy, pos.z + oz, 0.0, 0.0, 0.0)
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
        if (!level().isClientSide) {
            val serverLevel = level() as ServerLevel
            serverLevel.explode(this, x, y, z, explosionPower.toFloat(), Level.ExplosionInteraction.BLOCK)

            var centerGround = blockPosition()
            while (serverLevel.isEmptyBlock(centerGround) && centerGround.y > serverLevel.minBuildHeight) {
                centerGround = centerGround.below()
            }

            val radius = 2
            for (xo in -radius..radius) {
                for (yo in -radius..radius) {
                    for (zo in -radius..radius) {
                        if (xo * xo + yo * yo + zo * zo <= radius * radius) {
                            serverLevel.setBlockAndUpdate(centerGround.offset(xo, yo + 1, zo), Blocks.MAGMA_BLOCK.defaultBlockState())
                        }
                    }
                }
            }

            serverLevel.playSound(null, centerGround, ModSounds.METEOR_IMPACT.get(), SoundSource.BLOCKS, 5.0f, 0.9f)
            serverLevel.playSound(null, centerGround, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, 4.0f, 0.7f)

            for (player in serverLevel.players()) {
                val distSq = player.distanceToSqr(this)
                if (distSq < 120.0 * 120.0) {
                    val factor = (1.0 - (Math.sqrt(distSq) / 120.0)).toFloat().coerceIn(0.1f, 1.0f)
                    PacketDistributor.sendToPlayer(player, SyncScreenShakePayload(intensity = 4.5f * factor, duration = 25))
                }
            }
            discard()
        }
    }

    override fun getBoundingBoxForCulling(): AABB {
        return if (meteorType == MeteorType.GIANT) {
            super.getBoundingBoxForCulling().inflate(100.0)
        } else {
            super.getBoundingBoxForCulling()
        }
    }
    
    override fun shouldRenderAtSqrDistance(distance: Double): Boolean {
        return true
    }

    private fun onGiantImpact() {
        if (!level().isClientSide) {
            val serverLevel = level() as ServerLevel
            serverLevel.explode(this, x, y, z, (explosionPower * 3.0f), Level.ExplosionInteraction.BLOCK)

            var centerGround = blockPosition()
            while (serverLevel.isEmptyBlock(centerGround) && centerGround.y > serverLevel.minBuildHeight) {
                centerGround = centerGround.below()
            }

            val radius = 7
            for (xo in -radius..radius) {
                for (yo in -radius..radius) {
                    for (zo in -radius..radius) {
                        if (xo * xo + yo * yo + zo * zo <= radius * radius) {
                            serverLevel.setBlockAndUpdate(centerGround.offset(xo, yo, zo), Blocks.MAGMA_BLOCK.defaultBlockState())
                        }
                    }
                }
            }

            serverLevel.playSound(null, centerGround, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 12.0f, 0.3f)
            serverLevel.playSound(null, centerGround, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, 10.0f, 0.3f)

            for (player in serverLevel.players()) {
                PacketDistributor.sendToPlayer(player, SyncScreenShakePayload(intensity = 12.0f, duration = 70))
            }
            discard()
        }
    }

    override fun readAdditionalSaveData(compound: CompoundTag) {
        explosionPower = compound.getInt("ExplosionPower")
        meteorType = MeteorType.entries[compound.getInt("MeteorType").coerceIn(0, 2)]
    }

    override fun addAdditionalSaveData(compound: CompoundTag) {
        compound.putInt("ExplosionPower", explosionPower)
        compound.putInt("MeteorType", meteorType.ordinal)
    }

    companion object {
        private val TYPE_ACCESOR: EntityDataAccessor<Byte> =
            SynchedEntityData.defineId(MeteorEntity::class.java, EntityDataSerializers.BYTE)
    }
}