package com.grindlesstudio.shattereddome.client.renderer

import com.grindlesstudio.shattereddome.ShatteredDome
import com.grindlesstudio.shattereddome.entity.MeteorEntity
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.math.Axis
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.ResourceLocation

class MeteorRenderer(context: EntityRendererProvider.Context) : EntityRenderer<MeteorEntity>(context) {

    companion object {
        private val TEXTURE = ResourceLocation.fromNamespaceAndPath(ShatteredDome.MOD_ID, "textures/entity/meteor.png")
    }

    init {
        shadowRadius = 1.0f
    }

    override fun render(
        entity: MeteorEntity,
        entityYaw: Float,
        partialTicks: Float,
        poseStack: PoseStack,
        buffer: MultiBufferSource,
        packedLight: Int
    ) {
        if (entity.meteorType == MeteorEntity.MeteorType.STAR) {
            return
        }

        poseStack.pushPose()

        // Масштабирование: гигант (60x60 блоков) или обычный метеорит (2.5x)
        if (entity.meteorType == MeteorEntity.MeteorType.GIANT) {
            poseStack.scale(60.0f, 60.0f, 60.0f)
        } else {
            poseStack.scale(2.5f, 2.5f, 2.5f)
        }

        // Вращаем ТОЛЬКО обычные метеориты.
        // Гигантский метеорит сохраняет статичное монолитное положение.
        if (entity.meteorType != MeteorEntity.MeteorType.GIANT) {
            val rotation = (entity.tickCount + partialTicks) * 10.0f
            poseStack.mulPose(Axis.XP.rotationDegrees(rotation))
            poseStack.mulPose(Axis.YP.rotationDegrees(rotation * 0.5f))
        }

        poseStack.translate(-0.5, -0.5, -0.5)

        val builder = buffer.getBuffer(RenderType.entityCutoutNoCull(getTextureLocation(entity)))
        renderCube(poseStack, builder, packedLight)

        poseStack.popPose()
        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight)
    }

    private fun renderCube(poseStack: PoseStack, builder: VertexConsumer, packedLight: Int) {
        val matrix = poseStack.last().pose()

        // 1. Юг
        vertex(matrix, builder, 0f, 0f, 1f, 0f, 1f, 0f, 0f, 1f, packedLight)
        vertex(matrix, builder, 1f, 0f, 1f, 1f, 1f, 0f, 0f, 1f, packedLight)
        vertex(matrix, builder, 1f, 1f, 1f, 1f, 0f, 0f, 0f, 1f, packedLight)
        vertex(matrix, builder, 0f, 1f, 1f, 0f, 0f, 0f, 0f, 1f, packedLight)

        // 2. Север
        vertex(matrix, builder, 1f, 0f, 0f, 0f, 1f, 0f, 0f, -1f, packedLight)
        vertex(matrix, builder, 0f, 0f, 0f, 1f, 1f, 0f, 0f, -1f, packedLight)
        vertex(matrix, builder, 0f, 1f, 0f, 1f, 0f, 0f, 0f, -1f, packedLight)
        vertex(matrix, builder, 1f, 1f, 0f, 0f, 0f, 0f, 0f, -1f, packedLight)

        // 3. Восток
        vertex(matrix, builder, 1f, 0f, 1f, 0f, 1f, 1f, 0f, 0f, packedLight)
        vertex(matrix, builder, 1f, 0f, 0f, 1f, 1f, 1f, 0f, 0f, packedLight)
        vertex(matrix, builder, 1f, 1f, 0f, 1f, 0f, 1f, 0f, 0f, packedLight)
        vertex(matrix, builder, 1f, 1f, 1f, 0f, 0f, 1f, 0f, 0f, packedLight)

        // 4. Запад
        vertex(matrix, builder, 0f, 0f, 0f, 0f, 1f, -1f, 0f, 0f, packedLight)
        vertex(matrix, builder, 0f, 0f, 1f, 1f, 1f, -1f, 0f, 0f, packedLight)
        vertex(matrix, builder, 0f, 1f, 1f, 1f, 0f, -1f, 0f, 0f, packedLight)
        vertex(matrix, builder, 0f, 1f, 0f, 0f, 0f, -1f, 0f, 0f, packedLight)

        // 5. Верх
        vertex(matrix, builder, 0f, 1f, 1f, 0f, 1f, 0f, 1f, 0f, packedLight)
        vertex(matrix, builder, 1f, 1f, 1f, 1f, 1f, 0f, 1f, 0f, packedLight)
        vertex(matrix, builder, 1f, 1f, 0f, 1f, 0f, 0f, 1f, 0f, packedLight)
        vertex(matrix, builder, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, packedLight)

        // 6. Низ
        vertex(matrix, builder, 0f, 0f, 0f, 0f, 1f, 0f, -1f, 0f, packedLight)
        vertex(matrix, builder, 1f, 0f, 0f, 1f, 1f, 0f, -1f, 0f, packedLight)
        vertex(matrix, builder, 1f, 0f, 1f, 1f, 0f, 0f, -1f, 0f, packedLight)
        vertex(matrix, builder, 0f, 0f, 1f, 0f, 0f, 0f, -1f, 0f, packedLight)
    }

    private fun vertex(
        matrix: org.joml.Matrix4f,
        builder: VertexConsumer,
        x: Float, y: Float, z: Float,
        u: Float, v: Float,
        nx: Float, ny: Float, nz: Float,
        light: Int
    ) {
        builder.addVertex(matrix, x, y, z)
            .setColor(255, 255, 255, 255)
            .setUv(u, v)
            .setOverlay(OverlayTexture.NO_OVERLAY)
            .setLight(light)
            .setNormal(nx, ny, nz)
    }

    override fun getTextureLocation(entity: MeteorEntity): ResourceLocation {
        return TEXTURE
    }
}