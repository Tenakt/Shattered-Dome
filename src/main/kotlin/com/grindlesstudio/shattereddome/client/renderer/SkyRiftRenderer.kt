package com.grindlesstudio.shattereddome.client.renderer

import com.grindlesstudio.shattereddome.client.SkyBreakClientState
import com.grindlesstudio.shattereddome.client.fx.ScreenShakeHandler
import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.client.event.RenderLevelStageEvent
import org.joml.Matrix4f
import team.lodestar.lodestone.registry.common.particle.LodestoneParticleTypes
import team.lodestar.lodestone.systems.particle.builder.WorldParticleBuilder
import team.lodestar.lodestone.systems.particle.data.GenericParticleData
import team.lodestar.lodestone.systems.particle.data.color.ColorParticleData
import team.lodestar.lodestone.systems.particle.data.spin.SpinParticleData
import java.awt.Color
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

object SkyRiftRenderer {

    private const val RIFT_CURVE_SUBDIVISIONS = 3
    private const val RIFT_DISK_SEGMENTS = 6
    private const val MIN_RIFT_RENDER_PROGRESS = 0.20f

    private data class FogCluster(
        val direction: Vec3,
        val points: Array<Vec3>,
        val growthThreshold: Float,
        val scaleMultiplier: Float,
        // Сглаженные линии создаются один раз при инициализации, не каждый кадр.
        val renderPoints: List<Vec3>
    )

    private val FOG_CLUSTERS: Array<FogCluster> = generateSkyRifts()

    private fun generateSkyRifts(): Array<FogCluster> {
        val clusters = mutableListOf<FogCluster>()
        val random = java.util.Random(1337L)

        val firstDir = Vec3(0.0, 0.9, -0.2).normalize()
        val firstPoints = arrayOf(
            Vec3(0.0, -4.0, 0.0),
            Vec3(1.5, 0.0, 0.0),
            Vec3(-1.0, 4.0, 0.0),
            Vec3(0.5, 8.0, 0.0)
        )

        clusters.add(
            FogCluster(
                firstDir,
                firstPoints,
                0.0f,
                1.0f,
                interpolateCurve(firstPoints, RIFT_CURVE_SUBDIVISIONS)
            )
        )

        val totalClusters = 50

        for (i in 0 until totalClusters) {
            val azimuth = random.nextDouble() * Math.PI * 2.0
            val y = 0.1 + random.nextDouble() * 0.88
            val r = kotlin.math.sqrt(1.0 - y * y)

            val direction = Vec3(
                r * kotlin.math.cos(azimuth),
                y,
                r * kotlin.math.sin(azimuth)
            ).normalize()

            val pointCount = 3 + random.nextInt(3)
            val points = mutableListOf<Vec3>()
            val branchAngle = random.nextDouble() * Math.PI * 2.0

            var curX = 0.0
            var curY = -4.0 + random.nextDouble() * 2.0
            val stepLen = 3.0 + random.nextDouble() * 3.0

            for (p in 0 until pointCount) {
                val dx = (random.nextDouble() - 0.5) * 5.0
                val dy = stepLen + (random.nextDouble() - 0.5) * 2.0

                val rotX =
                    curX * kotlin.math.cos(branchAngle) -
                            curY * kotlin.math.sin(branchAngle)
                val rotY =
                    curX * kotlin.math.sin(branchAngle) +
                            curY * kotlin.math.cos(branchAngle)

                points.add(Vec3(rotX, rotY, 0.0))
                curX += dx
                curY += dy
            }

            val pointArray = points.toTypedArray()
            clusters.add(
                FogCluster(
                    direction,
                    pointArray,
                    0.08f + (i.toFloat() / totalClusters) * 0.84f,
                    0.5f + random.nextFloat() * 0.5f,
                    interpolateCurve(pointArray, RIFT_CURVE_SUBDIVISIONS)
                )
            )
        }

        return clusters.toTypedArray()
    }

    private var animTime = 0f
    private var finaleTick = -1
    private var finaleStarted = false
    private var wasBroken = false

    private const val FLASH_DURATION = 500
    private const val METEOR_DURATION = 60
    private const val FINALE_DURATION = FLASH_DURATION + METEOR_DURATION
    private const val METEOR_SHAKE_MAX = 1.8f

