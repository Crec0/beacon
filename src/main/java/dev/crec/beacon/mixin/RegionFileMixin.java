package dev.crec.beacon.mixin;

import net.minecraft.world.level.chunk.storage.RegionFile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.nio.file.CopyOption;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

@Mixin(RegionFile.class)
public class RegionFileMixin {
    @ModifyArg(
        method = "method_22411(Ljava/nio/file/Path;Ljava/nio/file/Path;)V",
        at = @At(value = "INVOKE", target = "Ljava/nio/file/Files;move(Ljava/nio/file/Path;Ljava/nio/file/Path;[Ljava/nio/file/CopyOption;)Ljava/nio/file/Path;"),
        index = 2
    )
    private static CopyOption[] disableSaving20Chunks(CopyOption[] options) {
        var arr = Arrays.asList(options);
        arr.add(StandardCopyOption.ATOMIC_MOVE);
        return arr.toArray(CopyOption[]::new);
    }
}
