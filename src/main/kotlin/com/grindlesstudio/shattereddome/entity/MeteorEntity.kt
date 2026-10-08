package com.grindlesstudio.shattereddome.entity

import com.grindlesstudio.shattereddome.init.ModEntities
import com.grindlesstudio.shattereddome.sound.ModSounds
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MoverType
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3

class MeteorEntity : Entity {
    private var explosionPower: Int = 4

    constructor(entityType: EntityType<out MeteorEntity>, level: Level) : super(entityType, level)

    constructor(level: Level, x: Double, y: Double, z: Double, explosionPower: Int = 5) : this(
        ModEntities.METEOR.get() as EntityType<out MeteorEntity>,
        level
    ) {
        setPos(x, y, z)
        this.explosionPower = explosionPower
        deltaMovement = Vec3(
            (random.nextDouble() - 0.5) * 0.4,
            -1.2,
            (random.nextDouble() - 0.5) * 0.4
        )
    }

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {}

    override fun tick() {
        super.tick()

        if (!isNoGravity) {
            deltaMovement = deltaMovement.add(0.0, -0.03, 0.0)
        }

        move(MoverType.SELF, deltaMovement)

        if (level().isClientSide) {
            spawnTrailParticles()
        } else if (onGround() || horizontalCollision || verticalCollision) {
            onImpact()
        }

        if (y < level().minBuildHeight) {
            discard()
        }
    }

    private fun spawnTrailParticles() {
        val pos = position()
        for (i in 0 until 6) {
            val offsetX = (random.nextDouble() - 0.5) * 1.5
            val offsetY = (random.nextDouble() - 0.5) * 1.5
            val offsetZ = (random.nextDouble() - 0.5) * 1.5

            level().addParticle(ParticleTypes.FLAME, pos.x + offsetX, pos.y + offsetY, pos.z + offsetZ, 0.0, 0.1, 0.0)
            level().addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, pos.x + offsetX, pos.y + offsetY, pos.z + offsetZ, 0.0, 0.15, 0.0)
            level().addParticle(ParticleTypes.LAVA, pos.x + offsetX, pos.y + offsetY, pos.z + offsetZ, 0.0, 0.0, 0.0)
        }
    }

    private fun onImpact() {
        if (!level().isClientSide) {
            // 1. Создаём взрыв
            level().explode(this, x, y, z, explosionPower.toFloat(), Level.ExplosionInteraction.BLOCK)

            // 2. Ищем реальное дно кратера в центре падения
            var centerGround = blockPosition()
            while (level().isEmptyBlock(centerGround) && centerGround.y > level().minBuildHeight) {
                centerGround = centerGround.below()
            }

            // 3. Формируем магмовую сферу с центром на дне кратера (Радиус = 2)
            val radius = 2
            for (xOffset in -radius..radius) {
                for (yOffset in -radius..radius) {
                    for (zOffset in -radius..radius) {
                        if (xOffset * xOffset + yOffset * yOffset + zOffset * zOffset <= radius * radius) {
                            // Приподнимаем центр сферы на 1 блок над найденным дном
                            val targetPos = centerGround.offset(xOffset, yOffset + 1, zOffset)
                            level().setBlockAndUpdate(targetPos, Blocks.MAGMA_BLOCK.defaultBlockState())
                        }
                    }
                }
            }

            // 4. Звук удара
            level().playSound(
                null, centerGround,
                ModSounds.METEOR_IMPACT.get(),
                SoundSource.BLOCKS,
                5.0f, 0.8f + random.nextFloat() * 0.4f
            )

            discard()
        }
    }

    override fun readAdditionalSaveData(compound: CompoundTag) {
        explosionPower = compound.getInt("ExplosionPower")
    }

    override fun addAdditionalSaveData(compound: CompoundTag) {
        compound.putInt("ExplosionPower", explosionPower)
    }
}