    /**
     * 500 тиков — нарастание вспышки и предварительной тряски.
     * Следующие 60 тиков — полёт сущности и угасание вспышки.
     */
    fun tickMeteor() {
        val active = SkyBreakClientState.isBroken

        if (!active) {
            finaleTick = -1
            finaleStarted = false
            wasBroken = false
            SkyBreakClientState.skyFlashIntensity = 0f
            SkyBreakClientState.finaleFlashActive = false
            SkyBreakClientState.finaleFlashSource = null
            SkyBreakClientState.finaleFlashTarget = null
            return
        }

        if (!wasBroken) {
            finaleTick = -1
            finaleStarted = false
            SkyBreakClientState.skyFlashIntensity = 0f
            wasBroken = true
        }

        if (!finaleStarted && SkyBreakClientState.finaleFlashActive) {
            finaleStarted = true
            finaleTick = 0
        }

        if (!finaleStarted || finaleTick < 0) return

        when {
            finaleTick < FLASH_DURATION -> {
                val t = (finaleTick + 1).toFloat() / FLASH_DURATION
                val eased = t * t * (3f - 2f * t)

                SkyBreakClientState.skyFlashIntensity = eased

                // Тряска начинается раньше, чем метеорит становится видимым.
                ScreenShakeHandler.addShake(
                    0.02f + eased * METEOR_SHAKE_MAX,
                    2
                )
            }

            finaleTick < FINALE_DURATION -> {
                val flightTick = finaleTick - FLASH_DURATION
                val t = (flightTick + 1).toFloat() / METEOR_DURATION

                SkyBreakClientState.skyFlashIntensity = 0.65f * (1f - t)
                ScreenShakeHandler.addShake(0.35f + t * 0.85f, 2)
            }

            else -> {
                SkyBreakClientState.skyFlashIntensity = 0f
                finaleStarted = false
                finaleTick = -1
                SkyBreakClientState.finaleFlashActive = false
                SkyBreakClientState.finaleFlashSource = null
                SkyBreakClientState.finaleFlashTarget = null
                return
            }
        }

        finaleTick++
    }

    fun render(event: RenderLevelStageEvent) {
        if (event.stage != RenderLevelStageEvent.Stage.AFTER_SKY) return

        val progress = SkyBreakClientState.riftProgress.coerceIn(0f, 1f)
        val flash = SkyBreakClientState.skyFlashIntensity.coerceIn(0f, 1f)

        if (
            !SkyBreakClientState.isBroken &&
            progress <= 0f &&
            flash <= 0f
        ) {
            return
        }

        // В начале прогресса ещё нет видимых точек трещин; пропускаем пустую сборку.
        if (flash <= 0.001f && progress < MIN_RIFT_RENDER_PROGRESS) return

        animTime += 0.012f

        val mc = Minecraft.getInstance()
        val renderDistance = mc.options.renderDistance().get() * 16.0
        val skyDistance = (renderDistance * 0.85).coerceAtMost(230.0)
        val matrix = event.poseStack.last().pose()

        RenderSystem.setShader(GameRenderer::getPositionColorShader)
        RenderSystem.enableBlend()
        RenderSystem.disableDepthTest()
        RenderSystem.depthMask(false)

        val buffer = Tesselator.getInstance().begin(
            VertexFormat.Mode.TRIANGLES,
            DefaultVertexFormat.POSITION_COLOR
        )

        try {
            RenderSystem.blendFunc(
                GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE
            )

            var hasGeometry = false

            if (flash > 0.001f) {
                drawFlash(buffer, matrix, skyDistance, flash, event.camera.position)
                hasGeometry = true
            }

            if (progress >= MIN_RIFT_RENDER_PROGRESS) {
                hasGeometry = drawRift(buffer, matrix, skyDistance, progress) || hasGeometry
            }

            // Критическая оптимизация: все трещины отправляются видеокарте одним draw call.
            // На первых кадрах роста у трещин ещё могут быть только прозрачные точки;
            // в таком случае не строим пустой буфер.
            if (hasGeometry) {
                BufferUploader.drawWithShader(buffer.buildOrThrow())
            }
        } finally {
            RenderSystem.defaultBlendFunc()
            RenderSystem.depthMask(true)
            RenderSystem.enableDepthTest()
            RenderSystem.disableBlend()
        }
    }

    private fun drawFlash(
        buffer: BufferBuilder,
        matrix: Matrix4f,
        distance: Double,
        intensity: Float,
        cameraPosition: Vec3
    ) {
        val source = SkyBreakClientState.finaleFlashSource

        val direction = source
            ?.subtract(cameraPosition)
            ?.takeIf { it.lengthSqr() > 0.0001 }
            ?.normalize()
            ?: Vec3(0.0, 0.94, -0.34).normalize()

        val center = direction.scale(distance)
        val reference = if (abs(direction.y) > 0.8) {
            Vec3(1.0, 0.0, 0.0)
        } else {
            Vec3(0.0, 1.0, 0.0)
        }

        val right = reference.cross(direction).normalize()
        val up = direction.cross(right).normalize()
        val scale = (distance / 100.0).toFloat()
        val pulse = 0.82f + 0.18f * sin(intensity * Math.PI.toFloat())

        drawRadialFogDisk(
            buffer, matrix, center, right, up, distance, scale,
            0f, 0f,
            5.5f + intensity * 15f,
            0.18f * intensity * pulse,
            0.40f * intensity,
            0.96f * intensity,
            0.45f * intensity,
            24
        )

        drawRadialFogDisk(
            buffer, matrix, center, right, up, distance, scale,
            0f, 0f,
            2.5f + intensity * 8f,
            0.75f * intensity,
            0.88f * intensity,
            1f,
            0.85f * intensity,
            24
        )

        drawRadialFogDisk(
            buffer, matrix, center, right, up, distance, scale,
            0f, 0f,
            0.8f + intensity * 5.0f,
            intensity,
            intensity,
            1f,
            intensity,
            24
        )
    }

