# Ambiorix Pay 2.7 GT 📱🇨🇴

**Ambiorix Pay (Versión 2.7)** es una pantalla inteligente para comercios en Colombia que reciben pagos por transferencia y códigos QR (Nequi, Bancolombia, Daviplata, Dale!, Movii, etc.).

> **AVISO IMPORTANTE DE SEGURIDAD Y ARQUITECTURA:**  
> Ambiorix Pay **NO** procesa pagos, **NO** mueve dinero, **NO** se conecta a las APIs bancarias y **NO TIENE EL PERMISO DE INTERNET** (`android.permission.INTERNET`). El dinero llega directo a la cuenta bancaria del comerciante. La app únicamente escucha las notificaciones del sistema de forma 100% local, extrae el monto en COP y muestra una pantalla verde de confirmación con voz y sonido para que el comerciante y el cliente verifiquen la transacción desde varios metros de distancia.

---

## 🌟 Características Principales

1. **Modo Kiosco de Alto Contraste:**
   - Tema oscuro con acentos dorados diseñado para alta visibilidad en mostradores comerciales.
   - Muestra 1 o 2 códigos QR (imágenes propias subidas por el comercio desde su galería y almacenadas en el almacenamiento privado local de la app).
   - Animación pulsante "Esperando pago...".
   - Pantalla siempre encendida (`FLAG_KEEP_SCREEN_ON`), barras del sistema ocultas y botón atrás bloqueado.

2. **Detección Determinista de Pagos:**
   - Implementado mediante `NotificationListenerService`.
   - **Filtro en 5 pasos:**
     1. App autorizada por *Package Name*.
     2. Detección de palabras clave de pago recibido (sin tildes) y exclusión inmediata de palabras como *enviaste, pagaste, compraste, código de seguridad, promoción*.
     3. Extracción y normalización de montos en COP (`$35.000`, `$ 35.000`, `35.000 pesos`, `COP 35.000`, `$1.250.000`, etc.), ignorando saldos remanentes o comisiones.
     4. Prevención de duplicados en ventana de 2 minutos.
     5. Descarte de notificaciones rezagadas (más de 3 minutos).

3. **Confirmación Visual y por Voz Fluida:**
   - Pantalla verde esmeralda con monto gigante que se adapta al ancho.
   - **Voz de pago continua y natural:** Emite una única cadena completa sin pausas robóticas (`"Pago recibido, treinta y cinco mil pesos. Gracias por tu compra."`).
   - **Dos opciones independientes en Configuración:**
     - *Voz de pago:* Dice `"Pago recibido, <monto en palabras> pesos"`.
     - *Agregar "Gracias por tu compra":* Añade la frase de cortesía al final.
   - **Dicción exacta de montos en palabras:**
     - `35.000` → *"treinta y cinco mil pesos"*
     - `21.000` → *"veintiún mil pesos"*
     - `100.000` → *"cien mil pesos"*
     - `1.250` → *"mil doscientos cincuenta pesos"*
     - `1.000.000` → *"un millón de pesos"*
     - `1.500.000` → *"un millón quinientos mil pesos"*
   - Tono armónico instantáneo (*Chime* con ToneGenerator).
   - Botón *"Probar voz ($35.000)"* en Configuración para validar la entonación.
   - Detección automática si el teléfono carece de voz en español, ofreciendo acceso directo a los ajustes TTS del sistema para descargarla.
   - Regreso automático a la pantalla de códigos QR tras un tiempo configurable (3 a 30 segundos, 7 s por defecto).
   - Interrupción inmediata con `QUEUE_FLUSH` si llega otro pago nuevo.

4. **Control Maestro del Servicio y Horarios Comerciales:**
   - **Interruptor Maestro (ENCENDIDO / APAGADO):**
     - Permite encender o apagar el servicio de escucha y confirmaciones en cualquier momento.
     - Si se apaga mientras hay una confirmación en pantalla, cancela inmediatamente la voz/tono y regresa a la pantalla de espera.
     - No borra el historial de cobros acumulados.
     - Persiste a través de reinicios del sistema y cierres de la app.
   - **Programación de Horarios Comerciales:**
     - Activar o desactivar horario programado (permite modo manual 24/7).
     - Configuración de apertura y cierre con selector horario de 12 horas (a. m. / p. m.).
     - Selección de días hábiles de la semana (L, M, M, J, V, S, D).
     - Modo unificado o personalizado con horarios independientes por día.
     - Fuera del horario de atención: se suspenden los anuncios sonoros, las pantallas emergentes y el procesamiento de notificaciones sin alterar los registros existentes.
   - **Distinción Estricta del Estado Operativo:**
     - **Operativo:** Servicio encendido, permisos concedidos y dentro de la jornada comercial.
     - **Servicio Apagado:** Interruptor maestro apagado por el comercio.
     - **Falta Permiso:** Permiso de notificaciones del sistema desactivado.
     - **Fuera de Horario:** Negocio cerrado según la programación comercial.

