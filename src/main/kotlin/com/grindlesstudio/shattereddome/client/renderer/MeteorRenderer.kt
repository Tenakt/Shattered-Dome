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
        // Текстура по умолчанию для обычных метеоритов
        private val DEFAULT_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            ShatteredDome.MOD_ID,
            "textures/entity/meteor.png"
        )

        // Отдельная текстура для падающей звезды
        private val STAR_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            ShatteredDome.MOD_ID,
            "textures/entity/star.png"
        )
    }

    init {
        shadowRadius = 1.0f
    }

    /**
     * Выбирает нужный файл текстуры в зависимости от типа метеорита
     */
    override fun getTextureLocation(entity: MeteorEntity): ResourceLocation {
        return when (entity.meteorType) {
            MeteorEntity.MeteorType.STAR -> STAR_TEXTURE
            else -> DEFAULT_TEXTURE
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
        poseStack.pushPose()

        when (entity.meteorType) {
            MeteorEntity.MeteorType.GIANT -> {
                poseStack.scale(180.0f, 180.0f, 180.0f)
            }

            MeteorEntity.MeteorType.FRAGMENT,
            MeteorEntity.MeteorType.DECORATIVE_FRAGMENT -> {
                poseStack.scale(1.4f, 1.4f, 1.4f)
            }

            MeteorEntity.MeteorType.STAR -> {
                // Вытянутая форма падающей звезды
                poseStack.scale(0.35f, 0.35f, 2.5f)
            }

            MeteorEntity.MeteorType.NORMAL -> {
                poseStack.scale(2.5f, 2.5f, 2.5f)
            }
        }

        // Вращение модели во время полёта
        if (entity.meteorType != MeteorEntity.MeteorType.GIANT) {
            val rotation = (entity.tickCount + partialTicks) * 10.0f
            poseStack.mulPose(Axis.XP.rotationDegrees(rotation))
            poseStack.mulPose(Axis.YP.rotationDegrees(rotation * 0.5f))
        }

        poseStack.translate(-0.5, -0.5, -0.5)

        // Звезда подсвечивается на полную яркость даже ночью
        val renderLight = if (entity.meteorType == MeteorEntity.MeteorType.STAR) {
            LightTexture.FULL_BRIGHT
        } else {
            packedLight
        }

        // getTextureLocation(entity) автоматически подставит нужную текстуру
        val builder = buffer.getBuffer(
            RenderType.entityCutoutNoCull(getTextureLocation(entity))
        )

        renderCube(poseStack, builder, renderLight)

        poseStack.popPose()

        super.render(
            entity,
            entityYaw,
            partialTicks,
            poseStack,
            buffer,
            packedLight
        )
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