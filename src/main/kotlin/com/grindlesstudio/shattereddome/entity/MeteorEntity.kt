package com.grindlesstudio.shattereddome.entity

import com.grindlesstudio.shattereddome.SkyBreakServerManager
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

    var meteorType: MeteorType
        get() = MeteorType.entries[entityData.get(TYPE_ACCESSOR).toInt().coerceIn(0, 4)]
        set(value) {
            entityData.set(TYPE_ACCESSOR, value.ordinal.toByte())
        }

    private val playersAlreadyWarned = mutableSetOf<UUID>()

    // Управляемый полёт сюжетного гигантского метеорита.
    private var skyfallFlightActive: Boolean = false
    private var skyfallFlightTicks: Int = 0
    private var skyfallFlightDuration: Int = 55
    private var skyfallTarget: Vec3 = Vec3.ZERO
    private var skyfallImpactPending: Boolean = false

    /**
     * Запускает управляемый полёт к заданной точке.
     * Во время такого полёта столкновения с блоками не прерывают анимацию:
     * метеорит доходит до цели и только затем взрывается.
     */
    fun configureSkyfallFlight(target: Vec3, durationTicks: Int = 55) {
        meteorType = MeteorType.GIANT
        skyfallTarget = target
        skyfallFlightDuration = durationTicks.coerceAtLeast(1)
        skyfallFlightTicks = 0
        skyfallFlightActive = true
        skyfallImpactPending = false
        val pos = position()
        val direction = Vec3(
            target.x - pos.x,
            target.y - pos.y,
            target.z - pos.z
        )

        deltaMovement = direction.scale(1.0 / skyfallFlightDuration)
        setNoGravity(true)
    }

    private fun tickSkyfallFlight() {
        skyfallFlightTicks++

        val ticksRemaining = (skyfallFlightDuration - skyfallFlightTicks + 1).coerceAtLeast(1)
        val pos = position()
        val direction = Vec3(
            skyfallTarget.x - pos.x,
            skyfallTarget.y - pos.y,
            skyfallTarget.z - pos.z
        )

        deltaMovement = direction.scale(1.0 / ticksRemaining)
        move(MoverType.SELF, deltaMovement)

        if (skyfallFlightTicks >= skyfallFlightDuration) {
            setPos(skyfallTarget.x, skyfallTarget.y, skyfallTarget.z)
            deltaMovement = Vec3.ZERO
            skyfallFlightActive = false
            skyfallImpactPending = true
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
    }

    override fun tick() {
        super.tick()

        if (meteorType == MeteorType.GIANT && skyfallFlightActive) {
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

        if (skyfallImpactPending && !level().isClientSide && !isRemoved) {
            skyfallImpactPending = false
            onGiantImpact()
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

            // Увеличенный след позади звезды.
            val steps = 20
            val trailLength = 12.0

            for (i in 0..steps) {
                val factor = i.toDouble() / steps

                val px = pos.x - velocity.x * factor * trailLength
                val py = pos.y - velocity.y * factor * trailLength
                val pz = pos.z - velocity.z * factor * trailLength

                // Принудительное создание частиц помогает избежать
                // их пропуска из-за ограничения количества частиц.
                clientLevel.addParticle(
                    ParticleTypes.END_ROD,
                    true,
                    px, py, pz,
                    0.0, 0.0, 0.0
                )

                // Дополнительное свечение вдоль следа.
                if (i % 2 == 0) {
                    clientLevel.addParticle(
                        ParticleTypes.INSTANT_EFFECT,
                        true,
                        px, py, pz,
                        0.0, 0.0, 0.0
                    )
                }
            }

            // Яркая голова звезды.
            repeat(4) {
                clientLevel.addParticle(
                    ParticleTypes.FLAME,
                    true,
                    pos.x + (random.nextDouble() - 0.5) * 0.2,
                    pos.y + (random.nextDouble() - 0.5) * 0.2,
                    pos.z + (random.nextDouble() - 0.5) * 0.2,
                    0.0, 0.0, 0.0
                )
            }
        } else {
            val serverLevel = level() as ServerLevel

            WorldParticleBuilder.create(LodestoneParticleTypes.WISP_PARTICLE)
                .setColorData(
                    ColorParticleData.create(
                        Color(180, 220, 255),
                        Color(120, 70, 255)
                    ).build()
                )
                .setScaleData(
                    GenericParticleData.create(2.5f, 0f).build()
                )
                .setTransparencyData(
                    GenericParticleData.create(0.9f, 0f).build()
                )
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
                level().playSound(
                    null,
                    blockPosition(),
                    SoundEvents.ELYTRA_FLYING,
                    SoundSource.HOSTILE,
                    2.0f,
                    0.6f
                )
            }

            spawnServerTrailParticles(
                Color(220, 80, 255),
                Color(80, 0, 160),
                2.5f
            )

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
            val intensity = (
                    2.4 * (1.0 - distance / effectRadius)
                    ).toFloat().coerceIn(0.25f, 2.4f)

            player.playNotifySound(
                SoundEvents.ELYTRA_FLYING,
                SoundSource.AMBIENT,
                2.0f,
                0.65f + Random.nextFloat() * 0.45f
            )

            PacketDistributor.sendToPlayer(
                player,
                SyncScreenShakePayload(
                    intensity = intensity,
                    duration = 8 + (intensity * 5).toInt()
                )
            )
        }

        spawnServerTrailParticles(
            Color(255, 120, 30),
            Color(180, 20, 10),
            2.0f
        )

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

        // Декоративные осколки не наносят урон,
        // не взрываются и не изменяют блоки.
        if (
            tickCount >= 360 ||
            onGround() ||
            horizontalCollision ||
            verticalCollision
        ) {
            discard()
            return
        }

        spawnServerTrailParticles(
            Color(255, 150, 45),
            Color(190, 35, 15),
            2.0f
        )
    }

    // ------------------------------------------------------------
    // ГИГАНТСКИЙ МЕТЕОРИТ
    // ------------------------------------------------------------

    private fun tickGiant() {
        if (level().isClientSide) {
            spawnGiantTrailParticles()
        } else {
            val serverLevel = level() as ServerLevel

            if (tickCount % 10 == 0) {
                serverLevel.playSound(
                    null,
                    blockPosition(),
                    SoundEvents.FIREWORK_ROCKET_BLAST,
                    SoundSource.HOSTILE,
                    8.0f,
                    0.25f
                )
            }

            spawnServerTrailParticles(
                Color(255, 110, 10),
                Color(180, 0, 220),
                12.0f
            )

            for (player in serverLevel.players()) {
                val distanceSquared = player.distanceToSqr(this)

                if (distanceSquared < 300.0 * 300.0) {
                    PacketDistributor.sendToPlayer(
                        player,
                        SyncScreenShakePayload(
                            intensity = 2.0f,
                            duration = 15
                        )
                    )
                }
            }

            // Во время управляемого полёта столкновения не должны
            // вызывать преждевременный взрыв.
            if (!skyfallFlightActive &&
                !skyfallImpactPending &&
                (onGround() || horizontalCollision || verticalCollision)
            ) {
                onGiantImpact()
            }
        }
    }

    // ------------------------------------------------------------
    // ЧАСТИЦЫ
    // ------------------------------------------------------------

    private fun spawnTrailParticles() {
        val pos = position()

        repeat(5) {
            val ox = (random.nextDouble() - 0.5) * 1.5
            val oy = (random.nextDouble() - 0.5) * 1.5
            val oz = (random.nextDouble() - 0.5) * 1.5

            level().addParticle(
                ParticleTypes.FLAME,
                pos.x + ox, pos.y + oy, pos.z + oz,
                0.0, 0.05, 0.0
            )

            level().addParticle(
                ParticleTypes.CAMPFIRE_COSY_SMOKE,
                pos.x + ox, pos.y + oy, pos.z + oz,
                0.0, 0.1, 0.0
            )
        }
    }

    private fun spawnGiantTrailParticles() {
        val pos = position()

        repeat(25) {
            val ox = (random.nextDouble() - 0.5) * 8.0
            val oy = (random.nextDouble() - 0.5) * 8.0
            val oz = (random.nextDouble() - 0.5) * 8.0

            level().addParticle(
                ParticleTypes.EXPLOSION,
                pos.x + ox, pos.y + oy, pos.z + oz,
                0.0, 0.0, 0.0
            )

            level().addParticle(
                ParticleTypes.LAVA,
                pos.x + ox, pos.y + oy, pos.z + oz,
                0.0, 0.0, 0.0
            )
        }
    }

    private fun spawnServerTrailParticles(
        startColor: Color,
        endColor: Color,
        scale: Float
    ) {
        val serverLevel = level() as? ServerLevel ?: return

        WorldParticleBuilder.create(LodestoneParticleTypes.WISP_PARTICLE)
            .setColorData(
                ColorParticleData.create(startColor, endColor).build()
            )
            .setScaleData(
                GenericParticleData.create(scale, 0f).build()
            )
            .setTransparencyData(
                GenericParticleData.create(0.85f, 0f).build()
            )
            .setLifetime(25)
            .spawn(serverLevel, x, y, z)
    }

    // ------------------------------------------------------------
    // СТОЛКНОВЕНИЕ ОБЫЧНОГО МЕТЕОРИТА И ОСКОЛКА
    // ------------------------------------------------------------

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

        while (
            serverLevel.isEmptyBlock(centerGround) &&
            centerGround.y > serverLevel.minBuildHeight
        ) {
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

        serverLevel.playSound(
            null,
            centerGround,
            ModSounds.METEOR_IMPACT.get(),
            SoundSource.BLOCKS,
            5.0f,
            0.9f
        )

        serverLevel.playSound(
            null,
            centerGround,
            SoundEvents.LIGHTNING_BOLT_THUNDER,
            SoundSource.WEATHER,
            4.0f,
            0.7f
        )

        for (player in serverLevel.players()) {
            val distanceSquared = player.distanceToSqr(this)

            if (distanceSquared < 120.0 * 120.0) {
                val factor = (
                        1.0 - (sqrt(distanceSquared) / 120.0)
                        ).toFloat().coerceIn(0.1f, 1.0f)

                PacketDistributor.sendToPlayer(
                    player,
                    SyncScreenShakePayload(
                        intensity = 4.5f * factor,
                        duration = 25
                    )
                )
            }
        }

        discard()
    }

    // ------------------------------------------------------------
    // СТОЛКНОВЕНИЕ ГИГАНТСКОГО МЕТЕОРИТА
    // ------------------------------------------------------------

    private fun onGiantImpact() {
        if (level().isClientSide) return

        val serverLevel = level() as ServerLevel

        serverLevel.explode(
            this,
            x, y, z,
            explosionPower * 3.0f,
            Level.ExplosionInteraction.BLOCK
        )

        var centerGround = blockPosition()

        while (
            serverLevel.isEmptyBlock(centerGround) &&
            centerGround.y > serverLevel.minBuildHeight
        ) {
            centerGround = centerGround.below()
        }

        val radius = 7

        for (xo in -radius..radius) {
            for (yo in -radius..radius) {
                for (zo in -radius..radius) {
                    if (xo * xo + yo * yo + zo * zo <= radius * radius) {
                        serverLevel.setBlockAndUpdate(
                            centerGround.offset(xo, yo, zo),
                            Blocks.MAGMA_BLOCK.defaultBlockState()
                        )
                    }
                }
            }
        }

        serverLevel.playSound(
            null,
            centerGround,
            SoundEvents.GENERIC_EXPLODE.value(),
            SoundSource.BLOCKS,
            12.0f,
            0.3f
        )

        serverLevel.playSound(
            null,
            centerGround,
            SoundEvents.LIGHTNING_BOLT_THUNDER,
            SoundSource.WEATHER,
            10.0f,
            0.3f
        )

        for (player in serverLevel.players()) {
            PacketDistributor.sendToPlayer(
                player,
                SyncScreenShakePayload(
                    intensity = 12.0f,
                    duration = 70
                )
            )
        }

        // После основного взрыва запускаем отдельный дождь осколков.
        serverLevel.players()
            .minByOrNull { it.distanceToSqr(this) }
            ?.let { nearestPlayer ->
                SkyBreakServerManager.spawnMeteorFragmentBurst(serverLevel, nearestPlayer)
            }

        discard()
    }

    // ------------------------------------------------------------
    // ПРОРИСОВКА И СОХРАНЕНИЕ
    // ------------------------------------------------------------

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
        meteorType = MeteorType.entries[
            compound.getInt("MeteorType").coerceIn(0, 4)
        ]
    }

    override fun addAdditionalSaveData(compound: CompoundTag) {
        compound.putInt("ExplosionPower", explosionPower)
        compound.putInt("MeteorType", meteorType.ordinal)
    }

    companion object {
        private val TYPE_ACCESSOR: EntityDataAccessor<Byte> =
            SynchedEntityData.defineId(
                MeteorEntity::class.java,
                EntityDataSerializers.BYTE
            )
    }
}
