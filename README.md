# Aurora Fotos

App Android para **Galaxy S24 Ultra** (y cualquier Galaxy reciente) que resuelve una sola cosa:
elegir **qué vas a fotografiar de noche**, dejar el teléfono en el trípode y que la app haga el
resto (ISO, obturación, enfoque a infinito, balance de blancos, intervalos, apilado y video).

Presets incluidos:

| Preset | Qué hace | Salida |
|---|---|---|
| Aurora · foto | frames sumados a ISO 1600 hasta 8 s equivalentes | DNG 16 bit + JPEG |
| Aurora · timelapse | cada 4 s una toma de 2 s equivalentes, 30 min | JPEG por toma + MP4 4K 24 fps |
| Aurora · video 24 fps | video real 4K: 1/24 s, ISO 3200, WB fijo, foco ∞ (lo que se configura a mano en Pro Video) | MP4 4K HEVC |
| Exposición larga | frames sumados a ISO 800 hasta 30 s equivalentes | DNG 16 bit + JPEG |
| Exposición larga · timelapse | 10 s equivalentes cada 20 s durante 30 min | JPEG + MP4 4K |
| Vía Láctea / estrellas | frames sumados a ISO 3200 hasta 30 s equivalentes | DNG 16 bit + JPEG |
| Star trails | tomas de 4 s equivalentes durante 60 min, fusión "aclarar" | DNG + JPEG finales + MP4 1080p con la estela creciendo |

Todo es editable en **Ajustes del preset** (ISO, exposición, frames, modo de apilado, intervalo,
duración, lente, enfoque, WB, fps…). Los archivos se guardan en `DCIM/AuroraFotos/<sesión>/`
junto con un `_info.txt` con los parámetros reales aplicados.

## Por qué suma frames cortos en vez de hacer una toma de 30 s

Samsung solo deja a las apps de terceros exposiciones muy cortas por Camera2: en el S24 Ultra
probado, **1/9 s (111 ms) por frame** (Expert RAW y el modo Pro tienen acceso privilegiado y
llegan a 30 s). Por eso la app captura ráfagas RAW encadenadas y las **suma** en el propio teléfono,
en el dominio Bayer con acumuladores de 32 bits: fotón a fotón, 270 frames de 1/9 s son una
exposición de 30 s. Cada preset define una **exposición total objetivo** y la app calcula en el
momento cuántos frames necesita con la exposición que el sensor aplica de verdad.

El resultado se guarda como **DNG lineal de 16 bits** con sus propios niveles y
`BaselineExposure = log2(frames)`, así que Lightroom / Camera Raw lo abren como la exposición larga
que es, con las altas luces recuperables. El JPEG y los frames del video se generan en la app con un
demosaico propio (balance de blancos y matriz de color del sensor, ganancia automática, gamma sRGB).

Modos de apilado: **ADD** (suma = exposición larga, recomendado), **AVERAGE** (mismos datos, se ve con
el brillo de un frame), **LIGHTEN** (cada toma se suma y las tomas se fusionan con el máximo: star trails),
**NONE** (cada frame suelto).

Un ingeniero de Samsung ha dicho que *pedir* una exposición mayor a la declarada a veces funciona. El
Diagnóstico incluye **Probar exposición forzada**: pide 0.5 → 30 s y reporta qué aplicó el sensor. Si lo
respeta, activa "Forzar exposición" en los presets.

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
  stacking/   RawStacker (sumas Bayer 32 bit), DngWriter + DngPatcher (DNG 16 bit), RawDemosaic/RawRender
  video/      TimelapseEncoder (frames → MP4), VideoEncoder (grabación en tiempo real)
  storage/    MediaSaver (MediaStore → DCIM/AuroraFotos)
  ui/         MainActivity, CaptureActivity (encuadre + progreso), SettingsActivity
  diag/       DiagnosticsActivity
```

## Fase B (pendiente, opcional)

Orquestar Expert RAW / modo Pro de Samsung con un AccessibilityService para los casos donde
hacen falta los 30 s reales o el modo Astrophoto de 6–12 min. Solo si en la práctica los 3.9 s
apilados se quedan cortos.
