# Official Sherpa-ONNX Android runtime integration

NAYRO uses the upstream Sherpa-ONNX Android Kotlin/JNI runtime for local keyword spotting. It must not substitute a Kotlin compatibility stub, fake JNI bridge, or third-party wrapper.

## Pinned upstream dependency

- Upstream: https://github.com/k2-fsa/sherpa-onnx
- Release: v2.0.3
- Artifact: `sherpa-onnx-2.0.3.aar`
- Official release SHA-256: `4c766e2e74c9d33d595338c21fe3b305c73ba45d47e96a235675c33142a2ec50`
- Runtime ABI coverage: `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`
- Required native libraries per ABI: `libsherpa-onnx-jni.so` and `libonnxruntime.so`

The AAR is downloaded from the official upstream release at build preparation time and SHA-256 verified. It is intentionally not committed to Git because it is a large generated binary. The preparation script also verifies the AAR ZIP, official Kotlin API classes, and native libraries before Gradle consumes it.

## Prepare a local checkout

From the repository root:

```bash
bash scripts/prepare-sherpa-onnx-android.sh
./gradlew verifySherpaRuntimeAssets
```

The script installs the verified AAR at `app/libs/sherpa-onnx-2.0.3.aar`. Do not download an arbitrary AAR, rename a third-party artifact, or add a compatibility shim to bypass the check.

## NAYRO KWS model contract

The app bundles the GigaSpeech Zipformer2 KWS transducer model under `app/src/main/assets/kws-model/`:

- Encoder: `encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx`
- Decoder: `decoder-epoch-12-avg-2-chunk-16-left-64.onnx`
- Joiner: `joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx`
- Tokens: `tokens.txt`
- Default phrases: `keywords.txt`

The `OnlineModelConfig.modelType` must remain `zipformer2` for these files. Runtime initialization passes Android's AssetManager so model and token paths remain asset-relative. NAYRO supplies the active tokenized wake phrase list to `KeywordSpotter.createStream`; if that stream cannot be created, initialization must fail rather than silently falling back to a different keyword list.

## CI verification

The upstream Android integration needs the JNI bridge and ONNX Runtime shared object. C API and C++ API shared objects are not required for this Kotlin/JNI path. CI therefore requires the two actual runtime libraries for every ABI configured in the app, verifies the Kotlin API classes NAYRO imports, and validates the bundled KWS model files. It does not merely check that a file with an AAR extension exists.

Feature-branch pushes and pull-request validation download/cache and verify the runtime and compile/test the app, but do not assemble an APK. The debug APK is assembled only after a push/merge to `main` or a manual workflow dispatch on `main`.
