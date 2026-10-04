# Estado del arte: fotografía nocturna / auroras con el Galaxy S24 Ultra desde una app propia

Investigación realizada en octubre de 2026 para decidir cómo construir Aurora Fotos.

## 1. Lo que Samsung ya ofrece (acceso privilegiado, solo sus apps)

| Función | Dónde | Límites / notas |
|---|---|---|
| Exposición manual hasta **30 s**, ISO, enfoque manual, WB | Pro mode y Expert RAW | Solo apps de Samsung. Terceros no llegan a 30 s. |
| **Astrophoto** (apila exposiciones durante 3 / 6 / 12 min, guía de constelaciones) | Expert RAW (icono de constelación) | Se instala desde Galaxy Store. "Disparar y esperar". |
| **Multi-exposure** continuo: 2–9 tomas a intervalo fijo; mezcla Add / Average / Bright / Dark | Expert RAW | Máximo 9 × 30 s ≈ 270 s. Bright = star trails. |
| **Night Hyperlapse** con **Star Trails**, 300x, timer infinito | Camera → More → Hyperlapse | Lo que la comunidad usa para timelapse de auroras. Sin control manual fino. |
| **Timer multi-photo** (intervalo + cantidad) | Camera Assistant (Galaxy Store) | Intervalómetro básico de la app nativa. |
| DNG 16 bit multi-frame 12/24/50 MP, ND virtual, AstroPortrait | Expert RAW v5.x (ago-2025) | Sin API pública ni intents documentados. |

