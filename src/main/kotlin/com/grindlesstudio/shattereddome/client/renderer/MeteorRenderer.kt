package com.grindlesstudio.shattereddome.client.renderer

import com.grindlesstudio.shattereddome.ShatteredDome
import com.grindlesstudio.shattereddome.entity.MeteorEntity
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.math.Axis
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.ResourceLocation
import org.joml.Matrix4f

class MeteorRenderer(
    context: EntityRendererProvider.Context
) : EntityRenderer<MeteorEntity>(context) {

    companion object {
        private val DEFAULT_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            ShatteredDome.MOD_ID,
            "textures/entity/meteor.png"
        )

        private val STAR_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            ShatteredDome.MOD_ID,
            "textures/entity/star.png"
        )

        private const val STAR_SIZE = 2.5f
    }

    init {
        shadowRadius = 1.0f
    }

    override fun getTextureLocation(entity: MeteorEntity): ResourceLocation {
        return if (entity.meteorType == MeteorEntity.MeteorType.STAR) {
            STAR_TEXTURE
        } else {
            DEFAULT_TEXTURE
        }
    }

    override fun render(
        entity: MeteorEntity,
        entityYaw: Float,
        partialTicks: Float,
        poseStack: PoseStack,
        buffer: MultiBufferSource,
        packedLight: Int
    ) {
        // Оторванный фрагмент неба — отдельный плоский треугольник с космосом внутри.
        // Он обращён к камере каждого клиента и может вращаться во время падения.
        if (entity.meteorType == MeteorEntity.MeteorType.SKY_SHARD) {
            renderSkyShard(entity, partialTicks, poseStack, buffer)
            super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight)
            return
        }

        // Падающая звезда — именно плоская текстурированная картинка, не куб.
        // cameraOrientation() берётся на клиенте, поэтому каждый игрок видит
        // свою копию звезды лицевой стороной к собственной камере.
        if (entity.meteorType == MeteorEntity.MeteorType.STAR) {
            poseStack.pushPose()
            poseStack.mulPose(this.entityRenderDispatcher.cameraOrientation())
            poseStack.scale(STAR_SIZE, STAR_SIZE, 1.0f)

            val starBuffer = buffer.getBuffer(
                RenderType.entityCutoutNoCull(STAR_TEXTURE)
            )
            renderStarBillboard(
                poseStack.last().pose(),
                starBuffer,
                LightTexture.FULL_BRIGHT
            )

            poseStack.popPose()
            super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight)
            return
        }

        poseStack.pushPose()

        when (entity.meteorType) {
            MeteorEntity.MeteorType.GIANT -> {
                // Оставляем размер существующего сюжетного метеорита без изменений.
                poseStack.scale(180.0f, 180.0f, 180.0f)
            }

            MeteorEntity.MeteorType.FRAGMENT,
            MeteorEntity.MeteorType.DECORATIVE_FRAGMENT -> {
                poseStack.scale(1.4f, 1.4f, 1.4f)
            }

            MeteorEntity.MeteorType.NORMAL -> {
                poseStack.scale(2.5f, 2.5f, 2.5f)
            }

            MeteorEntity.MeteorType.STAR,
            MeteorEntity.MeteorType.SKY_SHARD -> Unit // обработаны выше
        }

        // Обычные метеориты и осколки вращаются; большой сюжетный метеорит — нет.
        if (entity.meteorType != MeteorEntity.MeteorType.GIANT) {
            val rotation = (entity.tickCount + partialTicks) * 10.0f
            poseStack.mulPose(Axis.XP.rotationDegrees(rotation))
            poseStack.mulPose(Axis.YP.rotationDegrees(rotation * 0.5f))
        }

        poseStack.translate(-0.5, -0.5, -0.5)

        val texture = getTextureLocation(entity)

        val renderType = when (entity.meteorType) {
            MeteorEntity.MeteorType.GIANT,
            MeteorEntity.MeteorType.FRAGMENT,
            MeteorEntity.MeteorType.DECORATIVE_FRAGMENT -> {
                RenderType.entityTranslucentEmissive(texture)
            }

            else -> {
                RenderType.entityCutoutNoCull(texture)
            }
        }

        val builder = buffer.getBuffer(renderType)

        val meteorLight = when (entity.meteorType) {
            MeteorEntity.MeteorType.GIANT,
            MeteorEntity.MeteorType.FRAGMENT,
            MeteorEntity.MeteorType.DECORATIVE_FRAGMENT -> LightTexture.FULL_BRIGHT

            else -> packedLight
        }

        renderCube(poseStack, builder, meteorLight)
        poseStack.popPose()

        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight)
    }

    private fun renderSkyShard(
        entity: MeteorEntity,
        partialTicks: Float,
        poseStack: PoseStack,
        buffer: MultiBufferSource
    ) {
        poseStack.pushPose()
        poseStack.mulPose(this.entityRenderDispatcher.cameraOrientation())

        val direction = if (entity.id % 2 == 0) 1f else -1f
        val spin = (entity.tickCount + partialTicks) * 2.7f * direction
        poseStack.mulPose(Axis.ZP.rotationDegrees(spin))

        // Большая плоская часть неба — примерно 20–25 блоков по высоте.
        poseStack.scale(22.0f, 22.0f, 1.0f)
        val matrix = poseStack.last().pose()
        val builder = buffer.getBuffer(RenderType.lightning())

        val top = floatArrayOf(0f, 0.66f)
        val left = floatArrayOf(-0.62f, -0.43f)
        val right = floatArrayOf(0.63f, -0.47f)
        val innerTop = floatArrayOf(-0.015f, 0.47f)
        val innerLeft = floatArrayOf(-0.455f, -0.30f)
        val innerRight = floatArrayOf(0.46f, -0.33f)

        // Внешний фиолетовый край и тёмная поверхность с неровной формой.
        drawColorTriangle(builder, matrix, top, left, right, 112, 35, 255, 235, 0.0f)
        drawColorTriangle(builder, matrix, top, left, innerLeft, 150, 38, 255, 230, 0.01f)
        drawColorTriangle(builder, matrix, left, innerLeft, innerRight, 85, 15, 190, 235, 0.01f)
        drawColorTriangle(builder, matrix, left, innerRight, right, 125, 20, 245, 230, 0.01f)
        drawColorTriangle(builder, matrix, top, innerLeft, innerTop, 140, 44, 255, 230, 0.01f)
        drawColorTriangle(builder, matrix, top, innerTop, innerRight, 120, 24, 250, 225, 0.01f)
        drawColorTriangle(builder, matrix, innerTop, innerLeft, innerRight, 12, 2, 38, 245, 0.025f)

        // Звёзды — маленькие четырёхлучевые кристаллы на фиолетовой плоскости.
        drawShardStar(builder, matrix, -0.21f, 0.08f, 0.052f, 0.04f, 205, 190, 255, 245)
        drawShardStar(builder, matrix, 0.20f, 0.10f, 0.038f, 0.03f, 125, 205, 255, 235)
        drawShardStar(builder, matrix, -0.04f, -0.20f, 0.043f, 0.03f, 240, 225, 255, 245)
        drawShardStar(builder, matrix, 0.28f, -0.20f, 0.026f, 0.02f, 180, 95, 255, 225)

        // Маленькая планета с сияющим ободком. Цвет меняется у разных осколков.
        val planetColors = when (Math.floorMod(entity.id, 3)) {
            0 -> intArrayOf(70, 125, 255)
            1 -> intArrayOf(210, 65, 190)
            else -> intArrayOf(255, 125, 65)
        }
        drawShardPlanet(
            builder, matrix,
            0.11f, -0.08f,
            0.12f,
            planetColors[0], planetColors[1], planetColors[2]
        )

        // Тонкая светлая кромка вокруг треугольника подчёркивает, что это кусок оболочки.
        drawShardEdge(builder, matrix, top, left, 205, 105, 255, 245)
        drawShardEdge(builder, matrix, left, right, 170, 75, 255, 235)
        drawShardEdge(builder, matrix, right, top, 220, 135, 255, 245)

        poseStack.popPose()
    }

    private fun drawColorTriangle(
        builder: VertexConsumer,
        matrix: Matrix4f,
        a: FloatArray,
        b: FloatArray,
        c: FloatArray,
        red: Int,
        green: Int,
        blue: Int,
        alpha: Int,
        z: Float
    ) {
        // RenderType.lightning() uses QUADS: repeat C as the fourth vertex
        // so this quad rasterizes as one triangle plus one degenerate triangle.
        colorVertex(builder, matrix, a[0], a[1], z, red, green, blue, alpha)
        colorVertex(builder, matrix, b[0], b[1], z, red, green, blue, alpha)
        colorVertex(builder, matrix, c[0], c[1], z, red, green, blue, alpha)
        colorVertex(builder, matrix, c[0], c[1], z, red, green, blue, alpha)
    }

    private fun drawShardEdge(
        builder: VertexConsumer,
        matrix: Matrix4f,
        a: FloatArray,
        b: FloatArray,
        red: Int,
        green: Int,
        blue: Int,
        alpha: Int
    ) {
        val dx = b[0] - a[0]
        val dy = b[1] - a[1]
        val length = kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toFloat().coerceAtLeast(0.001f)
        val width = 0.018f
        val nx = -dy / length * width
        val ny = dx / length * width
        val a1 = floatArrayOf(a[0] + nx, a[1] + ny)
        val a2 = floatArrayOf(a[0] - nx, a[1] - ny)
        val b1 = floatArrayOf(b[0] + nx, b[1] + ny)
        val b2 = floatArrayOf(b[0] - nx, b[1] - ny)

        // One four-vertex quad for the glowing rim segment.
        colorVertex(builder, matrix, a1[0], a1[1], 0.04f, red, green, blue, alpha)
        colorVertex(builder, matrix, b1[0], b1[1], 0.04f, red, green, blue, alpha)
        colorVertex(builder, matrix, b2[0], b2[1], 0.04f, red, green, blue, alpha)
        colorVertex(builder, matrix, a2[0], a2[1], 0.04f, red, green, blue, alpha)
    }

    private fun drawShardStar(
        builder: VertexConsumer,
        matrix: Matrix4f,
        x: Float,
        y: Float,
        radiusX: Float,
        radiusY: Float,
        red: Int,
        green: Int,
        blue: Int,
        alpha: Int
    ) {
        val top = floatArrayOf(x, y + radiusY)
        val right = floatArrayOf(x + radiusX * 0.42f, y)
        val bottom = floatArrayOf(x, y - radiusY)
        val left = floatArrayOf(x - radiusX * 0.42f, y)
        val middle = floatArrayOf(x, y)

        drawColorTriangle(builder, matrix, middle, top, right, red, green, blue, alpha, 0.05f)
        drawColorTriangle(builder, matrix, middle, right, bottom, red, green, blue, alpha, 0.05f)
        drawColorTriangle(builder, matrix, middle, bottom, left, red, green, blue, alpha, 0.05f)
        drawColorTriangle(builder, matrix, middle, left, top, red, green, blue, alpha, 0.05f)
    }

    private fun drawShardPlanet(
        builder: VertexConsumer,
        matrix: Matrix4f,
        centerX: Float,
        centerY: Float,
        radius: Float,
        red: Int,
        green: Int,
        blue: Int
    ) {
        val segments = 12
        for (i in 0 until segments) {
            val a0 = Math.PI * 2.0 * i / segments
            val a1 = Math.PI * 2.0 * (i + 1) / segments
            val x0 = centerX + kotlin.math.cos(a0).toFloat() * radius
            val y0 = centerY + kotlin.math.sin(a0).toFloat() * radius
            val x1 = centerX + kotlin.math.cos(a1).toFloat() * radius
            val y1 = centerY + kotlin.math.sin(a1).toFloat() * radius
            colorVertex(builder, matrix, centerX, centerY, 0.055f, red / 3, green / 3, blue / 3, 220)
            colorVertex(builder, matrix, x0, y0, 0.055f, red, green, blue, 245)
            colorVertex(builder, matrix, x1, y1, 0.055f, red, green, blue, 245)
            colorVertex(builder, matrix, x1, y1, 0.055f, red, green, blue, 245)
        }
        // Highlight on the planet's upper-left edge.
        drawShardStar(builder, matrix, centerX - radius * 0.32f, centerY + radius * 0.32f,
            radius * 0.30f, radius * 0.25f, 220, 225, 255, 210)
    }

    private fun colorVertex(
        builder: VertexConsumer,
        matrix: Matrix4f,
        x: Float,
        y: Float,
        z: Float,
        red: Int,
        green: Int,
        blue: Int,
        alpha: Int
    ) {
        builder.addVertex(matrix, x, y, z).setColor(red, green, blue, alpha)
    }

    private fun renderStarBillboard(
        matrix: Matrix4f,
        builder: VertexConsumer,
        packedLight: Int
    ) {
        // Квадрат центрирован в точке сущности. UV-карта занимает всю текстуру.
        // NoCull позволяет видеть изображение с обеих сторон.
        vertex(matrix, builder, -0.5f, -0.5f, 0f, 0f, 1f, 0f, 0f, 1f, packedLight)
        vertex(matrix, builder, 0.5f, -0.5f, 0f, 1f, 1f, 0f, 0f, 1f, packedLight)
        vertex(matrix, builder, 0.5f, 0.5f, 0f, 1f, 0f, 0f, 0f, 1f, packedLight)
        vertex(matrix, builder, -0.5f, 0.5f, 0f, 0f, 0f, 0f, 0f, 1f, packedLight)
    }

    private fun renderCube(
        poseStack: PoseStack,
        builder: VertexConsumer,
        packedLight: Int
    ) {
        val matrix = poseStack.last().pose()

        // Юг
        vertex(matrix, builder, 0f, 0f, 1f, 0f, 1f, 0f, 0f, 1f, packedLight)
        vertex(matrix, builder, 1f, 0f, 1f, 1f, 1f, 0f, 0f, 1f, packedLight)
        vertex(matrix, builder, 1f, 1f, 1f, 1f, 0f, 0f, 0f, 1f, packedLight)
        vertex(matrix, builder, 0f, 1f, 1f, 0f, 0f, 0f, 0f, 1f, packedLight)

        // Север
        vertex(matrix, builder, 1f, 0f, 0f, 0f, 1f, 0f, 0f, -1f, packedLight)
        vertex(matrix, builder, 0f, 0f, 0f, 1f, 1f, 0f, 0f, -1f, packedLight)
        vertex(matrix, builder, 0f, 1f, 0f, 1f, 0f, 0f, 0f, -1f, packedLight)
        vertex(matrix, builder, 1f, 1f, 0f, 0f, 0f, 0f, 0f, -1f, packedLight)

        // Восток
        vertex(matrix, builder, 1f, 0f, 1f, 0f, 1f, 1f, 0f, 0f, packedLight)
        vertex(matrix, builder, 1f, 0f, 0f, 1f, 1f, 1f, 0f, 0f, packedLight)
        vertex(matrix, builder, 1f, 1f, 0f, 1f, 0f, 1f, 0f, 0f, packedLight)
        vertex(matrix, builder, 1f, 1f, 1f, 0f, 0f, 1f, 0f, 0f, packedLight)

        // Запад
        vertex(matrix, builder, 0f, 0f, 0f, 0f, 1f, -1f, 0f, 0f, packedLight)
        vertex(matrix, builder, 0f, 0f, 1f, 1f, 1f, -1f, 0f, 0f, packedLight)
        vertex(matrix, builder, 0f, 1f, 1f, 1f, 0f, -1f, 0f, 0f, packedLight)
        vertex(matrix, builder, 0f, 1f, 0f, 0f, 0f, -1f, 0f, 0f, packedLight)

        // Верх
        vertex(matrix, builder, 0f, 1f, 1f, 0f, 1f, 0f, 1f, 0f, packedLight)
        vertex(matrix, builder, 1f, 1f, 1f, 1f, 1f, 0f, 1f, 0f, packedLight)
        vertex(matrix, builder, 1f, 1f, 0f, 1f, 0f, 0f, 1f, 0f, packedLight)
        vertex(matrix, builder, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, packedLight)

        // Низ
        vertex(matrix, builder, 0f, 0f, 0f, 0f, 1f, 0f, -1f, 0f, packedLight)
        vertex(matrix, builder, 1f, 0f, 0f, 1f, 1f, 0f, -1f, 0f, packedLight)
        vertex(matrix, builder, 1f, 0f, 1f, 1f, 0f, 0f, -1f, 0f, packedLight)
        vertex(matrix, builder, 0f, 0f, 1f, 0f, 0f, 0f, -1f, 0f, packedLight)
    }

    private fun vertex(
        matrix: Matrix4f,
        builder: VertexConsumer,
        x: Float,
        y: Float,
        z: Float,
        u: Float,
        v: Float,
        nx: Float,
        ny: Float,
        nz: Float,
        light: Int
    ) {
        builder.addVertex(matrix, x, y, z)
            .setColor(255, 255, 255, 255)
            .setUv(u, v)
            .setOverlay(OverlayTexture.NO_OVERLAY)
            .setLight(light)
            .setNormal(nx, ny, nz)
    }

}
