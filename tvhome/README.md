# TV Home

Launcher mínimo para Android TV / Google TV. Reemplaza a AT4K.

## Qué es

Una sola Activity, Java puro, **cero dependencias** (sin AndroidX, sin Compose,
sin Kotlin, sin RecyclerView). Usa únicamente vistas del sistema.

- **34 KB de APK** (AT4K ronda los 15 MB).
- **~22 MB de RAM en reposo** (AT4K medido en la onn: ~66 MB).
- **Sin permiso de INTERNET**: por construcción no puede mostrar publicidad ni
  enviar telemetría.
- Sin servicios, sin alarmas, sin base de datos. Un único receptor de paquetes,
  registrado solo mientras el launcher está visible.

## Qué muestra

- **Fila 1**: apps con categoría LEANBACK (más apps de usuario con LAUNCHER).
  Orden: el que hayas dejado con "Mover"; si no, `PINNED` y después alfabético.
- **Fila 2**: Ajustes + entradas HDMI/passthrough detectadas por `TvInputManager`
  (ordenadas por nombre: Compuesto, HDMI 1, HDMI 2…). Las entradas usan siempre
  un icono genérico de pantalla, porque el que reporta el firmware viene nulo,
  vacío o blanco según el equipo.
- Tarjetas rectangulares con el icono centrado (sin estirarlo) y redondeado.
- **Tema claro por defecto**, oscuro opcional. El switch sol/luna de arriba a la
  izquierda del reloj cambia el tema y lo deja guardado (`SharedPreferences`).
- Reloj y fecha arriba a la derecha, en el formato del sistema.
- Carrusel horizontal: si hay más de 5-6 apps, se corta un icono en el borde y
  la fila se desplaza **lo mínimo necesario** al navegar.
- La lista se actualiza sola al **instalar o desinstalar** una app, sin reiniciar
  el launcher y sin mover el foco (receptor de paquetes + refresco al volver).

## Menú de la app (mantener OK)

Con una tarjeta enfocada, mantener pulsado OK abre un menú flotante:

| Opción | Qué hace |
|---|---|
| **Mover** | ◀ ▶ cambian el icono de lugar; OK lo suelta y guarda; Atrás cancela |
| **Información** | Abre la ficha de la app del sistema (versión, forzar cierre, desinstalar…) |

## Navegación

| Tecla | Acción |
|---|---|
| ◀ ▶ | Moverse por la fila de apps (hace scroll automático) |
| ▲ ▼ | Cambiar entre fila de apps, fila de sistema y switch de tema |
| OK | Abrir / cambiar el tema (con el switch enfocado) |
| Mantener OK | Menú de la tarjeta: Mover / Información |
| Inicio | Volver al primer icono |
| Atrás | No sale del launcher; vuelve al primer icono |

El foco se recuerda al volver de una app.

## Estructura

```
tvhome/
├── instalar-launcher.ps1          # instala y fija el Home
├── settings.gradle
├── build.gradle
├── keystore.properties            # firma local (tvhome.jks)
├── app/build.gradle
└── app/src/main/
    ├── AndroidManifest.xml
    ├── java/com/bumbu/tvhome/MainActivity.java
    └── res/
        ├── drawable/bg_light.xml     # fondo tema claro
        ├── drawable/bg_dark.xml      # fondo tema oscuro
        ├── drawable/ic_sun.xml
        ├── drawable/ic_moon.xml
        ├── drawable/ic_move.xml      # icono "Mover"
        ├── drawable/ic_info.xml      # icono "Información"
        ├── drawable/ic_input.xml     # icono genérico de entrada HDMI
        ├── drawable/ic_launcher.xml
        └── values/styles.xml
```

## Compilar

El toolchain está en `%TEMP%\opencode\tools` (JDK 17 + Gradle 8.9) y el SDK en
`%TEMP%\opencode\android-sdk`.

```powershell
$env:JAVA_HOME = "$env:TEMP\opencode\tools\jdk-17.0.20.1+1"
$env:ANDROID_HOME = "$env:TEMP\opencode\android-sdk"
& "$env:TEMP\opencode\tools\gradle-8.9\bin\gradle.bat" -p . assembleRelease --no-daemon
```

Sale en `app\build\outputs\apk\release\app-release.apk`.

## Instalar

```powershell
# un solo equipo conectado por ADB
.\instalar-launcher.ps1

# indicando IP y puerto de "Depuración inalámbrica"
.\instalar-launcher.ps1 -Direccion 192.168.0.126:41261

# recompilando antes
.\instalar-launcher.ps1 -Compilar
```

## Volver a AT4K

```powershell
.\instalar-launcher.ps1 -HomeAt4k
```

O desinstalar del todo:

```powershell
adb uninstall com.bumbu.tvhome
```

## Ajustes de diseño

Todo está en `MainActivity.java` (geometría medida del mockup a 1280×720):

| Constante | Valor | Qué controla |
|---|---|---|
| `CARD_W` / `CARD_H` | 150 × 87 dp | Tarjeta de app (rectangular) |
| `ICON` | 60 dp | Icono dentro de la tarjeta (centrado, sin deformar) |
| `GAP` | 18 dp | Separación entre tarjetas |
| `CARD_R` | 15 dp | Redondeo de la tarjeta |
| `SYS_W` / `SYS_H` / `SYS_ICON` | 150 × 66 / 40 dp | Tarjeta de la fila 2 |
| `SW_W` / `SW_H` / `SW_KNOB` | 72 × 30 / 24 dp | Switch de tema |
| escala de foco | 1.06 | Agrandado de la tarjeta al enfocar |
| `PINNED` | array | Apps fijadas y su orden |
| `HIDDEN` | array | Paquetes que no se muestran |
| `loadPalette()` | `0xFF...` | Colores de ambos temas |
| `bg_light.xml` / `bg_dark.xml` | — | Degradado + halo de cada tema |
| menú | 252 × 54 dp | Ancho del menú y alto de cada opción |

La etiqueta de la tarjeta enfocada pasa a negrita; el anillo de foco es el borde
de la propia tarjeta, con color suave para no endurecer el tema claro
(gris `#8E9297` en claro, blanco en oscuro) más un leve aumento de sombra.

## Orden y actualización

- El orden elegido con "Mover" se guarda como lista de paquetes en
  `SharedPreferences` (clave `order`). Las apps nuevas van al final.
- `refreshApps()` compara la lista real con la mostrada y solo reconstruye si
  cambió algo, conservando el foco por paquete (no por posición).

## Estado por equipo

| Equipo | Launcher | Debloat |
|---|---|---|
| onn. 4K (Android 14) | TV Home 1.1, Home fijado | 20 paquetes fuera del usuario 0, `launcherx` deshabilitado |
| Hisense HiSmart 2K ATV4 (Android 11) | TV Home 1.1, Home fijado | 18 paquetes fuera del usuario 0, `tvlauncher` deshabilitado, AT4K desinstalado |
| Motorola Google TV 2K (Android 14) | TV Home 1.1, Home fijado | 15 paquetes deshabilitados, `launcherx` deshabilitado, AT4K desinstalado |

**Ojo (Hisense)**: `com.google.android.leanbacklauncher.partnercustomizer` **no** se
desinstala. Su proveedor `tvlauncher.widget` lo usa `com.mediatek.tv.service`; si
falta, ese servicio crashea al cambiar la red y se lleva puesta la pantalla
"Ajustes → Internet y red" (se cerraba sola).

## Pendiente (etapas siguientes)

- Etapa 4: overscan configurable, resolución, pulido.
