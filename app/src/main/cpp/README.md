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

llama.cpp is vendored into this directory's `llama/`, which is `.gitignore`d
(never committed — see the repo `.gitignore`). Pin the known-good tag so the
symbols below stay valid:

```bash
cd app/src/main/cpp
git clone https://github.com/ggml-org/llama.cpp llama
cd llama && git checkout b10045
```

Then build the app normally (`./gradlew assembleDebug`). Gradle detects
`llama/CMakeLists.txt` and compiles `libmink_llm.so` for the `arm64-v8a` and
`x86_64` ABIs declared in `build.gradle.kts`.

Because `llama/` is never committed, a plain checkout (CI included) has no
sources, builds no `.so`, and runs the fast rules-only app. To go back to that
locally, delete or rename `llama/`.

## API compatibility

`mink_llm.cpp` tracks the llama.cpp public C API at **`b10045`** (mid-2026).
The load-bearing symbols and the eras they belong to:

| Used here                                         | Superseded name (older tags)   |
| ------------------------------------------------- | ------------------------------ |
| `llama_model_load_from_file`                      | `llama_load_model_from_file`   |
| `llama_init_from_model`                           | `llama_new_context_with_model` |
| `llama_model_free`                                | `llama_free_model`             |
| `llama_memory_clear(llama_get_memory(ctx), true)` | `llama_kv_self_clear` / `llama_kv_cache_clear` |

The vocab-based `llama_tokenize` / `llama_token_to_piece` (first arg
`const llama_vocab *`) also postdate the pre-2025 model-based signatures. If you
re-pin an older tag, expect to reverse these.

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

- FULL tier: `MiniCPM5-1B-Q8_0.gguf` (~1.15 GB)
- LITE / MINIMAL tiers: `MiniCPM5-1B-Q4_K_M.gguf` (~0.69 GB)

from `openbmb/MiniCPM5-1B-GGUF` on HuggingFace (case-sensitive names; the
`resolve/main/` URL 404s on the wrong case). Downloads are opt-in, resumable,
and refuse a metered connection unless the user allows it.
