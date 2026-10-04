# Aurora Fotos

App Android para **Galaxy S24 Ultra** (y cualquier Galaxy reciente) que resuelve una sola cosa:
elegir **qué vas a fotografiar de noche**, dejar el teléfono en el trípode y que la app haga el
resto (ISO, obturación, enfoque a infinito, balance de blancos, intervalos, apilado y video).

Presets incluidos:

| Preset | Qué hace | Salida |
|---|---|---|
| Aurora · foto | 4 × 3 s ISO 1600 apiladas (promedio) | DNG + JPEG |
| Aurora · timelapse | 1 toma de 2 s cada 3 s durante 30 min | JPEG por frame + MP4 4K 24 fps |
| Exposición larga | 8 × exposición máxima sumadas (≈ 31 s) | DNG + JPEG |
| Exposición larga · timelapse | 4 × máx cada 15 s durante 30 min | JPEG + MP4 |
| Vía Láctea / estrellas | 16 × máx ISO 3200 promediadas | DNG + JPEG |
| Star trails | continuo 60 min, fusión "aclarar" | DNG + JPEG finales + MP4 con la estela creciendo |

Todo es editable en **Ajustes del preset** (ISO, exposición, frames, modo de apilado, intervalo,
duración, lente, enfoque, WB, fps…). Los archivos se guardan en `DCIM/AuroraFotos/<sesión>/`
junto con un `_info.txt` con los parámetros reales aplicados.

## Por qué apila tomas cortas en vez de una de 30 s

Samsung solo deja a las apps de terceros usar exposiciones de hasta **~3.9 s** por Camera2
(Expert RAW y el modo Pro de Samsung tienen acceso privilegiado y llegan a 30 s). Por eso la app
captura ráfagas de tomas cortas y las fusiona en el propio teléfono, en el dominio RAW (Bayer, 16 bit):

- **AVERAGE**: promedio → menos ruido, mismo brillo (auroras, Vía Láctea).
- **ADD**: suma de la señal sobre el nivel de negro, recortada al nivel de blanco → brillo de una exposición N veces más larga.
- **LIGHTEN**: máximo por píxel acumulado en toda la sesión → star trails.

Para auroras, tomas de 1–4 s son de hecho mejores que una de 30 s (no se emborrona el movimiento).
Ver [docs/estado-del-arte.md](docs/estado-del-arte.md) para la investigación completa.

## Instalar

1. Abre la página de **Releases** del repo y descarga `AuroraFotos-debug.apk` del pre-release
   `latest-debug` (lo genera GitHub Actions en cada push). También está como artifact del workflow.
2. En el teléfono, permite instalar desde esa fuente e instala el APK.
3. Concede permisos de **cámara** y **notificaciones**.

## Primer uso: Diagnóstico

Antes de salir de noche pulsa **Diagnóstico de cámara**. Muestra lo que Samsung expone a terceros
en *tu* firmware (rango de exposición, ISO, RAW, lentes, extensiones) y permite capturar un DNG de
prueba a exposición máxima. Abre ese `test.dng` en Lightroom/Galería: si sale rayado o con bandas,
tu firmware tiene el bug de RAW de terceros del S24 Ultra y hay que desmarcar "Guardar DNG" en los
presets hasta que lo actualices.

## Uso

1. Elige el preset y pulsa **Encuadrar y empezar**: la vista previa fuerza ISO alto y 1/4 s para que
   veas estrellas/aurora y compruebes el enfoque.
2. Pulsa **Iniciar**. Cuenta atrás de 3 s, luego la captura corre en un servicio en primer plano:
   puedes apagar la pantalla; se para desde la app o desde la notificación.
3. Al terminar se apilan las tomas, se monta el MP4 (si procede) y verás la carpeta de salida.

## Desarrollo

- Kotlin, Camera2 directo, sin AndroidX (solo framework + coroutines) para poder compilarse contra
  un `android.jar` pelado. minSdk 34, compileSdk 35.
- `./gradlew assembleDebug` (necesita el Android SDK). `./gradlew testDebugUnitTest` para los tests.
- `tools/local-check.sh` compila y testea sin AGP (kotlinc + aapt2) para entornos sin acceso al SDK.

Estructura:

```
app/src/main/java/com/aurorafotos/
  camera/     CameraInfo (características), CameraController (captura manual RAW+JPEG)
  capture/    CaptureEngine (sesión: cuenta atrás, tomas, apilado, video), CaptureService (FGS cámara)
  presets/    Preset, Presets (los 6), PresetStore (overrides del usuario)
  stacking/   RawStacker (Bayer 16 bit), BitmapStacker (JPEG), DngWriter
  video/      TimelapseEncoder (MediaCodec HEVC/AVC + MediaMuxer, 4K)
  storage/    MediaSaver (MediaStore → DCIM/AuroraFotos)
  ui/         MainActivity, CaptureActivity (encuadre + progreso), SettingsActivity
  diag/       DiagnosticsActivity
```

## Fase B (pendiente, opcional)

Orquestar Expert RAW / modo Pro de Samsung con un AccessibilityService para los casos donde
hacen falta los 30 s reales o el modo Astrophoto de 6–12 min. Solo si en la práctica los 3.9 s
apilados se quedan cortos.
