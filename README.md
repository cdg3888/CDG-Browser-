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

La interfaz Android permite abrir y cambiar entre pestañas y favoritos en un panel lateral izquierdo, y acceder a descargas, ajustes y protección desde el panel derecho. Ofrece cinco temas visuales, motor de búsqueda y página de inicio configurables; las preferencias se guardan localmente. La barra agrupa URL, «Ir» y «Actualizar», se oculta al desplazarse hacia abajo y reaparece al subir o tocar el control superior. «Ajustes» concentra protección, detector multimedia, calidad mínima, fragmentos HLS/DASH, remultiplexado MP4, subtítulos, carpeta de descargas y actualización de yt-dlp. El módulo `:downloads` guarda archivos multimedia que la página ofrece directamente y evita sobrescribir nombres existentes. Cuando se detecta contenido HLS, DASH o video, aparece un botón flotante «Medios» sobre la página; sus tarjetas muestran la miniatura, título, duración y resolución detectados. Las calidades desde el mínimo configurado aparecen directamente en la tarjeta, con opciones menores y audio bajo «Opciones avanzadas», sin diálogos de selección. La pantalla «Descargas» combina tareas en curso e historial terminado, permite elegir una carpeta mediante el selector de Android y muestra los archivos disponibles en esa carpeta. La detección no incluye navegación privada y no puede resolver por sí sola URL `blob:` ni garantizar que GeckoView exponga todas las solicitudes.

Los enlaces se pueden copiar o analizar con yt-dlp para consultar metadatos y elegir entre los formatos que el sitio expone, extraer MP3 o incrustar subtítulos disponibles. La cadena intenta primero URLs directas y después yt-dlp; no hay extractores nativos específicos de sitios en este proyecto. Los videos se remultiplexan a MP4 sin recodificarlos; los archivos usan el título del medio y, si es genérico, el de la página. Las listas no se descargan como playlist. Para HLS/DASH yt-dlp puede descargar hasta 32 fragmentos simultáneamente; los archivos de video directos dependen del servidor y no se dividen en fragmentos. Los formatos separados de audio y video se solicitan como una combinación y yt-dlp usa el FFmpeg incluido para unirlos cuando es necesario. El gestor muestra progreso, permite cancelar yt-dlp y abrir o compartir archivos. El historial de descargas se guarda localmente; si se cierra la app durante una tarea, al volver se marca como interrumpida y se puede reintentar (no se reanuda desde el byte exacto). yt-dlp no recibe las cookies del navegador y no evita DRM ni controles de acceso; el análisis puede fallar en sitios que requieren autenticación o cuando cambian sus extractores, y las URL de medios pueden caducar.

La actualización estable de yt-dlp es manual. Verifica el digest SHA-256 publicado por la API oficial de GitHub, permite solo redirecciones HTTPS a hosts autorizados, descarga a almacenamiento privado, conserva una copia anterior y valida la versión antes de finalizar el reemplazo. Si el digest no está disponible o falla alguna comprobación, la actualización se rechaza. Los archivos se guardan en `Descargas/CDG Browser` (Android 10 o posterior); en versiones anteriores, quedan en el almacenamiento específico de la aplicación. Descarga solo contenido que tengas derecho a guardar. El motor yt-dlp incluye bibliotecas nativas ARM y las APK se generan para `arm64-v8a` y `armeabi-v7a`.

El panel Protección muestra el estado, los dominios de la lista, los bloqueos de la sesión, excepciones por sitio y actualización manual. El filtro local de anuncios sigue siendo una lista de hosts (sin interpretación general de EasyList ni reglas cosméticas); el filtro y la detección multimedia son independientes. Se pueden bloquear ventanas emergentes no solicitadas y activar el bloqueo estricto de redirecciones posteriores a un clic. Este último puede interrumpir inicios de sesión y cambios HTTP a HTTPS; desactívalo si una página deja de funcionar. Las redirecciones repetidas también se limitan, pero esto no es un servicio de reputación ni protección anti-phishing. Comprueba el dominio antes de autenticarte.
