package com.grindlesstudio.shattereddome.init

import com.grindlesstudio.shattereddome.ShatteredDome
import net.minecraft.core.registries.Registries
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockBehaviour
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister
import java.util.function.Supplier

object ModBlocks {
    val BLOCKS: DeferredRegister<Block> = DeferredRegister.create(Registries.BLOCK, ShatteredDome.MOD_ID)

    val METEOR_BLOCK: DeferredHolder<Block, Block> = BLOCKS.register("meteor", Supplier {
        Block(BlockBehaviour.Properties.of().noOcclusion())
    })
}