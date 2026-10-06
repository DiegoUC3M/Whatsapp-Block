# Preparar el envío a Google Play

El código elimina varios obstáculos de la versión anterior. La revisión de Google, la firma privada y los trámites de la cuenta siguen siendo necesarios. No envíes una versión que no hayas probado con WhatsApp real.

## Ficha propuesta

**Nombre:** Pausa de Chats

**Descripción breve:** Limita chats elegidos con horarios o cuotas y guarda tus reglas en el teléfono.

**Descripción:**

Pausa de Chats te ayuda a descansar de conversaciones concretas. Escribe el nombre que aparece en la cabecera del chat y elige un bloqueo continuo, días y horarios, o una cuota de minutos por hora. Puedes pausar todas las reglas, desactivar una regla o borrar tus datos desde la app.

Compatible con WhatsApp y WhatsApp Business. Necesita que aceptes el aviso de acceso y habilites manualmente el servicio de accesibilidad de Android. El servicio consulta la estructura de la pantalla y el título del chat abierto para aplicar tus reglas y usar Atrás cuando corresponde. No lee mensajes, no hace capturas ni envía nombres, reglas o contadores fuera del teléfono.

No bloquea mensajes ni llamadas entrantes. Dos chats con el mismo nombre mostrado pueden coincidir con la misma regla. La compatibilidad depende de la interfaz de WhatsApp: si no reconoce una conversación con seguridad, no actúa. Es una aplicación independiente sin afiliación, patrocinio o autorización de WhatsApp o Meta.

Utiliza el icono propio del proyecto y capturas reales con nombres ficticios. La ficha debe mostrar el mismo funcionamiento y consentimiento que el AAB. Configura un correo de soporte que controles en Play Console; el contacto de privacidad provisional es el mecanismo público de incidencias descrito en la política.

## Declaración de accesibilidad

- La app utiliza AccessibilityService para **funcionalidad de la aplicación**; no es una herramienta destinada principalmente a personas con discapacidad. `isAccessibilityTool=false`.
- Finalidad: reconocer el chat visible de WhatsApp/Business que el usuario haya escrito y aplicar exclusivamente sus reglas de horario/cuota mediante Atrás.
- Datos consultados: paquete/ventana activa y enfocada, visibilidad e identificadores de la estructura del chat y nombre mostrado en su cabecera. Nunca se consulta el texto del campo de escritura ni de los mensajes.
- Almacenamiento: reglas, nombres configurados y contadores locales. Los títulos no configurados se descartan; no se envían a servidores ni terceros.
- No existe una API de WhatsApp que permita reconocer desde otra app el chat concreto actualmente visible. UsageStats solo identifica aplicaciones; no permite aplicar cuotas por conversación. El servicio se limita a los dos paquetes compatibles y no importa contactos ni mensajes.
- Acciones: reglas deterministas elegidas por el usuario → una acción Atrás, después de verificar de nuevo la conversación. No planifica ni ejecuta acciones autónomas; no modifica ajustes, envía mensajes ni obstaculiza desinstalación o revocación.
- Control: aviso destacado antes de los ajustes, botones separados de aceptación/rechazo, pausa, revocación y borrado. Si falta consentimiento, el servicio se deshabilita.

Adapta las respuestas a los campos que muestre Play Console. Presenta un vídeo de la versión final, accesible para los revisores, con este guion:

1. Instalación limpia: abrir la app, pulsar Configurar accesibilidad y mostrar completo el aviso.
2. Rechazar: demostrar que no se abren los ajustes ni se activa la función; consultar la política y crear una regla.
3. Reabrir el aviso, aceptar y habilitar el servicio manualmente en Android.
4. Abrir el chat ficticio elegido y demostrar el modo horario y la cuota; mostrar que lista de chats, perfil y otras apps quedan utilizables.
5. Pausar, revocar y borrar datos; mostrar que el bloqueo/conteo se detienen y las reglas desaparecen al borrarlas.

## Privacidad y Seguridad de los datos

La política está incluida en la app y publicada en [esta URL pública](https://github.com/DiegoUC3M/Whatsapp-Block/blob/codex/play-store-preparation/PRIVACY.md). Comprueba que se puede abrir sin iniciar sesión antes de enviar. Mantén esta rama y su URL mientras la uses en la app y en Play Console; si cambias la URL, actualiza también `privacy_url` y los documentos.

Esta versión no transmite datos fuera del dispositivo ni incluye SDK de analítica/publicidad. Conforme a la definición de recopilación de Play, el procesamiento exclusivamente local permite responder que **no se recopilan ni comparten datos**. El aviso y la política explican de todos modos los datos consultados y almacenados localmente. Revisa estas respuestas si añades red, anuncios, diagnóstico remoto o cualquier SDK. La app no ofrece cuentas de usuario.

## Firma, comprobación y envío

1. Configura una clave de subida estable y privada según [BUILDING.md](BUILDING.md); nunca uses la clave debug del repositorio antiguo. Conserva una copia segura y configura Play App Signing. Si ya existe una publicación con este package, respeta su clave/registro y usa un `versionCode` superior al publicado.
2. Ejecuta tests, lint y `bundleRelease`; utiliza el AAB firmado. Verifica la firma del AAB con `jarsigner -verify` y confirma el resultado `jar verified.`. Las claves de subida suelen ser autofirmadas: los avisos de confianza de su certificado no indican por sí solos una firma inválida. Tras subirlo, revisa el informe previo al lanzamiento de Play.
3. Prueba en un teléfono con WhatsApp y otro caso con Business: aviso aceptado/rechazado, reinstalación/actualización, pantalla apagada y bloqueo, cambio de app, diálogos/teclado, nombres duplicados, franjas horarias y cambio de hora/día. Las pruebas de lógica no sustituyen esta validación de la interfaz externa.
4. Completa ficha, capturas, categoría, clasificación de contenido, público objetivo, URL de privacidad, datos del desarrollador, Seguridad de los datos y declaración/vídeo de accesibilidad. Atiende los requisitos de verificación y pruebas que correspondan a tu cuenta.
5. Distribuye primero mediante pruebas internas/cerradas de Play y revisa los resultados antes de solicitar producción. Este repositorio no sube ni publica automáticamente.

## Referencias oficiales consultadas

- [Uso de AccessibilityService y reglas deterministas](https://support.google.com/googleplay/android-developer/answer/10964491?hl=en).
- [Requisitos de target API](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en): API 36 para nuevas apps/actualizaciones móviles desde el 31 de agosto de 2026.
- [Datos del usuario y política de privacidad](https://support.google.com/googleplay/android-developer/answer/10144311?hl=en).
- [Seguridad de los datos: definición y procesamiento local](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en).
- [Identidad y suplantación](https://support.google.com/googleplay/android-developer/answer/9888374?hl=en).

Políticas consultadas el 6 de octubre de 2026. Cumplir estas medidas reduce riesgos; Google puede solicitar cambios o rechazar el uso declarado.
