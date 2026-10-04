package com.aurorafotos.presets


enum class StackMode { NONE, AVERAGE, ADD, LIGHTEN }

/**
 * Everything the capture engine needs to run a session unattended.
 *
 * exposureNs == MAX_EXPOSURE means "use the longest exposure the device exposes
 * to third-party apps" (about 3.9 s on the Galaxy S24 Ultra).
 */
data class Preset(
    val id: String,
    val name: String,
    val description: String,
    val iso: Int,
    val exposureNs: Long,
    /** Frames captured back-to-back for every shot (stacked when stackMode != NONE). */
    val framesPerShot: Int,
    val stackMode: StackMode,
    /** Delay between the start of consecutive shots. 0 = run shots back-to-back. */
    val intervalMs: Long,
    /** Total session length. 0 = run until the user stops. Ignored when totalShots > 0. */
    val durationMs: Long,
    /** Fixed number of shots. 0 = use durationMs. */
    val totalShots: Int,
    val saveRaw: Boolean,
    val saveJpeg: Boolean,
    val makeVideo: Boolean,
    val videoFps: Int,
    /** Lens focus distance in diopters: 0 = infinity. */
    val focusDiopters: Float,
    /** White balance in kelvin for the JPEGs (0 = auto). RAW is unaffected. */
    val wbKelvin: Int,
    /** Camera2 id. "0" is the main wide camera on Galaxy phones. */
    val cameraId: String,
    val countdownSec: Int,
) {
    val isTimelapse: Boolean get() = intervalMs > 0 || totalShots != 1

    /** Plain key=value lines; kept free of Android classes so it is unit-testable on the JVM. */
    fun serialize(): String = buildString {
        fun kv(k: String, v: Any) = append(k).append('=').append(v.toString().replace("\n", " ")).append('\n')
        kv("id", id); kv("name", name); kv("description", description)
        kv("iso", iso); kv("exposureNs", exposureNs); kv("framesPerShot", framesPerShot)
        kv("stackMode", stackMode.name); kv("intervalMs", intervalMs); kv("durationMs", durationMs)
        kv("totalShots", totalShots); kv("saveRaw", saveRaw); kv("saveJpeg", saveJpeg)
        kv("makeVideo", makeVideo); kv("videoFps", videoFps); kv("focusDiopters", focusDiopters)
        kv("wbKelvin", wbKelvin); kv("cameraId", cameraId); kv("countdownSec", countdownSec)
    }

    companion object {
        const val MAX_EXPOSURE = -1L
        const val SEC = 1_000_000_000L

        fun deserialize(text: String, fallback: Preset): Preset {
            val m = HashMap<String, String>()
            for (line in text.lineSequence()) {
                val i = line.indexOf('=')
                if (i > 0) m[line.substring(0, i)] = line.substring(i + 1)
            }
            fun str(k: String, d: String) = m[k] ?: d
            fun int(k: String, d: Int) = m[k]?.toIntOrNull() ?: d
            fun long(k: String, d: Long) = m[k]?.toLongOrNull() ?: d
            fun bool(k: String, d: Boolean) = m[k]?.toBooleanStrictOrNull() ?: d
            fun float(k: String, d: Float) = m[k]?.toFloatOrNull() ?: d
            return Preset(
                id = str("id", fallback.id),
                name = str("name", fallback.name),
                description = str("description", fallback.description),
                iso = int("iso", fallback.iso),
                exposureNs = long("exposureNs", fallback.exposureNs),
                framesPerShot = int("framesPerShot", fallback.framesPerShot),
                stackMode = m["stackMode"]?.let { v -> StackMode.values().firstOrNull { it.name == v } } ?: fallback.stackMode,
                intervalMs = long("intervalMs", fallback.intervalMs),
                durationMs = long("durationMs", fallback.durationMs),
                totalShots = int("totalShots", fallback.totalShots),
                saveRaw = bool("saveRaw", fallback.saveRaw),
                saveJpeg = bool("saveJpeg", fallback.saveJpeg),
                makeVideo = bool("makeVideo", fallback.makeVideo),
                videoFps = int("videoFps", fallback.videoFps),
                focusDiopters = float("focusDiopters", fallback.focusDiopters),
                wbKelvin = int("wbKelvin", fallback.wbKelvin),
                cameraId = str("cameraId", fallback.cameraId),
                countdownSec = int("countdownSec", fallback.countdownSec),
            )
        }
    }
}

