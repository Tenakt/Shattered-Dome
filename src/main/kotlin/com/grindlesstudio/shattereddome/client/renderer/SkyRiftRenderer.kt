package com.grindlesstudio.shattereddome.client.renderer

import com.grindlesstudio.shattereddome.client.SkyBreakClientState
import com.grindlesstudio.shattereddome.network.SkyEventStage
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
    private const val MIN_RIFT_RENDER_PROGRESS = 0.005f

    private data class FogCluster(
        val direction: Vec3,
        val points: Array<Vec3>,
        val growthThreshold: Float,
        val scaleMultiplier: Float,
        // Сглаженные линии создаются один раз при инициализации, не каждый кадр.
        val renderPoints: List<Vec3>
    )

    private val FOG_CLUSTERS: Array<FogCluster> = generateSkyRifts()


    /** Крупные отверстия в оболочке; форма задаётся отдельно для каждого разлома. */
    private data class RiftPatch(
        val direction: Vec3,
        val growthThreshold: Float,
        val size: Float,
        val rotation: Float,
        val scaleMultiplier: Float,
        val seed: Int
    )

    private data class RiftPatchFrame(
        val patch: RiftPatch,
        val center: Vec3,
        val right: Vec3,
        val up: Vec3,
        val scale: Float,
        val growth: Float,
        val size: Float,
        val outline: Array<Vec3>
    )

    // Единая форма всех разломов: вытянутый рваный зигзаг с фиолетовой каймой.
    // Координаты подобраны по референсу: длинный силуэт и неглубокие зубцы.
    private val ZIGZAG_SHAPE = arrayOf(
        Vec3(-1.00, 0.13, 0.0),
        Vec3(-0.77, 0.34, 0.0),
        Vec3(-0.55, 0.16, 0.0),
        Vec3(-0.29, 0.29, 0.0),
        Vec3(-0.10, 0.08, 0.0),
        Vec3(0.18, 0.22, 0.0),
        Vec3(0.40, 0.04, 0.0),
        Vec3(0.68, 0.11, 0.0),
        Vec3(1.00, -0.19, 0.0),
        Vec3(0.74, -0.39, 0.0),
        Vec3(0.49, -0.20, 0.0),
        Vec3(0.25, -0.34, 0.0),
        Vec3(0.02, -0.13, 0.0),
        Vec3(-0.23, -0.29, 0.0),
        Vec3(-0.47, -0.07, 0.0),
        Vec3(-0.73, -0.17, 0.0),
        Vec3(-1.00, 0.02, 0.0)
    )

    private val COSMIC_STAR_POSITIONS = arrayOf(
        Vec3(-0.72, 0.08, 0.0),
        Vec3(-0.48, 0.10, 0.0),
        Vec3(-0.22, -0.04, 0.0),
        Vec3(0.05, 0.00, 0.0),
        Vec3(0.31, -0.10, 0.0),
        Vec3(0.56, -0.16, 0.0),
        Vec3(0.76, -0.25, 0.0)
    )

    // Все разломы имеют один зигзагообразный силуэт; различаются только положением,
    // размером и наклоном, чтобы не появлялись круги, овалы и треугольники.
    // Фиксированный seed делает раскладку случайной на вид, но стабильной между запусками.
    // Азимут и высота независимы: разломы больше не выстраиваются диагональной цепочкой.
    private val RIFT_PATCHES: Array<RiftPatch> = generateRiftPatches()

    private fun generateRiftPatches(): Array<RiftPatch> {
        val random = java.util.Random(0x51A7D0L)
        val count = 8

        return Array(count) { index ->
            val azimuth = random.nextDouble() * Math.PI * 2.0
            val vertical = 0.12 + random.nextDouble() * 0.80
            val horizontal = sqrt(1.0 - vertical * vertical)
            val direction = Vec3(
                horizontal * cos(azimuth),
                vertical,
                horizontal * sin(azimuth)
            ).normalize()

            val growthThreshold = if (index == 0) {
                0f
            } else {
                0.04f + random.nextFloat() * 0.62f
            }

            RiftPatch(
                direction = direction,
                growthThreshold = growthThreshold,
                size = 24f + random.nextFloat() * 13f,
                rotation = (random.nextFloat() - 0.5f) * 1.15f,
                scaleMultiplier = 0.78f + random.nextFloat() * 0.35f,
                seed = index
            )
        }
    }

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
    private var shardCooldown = 0

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
            SkyBreakClientState.openingFlashIntensity = 0f
            SkyBreakClientState.openingFlashTicks = 0
            shardCooldown = 0
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
        val flash = maxOf(
            SkyBreakClientState.skyFlashIntensity,
            SkyBreakClientState.openingFlashIntensity
        ).coerceIn(0f, 1f)

        if (!SkyBreakClientState.isBroken && progress <= 0f && flash <= 0f) return

        if (
            flash <= 0.001f && progress < MIN_RIFT_RENDER_PROGRESS &&
            SkyBreakClientState.eventStage != SkyEventStage.RESTORING
        ) return

        animTime += 0.012f

        val mc = Minecraft.getInstance()
        val renderDistance = mc.options.renderDistance().get() * 16.0
        val skyDistance = (renderDistance * 0.85).coerceAtMost(230.0)
        val matrix = event.poseStack.last().pose()
        val showRifts = progress >= MIN_RIFT_RENDER_PROGRESS ||
                (SkyBreakClientState.eventStage == SkyEventStage.RESTORING && progress > 0.001f)
        val patchFrames = if (showRifts) createPatchFrames(skyDistance, progress) else emptyList()

        RenderSystem.setShader(GameRenderer::getPositionColorShader)
        RenderSystem.enableBlend()
        RenderSystem.disableDepthTest()
        RenderSystem.depthMask(false)

        try {
            // PASS 1: полупрозрачная тёмно-фиолетовая поверхность — это само отверстие в небе.
            if (patchFrames.isNotEmpty()) {
                RenderSystem.blendFunc(
                    GlStateManager.SourceFactor.SRC_ALPHA,
                    GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA
                )
                val voidBuffer = Tesselator.getInstance().begin(
                    VertexFormat.Mode.TRIANGLES,
                    DefaultVertexFormat.POSITION_COLOR
                )
                if (drawRiftOpenings(voidBuffer, matrix, skyDistance, patchFrames)) {
                    BufferUploader.drawWithShader(voidBuffer.buildOrThrow())
                }
            }

            // PASS 2: свечение рваных краёв, звёзды, планеты и вспышка.
            val glowBuffer = Tesselator.getInstance().begin(
                VertexFormat.Mode.TRIANGLES,
                DefaultVertexFormat.POSITION_COLOR
            )
            RenderSystem.blendFunc(
                GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE
            )

            var hasGlow = false
            if (flash > 0.001f) {
                drawFlash(glowBuffer, matrix, skyDistance, flash, event.camera.position)
                hasGlow = true
            }
            if (patchFrames.isNotEmpty()) {
                hasGlow = drawRift(glowBuffer, matrix, skyDistance, patchFrames) || hasGlow
            }
            if (hasGlow) {
                BufferUploader.drawWithShader(glowBuffer.buildOrThrow())
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

    /** Расчёт геометрии крупных разломов выполняется один раз для обоих render pass. */
    private fun createPatchFrames(distance: Double, progress: Float): List<RiftPatchFrame> {
        val amount = progress.coerceIn(0f, 1f)
        val frames = ArrayList<RiftPatchFrame>(RIFT_PATCHES.size)

        for (patch in RIFT_PATCHES) {
            if (amount <= patch.growthThreshold) continue
            val rawGrowth = if (patch.growthThreshold >= 1f) {
                0f
            } else {
                ((amount - patch.growthThreshold) / (1f - patch.growthThreshold)).coerceIn(0f, 1f)
            }
            if (rawGrowth <= 0.001f) continue

            val growth = rawGrowth * rawGrowth * (3f - 2f * rawGrowth)
            val size = patch.size * growth
            val rot = patch.rotation.toDouble()
            val cr = cos(rot)
            val sr = sin(rot)
            val outline = Array(ZIGZAG_SHAPE.size) { index ->
                val point = ZIGZAG_SHAPE[index]
                val px = point.x * size
                val py = point.y * size
                Vec3(
                    px * cr - py * sr,
                    px * sr + py * cr,
                    0.0
                )
            }

            val normal = patch.direction
            val reference = if (abs(normal.y) > 0.8) Vec3(1.0, 0.0, 0.0) else Vec3(0.0, 1.0, 0.0)
            val right = reference.cross(normal).normalize()
            val up = normal.cross(right).normalize()
            frames.add(
                RiftPatchFrame(
                    patch = patch,
                    center = normal.scale(distance),
                    right = right,
                    up = up,
                    scale = (distance / 100.0).toFloat() * patch.scaleMultiplier,
                    growth = growth,
                    size = size,
                    outline = outline
                )
            )
        }
        return frames
    }

    /** Закрашивает сами отверстия в оболочке. Применяется обычное alpha blending. */
    private fun drawRiftOpenings(
        buffer: BufferBuilder,
        matrix: Matrix4f,
        distance: Double,
        frames: List<RiftPatchFrame>
    ): Boolean {
        var emitted = false
        for (frame in frames) {
            // Не отправляем практически прозрачные треугольники: на таком росте
            // отверстие ещё не видно, зато буфер тратит лишние вершины.
            if (frame.growth <= 0.01f) continue
            val alpha = (0.94f * frame.growth).coerceIn(0f, 0.94f)
            for (i in frame.outline.indices) {
                val p1 = frame.outline[i]
                val p2 = frame.outline[(i + 1) % frame.outline.size]
                // Тёмная сердцевина постепенно открывается из маленького разрыва.
                vertex(buffer, matrix, frame.center, frame.right, frame.up, distance, frame.scale,
                    0f, 0f, 0.012f, 0.002f, 0.040f, alpha)
                vertex(buffer, matrix, frame.center, frame.right, frame.up, distance, frame.scale,
                    p1.x.toFloat(), p1.y.toFloat(), 0.035f, 0.004f, 0.095f, alpha)
                vertex(buffer, matrix, frame.center, frame.right, frame.up, distance, frame.scale,
                    p2.x.toFloat(), p2.y.toFloat(), 0.026f, 0.003f, 0.070f, alpha)
                emitted = true
            }
        }
        return emitted
    }

    /** Рисует все крупные разломы в единой форме зигзагообразной трещины. */
    private fun drawRift(
        buffer: BufferBuilder,
        matrix: Matrix4f,
        distance: Double,
        frames: List<RiftPatchFrame>
    ): Boolean {
        var emittedGeometry = false
        for (frame in frames) {
            val fade = frame.growth.coerceIn(0f, 1f)
            // При совсем малом росте все обводки/диски ниже альфа-порога,
            // поэтому drawRift не должен сообщать, что буфер уже заполнен.
            if (fade <= 0.01f) continue
            val outline = frame.outline
            emittedGeometry = true

            // Неоновая кромка повторяет конкретную форму разлома.
            for (i in outline.indices) {
                drawRiftEdge(
                    buffer, matrix, frame, outline[i], outline[(i + 1) % outline.size],
                    width = 0.82f, r = 0.38f, g = 0.015f, b = 0.92f, alpha = 0.72f * fade
                )
                drawRiftEdge(
                    buffer, matrix, frame, outline[i], outline[(i + 1) % outline.size],
                    width = 0.22f, r = 0.86f, g = 0.52f, b = 1f, alpha = 0.82f * fade
                )
            }

            // Фиолетовая туманность в глубине проёма; края всё ещё остаются видимыми.
            drawRadialFogDisk(
                buffer, matrix, frame.center, frame.right, frame.up, distance, frame.scale,
                0f, 0f, frame.size * 0.36f,
                0.11f, 0.006f, 0.46f, 0.15f * fade, 12
            )
            drawRadialFogDisk(
                buffer, matrix, frame.center, frame.right, frame.up, distance, frame.scale,
                -frame.size * 0.10f, frame.size * 0.08f, frame.size * 0.19f,
                0.25f, 0.018f, 0.70f, 0.10f * fade, 10
            )

            // Звёздное поле внутри каждой дыры. Мерцают только точки космоса, не контур.
            for (starIndex in COSMIC_STAR_POSITIONS.indices) {
                val position = COSMIC_STAR_POSITIONS[starIndex]
                val seed = frame.patch.seed * 1.71f + starIndex * 2.19f
                val x = (position.x * frame.size).toFloat()
                val y = (position.y * frame.size).toFloat()
                val twinkle = (0.62f + 0.32f * sin(animTime * 1.8f + seed)).coerceIn(0.15f, 0.96f)
                drawRadialFogDisk(
                    buffer, matrix, frame.center, frame.right, frame.up, distance, frame.scale,
                    x, y, 0.16f * fade,
                    0.88f, 0.90f, 0.96f, twinkle * fade * 0.20f, 5
                )
                drawRadialFogDisk(
                    buffer, matrix, frame.center, frame.right, frame.up, distance, frame.scale,
                    x, y, 0.07f * fade,
                    0.98f, 0.98f, 1.0f, twinkle * fade * 0.24f, 4
                )
            }

            // Планеты находятся внутри отверстий, а не отдельными точками на небе.
            // Часть планет имеет кольца; расположение меняется от разлома к разлому.
            val planetAnchor = when (frame.patch.seed % 4) {
                0 -> Vec3(-0.18, 0.035, 0.0)
                1 -> Vec3(0.24, -0.075, 0.0)
                2 -> Vec3(-0.36, -0.055, 0.0)
                else -> Vec3(0.08, 0.09, 0.0)
            }
            if (frame.patch.seed % 4 != 1) {
                drawCosmicPlanet(
                    buffer, matrix, frame.center, frame.right, frame.up, distance, frame.scale,
                    (planetAnchor.x * frame.size).toFloat(),
                    (planetAnchor.y * frame.size).toFloat(),
                    frame.patch.seed, fade
                )
            }
            if (frame.patch.seed % 3 == 0) {
                drawCosmicPlanet(
                    buffer, matrix, frame.center, frame.right, frame.up, distance, frame.scale,
                    (0.40f * frame.size), (-0.16f * frame.size),
                    frame.patch.seed + 1, fade * 0.78f
                )
            }

        }
        return emittedGeometry
    }

    private fun drawCosmicPlanet(
        buffer: BufferBuilder,
        matrix: Matrix4f,
        center: Vec3,
        right: Vec3,
        up: Vec3,
        distance: Double,
        scale: Float,
        x: Float,
        y: Float,
        seed: Int,
        fade: Float
    ) {
        val (planetR, planetG, planetB) = when (seed % 3) {
            0 -> Triple(0.16f, 0.20f, 1.0f)
            1 -> Triple(0.76f, 0.10f, 0.52f)
            else -> Triple(1.0f, 0.28f, 0.08f)
        }

        drawRadialFogDisk(
            buffer, matrix, center, right, up, distance, scale,
            x, y, 3.1f,
            planetR * 0.55f, planetG * 0.50f, planetB,
            0.25f * fade, 14
        )
        drawRadialFogDisk(
            buffer, matrix, center, right, up, distance, scale,
            x, y, 1.95f,
            planetR, planetG, planetB,
            0.78f * fade, 14
        )
        drawRadialFogDisk(
            buffer, matrix, center, right, up, distance, scale,
            x - 0.45f, y + 0.50f, 0.52f,
            0.76f, 0.80f, 1f,
            0.78f * fade, 8
        )

        if (seed % 2 == 0) {
            drawCosmicRing(
                buffer, matrix, center, right, up, distance, scale,
                x, y, 3.0f, 0.22f,
                0.70f, 0.34f, 1.0f, 0.48f * fade
            )
        }
    }

    private fun drawCosmicRing(
        buffer: BufferBuilder,
        matrix: Matrix4f,
        center: Vec3,
        right: Vec3,
        up: Vec3,
        distance: Double,
        scale: Float,
        cx: Float,
        cy: Float,
        radius: Float,
        thickness: Float,
        r: Float,
        g: Float,
        b: Float,
        alpha: Float
    ) {
        if (alpha <= 0.001f) return
        val segments = 20
        val step = (Math.PI * 2.0 / segments).toFloat()
        val innerRadius = radius - thickness
        for (i in 0 until segments) {
            val a0 = i * step
            val a1 = (i + 1) * step
            val ox0 = cx + cos(a0) * radius
            val oy0 = cy + sin(a0) * radius
            val ox1 = cx + cos(a1) * radius
            val oy1 = cy + sin(a1) * radius
            val ix0 = cx + cos(a0) * innerRadius
            val iy0 = cy + sin(a0) * innerRadius
            val ix1 = cx + cos(a1) * innerRadius
            val iy1 = cy + sin(a1) * innerRadius

            vertex(buffer, matrix, center, right, up, distance, scale, ox0, oy0, r, g, b, alpha)
            vertex(buffer, matrix, center, right, up, distance, scale, ox1, oy1, r, g, b, alpha)
            vertex(buffer, matrix, center, right, up, distance, scale, ix0, iy0, r, g, b, alpha)
            vertex(buffer, matrix, center, right, up, distance, scale, ix0, iy0, r, g, b, alpha)
            vertex(buffer, matrix, center, right, up, distance, scale, ox1, oy1, r, g, b, alpha)
            vertex(buffer, matrix, center, right, up, distance, scale, ix1, iy1, r, g, b, alpha)
        }
    }


    private fun drawRiftEdge(
        buffer: BufferBuilder,
        matrix: Matrix4f,
        frame: RiftPatchFrame,
        p1: Vec3,
        p2: Vec3,
        width: Float,
        r: Float,
        g: Float,
        b: Float,
        alpha: Float
    ) {
        if (alpha <= 0.001f) return
        val dx = (p2.x - p1.x).toFloat()
        val dy = (p2.y - p1.y).toFloat()
        val length = sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
        val nx = -dy / length * width
        val ny = dx / length * width
        val x1 = p1.x.toFloat()
        val y1 = p1.y.toFloat()
        val x2 = p2.x.toFloat()
        val y2 = p2.y.toFloat()

        vertex(buffer, matrix, frame.center, frame.right, frame.up, frame.center.length(), frame.scale,
            x1 + nx, y1 + ny, r, g, b, alpha)
        vertex(buffer, matrix, frame.center, frame.right, frame.up, frame.center.length(), frame.scale,
            x1 - nx, y1 - ny, r, g, b, alpha)
        vertex(buffer, matrix, frame.center, frame.right, frame.up, frame.center.length(), frame.scale,
            x2 - nx, y2 - ny, r, g, b, alpha)
        vertex(buffer, matrix, frame.center, frame.right, frame.up, frame.center.length(), frame.scale,
            x1 + nx, y1 + ny, r, g, b, alpha)
        vertex(buffer, matrix, frame.center, frame.right, frame.up, frame.center.length(), frame.scale,
            x2 - nx, y2 - ny, r, g, b, alpha)
        vertex(buffer, matrix, frame.center, frame.right, frame.up, frame.center.length(), frame.scale,
            x2 + nx, y2 + ny, r, g, b, alpha)
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

        if (SkyBreakClientState.openingFlashTicks > 0) {
            val remaining = SkyBreakClientState.openingFlashTicks
            SkyBreakClientState.openingFlashIntensity = if (remaining > 31) {
                0.92f
            } else {
                (remaining / 31f * 0.92f).coerceIn(0f, 0.92f)
            }
            SkyBreakClientState.openingFlashTicks--
        } else {
            SkyBreakClientState.openingFlashIntensity = 0f
        }

        val riftStage = SkyBreakClientState.eventStage == SkyEventStage.RIFT_OPENING ||
                SkyBreakClientState.eventStage == SkyEventStage.METEOR_STORM
        if (riftStage && SkyBreakClientState.riftProgress > 0.22f) {
            if (shardCooldown > 0) {
                shardCooldown--
            } else {
                // Клиентские обломки не взаимодействуют с блоками: это лёгкий визуальный слой.
                repeat(Random.nextInt(1, 3)) {
                    val startX = player.x + Random.nextDouble(-52.0, 52.0)
                    val startY = player.y + Random.nextDouble(58.0, 95.0)
                    val startZ = player.z + Random.nextDouble(-52.0, 52.0)
                    val vx = Random.nextDouble(-0.42, 0.42)
                    val vy = Random.nextDouble(-1.75, -0.75)
                    val vz = Random.nextDouble(-0.42, 0.42)
                    level.addParticle(
                        net.minecraft.core.particles.ParticleTypes.REVERSE_PORTAL,
                        true, startX, startY, startZ, vx, vy, vz
                    )
                    if (Random.nextBoolean()) {
                        level.addParticle(
                            net.minecraft.core.particles.ParticleTypes.END_ROD,
                            true, startX, startY, startZ, vx * 0.6, vy, vz * 0.6
                        )
                    }
                }
                shardCooldown = Random.nextInt(3, 8)
            }
        } else {
            shardCooldown = 0
        }

        if (
            !SkyBreakClientState.isBroken ||
            SkyBreakClientState.riftProgress <= 0f ||
            SkyBreakClientState.eventStage == SkyEventStage.RESTORING
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
