# Native guardian bridge (llama.cpp)

Mink's guardian can run MiniCPM5-1B entirely on device through a thin JNI
wrapper over [llama.cpp](https://github.com/ggml-org/llama.cpp). This directory
holds that wrapper (`mink_llm.cpp`) and its `CMakeLists.txt`.

## The app builds without this

The native build is optional. `app/build.gradle.kts` only wires the CMake build
when both of these exist:

- `app/src/main/cpp/CMakeLists.txt` (this directory), and
- `app/src/main/cpp/llama/` (the vendored llama.cpp sources).

If `llama/` is absent, no `.so` is produced, `LlamaBridge.isAvailable` stays
`false` at runtime, and the guardian degrades to the deterministic
`RulesEngine`. Nothing else needs to change.

## Vendoring llama.cpp

From the repo root:

```bash
cd app/src/main/cpp
git clone https://github.com/ggml-org/llama.cpp llama
cd llama
# Pin a known-good commit so the symbol names below stay valid.
git checkout b4400
```

Then build the app normally (`./gradlew assembleDebug`). Gradle detects the
`llama/` directory and compiles `libmink_llm.so` for the `arm64-v8a` and
`x86_64` ABIs declared in `build.gradle.kts`.

To go back to the rules-only build, delete or rename the `llama/` directory.

## API compatibility

`mink_llm.cpp` targets a recent (2025) llama.cpp API. If you pin an older tag,
adjust these symbols:

| Used here                     | Older name                      |
| ----------------------------- | ------------------------------- |
| `llama_model_load_from_file`  | `llama_load_model_from_file`    |
| `llama_init_from_model`       | `llama_new_context_with_model`  |
| `llama_model_free`            | `llama_free_model`              |
| `llama_kv_self_clear`         | `llama_kv_cache_clear`          |

The wrapper exposes exactly the five `external fun`s in
`com.mink.guardian.llm.LlamaBridge`:

- `nativeInit(modelPath, nCtx, nThreads, nGpuLayers)` returns an opaque handle.
- `nativePrompt(handle, prompt)` clears the context and evaluates the prompt.
- `nativeSampleToken(handle, temperature, topP)` samples one token and returns
  its decoded piece, or an empty string at end of stream. Split multi-byte
  UTF-8 characters are buffered so the JNI string is always valid.
- `nativeResetContext(handle)` clears the KV cache for reuse.
- `nativeFree(handle)` releases the context and model.

## Models

The `ModelManager` downloads the GGUF for the device tier into
`filesDir/models/`:

- FULL tier: `minicpm5-1b-q8_0.gguf`
- LITE / MINIMAL tiers: `minicpm5-1b-q4_k_m.gguf`

from `openbmb/MiniCPM5-1B-GGUF` on HuggingFace. Downloads are opt-in, resumable,
and refuse a metered connection unless the user allows it.