object Presets {
    val AURORA_PHOTO = Preset(
        id = "aurora_photo",
        name = "Aurora · foto",
        description = "4 tomas de 3 s a ISO 1600 apiladas (promedio) en un DNG limpio + JPEG. Ideal para auroras que se mueven.",
        iso = 1600, exposureNs = 3 * Preset.SEC, framesPerShot = 4, stackMode = StackMode.AVERAGE,
        intervalMs = 0, durationMs = 0, totalShots = 1,
        saveRaw = true, saveJpeg = true, makeVideo = false, videoFps = 24,
        focusDiopters = 0f, wbKelvin = 3800, cameraId = "0", countdownSec = 3,
    )

    val AURORA_TIMELAPSE = Preset(
        id = "aurora_timelapse",
        name = "Aurora · timelapse",
        description = "Una toma de 2 s cada 3 s durante 30 min (600 frames). Monta un MP4 4K a 24 fps y guarda los JPEG.",
        iso = 1600, exposureNs = 2 * Preset.SEC, framesPerShot = 1, stackMode = StackMode.NONE,
        intervalMs = 3_000, durationMs = 30 * 60_000L, totalShots = 0,
        saveRaw = false, saveJpeg = true, makeVideo = true, videoFps = 24,
        focusDiopters = 0f, wbKelvin = 3800, cameraId = "0", countdownSec = 3,
    )

    val LONG_EXPOSURE = Preset(
        id = "long_exposure",
        name = "Exposición larga",
        description = "8 tomas a la exposición máxima que Samsung permite (~3.9 s) sumadas: equivale a ~31 s de obturación.",
        iso = 800, exposureNs = Preset.MAX_EXPOSURE, framesPerShot = 8, stackMode = StackMode.ADD,
        intervalMs = 0, durationMs = 0, totalShots = 1,
        saveRaw = true, saveJpeg = true, makeVideo = false, videoFps = 24,
        focusDiopters = 0f, wbKelvin = 4000, cameraId = "0", countdownSec = 3,
    )

    val LONG_EXPOSURE_TIMELAPSE = Preset(
        id = "long_exposure_timelapse",
        name = "Exposición larga · timelapse",
        description = "Cada 15 s, una exposición larga apilada (4 × ~3.9 s). 30 min → MP4 4K con movimiento suave de nubes/aurora.",
        iso = 800, exposureNs = Preset.MAX_EXPOSURE, framesPerShot = 4, stackMode = StackMode.ADD,
        intervalMs = 15_000, durationMs = 30 * 60_000L, totalShots = 0,
        saveRaw = false, saveJpeg = true, makeVideo = true, videoFps = 24,
        focusDiopters = 0f, wbKelvin = 4000, cameraId = "0", countdownSec = 3,
    )

    val MILKY_WAY = Preset(
        id = "milky_way",
        name = "Vía Láctea / estrellas",
        description = "16 tomas a ISO 3200 y exposición máxima, promediadas para bajar el ruido. DNG + JPEG.",
        iso = 3200, exposureNs = Preset.MAX_EXPOSURE, framesPerShot = 16, stackMode = StackMode.AVERAGE,
        intervalMs = 0, durationMs = 0, totalShots = 1,
        saveRaw = true, saveJpeg = true, makeVideo = false, videoFps = 24,
        focusDiopters = 0f, wbKelvin = 4000, cameraId = "0", countdownSec = 3,
    )

    val STAR_TRAILS = Preset(
        id = "star_trails",
        name = "Star trails",
        description = "Tomas continuas a exposición máxima durante 60 min, fusionadas con 'aclarar' (máximo). DNG + JPEG finales y MP4 con la estela creciendo.",
        iso = 800, exposureNs = Preset.MAX_EXPOSURE, framesPerShot = 1, stackMode = StackMode.LIGHTEN,
        intervalMs = 0, durationMs = 60 * 60_000L, totalShots = 0,
        saveRaw = true, saveJpeg = true, makeVideo = true, videoFps = 30,
        focusDiopters = 0f, wbKelvin = 4000, cameraId = "0", countdownSec = 3,
    )

    val ALL: List<Preset> = listOf(
        AURORA_PHOTO, AURORA_TIMELAPSE, LONG_EXPOSURE, LONG_EXPOSURE_TIMELAPSE, MILKY_WAY, STAR_TRAILS
    )

    fun byId(id: String): Preset = ALL.first { it.id == id }
}
