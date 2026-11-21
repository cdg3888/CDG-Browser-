# CDG Browser

CDG Browser contiene una interfaz web React/Vite y una aplicación Android nativa basada en GeckoView.

La interfaz React es una demostración visual: los sitios se muestran en un `iframe` y el navegador Android es la experiencia que integra las funciones nativas de navegación.

## Ejecutar la interfaz web

Requiere Node.js.

```sh
npm install
npm run dev
```

## Compilar la APK Android

Requiere JDK 17 o posterior y Android SDK con plataforma 37.2 y Build Tools 37.0.0. GeckoView 160 requiere compilar con API 37.2; la aplicación sigue orientada a API 36.

```sh
cd android
./gradlew assembleDebug
```

Se genera una APK de depuración por arquitectura en `android/app/build/outputs/apk/debug/` (por ejemplo, `app-arm64-v8a-debug.apk` para la mayoría de los teléfonos Android actuales).

La aplicación Android combina la protección estricta de GeckoView con una WebExtension local que filtra solicitudes de terceros usando una lista de hosts incluida en la app. En «Protección» se puede pausar ese filtro, crear una excepción para el sitio actual y actualizar la lista manualmente. La actualización usa [StevenBlack/hosts](https://github.com/StevenBlack/hosts) (licencia MIT), solo tras confirmación; GitHub ve la IP de red, pero no se envían el historial ni la dirección visitada. No hay actualización automática. Las reglas son por host: no eliminan anuncios propios del sitio ni aplican filtros cosméticos, así que no garantizan quitar toda publicidad.

La app desactiva la telemetría móvil de GeckoView y no integra SDK propio de analítica. La navegación normal sigue comunicándose con los sitios que se visitan. El bloqueo estricto integrado de GeckoView continúa activo incluso si se pausan las reglas locales.

La interfaz Android permite abrir y cambiar entre pestañas, guardar favoritos, ocultar la barra de direcciones y alternar entre tema claro y oscuro; estas preferencias se guardan localmente. El módulo `:downloads` guarda archivos de video que la página ofrece directamente. El módulo independiente `:ytdlp` integra yt-dlp para analizar y descargar videos de direcciones HTTP(S), con progreso y cancelación. Ambos guardan el resultado en `Películas/CDG Browser` (Android 10 o posterior); en versiones anteriores, los archivos quedan en el almacenamiento específico de la aplicación. yt-dlp no recibe las cookies del navegador y no evita DRM ni controles de acceso; usa el formato disponible públicamente y puede no funcionar con sitios que requieren autenticación o con cambios en sus extractores. Descarga solo contenido que tengas derecho a guardar. El motor yt-dlp incluye bibliotecas nativas ARM y las APK se generan para `arm64-v8a` y `armeabi-v7a`.