    /** Трещины используют фиксированные цвета и альфу: вспышка не влияет на яркость. */
    private fun drawRift(
        buffer: BufferBuilder,
        matrix: Matrix4f,
        distance: Double,
        progress: Float
    ): Boolean {
        val amount = progress.coerceIn(0f, 1f)
        var emittedGeometry = false

        for ((index, cluster) in FOG_CLUSTERS.withIndex()) {
            if (amount < cluster.growthThreshold) continue

            val clusterProgress = if (cluster.growthThreshold == 0f) {
                amount
            } else {
                ((amount - cluster.growthThreshold) /
                        (1f - cluster.growthThreshold)).coerceIn(0f, 1f)
            }

            val center = cluster.direction.scale(distance)
            val scale = (distance / 100.0).toFloat() * cluster.scaleMultiplier
            val normal = cluster.direction

            val reference = if (abs(normal.y) > 0.8) {
                Vec3(1.0, 0.0, 0.0)
            } else {
                Vec3(0.0, 1.0, 0.0)
            }

            val right = reference.cross(normal).normalize()
            val up = normal.cross(right).normalize()
            val points = cluster.renderPoints
            val visibleCount = (points.size * clusterProgress)
                .toInt()
                .coerceAtMost(points.size)

            val seedOffset = index * 2.31f

            for (i in 0 until visibleCount) {
                val point = points[i]
                val waveX = sin(animTime * 1.5f + i * 0.5f + seedOffset) * 0.8f
                val waveY = cos(animTime * 1.2f + i * 0.4f + seedOffset) * 0.6f

                val cx = (point.x * 0.8 + waveX).toFloat()
                val cy = (point.y + waveY).toFloat()

                val alphaFade = sin(
                    (i.toFloat() / (points.size - 1).coerceAtLeast(1)) *
                            Math.PI.toFloat()
                )
                if (alphaFade <= 0.001f || clusterProgress <= 0f) continue
                emittedGeometry = true

                drawRadialFogDisk(
                    buffer, matrix, center, right, up, distance, scale,
                    cx, cy,
                    5f,
                    0.45f,
                    0.02f,
                    0.85f,
                    0.05f * clusterProgress * alphaFade
                )

                drawRadialFogDisk(
                    buffer, matrix, center, right, up, distance, scale,
                    cx + sin(animTime + i) * 0.5f,
                    cy + cos(animTime + i) * 0.5f,
                    2.5f,
                    0.85f,
                    0.10f,
                    0.95f,
                    0.12f * clusterProgress * alphaFade
                )

                drawRadialFogDisk(
                    buffer, matrix, center, right, up, distance, scale,
                    cx, cy,
                    1f,
                    0.98f, 0.70f, 1f,
                    0.30f * clusterProgress * alphaFade
                )
            }
        }

        return emittedGeometry
    }

    /** Добавляет один радиальный диск в общий буфер; не создаёт и не отправляет отдельный буфер. */
    private fun drawRadialFogDisk(
        buffer: BufferBuilder,
        matrix: Matrix4f,
        center: Vec3,
        right: Vec3,
        up: Vec3,
        skyDistance: Double,
        scale: Float,
        cx: Float,
        cy: Float,
        radius: Float,
        r: Float,
        g: Float,
        b: Float,
        alpha: Float,
        segments: Int = RIFT_DISK_SEGMENTS
    ) {
        if (alpha <= 0.001f || radius <= 0f) return

        val angleStep = (Math.PI * 2.0 / segments).toFloat()

        for (i in 0 until segments) {
            val a1 = i * angleStep
            val a2 = (i + 1) * angleStep

            val x1 = cx + cos(a1) * radius
            val y1 = cy + sin(a1) * radius
            val x2 = cx + cos(a2) * radius
            val y2 = cy + sin(a2) * radius

            vertex(buffer, matrix, center, right, up, skyDistance, scale, cx, cy, r, g, b, alpha)
            vertex(buffer, matrix, center, right, up, skyDistance, scale, x1, y1, r, g, b, 0f)
            vertex(buffer, matrix, center, right, up, skyDistance, scale, x2, y2, r, g, b, 0f)
        }
    }

