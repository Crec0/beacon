package dev.crec.beacon

import com.mojang.logging.LogUtils
import dev.furq.holodisplays.api.HoloDisplaysAPI
import kotlinx.io.files.FileNotFoundException
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.Logger
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.notExists

object Beacon : ModInitializer {
    const val MOD_ID = "beacon"

    val configDir: Path = FabricLoader.getInstance().configDir.resolve(MOD_ID)
    val scannerPath: Path = configDir.resolve("mc-scanner-0.6.0.jar")
    val outputPath: Path = configDir.resolve("results.json")
    val gameRootDir: Path = FabricLoader.getInstance().gameDir
    val logger: Logger = LogUtils.getLogger()

    val holoApi: HoloDisplaysAPI = HoloDisplaysAPI.get(MOD_ID)

    override fun onInitialize() {
        configDir.createDirectories()
        if (scannerPath.notExists()) {
            throw FileNotFoundException("mc-scanner.jar not found in the $configDir")
        }

        CommandRegistrationCallback.EVENT.register { dispatcher, ctx, _ ->
            Command().register(dispatcher, ctx)
        }
    }
}
