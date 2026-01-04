package dev.crec.beacon.utils

import dev.crec.beacon.mixin.MinecraftServerAccessor
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.Level
import java.nio.file.Path

fun MinecraftServer.getDimensionPath(dimension: ResourceKey<Level>): Path {
    return (this as MinecraftServerAccessor).storageSource.getDimensionPath(dimension)
}