    private fun interpolateCurve(
        points: Array<Vec3>,
        subdivisions: Int
    ): List<Vec3> {
        val result = ArrayList<Vec3>((points.size - 1) * subdivisions + 1)

        for (i in 0 until points.size - 1) {
            val p0 = points[(i - 1).coerceAtLeast(0)]
            val p1 = points[i]
            val p2 = points[i + 1]
            val p3 = points[(i + 2).coerceAtMost(points.size - 1)]

            for (j in 0 until subdivisions) {
                val t = j.toDouble() / subdivisions
                val t2 = t * t
                val t3 = t2 * t

                val x = 0.5 * (
                        2 * p1.x +
                                (-p0.x + p2.x) * t +
                                (2 * p0.x - 5 * p1.x + 4 * p2.x - p3.x) * t2 +
                                (-p0.x + 3 * p1.x - 3 * p2.x + p3.x) * t3
                        )

                val y = 0.5 * (
                        2 * p1.y +
                                (-p0.y + p2.y) * t +
                                (2 * p0.y - 5 * p1.y + 4 * p2.y - p3.y) * t2 +
                                (-p0.y + 3 * p1.y - 3 * p2.y + p3.y) * t3
                        )

                result.add(Vec3(x, y, 0.0))
            }
        }

        result.add(points.last())
        return result
    }

    /** Проецирует вершину на сферу неба без создания временных Vec3 на каждую вершину. */
    private fun vertex(
        buffer: BufferBuilder,
        matrix: Matrix4f,
        center: Vec3,
        right: Vec3,
        up: Vec3,
        skyDistance: Double,
        scale: Float,
        x: Float,
        y: Float,
        r: Float,
        g: Float,
        b: Float,
        a: Float
    ) {
        val offsetX = x.toDouble() * scale
        val offsetY = y.toDouble() * scale

        val flatX = center.x + right.x * offsetX + up.x * offsetY
        val flatY = center.y + right.y * offsetX + up.y * offsetY
        val flatZ = center.z + right.z * offsetX + up.z * offsetY

        val lengthSquared = flatX * flatX + flatY * flatY + flatZ * flatZ
        if (lengthSquared <= 1.0e-12) return

        val projection = skyDistance / sqrt(lengthSquared)

        buffer.addVertex(
            matrix,
            (flatX * projection).toFloat(),
            (flatY * projection).toFloat(),
            (flatZ * projection).toFloat()
        ).setColor(r, g, b, a)
    }

    /** Исходные Lodestone-частицы около активных разломов. */
    fun tick(mc: Minecraft) {
        val player = mc.player ?: return
        val level = mc.level ?: return

        if (
            !SkyBreakClientState.isBroken ||
            SkyBreakClientState.riftProgress <= 0f
        ) {
            return
        }

        if (Random.nextFloat() < 0.3f) {
            val cluster = FOG_CLUSTERS[Random.nextInt(FOG_CLUSTERS.size)]

            if (SkyBreakClientState.riftProgress < cluster.growthThreshold) {
                return
            }

            val point = cluster.points[Random.nextInt(cluster.points.size)]
            val distance = 35.0
            val center = player.position().add(cluster.direction.scale(distance))

            val reference = if (abs(cluster.direction.y) > 0.8) {
                Vec3(1.0, 0.0, 0.0)
            } else {
                Vec3(0.0, 1.0, 0.0)
            }

            val right = reference.cross(cluster.direction).normalize()
            val up = cluster.direction.cross(right).normalize()
            val scale = distance / 100.0

            val pos = center
                .add(
                    right.scale(
                        point.x * scale + Random.nextDouble(-1.5, 1.5)
                    )
                )
                .add(
                    up.scale(
                        point.y * scale + Random.nextDouble(-1.5, 1.5)
                    )
                )

            WorldParticleBuilder.create(LodestoneParticleTypes.WISP_PARTICLE)
                .setColorData(
                    ColorParticleData.create(
                        Color(160, 40, 240),
                        Color(30, 0, 70)
                    ).build()
                )
                .setScaleData(
                    GenericParticleData.create(
                        0.8f + Random.nextFloat() * 1.2f,
                        0f
                    ).build()
                )
                .setTransparencyData(
                    GenericParticleData.create(
                        0.2f * SkyBreakClientState.riftProgress,
                        0f
                    ).build()
                )
                .setSpinData(
                    SpinParticleData.create(0.02f, 0.1f).build()
                )
                .setLifetime(40 + Random.nextInt(20))
                .setMotion(
                    Random.nextDouble(-0.005, 0.005),
                    -0.003,
                    Random.nextDouble(-0.005, 0.005)
                )
                .spawn(level, pos.x, pos.y, pos.z)
        }
    }
}
