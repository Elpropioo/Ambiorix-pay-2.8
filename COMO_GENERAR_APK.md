# Guía Completa: Cómo Generar el APK de Ambiorix Pay

Esta guía te muestra cómo obtener el archivo `.apk` listo para instalar en cualquier teléfono Android (Android 10 o superior), ya sea **en GitHub** (sin necesidad de configurar Android Studio) o **en Android Studio**.

---

## Opción 1: Generar el APK en GitHub (Recomendado y 100% Automático)

No necesitas instalar Java ni Android Studio en tu computador. GitHub Actions compila el APK en la nube por ti.

### Pasos:

1. **Sube los archivos a tu repositorio de GitHub:**
   - Asegúrate de incluir la carpeta oculta `.github/workflows/build-apk.yml` y la carpeta `gradle/wrapper/`.
   - Haz `git push origin main` (o sube los archivos a tu rama).

2. **Ve a la pestaña "Actions" en tu repositorio de GitHub:**
   - En la parte superior de tu repositorio, haz clic en **Actions**.
   - Verás el flujo llamado **"Build Ambiorix Pay APK"** ejecutándose automáticamente.
   - También puedes lanzarlo manualmente haciendo clic en **"Run workflow"**.

3. **Descarga el APK:**
   - Una vez termine la compilación (ícono verde de verificación ✔️ en aproximadamente 2 minutos), haz clic sobre la ejecución.
   - En la sección **Artifacts** (al final de la página), encontrarás:
     - `AmbiorixPay-Debug-APK`: El APK para pruebas e instalación directa.
     - `AmbiorixPay-Release-APK`: El APK optimizado y listo para producción.
   - Haz clic para descargar el archivo ZIP con el APK e instálalo en tu teléfono.

---

## Opción 2: Generar el APK en Android Studio

Si prefieres compilarlo en tu computador con Android Studio:

### ¿Por qué no se habilitaba la opción "Build Bundle / APK" anteriormente?
En Android Studio, si el proyecto no tiene el binario `gradle-wrapper.jar` dentro de `gradle/wrapper/`, la sincronización de Gradle no se completa y Android Studio deshabilita ("pone en gris") las opciones de compilación. **Ya hemos incluido `gradle-wrapper.jar` (Gradle 8.9) en este paquete**, resolviendo este problema.

### Pasos:

1. **Abrir el proyecto:**
   - Descomprime el archivo `.zip`.
   - Abre Android Studio.
   - Selecciona **File > Open...** y elige la carpeta raíz del proyecto (la que contiene `build.gradle.kts` y `settings.gradle.kts`).

2. **Esperar la sincronización de Gradle:**
   - En la esquina inferior derecha verás el progreso de "Gradle Sync".
   - Espera a que termine (dirá *BUILD SUCCESSFUL* en la pestaña Build).
   - *Nota: Asegúrate de tener configurado JDK 17 en Android Studio (Settings > Build, Execution, Deployment > Build Tools > Gradle > Gradle JDK: 17).*

3. **Compilar el APK:**
   - En el menú superior de Android Studio, haz clic en:  
     **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
   - Al terminar, aparecerá una notificación emergente en la esquina inferior derecha diciendo:  
     *APK(s) generated successfully for 1 module: Locate*.
   - Haz clic en **Locate** para abrir la carpeta donde está tu archivo `.apk`:
     `app/build/outputs/apk/debug/app-debug.apk`

---

## Opción 3: Compilar desde la terminal de tu computador

Si tienes JDK 17 instalado en tu máquina:

- En **Windows (CMD o PowerShell)**:
  ```powershell
  .\gradlew.bat assembleDebug
  ```

- En **Mac o Linux**:
  ```bash
  chmod +x gradlew
  ./gradlew assembleDebug
  ```

El APK se generará en:  
`app/build/outputs/apk/debug/app-debug.apk`
