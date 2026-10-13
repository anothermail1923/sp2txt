package com.example.speech.recognizer;

/** Creates per-session recognizers from a shared, already loaded model. */
public interface RecognizerFactory extends AutoCloseable {

    StreamingRecognizer create() throws Exception;

    /** Engine name, e.g. {@code vosk}. */
    String engine();

    /** Identifier of the loaded model, e.g. its directory name. */
    String model();

    float sampleRate();

    @Override
    void close();
}
