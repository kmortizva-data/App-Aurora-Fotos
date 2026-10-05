package com.aurorafotos.presets


enum class StackMode { NONE, AVERAGE, ADD, LIGHTEN }

enum class PresetKind { PHOTO, VIDEO }

/** How presets are grouped in the UI. */
enum class Module(val title: String) { PHOTO("Fotos"), TIMELAPSE("Timelapse"), VIDEO("Video") }

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
    /**
     * Target total exposure per shot. When > 0, the number of frames is derived at runtime
     * from the exposure the device really allows (ceil(total / perFrame)), so a "30 s" shot
     * stays 30 s whether Samsung caps third-party apps at 3.9 s or at 0.5 s. 0 = use
     * [framesPerShot] as given.
     */
    val totalExposureNs: Long = 0,
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
    /**
     * Request [exposureNs] even if it is above what the device declares for third-party apps.
     * Samsung's HAL sometimes honours it; the real value is read back and used for the maths.
     */
    val forceExposure: Boolean = false,
    /** Render video frames at half resolution (4× faster; 1080p output). */
    val videoHalfRes: Boolean = false,
    /** PHOTO = stacked stills / timelapse; VIDEO = real-time manual recording at [videoFps]. */
    val kind: PresetKind = PresetKind.PHOTO,
) {
    val isVideo: Boolean get() = kind == PresetKind.VIDEO

    /** Fotos = una toma apilada; Timelapse = secuencias y star trails; Video = grabación real. */
    val module: Module
        get() = when {
            kind == PresetKind.VIDEO -> Module.VIDEO
            makeVideo || intervalMs > 0 || totalShots != 1 -> Module.TIMELAPSE
            else -> Module.PHOTO
        }

    val isTimelapse: Boolean get() = intervalMs > 0 || totalShots != 1

    /** Frames to capture per shot given the per-frame exposure the device actually applies. */
    fun effectiveFrames(actualExposureNs: Long): Int {
        if (totalExposureNs <= 0 || actualExposureNs <= 0) return framesPerShot.coerceIn(1, MAX_FRAMES)
        val n = (totalExposureNs + actualExposureNs - 1) / actualExposureNs
        return n.toInt().coerceIn(1, MAX_FRAMES)
    }

    /** Plain key=value lines; kept free of Android classes so it is unit-testable on the JVM. */
    fun serialize(): String = buildString {
        fun kv(k: String, v: Any) = append(k).append('=').append(v.toString().replace("\n", " ")).append('\n')
        kv("id", id); kv("name", name); kv("description", description)
        kv("iso", iso); kv("exposureNs", exposureNs); kv("framesPerShot", framesPerShot)
        kv("totalExposureNs", totalExposureNs)
        kv("stackMode", stackMode.name); kv("intervalMs", intervalMs); kv("durationMs", durationMs)
        kv("totalShots", totalShots); kv("saveRaw", saveRaw); kv("saveJpeg", saveJpeg)
        kv("makeVideo", makeVideo); kv("videoFps", videoFps); kv("focusDiopters", focusDiopters)
        kv("wbKelvin", wbKelvin); kv("cameraId", cameraId); kv("countdownSec", countdownSec)
        kv("forceExposure", forceExposure); kv("videoHalfRes", videoHalfRes); kv("kind", kind.name)
    }

    companion object {
        const val MAX_EXPOSURE = -1L
        const val SEC = 1_000_000_000L
        /** Upper bound for a per-shot stack (sanity limit; sums are 32-bit so this is generous). */
        const val MAX_FRAMES = 4000

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
                totalExposureNs = long("totalExposureNs", fallback.totalExposureNs),
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
                forceExposure = bool("forceExposure", fallback.forceExposure),
                videoHalfRes = bool("videoHalfRes", fallback.videoHalfRes),
                kind = m["kind"]?.let { v -> PresetKind.values().firstOrNull { it.name == v } } ?: fallback.kind,
            )
        }
    }
}

object Presets {
    val AURORA_PHOTO = Preset(
        id = "aurora_photo",
        name = "Aurora · foto",
        description = "Suma frames a ISO 1600 hasta 8 s de exposición equivalente. DNG de 16 bits (Lightroom lo ve como una toma de 8 s) + JPEG.",
        iso = 1600, exposureNs = Preset.MAX_EXPOSURE, framesPerShot = 72, totalExposureNs = 8 * Preset.SEC, stackMode = StackMode.ADD,
        intervalMs = 0, durationMs = 0, totalShots = 1,
        saveRaw = true, saveJpeg = true, makeVideo = false, videoFps = 24,
        focusDiopters = 0f, wbKelvin = 3800, cameraId = "0", countdownSec = 3,
    )

    val AURORA_TIMELAPSE = Preset(
        id = "aurora_timelapse",
        name = "Aurora · timelapse",
        description = "Cada 4 s, una toma de 2 s equivalentes (frames sumados) a ISO 1600 durante 30 min. MP4 4K a 24 fps + JPEG por toma.",
        iso = 1600, exposureNs = Preset.MAX_EXPOSURE, framesPerShot = 18, totalExposureNs = 2 * Preset.SEC, stackMode = StackMode.ADD,
        intervalMs = 4_000, durationMs = 30 * 60_000L, totalShots = 0,
        saveRaw = false, saveJpeg = true, makeVideo = true, videoFps = 24,
        focusDiopters = 0f, wbKelvin = 3800, cameraId = "0", countdownSec = 3,
    )

