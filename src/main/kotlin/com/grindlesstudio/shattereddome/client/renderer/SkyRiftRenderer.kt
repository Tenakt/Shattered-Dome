package com.grindlesstudio.shattereddome.client.renderer

import com.grindlesstudio.shattereddome.client.SkyBreakClientState
import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.systems.RenderSystem
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
import kotlin.random.Random

object SkyRiftRenderer {

    private data class FogCluster(
        val direction: Vec3,
        val points: Array<Vec3>,
        val growthThreshold: Float,
        val scaleMultiplier: Float
    )

    private val FOG_CLUSTERS: Array<FogCluster> = generateSkyRifts()

    private fun generateSkyRifts(): Array<FogCluster> {
        val clusters = mutableListOf<FogCluster>()
        val random = java.util.Random(1337L)

        val firstDir = Vec3(0.0, 0.9, -0.2).normalize()
        val firstPoints = arrayOf(
            Vec3(0.0, -4.0, 0.0), Vec3(1.5, 0.0, 0.0),
            Vec3(-1.0, 4.0, 0.0), Vec3(0.5, 8.0, 0.0)
        )
        clusters.add(FogCluster(firstDir, firstPoints, 0.0f, 1.0f))

        val totalClusters = 50

        for (i in 0 until totalClusters) {
            val azimuth = random.nextDouble() * Math.PI * 2.0
            val y = 0.1 + random.nextDouble() * 0.88
            val r = Math.sqrt(1.0 - y * y)

            val dirX = r * Math.cos(azimuth)
            val dirY = y
            val dirZ = r * Math.sin(azimuth)
            val direction = Vec3(dirX, dirY, dirZ).normalize()

            val pointCount = 3 + random.nextInt(3)
            val points = mutableListOf<Vec3>()

            val branchAngle = random.nextDouble() * Math.PI * 2.0
            var curX = 0.0
            var curY = -4.0 + random.nextDouble() * 2.0
            val stepLen = 3.0 + random.nextDouble() * 3.0

            for (p in 0 until pointCount) {
                val dx = (random.nextDouble() - 0.5) * 5.0
                val dy = stepLen + (random.nextDouble() - 0.5) * 2.0

                val rotX = curX * Math.cos(branchAngle) - curY * Math.sin(branchAngle)
                val rotY = curX * Math.sin(branchAngle) + curY * Math.cos(branchAngle)

                points.add(Vec3(rotX, rotY, 0.0))

                curX += dx
                curY += dy
            }

            val threshold = 0.08f + (i.toFloat() / totalClusters) * 0.84f
            val scaleMult = 0.5f + random.nextFloat() * 0.5f

            clusters.add(FogCluster(direction, points.toTypedArray(), threshold, scaleMult))
        }

        return clusters.toTypedArray()
    }

    private var animTime = 0f

    fun render(event: RenderLevelStageEvent) {
        val progress = SkyBreakClientState.riftProgress
        val flash = SkyBreakClientState.skyFlashIntensity
        if (progress <= 0f && flash <= 0f) return

        animTime += 0.012f

        // Плавное затухание вспышки неба при проходе метеора
        if (SkyBreakClientState.skyFlashIntensity > 0f) {
            SkyBreakClientState.skyFlashIntensity = (SkyBreakClientState.skyFlashIntensity - 0.03f).coerceAtLeast(0f)
        }

        val mc = Minecraft.getInstance()
        val renderDistance = mc.options.renderDistance().get() * 16.0
        val skyDistance = (renderDistance * 0.85).coerceAtMost(230.0)
        val matrix = event.poseStack.last().pose()

        RenderSystem.setShader(GameRenderer::getPositionColorShader)
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE)

        val flashBoost = 1.0f + flash * 2.5f // Усиление яркости при прорыве

        for ((index, cluster) in FOG_CLUSTERS.withIndex()) {
            if (progress < cluster.growthThreshold && flash <= 0f) continue

            val clusterProgress = if (cluster.growthThreshold == 0.0f) {
                progress.coerceIn(0f, 1f)
            } else {
                ((progress - cluster.growthThreshold) / (1.0f - cluster.growthThreshold)).coerceIn(0f, 1f)
            }

            val center = cluster.direction.scale(skyDistance)
            val scale = (skyDistance / 100.0).toFloat() * cluster.scaleMultiplier

            val normal = cluster.direction
            val ref = if (abs(normal.y) > 0.8) Vec3(1.0, 0.0, 0.0) else Vec3(0.0, 1.0, 0.0)
            val right = ref.cross(normal).normalize()
            val up = normal.cross(right).normalize()

            val interpolatedPoints = interpolateCurve(cluster.points, 6)
            val visibleCount = (interpolatedPoints.size * clusterProgress).toInt().coerceAtMost(interpolatedPoints.size)

            val seedOffset = index * 2.31f

            for (i in 0 until visibleCount) {
                val p = interpolatedPoints[i]

                val waveX = sin(animTime * 1.5f + i * 0.5f + seedOffset) * 0.8f
                val waveY = cos(animTime * 1.2f + i * 0.4f + seedOffset) * 0.6f

                val cx = (p.x * 0.8 + waveX).toFloat()
                val cy = (p.y + waveY).toFloat()

                val alphaFade = sin((i.toFloat() / (interpolatedPoints.size - 1).coerceAtLeast(1)) * Math.PI.toFloat())

                drawRadialFogDisk(
                    matrix, center, right, up, skyDistance, scale,
                    cx = cx, cy = cy, radius = 5.0f * (1.0f + flash * 0.8f),
                    r = 0.45f + flash * 0.4f, g = 0.02f, b = 0.85f + flash * 0.15f, alpha = 0.05f * clusterProgress * alphaFade * flashBoost
                )

                drawRadialFogDisk(
                    matrix, center, right, up, skyDistance, scale,
                    cx = cx + sin(animTime + i) * 0.5f, cy = cy + cos(animTime + i) * 0.5f, radius = 2.5f * (1.0f + flash * 0.5f),
                    r = 0.85f, g = 0.10f + flash * 0.3f, b = 0.95f, alpha = 0.12f * clusterProgress * alphaFade * flashBoost
                )

                drawRadialFogDisk(
                    matrix, center, right, up, skyDistance, scale,
                    cx = cx, cy = cy, radius = 1.0f,
                    r = 0.98f, g = 0.70f, b = 1.0f, alpha = 0.30f * clusterProgress * alphaFade * flashBoost
                )
            }
        }