5. **Configuración Protegida por PIN:**
   - PIN numérico inicial: `1234` (configurable, mínimo 4 dígitos).
   - Botón *"Probar pago ($35.000)"* para simular la confirmación en vivo sin requerir una transferencia bancaria real ni alterar el historial.
   - Historial local de pagos con fecha, hora, monto y app de origen, con opción de vaciado.

---

## 🛠️ Tecnologías y Versiones Utilizadas

- **Lenguaje:** Kotlin 2.0.20
- **UI:** Jetpack Compose con Compose BOM `2024.09.00`
- **Plugin de Compose:** `org.jetbrains.kotlin.plugin.compose` (Kotlin 2.0+)
- **Android Gradle Plugin (AGP):** `8.5.2`
- **Gradle:** `8.9`
- **JDK:** OpenJDK 17 (Temurin)
- **SDKs:** `minSdk 29` (Android 10), `targetSdk 34`, `compileSdk 34`
- **Carga de imágenes locales:** Coil Compose `2.7.0` (sin internet, lectura de `context.filesDir`)

---

## 🚀 Compilación del Proyecto

### Opción A: Compilación Automática con GitHub Actions (Recomendada)

El repositorio incluye el flujo de trabajo en `.github/workflows/build-apk.yml`.

1. Sube este repositorio a tu cuenta de GitHub (`main` o `master`).
2. Ve a la pestaña **Actions** en tu repositorio.
3. El workflow `Build Ambiorix Pay Debug APK` compilará las pruebas unitarias y ensamblará el APK automáticamente.
4. En la sección **Artifacts** de la ejecución finalizada, descarga `ambiorix-pay-debug.zip` que contendrá:
   ```
   app-debug.apk
   ```

### Opción B: Compilación Local con Android Studio

1. Abre Android Studio (versión Iguana, Jellyfish, Koala o superior con soporte para AGP 8.5+).
2. Selecciona **Open** y elige la carpeta raíz del proyecto.
3. Asegúrate de tener configurado JDK 17 en:  
   `Settings > Build, Execution, Deployment > Build Tools > Gradle > Gradle JDK` (Java 17).
4. Espera la sincronización de Gradle (`Sync Project with Gradle Files`).
5. En el menú superior, ejecuta:  
   `Build > Build Bundle(s) / APK(s) > Build APK(s)`.
6. El archivo se generará en:  
   `app/build/outputs/apk/debug/app-debug.apk`.

### Opción C: Compilación por Línea de Comandos (Linux / macOS / WSL)

```bash
# Otorgar permisos y ejecutar con Gradle 8.9 y JDK 17
gradle :app:testDebugUnitTest
gradle :app:assembleDebug
```

---

## 📲 Instalación y Configuración en Android 10+

### 1. Instalar el APK en el Teléfono
Transfiere el archivo `app-debug.apk` por cable USB, WhatsApp o descárgalo desde GitHub en el navegador del teléfono y presiona **Instalar**.

---

### 2. Habilitar Acceso a Notificaciones (CRÍTICO)

La app necesita permiso para leer las notificaciones que envían las apps bancarias instaladas en el mismo teléfono.

1. Abre **Ambiorix Pay**.
2. Verás una barra de advertencia en color rojo: *"Acceso a notificaciones desactivado"*.
3. Toca sobre la advertencia (o entra a Configuración con PIN `1234` y presiona **"Abrir Acceso a Notificaciones"**).
4. El sistema abrirá la pantalla de **Acceso a notificaciones**.
5. Busca **"Detector de Pagos Ambiorix"** y activa el interruptor.

> ⚠️ **ATENCIÓN EN ANDROID 13 Y ANDROID 14 ("Ajuste Restringido"):**  
> Si Android muestra el mensaje *"Ajuste restringido por motivos de seguridad"* y no te deja encender el interruptor:
> 1. Ve a los **Ajustes** del teléfono > **Aplicaciones** > **Ambiorix Pay**.
> 2. Toca los **tres puntos verticales (⋮)** en la esquina superior derecha.
> 3. Selecciona **"Permitir ajustes restringidos"** (te pedirá tu huella o PIN del teléfono).
> 4. Vuelve a **Ajustes > Notificaciones > Acceso a notificaciones** y activa ahora sí el interruptor de Ambiorix Pay.

---