Fuentes: [Expert RAW astro/multi-exposure (Android Authority)](https://www.androidauthority.com/expert-raw-astrophotography-3224913/) ·
[CamCyclopedia Multi Exposure](https://r2.community.samsung.com/t5/CamCyclopedia/Multiple-Exposure/ba-p/13245898) ·
[Ultra-long exposure con multi-exposure](https://r1.community.samsung.com/t5/galaxy-gallery/expert-raw-ultra-long-exposure-photography/td-p/23202418) ·
[Camera Assistant S24U](https://www.samsung.com/uk/support/mobile-devices/how-to-use-camera-assistant-on-your-galaxy-s24-ultra/) ·
[Astrophoto how-to](https://www.androidcentral.com/phones/how-to-use-astrophoto-mode-on-a-samsung-galaxy-phone) ·
[Expert RAW v5.0.02.8](https://www.sammyfans.com/2025/08/18/samsung-has-a-fresh-expert-raw-version-for-galaxy-phones/)

## 2. Lo que una app de terceros puede hacer (Camera2 API)

- **Exposición máxima ≈ 3.9 s** (rango 1/18367 – 3.9 s según la base de datos de Camera FV-5). Samsung limita
  a propósito el rango expuesto; en modelos previos era peor (S22U: 103 ms; A52s: 0.5 s). No hay SDK de cámara
  de Samsung vigente para saltárselo.
- **RAW de terceros en la cámara principal**: al lanzamiento salía "scrambled" en todas las apps (GCam, Lightroom,
  Open Camera, MotionCam, FV-5). Se corrigió durante 2024 (actualizaciones de Expert RAW/firmware; Lightroom publicó
  soporte oficial). Hay que verificarlo en cada firmware: la app incluye un diagnóstico con DNG de prueba.
- Resolución expuesta: 12 MP (binned), no 200 MP.
- Crash reportado al abrir cámara lógica "0" con física "6" en S24U (API de cámaras físicas inestable → la app no
  usa IDs físicos).
- **Camera Extensions NIGHT** disponible para terceros en S24U, pero sin control manual de ISO/obturación.
- RAW en streaming funciona: MotionCam Pro graba RAW a sensor completo 60 fps en las 4 lentes.

Fuentes: [Camera FV-5 ficha S24U](https://www.camerafv5.com/devices/manufacturers/samsung/sm-s9280_e3q_0/) ·
[Samsung dev forum: long exposure](https://forum.developer.samsung.com/t/camera-long-exposure-time/3227) ·
[Samsung dev forum: limitaciones terceros](https://forum.developer.samsung.com/t/camera-apps-suggestion-to-samsung-developers/19213) ·
[Open Camera: max exposure en Samsung](https://sourceforge.net/p/opencamera/discussion/general/thread/215b71d5d4/) ·
[RAW scrambled S24U (XDA)](https://xdaforums.com/t/gcam-for-s24-ultra.4652876/page-4) ·
[Adobe: DNG corruptos S24U](https://community.adobe.com/t5/lightroom-ecosystem-cloud-based-discussions/corrupted-dng-files-on-samsung-galaxy-s24-ultra/td-p/14378414) ·
[Crash physical camera id](https://forum.developer.samsung.com/t/crash-using-camera2-and-physical-camera-id/34955) ·
[CameraX Night en S24U](https://android-developers.googleblog.com/2024/12/instagram-on-android-low-light-photos.html) ·
[MotionCam en S24U](https://ymcinema.com/2024/07/09/meet-motioncam-real-raw-video-capture-from-android-phones/)

## 3. Quién ha resuelto (parcialmente) el problema

| Proyecto | Qué hace | Lección |
|---|---|---|
| GCam LMC 8.4 port (BSG / MGC) para S24U | Modo Astro: apila muchas exposiciones cortas hasta que paras. | Confirma que "muchas tomas cortas + apilado" es la vía para terceros. Cerrado, configs XML frágiles. |
| DeepSkyCamera / DSC Pro | Camera2, secuencias RAW (lights/darks/flats), live stacking alineado. | Referencia de app nativa de astro. Cerrado. |
| Intervalometer for TimeLapse | AccessibilityService que pulsa el obturador de cualquier app (incl. Samsung Pro/Expert RAW). | Precedente directo de "orquestar la app de Samsung desde fuera". |
| MacroDroid "UI Interaction" | Clics automatizados en otras apps. | Misma técnica sin código. |
| Open Camera (open source) | Camera2 manual, DNG, repeat mode, NR por apilado de hasta 8 frames. | Código reutilizable para pipeline Camera2 + DNG. |
| MotionCam Pro | RAW video + photo burst para stacking. | Lectura RAW de terceros viable y rápida en S24U. |
| Burst Photo (macOS, open source) | Apila ráfagas DNG estilo Night Sight. | Referencia de merge en RAW. |
| Camera FV-5 / ProShot / ProCam X | Pro apps genéricas. | Todas topan con 3.9 s. No dan "un toque y listo". |
| NightCap (iOS) | "Selecciona escena y dispara". | Es la UX buscada; no existía en Android para Samsung. |

Fuentes: [GCam-Ports](https://github.com/Gcam-Ports) · [GCam S24U XDA](https://xdaforums.com/t/gcam-for-s24-ultra.4652876/) ·
[DeepSkyCamera Pro](https://play.google.com/store/apps/details?id=de.seebi.deepskycamera.pro) ·
[Intervalometer for TimeLapse](https://play.google.com/store/apps/details?id=com.mobilephoton.intervalometer) ·
[MacroDroid UI Interaction](https://www.macrodroidforum.com/wiki/index.php/Action:_UI_Interaction) ·
[Open Camera](https://opencamera.org.uk/) · [android/camera-samples](https://github.com/android/camera-samples) ·
[CustomCamera](https://github.com/tribixbite/CustomCamera) · [lcamera](https://github.com/PkmX/lcamera) ·
[Burst Photo](https://petapixel.com/2022/10/10/burst-photo-app-gives-any-photo-the-night-mode-treatment/) ·
[NightCap](https://www.nightcapcamera.com/nightcap-camera/)

## 4. Recetas de la comunidad para el S24 Ultra (base de los presets)

- **Aurora foto** (Expert RAW / Pro): ISO 800–3200, 5–15 s según intensidad (auroras rápidas: 1–4 s), enfoque a
  infinito, WB 3500–4000 K, lente 1x, 12 MP, timer 2 s, RAW.
- **Vía Láctea**: 30 s máx (menos si hay trails), ISO 1600, o Astrophoto 4–6 min.
- **Aurora video**: 4K 24 fps, ISO 800–1600 (hasta 3200), 1/24–1/30 s.
- **Aurora timelapse**: Hyperlapse → Night lapse 300x, 1 h; o fotos cada 2–5 s y montar después.
- **Star trails**: Hyperlapse Night con "Star trails", o Expert RAW multi-exposure Bright.

Fuentes: [Guía aurora S24U Expert RAW (MM0ZIF)](https://mm0zif.radio/current/2024/10/guide-to-photographing-the-aurora-with-a-samsung-galaxy-s24-ultra-using-expert-raw/) ·
[Skies & Scopes Expert RAW](https://skiesandscopes.com/samsung-expert-raw-astrophotography/) ·
[camerasettings.com S24U](https://camerasettings.com/guides/phone/samsung-galaxy-s24-ultra/) ·
[Bunker Hill Media: auroras en Samsung](https://www.bunkerhillmedia.com/blog/how-to-film-the-northern-lights-on-a-samsung-phone) ·
[Samsung Members: aurora settings](https://r1.community.samsung.com/t5/galaxy-s/aurora-settings/td-p/29734733)

## 5. Conclusión y decisión

Nadie había hecho "la app de un toque para auroras en Samsung". Dos caminos:

- **A. App nativa (Camera2)** con presets, intervalómetro y apilado en el teléfono. Control total y robusta;
  tope de 3.9 s por toma (compensado apilando); depende de que el RAW de terceros esté arreglado en el firmware.
- **B. App orquestadora** que maneja Expert RAW / Pro con un AccessibilityService. Aprovecha 30 s y Astrophoto, pero
  es frágil ante cada update de One UI.

Decisión: **híbrido empezando por A** (esta app). B queda como fase opcional si los 3.9 s apilados se quedan cortos.
Primer hito: la pantalla de diagnóstico, para confirmar en el teléfono real el rango de exposición y el estado del RAW.
