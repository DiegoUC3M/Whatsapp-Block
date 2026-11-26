# Pausa de Chats

App Android independiente para limitar los chats que tú elijas en WhatsApp y WhatsApp Business. Proyecto anteriormente llamado WhatsApp Block. No está afiliado, patrocinado ni autorizado por WhatsApp o Meta.

## Funcionamiento

Escribe el nombre exacto mostrado en la cabecera del chat. Cada regla admite:

- Bloqueo continuo o por días y franjas horarias.
- Una cuota de 0 a 50 minutos por hora del reloj: al agotarla, vuelve atrás hasta la siguiente hora.
- Activación individual y borrado de la regla. El contador diario se muestra en modo cuota.

Antes de abrir los ajustes de accesibilidad, la app explica el acceso y pide una aceptación explícita. Rechazar permite seguir configurando reglas y leyendo la política. Android exige habilitar además el servicio de forma manual. En la pantalla principal puedes pausar todos los bloqueos, revocar la autorización o borrar los datos.

El servicio reconoce únicamente una conversación con cabecera y campo de escritura visibles, dentro de una ventana activa y enfocada de WhatsApp/Business. Recomprueba esa ventana antes de usar Atrás. El contador utiliza tiempo monotónico mientras el dispositivo está encendido y desbloqueado; se detiene al salir del chat, pausar, revocar o interrumpirse el servicio. Los horarios se comprueban también mientras un chat permanece abierto.

El tiempo de uso es una estimación con comprobaciones periódicas de aproximadamente un segundo. No se conserva un historial de conversaciones.

## Privacidad y límites

Los nombres introducidos, reglas y contadores se guardan en el almacenamiento privado del teléfono. No hay permiso de Internet, publicidad, analítica, capturas de pantalla, lectura de mensajes ni importación de la agenda. Se eliminó la función experimental de avatares y se borran sus huellas antiguas al actualizar.

Consulta la [política completa](PRIVACY.md), también incluida en la app sin conexión. La URL pública para la versión de esta rama es [Política de privacidad de Pausa de Chats](https://github.com/DiegoUC3M/Whatsapp-Block/blob/codex/play-store-preparation/PRIVACY.md).

El servicio necesita acceso a la estructura de la pantalla y al título del chat. Los identificadores de la interfaz de WhatsApp pueden cambiar: ante una pantalla que no reconoce, la app se abstiene de actuar. Dos chats con el mismo nombre mostrado pueden coincidir con la misma regla. Cambiar el nombre del chat requiere actualizar la regla. No se bloquea la recepción de mensajes o llamadas ni se modifica la cuenta de WhatsApp. No es un control parental ni una barrera que impida desactivar la app.

## Desarrollo y Google Play

Android 8.0 o posterior; compile/target SDK 36; identificador de producción conservado: `com.diegouc3m.whatsappblock`.

Lee [cómo compilar y configurar la firma](BUILDING.md). Las comprobaciones de los PR ejecutan tests, lint y compilación de debug; debug tiene un identificador separado y sirve para pruebas. El workflow manual **Build signed Play bundle** genera un AAB firmado mediante una clave de subida privada configurada en secrets, sin subirlo automáticamente a Play.

La preparación de código incluye consentimiento, controles de privacidad y una identidad propia, pero la aprobación depende de la revisión de Google. [PLAY_STORE.md](PLAY_STORE.md) contiene la ficha propuesta, la declaración de accesibilidad y el guion de pruebas/vídeo pendientes antes del envío. Prueba la detección con versiones reales de WhatsApp y Business antes de distribuir.
