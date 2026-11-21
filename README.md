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

La interfaz Android permite abrir y cambiar entre pestañas, guardar favoritos, ocultar la barra de direcciones y alternar entre tema claro y oscuro; estas preferencias se guardan localmente. El módulo `:downloads` guarda archivos multimedia que la página ofrece directamente y evita sobrescribir nombres existentes. Cuando se detecta contenido HLS, DASH o video, aparece un botón flotante «Medios» sobre la página; al abrirlo se muestran las opciones encontradas con el nombre de la página, el tipo y la URL. Los formatos analizados con yt-dlp pueden mostrar miniatura, duración, resolución, calidad y códec antes de descargar. La pantalla «Descargas» combina tareas en curso e historial terminado, permite elegir una carpeta mediante el selector de Android y muestra los archivos disponibles en esa carpeta. La detección no incluye navegación privada y no puede resolver por sí sola URL `blob:` ni garantizar que GeckoView exponga todas las solicitudes.

Los enlaces se pueden copiar o analizar con yt-dlp para consultar metadatos y elegir entre los formatos que el sitio expone, extraer MP3 o incrustar subtítulos disponibles. La cadena intenta primero URLs directas y después yt-dlp; no hay extractores nativos específicos de sitios en este proyecto. Los formatos separados de audio y video se solicitan como una combinación y yt-dlp usa el FFmpeg incluido para unirlos cuando es necesario. El gestor muestra progreso, permite cancelar yt-dlp y abrir o compartir archivos. El historial de descargas se guarda localmente; si se cierra la app durante una tarea, al volver se marca como interrumpida y se puede reintentar (no se reanuda desde el byte exacto). yt-dlp no recibe las cookies del navegador y no evita DRM ni controles de acceso; el análisis puede fallar en sitios que requieren autenticación o cuando cambian sus extractores, y las URL de medios pueden caducar.

La actualización estable de yt-dlp es manual. Verifica el digest SHA-256 publicado por la API oficial de GitHub, permite solo redirecciones HTTPS a hosts autorizados, descarga a almacenamiento privado, conserva una copia anterior y valida la versión antes de finalizar el reemplazo. Si el digest no está disponible o falla alguna comprobación, la actualización se rechaza. Los archivos se guardan en `Descargas/CDG Browser` (Android 10 o posterior); en versiones anteriores, quedan en el almacenamiento específico de la aplicación. Descarga solo contenido que tengas derecho a guardar. El motor yt-dlp incluye bibliotecas nativas ARM y las APK se generan para `arm64-v8a` y `armeabi-v7a`.

El filtro local de anuncios sigue siendo una lista de hosts (sin interpretación general de EasyList ni reglas cosméticas); la pantalla Protección puede mostrar los dominios con más bloqueos en la sesión actual. El filtro y la detección multimedia son independientes. Las ventanas nuevas se aceptan solo cuando GeckoView informa un gesto de usuario y la URL usa HTTP(S); las redirecciones repetidas se limitan, pero esto no es un servicio de reputación ni protección anti-phishing. Comprueba el dominio antes de autenticarte.
