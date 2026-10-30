# Transcriptor

App Android que transcribe audio a texto en español dentro del teléfono, sin cuentas ni servidores.

## Cómo funciona

```
archivo → AudioDecoder (códecs del sistema) → Resampler (mono 16 kHz)
        → detector de voz Silero → Whisper (sherpa-onnx) → segmentos con marca de tiempo
```

- **Motor:** Whisper cuantizado sobre sherpa-onnx (`app/libs/sherpa-onnx-2.0.1.aar`).
- **Modelos:** se descargan una vez desde Hugging Face con commit y SHA-256 fijos (`asr/SpeechModel.kt`). Rápido: 160 MB. Preciso: 375 MB.
- **Internet:** solo para esa descarga. Los audios no salen del teléfono.
- **Trabajo largo:** servicio en primer plano con cola persistida en disco (`service/TranscriptionService.kt`).
- **En vivo:** `service/LiveTranscriptionService.kt` graba del micrófono y usa un modelo de streaming (Kroko de Banafo, 156 MB, CC-BY-SA) para mostrar el texto mientras se habla. Whisper no sirve para esto: necesita la frase completa.
- **Formatos:** los que decodifique Android: MP3, M4A/AAC, WAV, FLAC, OGG, Opus, AMR y audio de videos. WMA no está incluido.

## Compilar

Requiere JDK 17 y Android SDK 36.

```
gradlew :app:testDebugUnitTest
gradlew :app:assembleDebug
```

Genera un APK por arquitectura en `app/build/outputs/apk/debug/`. La mayoría de los teléfonos usa `arm64-v8a`.
