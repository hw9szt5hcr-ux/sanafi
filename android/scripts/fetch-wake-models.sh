#!/usr/bin/env bash
# Downloads the openWakeWord models the wake-word probe runs, and the clip its test listens to.
# The pre-trained models are licensed CC BY-NC-SA 4.0 (non-commercial), so they are fetched at
# build time instead of being stored in this repository.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
assets="$here/app/src/main/assets/oww"
clips="$here/core/src/test/resources/oww"
release="https://github.com/dscripka/openWakeWord/releases/download/v0.5.1"
mkdir -p "$assets" "$clips"
fetch() {
  [ -s "$2" ] || curl --fail --silent --show-error --location --retry 3 -o "$2" "$1"
}
fetch "$release/melspectrogram.onnx" "$assets/melspectrogram.onnx"
fetch "$release/embedding_model.onnx" "$assets/embedding_model.onnx"
fetch "$release/alexa_v0.1.onnx" "$assets/alexa_v0.1.onnx"
fetch "https://raw.githubusercontent.com/dscripka/openWakeWord/main/tests/data/alexa_test.wav" "$clips/alexa_test.wav"
fetch "https://raw.githubusercontent.com/dscripka/openWakeWord/main/tests/data/hey_mycroft_test.wav" "$clips/hey_mycroft_test.wav"
ls -l "$assets" "$clips"
