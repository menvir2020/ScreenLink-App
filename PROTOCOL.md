# Protocolo ScreenLink v2

Contrato común entre la app de PC (`main.js`) y la de Android/TV (`ScreenLink.kt`).

## Roles

- **Transmisor:** comparte su pantalla. Está publicado en la red y espera conexiones.
- **Receptor:** busca transmisores, se conecta, muestra su pantalla completa y (opcional) lo controla.

Cada dispositivo puede ser Transmisor o Receptor, cambiando de modo en la app. Para compartir "en ambos sentidos", un equipo es Transmisor y el otro Receptor, y se invierten los modos.

## 1. Descubrimiento (misma red Wi-Fi)

- Servicio mDNS/DNS-SD `_screenlink._tcp`, publicado **solo en modo Transmisor**.
- Nombre de instancia: el nombre que el usuario puso al dispositivo.
- Puerto: el del servidor WebSocket (preferido 48200).
- TXT: `id` (UUID estable), `name`, `v` (`2`), `platform` (`windows`, `android`, `androidtv`), `role` (`tx`).
- Alternativa manual: conectar a `ws://IP:48200`.

## 2. Señalización (WebSocket, JSON)

El receptor abre la conexión hacia el transmisor.

| Mensaje | Dirección | Campos |
|---|---|---|
| `hello` | receptor → transmisor | `v`=2, `id`, `name`, `platform`, `control` (¿pide control?) |
| `accept` | transmisor → receptor | `control` (¿concede control?) |
| `reject` | transmisor → receptor | `reason`: `denied`, `busy`, `notx` |
| `signal` | ambos | `data`: `{sdp}` o `{candidate}` (WebRTC) |
| `bye` | ambos | cierra la sesión |

El **transmisor** crea la oferta SDP; el **receptor** responde.

## 3. Medios (WebRTC, solo red local, sin STUN/TURN)

- Vídeo de la pantalla del transmisor y, si la plataforma lo permite, el audio del sistema (PC). El receptor lo reproduce por su salida activa (altavoz o audífonos Bluetooth).
- `RTCDataChannel` `control`, creado por el transmisor.

## 4. Eventos de control (datachannel `control`, receptor → transmisor)

Coordenadas normalizadas 0..1 sobre la pantalla completa transmitida.

```json
{"k":"move","x":0.5,"y":0.2}
{"k":"down","x":0.5,"y":0.2,"b":0}     // b: 0 izq, 1 central, 2 der
{"k":"up","x":0.5,"y":0.2,"b":0}
{"k":"wheel","dx":0,"dy":120}
{"k":"key","d":true,"vk":65}           // código de tecla virtual de Windows
{"k":"text","s":"hola"}                // texto escrito (teclado en pantalla)
```

Solo se aplican si el transmisor concedió el control en `accept`.

## 5. Seguridad

- La primera conexión de un receptor pide permiso en el transmisor ("Permitir", "Permitir siempre" o "Rechazar"). Con "Permitir siempre" se guarda su `id` y las siguientes conexiones son inmediatas.
- Solo se aceptan conexiones de direcciones de red local.
- Una sesión a la vez.
- Atajo de emergencia en PC: `Ctrl+Alt+Shift+Q`.
