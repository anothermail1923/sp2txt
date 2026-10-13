# Vosk models

Place extracted Vosk models in this directory. The default configuration expects:

```
models/vosk-model-small-en-us-0.15/
    am/
    conf/
    graph/
    ivector/
```

Download it with `./scripts/download-model.sh`, or manually from
https://alphacephei.com/vosk/models (extract the ZIP — the directory must contain
the model files, not the archive).

Model folders are git-ignored. To use a different model, set `speech.model-path`
(or the `SPEECH_MODEL_PATH` environment variable).
