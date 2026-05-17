# BitZero Downloader (Android)

Aplicación Android nativa que replica la funcionalidad del script `bitzero.py`: descarga archivos camuflados desde servidores OJS a partir de una URL BitZero, sin necesidad de Python, Termux ni línea de comandos.

## Características

- ✅ **Interfaz oscura minimalista** (Material 3, mínimo Android 10)
- ✅ **Pegar URL** directamente con un toque
- ✅ **Descarga en segundo plano** con servicio foreground
- ✅ **Notificación persistente** con porcentaje y velocidad
- ✅ **Barra de progreso global** (suma todas las partes)
- ✅ **Cancelación** desde la app o la notificación
- ✅ **Historial** con SharedPreferences (200 últimas descargas)
- ✅ **Reanudación**: las partes ya descargadas en caché se reaprovechan
- ✅ **Decodificación PNG / HTML+XOR / ZIP** (modos BitZero 1, 2, 3)
- ✅ **6 patrones de URL** (probados en orden, igual que `bitzero.py`)
- ✅ **Reintentos automáticos** (3 por URL, con backoff)
- ✅ **Guardado en `Download/BitZero/`** vía MediaStore (no requiere permisos en Android 10+)

## Arquitectura

```
app/src/main/java/cu/techdev/bitzero/
├── MainActivity.kt        ← Pantalla principal (input URL + progreso)
├── HistoryActivity.kt     ← Pantalla de historial (RecyclerView)
├── DownloadService.kt     ← Servicio foreground + notificaciones
├── Downloader.kt          ← Núcleo: orquesta el flujo completo
├── BitZeroUrl.kt          ← Parser de URL + generador de URLs OJS
├── OjsLogin.kt            ← Login OJS (CSRF + form login)
├── Decoders.kt            ← decodePng / decodeHtml / decodeZip
├── HttpClient.kt          ← OkHttp con cookies, SSL trust-all, retry
└── HistoryStore.kt        ← Persistencia del historial (JSON)
```

La lógica replica exactamente `bitzero.py` v3.4:

| `bitzero.py`                | App Android                      |
|----------------------------|----------------------------------|
| `parse_bitzero_url()`      | `BitZeroUrl.parse()`             |
| `create_session()`         | `HttpClient.newSession()`        |
| `test_connection()`        | `OjsLogin.testConnection()`      |
| `ojs_login()`              | `OjsLogin.login()`               |
| `generate_download_urls()` | `BitZeroUrl.generateDownloadUrls()` |
| `download_file_with_fallback()` | `Downloader.downloadPart()` |
| `decode_png()`             | `Decoders.decodePng()`           |
| `decode_html()`            | `Decoders.decodeHtml()`          |
| `decode_zip()`             | `Decoders.decodeZip()`           |

## Compilación

### Opción A — Compilar en la nube con GitHub Actions (recomendado)

1. Sube este proyecto a un repo de GitHub.
2. El workflow `.github/workflows/build-apk.yml` se dispara automáticamente.
3. Tras ~5 minutos, descarga el APK desde la pestaña **Actions → último run → Artifacts**.

### Opción B — Compilar localmente

Requisitos:
- JDK 17
- Android SDK con `platforms;android-34` y `build-tools;34.0.0`
- 2 GB de RAM libres como mínimo

```bash
# 1. Establece local.properties apuntando a tu SDK:
echo "sdk.dir=/ruta/a/Android/Sdk" > local.properties

# 2. Compila APK de debug:
./gradlew :app:assembleDebug

# 3. El APK queda en:
#    app/build/outputs/apk/debug/app-debug.apk
```

### Opción C — Compilar en Android Studio

Abre la carpeta del proyecto en Android Studio Hedgehog (2023.1) o superior. Sync Gradle y pulsa **Build → Build Bundle(s)/APK(s) → Build APK(s)**.

## Instalación de la APK

1. Transfiere el `app-debug.apk` al teléfono (Telegram, USB, email…).
2. Habilita **Orígenes desconocidos** en Ajustes → Seguridad.
3. Tóca el APK y pulsa **Instalar**.
4. Abre la app, pega una URL BitZero y pulsa **Descargar**.

## Permisos solicitados

- `INTERNET` — para conectar al servidor OJS
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` — para descarga en background
- `POST_NOTIFICATIONS` (Android 13+) — para notificación de progreso
- `WAKE_LOCK` — para evitar que la pantalla apagada interrumpa la descarga

No requiere permisos de almacenamiento porque guarda vía MediaStore en `Download/BitZero/`.

## Notas técnicas

- **Reanudación**: si una descarga falla a mitad, las partes ya bajadas quedan en `context.cacheDir/bitzero_<submissionId>_<ids>/`. Al pulsar **Reanudar** desde el historial, las partes existentes se omiten.
- **ZIP cifrado (modo 3)**: la versión actual usa `java.util.zip` (sin contraseña). Si necesitas ZIP con password, sustituye `Decoders.decodeZip()` por `net.lingala.zip4j:zip4j:2.11.5`.
- **SSL**: el cliente acepta certificados auto-firmados (equivalente a `verify=False` en Python).

## Licencia

Proyecto privado. Uso autorizado únicamente sobre infraestructura propia.
