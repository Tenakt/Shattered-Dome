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
        // Прямая ссылка на PNG файл текстуры
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
        poseStack.pushPose()

        // Масштабирование
        poseStack.scale(2.5f, 2.5f, 2.5f)

        // Вращение при падении
        val rotation = (entity.tickCount + partialTicks) * 12.0f
        poseStack.mulPose(Axis.XP.rotationDegrees(rotation))
        poseStack.mulPose(Axis.YP.rotationDegrees(rotation * 0.5f))

        poseStack.translate(-0.5, -0.5, -0.5)

        // Рендерим куб с прямой привязкой к PNG текстуре
        val builder = buffer.getBuffer(RenderType.entityCutoutNoCull(getTextureLocation(entity)))
        renderCube(poseStack, builder, packedLight)

        poseStack.popPose()
        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight)
    }

    private fun renderCube(poseStack: PoseStack, builder: VertexConsumer, packedLight: Int) {
        val matrix = poseStack.last().pose()

        // 1. Юг (South, Z = 1)
        vertex(matrix, builder, 0f, 0f, 1f, 0f, 1f, 0f, 0f, 1f, packedLight)
        vertex(matrix, builder, 1f, 0f, 1f, 1f, 1f, 0f, 0f, 1f, packedLight)
        vertex(matrix, builder, 1f, 1f, 1f, 1f, 0f, 0f, 0f, 1f, packedLight)
        vertex(matrix, builder, 0f, 1f, 1f, 0f, 0f, 0f, 0f, 1f, packedLight)

        // 2. Север (North, Z = 0)
        vertex(matrix, builder, 1f, 0f, 0f, 0f, 1f, 0f, 0f, -1f, packedLight)
        vertex(matrix, builder, 0f, 0f, 0f, 1f, 1f, 0f, 0f, -1f, packedLight)
        vertex(matrix, builder, 0f, 1f, 0f, 1f, 0f, 0f, 0f, -1f, packedLight)
        vertex(matrix, builder, 1f, 1f, 0f, 0f, 0f, 0f, 0f, -1f, packedLight)

        // 3. Восток (East, X = 1)
        vertex(matrix, builder, 1f, 0f, 1f, 0f, 1f, 1f, 0f, 0f, packedLight)
        vertex(matrix, builder, 1f, 0f, 0f, 1f, 1f, 1f, 0f, 0f, packedLight)
        vertex(matrix, builder, 1f, 1f, 0f, 1f, 0f, 1f, 0f, 0f, packedLight)
        vertex(matrix, builder, 1f, 1f, 1f, 0f, 0f, 1f, 0f, 0f, packedLight)

        // 4. Запад (West, X = 0)
        vertex(matrix, builder, 0f, 0f, 0f, 0f, 1f, -1f, 0f, 0f, packedLight)
        vertex(matrix, builder, 0f, 0f, 1f, 1f, 1f, -1f, 0f, 0f, packedLight)
        vertex(matrix, builder, 0f, 1f, 1f, 1f, 0f, -1f, 0f, 0f, packedLight)
        vertex(matrix, builder, 0f, 1f, 0f, 0f, 0f, -1f, 0f, 0f, packedLight)

        // 5. Верх (Up, Y = 1)
        vertex(matrix, builder, 0f, 1f, 1f, 0f, 1f, 0f, 1f, 0f, packedLight)
        vertex(matrix, builder, 1f, 1f, 1f, 1f, 1f, 0f, 1f, 0f, packedLight)
        vertex(matrix, builder, 1f, 1f, 0f, 1f, 0f, 0f, 1f, 0f, packedLight)
        vertex(matrix, builder, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, packedLight)

        // 6. Низ (Down, Y = 0)
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