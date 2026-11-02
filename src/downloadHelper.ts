import JSZip from 'jszip';
import { saveAs } from 'file-saver';

// Import all Android project files as raw text using Vite's glob import
const projectRawFiles = import.meta.glob(
  [
    '/settings.gradle.kts',
    '/build.gradle.kts',
    '/gradle.properties',
    '/gradlew',
    '/gradlew.bat',
    '/gradle/wrapper/gradle-wrapper.properties',
    '/README.md',
    '/COMO_GENERAR_APK.md',
    '/.github/workflows/build-apk.yml',
    '/app/build.gradle.kts',
    '/app/proguard-rules.pro',
    '/app/src/main/AndroidManifest.xml',
    '/app/src/main/res/values/*.xml',
    '/app/src/main/res/xml/*.xml',
    '/app/src/main/res/drawable/*.xml',
    '/app/src/main/res/mipmap-anydpi-v26/*.xml',
    '/app/src/main/java/com/ambiorix/pay/**/*.kt',
    '/app/src/test/java/com/ambiorix/pay/*.kt'
  ],
  { query: '?raw', eager: true, import: 'default' }
);

export async function triggerClientSideZipDownload() {
  try {
    const zip = new JSZip();

    for (const [key, content] of Object.entries(projectRawFiles)) {
      const relativePath = key.replace(/^\//, '');
      if (typeof content === 'string') {
        if (relativePath === 'gradlew') {
          zip.file(relativePath, content, { unixPermissions: '755' });
        } else {
          zip.file(relativePath, content);
        }
      }
    }

    // Include binary gradle-wrapper.jar if available in public/
    try {
      const jarRes = await fetch('/gradle-wrapper.jar');
      if (jarRes.ok) {
        const jarBuf = await jarRes.arrayBuffer();
        zip.file('gradle/wrapper/gradle-wrapper.jar', jarBuf);
      }
    } catch {
      // GitHub Actions workflow also generates wrapper automatically if jar is absent
    }

    const blob = await zip.generateAsync({
      type: 'blob',
      compression: 'DEFLATE',
      compressionOptions: { level: 9 },
      platform: 'UNIX'
    });

    saveAs(blob, 'AmbiorixPay-v2.7-GT-Android-Project.zip');
    return true;
  } catch (err) {
    console.error('Error generating client-side zip:', err);
    try {
      const res = await fetch('/AmbiorixPOS-Android-Project.zip');
      if (res.ok) {
        const blob = await res.blob();
        saveAs(blob, 'AmbiorixPay-v2.7-GT-Android-Project.zip');
        return true;
      }
    } catch (e) {
      console.warn('Fallback zip download failed:', e);
    }
    return false;
  }
}
