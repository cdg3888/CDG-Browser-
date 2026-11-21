# CDG Browser para Android

CDG Browser es una aplicación Android nativa basada en GeckoView.

## Compilar la APK Android

Requiere JDK 17 o posterior y Android SDK con plataforma 37.2 y Build Tools 37.0.0. GeckoView 160 requiere compilar con API 37.2; la aplicación sigue orientada a API 36.

```sh
cd android
./gradlew assembleDebug
```

Se genera una APK de depuración por arquitectura en `android/app/build/outputs/apk/debug/` (por ejemplo, `app-arm64-v8a-debug.apk` para la mayoría de los teléfonos Android actuales).

Instala en un dispositivo conectado la APK correspondiente a su arquitectura con:

```sh
adb install -r android/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

La aplicación Android combina la protección estricta de GeckoView con una WebExtension local que filtra solicitudes de terceros usando una lista de hosts incluida en la app. En «Protección» se puede pausar ese filtro, crear una excepción para el sitio actual y actualizar la lista manualmente. La actualización usa [StevenBlack/hosts](https://github.com/StevenBlack/hosts) (licencia MIT), solo tras confirmación; GitHub ve la IP de red, pero no se envían el historial ni la dirección visitada. No hay actualización automática. Las reglas son por host: no eliminan anuncios propios del sitio ni aplican filtros cosméticos, así que no garantizan quitar toda publicidad.

La app desactiva la telemetría móvil de GeckoView y no integra SDK propio de analítica. La navegación normal sigue comunicándose con los sitios que se visitan. El bloqueo estricto integrado de GeckoView continúa activo incluso si se pausan las reglas locales.

La interfaz Android permite abrir y cambiar entre pestañas, guardar favoritos, ocultar la barra de direcciones y alternar entre tema claro y oscuro; estas preferencias se guardan localmente. El módulo `:downloads` guarda archivos multimedia que la página ofrece directamente y evita sobrescribir nombres existentes. El botón «Medios» detecta manifiestos HLS y DASH por URL o tipo de contenido, además de archivos de video directos; permite buscar y filtrar resultados por tipo, cambiar entre el sitio actual y todos los sitios, limpiar la lista temporal y muestra el título/URL de origen. La detección no incluye navegación privada. Los enlaces se pueden copiar o analizar con yt-dlp para elegir calidad de video, extraer MP3 o incrustar subtítulos disponibles. El gestor muestra progreso, permite cancelar yt-dlp y abrir o compartir archivos. El historial de descargas se guarda localmente; si se cierra la app durante una tarea, al volver se marca como interrumpida y se puede reintentar (no se reanuda desde el byte exacto). También se puede solicitar manualmente la actualización estable de yt-dlp. Los archivos se guardan en `Descargas/CDG Browser` (Android 10 o posterior); en versiones anteriores, quedan en el almacenamiento específico de la aplicación. yt-dlp no recibe las cookies del navegador y no evita DRM ni controles de acceso; usa el formato disponible públicamente y puede no funcionar con sitios que requieren autenticación o con cambios en sus extractores. Descarga solo contenido que tengas derecho a guardar. El motor yt-dlp incluye bibliotecas nativas ARM y las APK se generan para `arm64-v8a` y `armeabi-v7a`.