    val LONG_EXPOSURE = Preset(
        id = "long_exposure",
        name = "Exposición larga",
        description = "Suma frames a ISO 800 hasta 30 s de exposición equivalente: agua sedosa, estelas de luz, nubes. DNG 16 bits + JPEG.",
        iso = 800, exposureNs = Preset.MAX_EXPOSURE, framesPerShot = 270, totalExposureNs = 30 * Preset.SEC, stackMode = StackMode.ADD,
        intervalMs = 0, durationMs = 0, totalShots = 1,
        saveRaw = true, saveJpeg = true, makeVideo = false, videoFps = 24,
        focusDiopters = 0f, wbKelvin = 4000, cameraId = "0", countdownSec = 3,
    )

    val LONG_EXPOSURE_TIMELAPSE = Preset(
        id = "long_exposure_timelapse",
        name = "Exposición larga · timelapse",
        description = "Cada 20 s, una exposición equivalente a 10 s. 30 min → MP4 4K con movimiento suave de nubes/aurora.",
        iso = 800, exposureNs = Preset.MAX_EXPOSURE, framesPerShot = 90, totalExposureNs = 10 * Preset.SEC, stackMode = StackMode.ADD,
        intervalMs = 20_000, durationMs = 30 * 60_000L, totalShots = 0,
        saveRaw = false, saveJpeg = true, makeVideo = true, videoFps = 24,
        focusDiopters = 0f, wbKelvin = 4000, cameraId = "0", countdownSec = 3,
    )

    val MILKY_WAY = Preset(
        id = "milky_way",
        name = "Vía Láctea / estrellas",
        description = "Suma frames a ISO 3200 hasta 30 s equivalentes. Sin alineación: las estrellas se mueven poco en 30 s con el gran angular. DNG + JPEG.",
        iso = 3200, exposureNs = Preset.MAX_EXPOSURE, framesPerShot = 270, totalExposureNs = 30 * Preset.SEC, stackMode = StackMode.ADD,
        intervalMs = 0, durationMs = 0, totalShots = 1,
        saveRaw = true, saveJpeg = true, makeVideo = false, videoFps = 24,
        focusDiopters = 0f, wbKelvin = 4000, cameraId = "0", countdownSec = 3,
    )

    val STAR_TRAILS = Preset(
        id = "star_trails",
        name = "Star trails",
        description = "Tomas continuas de 4 s equivalentes a ISO 800 durante 60 min, fusionadas con 'aclarar'. DNG + JPEG finales y MP4 1080p con la estela creciendo.",
        iso = 800, exposureNs = Preset.MAX_EXPOSURE, framesPerShot = 36, totalExposureNs = 4 * Preset.SEC, stackMode = StackMode.LIGHTEN,
        intervalMs = 0, durationMs = 60 * 60_000L, totalShots = 0,
        saveRaw = true, saveJpeg = true, makeVideo = true, videoFps = 30,
        focusDiopters = 0f, wbKelvin = 4000, cameraId = "0", countdownSec = 3,
        videoHalfRes = true,
    )

    val AURORA_VIDEO = Preset(
        id = "aurora_video",
        name = "Aurora · video 24 fps",
        description = "Video 4K real a 24 fps con los parámetros manuales del Pro Video: 1/24 s, ISO 3200, WB fijo, foco a infinito. 10 min o hasta detener.",
        iso = 3200, exposureNs = 41_666_667L, framesPerShot = 1, totalExposureNs = 0, stackMode = StackMode.NONE,
        intervalMs = 0, durationMs = 10 * 60_000L, totalShots = 0,
        saveRaw = false, saveJpeg = false, makeVideo = true, videoFps = 24,
        focusDiopters = 0f, wbKelvin = 3800, cameraId = "0", countdownSec = 3,
        kind = PresetKind.VIDEO,
    )

    val AURORA_VIDEO_SLOW = Preset(
        id = "aurora_video_slow",
        name = "Aurora · video lento 9 fps",
        description = "Obturador lento: 1/9 s por frame (el máximo que deja Samsung), 9 fps reales, 2.7× más luz que a 24 fps. Ideal para auroras tenues; se reproduce a 9 fps.",
        iso = 3200, exposureNs = Preset.MAX_EXPOSURE, framesPerShot = 1, totalExposureNs = 0, stackMode = StackMode.NONE,
        intervalMs = 0, durationMs = 10 * 60_000L, totalShots = 0,
        saveRaw = false, saveJpeg = false, makeVideo = true, videoFps = 9,
        focusDiopters = 0f, wbKelvin = 3800, cameraId = "0", countdownSec = 3,
        kind = PresetKind.VIDEO,
    )

    val ALL: List<Preset> = listOf(
        AURORA_PHOTO, AURORA_TIMELAPSE, AURORA_VIDEO, AURORA_VIDEO_SLOW, LONG_EXPOSURE, LONG_EXPOSURE_TIMELAPSE, MILKY_WAY, STAR_TRAILS
    )

    fun byId(id: String): Preset = ALL.first { it.id == id }
}