        RenderSystem.defaultBlendFunc()
    }

    private fun drawRadialFogDisk(
        matrix: Matrix4f, center: Vec3, right: Vec3, up: Vec3,
        skyDistance: Double, scale: Float,
        cx: Float, cy: Float, radius: Float,
        r: Float, g: Float, b: Float, alpha: Float,
        segments: Int = 8
    ) {
        if (alpha <= 0.001f || radius <= 0f) return

        val buffer = Tesselator.getInstance().begin(
            VertexFormat.Mode.TRIANGLES,
            DefaultVertexFormat.POSITION_COLOR
        )

        var addedVertices = false
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

            addedVertices = true
        }

        if (addedVertices) {
            val builtData = buffer.build()
            if (builtData != null) {
                BufferUploader.drawWithShader(builtData)
            }
        }
    }

    private fun interpolateCurve(points: Array<Vec3>, subdivisions: Int): List<Vec3> {
        val result = mutableListOf<Vec3>()
        for (i in 0 until points.size - 1) {
            val p0 = points[(i - 1).coerceAtLeast(0)]
            val p1 = points[i]
            val p2 = points[i + 1]
            val p3 = points[(i + 2).coerceAtMost(points.size - 1)]

            for (j in 0 until subdivisions) {
                val t = j.toDouble() / subdivisions
                val t2 = t * t
                val t3 = t2 * t

                val x = 0.5 * ((2 * p1.x) + (-p0.x + p2.x) * t + (2 * p0.x - 5 * p1.x + 4 * p2.x - p3.x) * t2 + (-p0.x + 3 * p1.x - 3 * p2.x + p3.x) * t3)
                val y = 0.5 * ((2 * p1.y) + (-p0.y + p2.y) * t + (2 * p0.y - 5 * p1.y + 4 * p2.y - p3.y) * t2 + (-p0.y + 3 * p1.y - 3 * p2.y + p3.y) * t3)

                result.add(Vec3(x, y, 0.0))
            }
        }
        result.add(points.last())
        return result
    }

    private fun vertex(
        buffer: com.mojang.blaze3d.vertex.BufferBuilder,
        matrix: Matrix4f, center: Vec3, right: Vec3, up: Vec3, skyDistance: Double, scale: Float,
        x: Float, y: Float, r: Float, g: Float, b: Float, a: Float
    ) {
        val flatPos = center
            .add(right.scale((x * scale).toDouble()))
            .add(up.scale((y * scale).toDouble()))

        val sphericalPos = flatPos.normalize().scale(skyDistance)

        buffer.addVertex(matrix, sphericalPos.x.toFloat(), sphericalPos.y.toFloat(), sphericalPos.z.toFloat())
            .setColor(r, g, b, a)
    }

    fun tick(mc: Minecraft) {
        val player = mc.player ?: return
        val level = mc.level ?: return
        if (!SkyBreakClientState.isBroken || SkyBreakClientState.riftProgress <= 0f) return

        if (Random.nextFloat() < 0.3f) {
            val cluster = FOG_CLUSTERS[Random.nextInt(FOG_CLUSTERS.size)]
            if (SkyBreakClientState.riftProgress < cluster.growthThreshold) return

            val p = cluster.points[Random.nextInt(cluster.points.size)]

            val distance = 35.0
            val center = player.position().add(cluster.direction.scale(distance))
            val ref = if (abs(cluster.direction.y) > 0.8) Vec3(1.0, 0.0, 0.0) else Vec3(0.0, 1.0, 0.0)
            val right = ref.cross(cluster.direction).normalize()
            val up = cluster.direction.cross(right).normalize()

            val scale = distance / 100.0
            val pos = center
                .add(right.scale(p.x * scale + Random.nextDouble(-1.5, 1.5)))
                .add(up.scale(p.y * scale + Random.nextDouble(-1.5, 1.5)))

            WorldParticleBuilder.create(LodestoneParticleTypes.WISP_PARTICLE)
                .setColorData(ColorParticleData.create(Color(160, 40, 240), Color(30, 0, 70)).build())
                .setScaleData(GenericParticleData.create(0.8f + Random.nextFloat() * 1.2f, 0f).build())
                .setTransparencyData(GenericParticleData.create(0.2f * SkyBreakClientState.riftProgress, 0f).build())
                .setSpinData(SpinParticleData.create(0.02f, 0.1f).build())
                .setLifetime(40 + Random.nextInt(20))
                .setMotion(Random.nextDouble(-0.005, 0.005), -0.003, Random.nextDouble(-0.005, 0.005))
                .spawn(level, pos.x, pos.y, pos.z)
        }
    }
}