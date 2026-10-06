# Compilar Pausa de Chats

Requisitos: JDK 17, Android SDK Platform 36 y Build Tools 35.0.0. El wrapper fija Gradle 8.14.5 y verifica el SHA-256 de la distribución; el proyecto usa AGP 8.13.1 y Kotlin 2.2.20.

Configura `ANDROID_HOME` con tu SDK o crea un `local.properties` no versionado con `sdk.dir=/ruta/al/sdk`.

## Pruebas y APK de desarrollo

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

El APK está en `app/build/outputs/apk/debug/`. Tiene el identificador `com.diegouc3m.whatsappblock.debug` y la firma local de depuración de Android. Úsalo para probar; la distribución en Google Play utiliza el AAB de release y una clave de subida propia. La versión debug puede instalarse junto con la versión release: activa únicamente el servicio de la versión que estés probando.

La comprobación automática de cada PR ejecuta las pruebas, lint y la compilación del APK. También comprueba que release rechaza una firma ausente y genera una clave desechable para validar lint, APK y AAB de release, la firma del bundle, SDK objetivo e indicador de depuración. Esa clave y sus artefactos se borran; no sirven para publicar. Ya no publica APK de depuración como una Release de producción.

## AAB de release

Conserva de forma privada una clave de subida estable. No guardes claves, contraseñas ni `keystore.properties` en Git. Google Play App Signing administra la clave de firma de la aplicación; la clave de subida autentica los bundles que envías. Reutiliza la misma clave de subida en versiones posteriores o sigue el proceso de restablecimiento de Play Console.

Configura estas variables en tu entorno local, sin escribir sus valores en archivos versionados:

| Variable | Contenido |
| --- | --- |
| `PAUSA_CHATS_KEYSTORE_PATH` | Ruta al keystore de subida |
| `PAUSA_CHATS_KEYSTORE_PASSWORD` | Contraseña del keystore |
| `PAUSA_CHATS_KEY_ALIAS` | Alias de la clave |
| `PAUSA_CHATS_KEY_PASSWORD` | Contraseña de la clave |

Después ejecuta:

```sh
./gradlew testDebugUnitTest lintRelease bundleRelease
```

El bundle firmado está en `app/build/outputs/bundle/release/app-release.aab`. Los comandos de empaquetado de release fallan si falta la firma; no utilizan una clave debug ni producen silenciosamente un release sin firma. Aumenta `versionCode` antes de cada nueva subida a Play Console.

También puedes ejecutar manualmente **Build signed Play bundle** en GitHub Actions. Requiere los secrets `PAUSA_CHATS_KEYSTORE_BASE64` (el keystore codificado en Base64 sin saltos de línea), `PAUSA_CHATS_KEYSTORE_PASSWORD`, `PAUSA_CHATS_KEY_ALIAS` y `PAUSA_CHATS_KEY_PASSWORD`. La clave temporal se elimina al terminar y el único artefacto de release es el AAB. El workflow no sube automáticamente a Google Play.

## Antes de enviar a revisión

Prueba el consentimiento, la pausa, la revocación, el borrado de datos y cada modo de bloqueo en un dispositivo real. Comprueba que no actúa en la lista de conversaciones, la pantalla bloqueada ni otras aplicaciones. Android y las versiones de WhatsApp pueden cambiar sus etiquetas de accesibilidad; si no se identifica con seguridad una conversación, la app debe abstenerse de bloquearla.

Completa la ficha, la política de privacidad pública, Seguridad de los datos y la declaración de AccessibilityService en Play Console. La declaración necesita explicar y mostrar en vídeo el consentimiento y el uso del servicio. Compilar y firmar correctamente no garantiza la aprobación de Google.
