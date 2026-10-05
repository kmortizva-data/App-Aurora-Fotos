# Plan de pruebas nocturnas (sirve sin auroras)

Objetivo: comparar cada preset de Aurora Fotos con el equivalente de Samsung en la **misma escena**,
y medir tiempos reales. Escena ideal: cielo con estrellas y algo de primer plano con luces lejanas
(un pueblo, una carretera). Trípode siempre; teléfono cargado o enchufado; brillo de pantalla bajo.

Antes de salir: Ajustes → Aplicaciones → Aurora Fotos → Batería → **Sin restricciones**, para que
Samsung no mate el servicio en sesiones largas.

## 0. Diagnóstico (5 min, en casa)

| # | Qué | Qué anotar |
|---|---|---|
| 0.1 | Diagnóstico → **Probar exposición forzada** | Las líneas "pedido → aplicado". ¿Alguna RESPETADA? |
| 0.2 | Diagnóstico → **Capturar DNG de prueba** → abrir `test.dng` en Lightroom/Galería | ¿Se ve limpio o rayado/con bandas? |
| 0.3 | Diagnóstico → Copiar → pegar el texto en el chat | Rango de exposición e ISO de cada cámara |

## 1. Fotos

| # | Aurora Fotos | Samsung (misma escena, misma lente 1x) | Comparar |
|---|---|---|---|
| 1.1 | **Exposición larga** (30 s eq., ISO 800) | Expert RAW o Pro: 30 s, ISO 800, foco manual ∞, WB 4000 K | Brillo, ruido, estelas de luz; DNG en Lightroom lado a lado |
| 1.2 | **Vía Láctea / estrellas** (30 s eq., ISO 3200) | Pro: 30 s ISO 3200 y, si hay tiempo, Expert RAW Astrophoto 4 min | ¿Estrellas puntuales o arrastradas? Ruido en zonas oscuras |
| 1.3 | **Aurora · foto** (8 s eq., ISO 1600) hacia el cielo | Pro: 8 s ISO 1600 | Color del cielo y del JPEG vs DNG |

Anota de cada una la **duración real** que muestra al terminar ("N frames × 1/9 s = X s, Y s en total").

## 2. Timelapse (acorta las duraciones en Ajustes del preset para la prueba)

| # | Aurora Fotos | Ajuste para la prueba | Samsung | Comparar |
|---|---|---|---|---|
| 2.1 | **Aurora · timelapse** | Duración 5 min | Hyperlapse → Night lapse, 5 min | Parpadeo, brillo, color, fluidez del MP4 |
| 2.2 | **Exposición larga · timelapse** | Duración 5 min | (no hay equivalente) | Suavidad de nubes/luces |
| 2.3 | **Star trails** | Duración 15 min | Hyperlapse → Star trails, 15 min | Longitud y limpieza de las estelas; MP4 de la estela creciendo |

## 3. Video (1 minuto cada uno, mismo encuadre)

| # | Aurora Fotos | Samsung | Comparar |
|---|---|---|---|
| 3.1 | **Aurora · video 24 fps** | Pro Video: 4K 24 fps, ISO 3200, 1/24 s, WB 3800 K, foco manual ∞ | Ruido (lo que Samsung reduce mejor), color, nitidez |

## 4. Qué mandar después

1. El texto del diagnóstico (0.1 y 0.3).
2. Los `_info.txt` de `DCIM/AuroraFotos/<sesión>/` de 1.1, 2.1 y 3.1.
3. Capturas de pantalla de los JPEG de la app junto a los de Samsung (misma escena).
4. Lo que haya quedado mal: oscuro, verdoso/magenta, con bandas, parpadeo, o que la app se haya parado.

Con eso se ajustan: ganancia y curva del revelado, balance de blancos por defecto, bitrate del
video y las duraciones por defecto de los presets.
