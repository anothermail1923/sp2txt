package com.example.speech.recognizer;

/**
 * A recognizer for one audio stream. Implementations are not thread-safe;
 * callers must make sure only one thread uses an instance at a time.
 */
public interface StreamingRecognizer extends AutoCloseable {

    /**
     * Feeds a chunk of mono signed 16-bit little-endian PCM audio.
     *
     * @return {@code true} when a phrase has been completed and
     *         {@link #result()} should be read
     */
    boolean acceptAudio(byte[] pcm, int length);

    /** Provisional text for the phrase currently being spoken. */
    String partialResult();

    /** Text of the phrase that was just completed. */
    String result();

    /** Flushes any buffered audio and returns the remaining text. */
    String finalResult();

    @Override
    void close();
}
