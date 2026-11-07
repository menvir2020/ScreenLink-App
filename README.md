# ScreenLink para Android (celular, tablet y TV JVC)

Todo el código está en un solo archivo: **`app/src/main/java/com/screenlink/ScreenLink.kt`**.
Los demás archivos son la configuración de Android (manifiesto y Gradle).

## Generar el APK (Android Studio)

1. Instala [Android Studio](https://developer.android.com/studio) y abre esta carpeta (`screenlink-android`).
2. Espera a que termine la sincronización de Gradle (descarga lo necesario).
3. Menú **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
4. El APK queda en `app/build/outputs/apk/debug/app-debug.apk`.

## Instalar

- **Celular:** copia el APK, ábrelo y permite "instalar apps desconocidas".
- **TV JVC (Android TV / Google TV):** activa las opciones de desarrollador (Ajustes > Acerca de > pulsa 7 veces en "Compilación"), activa "Depuración USB/inalámbrica" y instala con `adb connect IP_DE_LA_TV` y `adb install app-debug.apk`. También sirve una app como "Send Files to TV" o un USB con un explorador de archivos.

## Uso

- Pantalla vertical en celular y horizontal en tablet y TV, con el mismo diseño que la app de PC.
- **Transmisor:** comparte la pantalla. Android pide confirmar la captura en cada sesión (no se puede evitar). Para que el receptor lo controle, activa ScreenLink en **Ajustes > Accesibilidad**.
- **Receptor:** "Iniciar búsqueda de dispositivos" y toca el nombre. La pantalla remota llena toda la tuya. Con "Control total":
  - **Celular/tablet:** un dedo = clic o arrastre, dos dedos = desplazar, "Clic derecho" y "Teclado" en la barra.
  - **TV:** las flechas del mando mueven un cursor y OK hace clic. Menú o Play/Pausa = clic derecho; Adelantar y Rebobinar = desplazar.
  - **Teclado y ratón Bluetooth/USB:** se reenvían al transmisor.
- **Atrás** muestra u oculta la barra con "Ajuste" (llenar o proporcional) y "Terminar".

## Límites que conviene conocer

- Muchas TV Android no incluyen la función de captura de pantalla: en ese caso la TV funciona como **Receptor**, no como Transmisor.
- Controlar una TV Android desde otro equipo requiere Android 13 o superior (flechas y OK por Accesibilidad). En versiones anteriores solo funcionan toques, Atrás e Inicio.
- Android no permite que otra app controle pantallas de contraseñas ni de pagos.
- El audio de un celular o TV transmisor no se envía (Android lo restringe); el del PC sí.
- El Bluetooth del receptor no se puede "prestar" al transmisor. Sí se reenvían su teclado y ratón, y el audio del PC suena por los audífonos del receptor.
- Mandos de juego Bluetooth: en TV mueven el cursor como el control remoto, pero no se reenvían como mando.