### 3. Recomendaciones para Teléfonos Dedicados 24/7 en el Negocio

Dado que el dispositivo estará encendido durante jornadas de 10 a 16 horas continuas:

1. **Desactivar Ahorro de Batería para Ambiorix Pay:**
   - Ve a **Ajustes > Aplicaciones > Ambiorix Pay > Batería**.
   - Cambia de *Optimizado* a **Sin restricciones** (Unrestricted). Esto evita que el sistema mate el servicio de notificaciones en segundo plano tras horas de inactividad.
2. **Desactivar Ahorro de Batería en las Apps Bancarias (Nequi, Bancolombia, Daviplata):**
   - Asegúrate de que las apps bancarias también tengan permiso de ejecutar en segundo plano y enviar notificaciones prioritarias con sonido e ícono en la barra de estado.
3. **Fijar Pantalla (Screen Pinning):**
   - Ve a Configuración de Ambiorix Pay > **Modo Kiosco y Fijar Pantalla**.
   - En los ajustes de Seguridad de Android activa **Fijar aplicaciones**.
   - Abre la vista de aplicaciones recientes de Android, presiona el ícono de Ambiorix Pay en la parte superior de la tarjeta y selecciona **Fijar** (Pin). Así nadie podrá salir de la app sin el PIN del teléfono.
4. **Cargador con temporizador o carga inteligente:**
   - Si el teléfono lo permite, activa la función de *Protección de batería* (limitar carga máxima al 80% u 85%) para no inflar la batería de litio por estar conectado permanentemente a la corriente.

---

## 🔍 Paquetes de Apps Bancarias (Package Names)

| App Bancaria | Package Name Principal | Estado de Verificación | Notas |
| :--- | :--- | :--- | :--- |
| **Nequi** | `com.nequi.MobileApp` | ✅ Verificado en Play Store | Paquete histórico y actual oficial. |
| **Bancolombia Personas** | `com.bancolombia.personas.gemini` | ✅ Verificado en Play Store | Versión moderna. Paquete anterior: `com.todo1.mobile` (ambos incluidos). |
| **Bancolombia A la mano** | `com.bancolombia.billetera` | ✅ Verificado en Play Store | App independiente A la mano. |
| **Daviplata** | `com.davivienda.daviplataapp` | ✅ Verificado en Play Store | Versión actual. `com.davivienda.daviplata` como respaldo. |
| **Dale!** | `co.com.bancobogota.dale` | ✅ Verificado en Play Store | Billetera digital Aval. |
| **Movii** | `com.movii` | ✅ Verificado en Play Store | Billetera digital SEDPE. |
| **Lulo Bank** | `com.lulobank.mobile` | ✅ Verificado en Play Store | Neobanco. |

*Si tu banco o cooperativa usa una app distinta, puedes escribir su package name exacto en el campo "Agregar package name" dentro de Configuración.*

---

## 🧪 Pruebas Unitarias Incluidas

El proyecto cuenta con suites de pruebas unitarias puras (sin emulador requerido):

- `PaymentNotificationParserTest.kt`: Valida la extracción de `$35.000`, `$ 35.000`, `$35,000`, `35.000 pesos`, `COP 35.000`, `$1.250.000`, `$35.000,50` y el aislamiento de montos de saldo remanente (`"Tu nuevo saldo disponible es $180.000"`).
- `PaymentNotificationFilterTest.kt`: Valida las 5 etapas del filtro (autorización, palabras clave de entrada, descarte de salidas como *"enviaste"* o claves dinámicas, prevención de duplicados en 2 minutos y reloj inyectable).
- `SpanishNumbersTest.kt`: Valida la fonética y gramática en español (`"treinta y cinco mil"`, `"veintiún mil"`, `"cien mil"`, `"un millón de pesos"`).

Ejecutar pruebas:
```bash
gradle :app:testDebugUnitTest
```

---

## 📋 Lista de Verificación en un Teléfono Físico Real

1. **Recepción con pantalla bloqueada:** Enviar una transferencia de prueba de $1.000 COP y comprobar que la pantalla se ilumina y muestra la confirmación en verde.
2. **Volumen del altavoz:** Asegurarse de que el volumen de contenido multimedia y notificaciones esté al 100% en el teléfono para que el anuncio de voz y el *chime* se escuchen claramente en el local.
3. **Múltiples pagos simultáneos:** Probar dos pagos consecutivos con el botón "Probar pago" y verificar que el temporizador se reinicia inmediatamente mostrando el nuevo pago.
4. **Reinicio del dispositivo:** Reiniciar el teléfono y verificar que Android reconecta automáticamente el `NotificationListenerService`.
