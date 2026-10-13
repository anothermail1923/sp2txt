package com.example.speech;

import com.example.speech.recognizer.RecognizerFactory;
import com.example.speech.recognizer.StreamingRecognizer;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Deterministic stand-in for Vosk: every third audio chunk completes the phrase
 * "hello world"; STOP flushes "goodbye" if audio arrived since the last phrase.
 */
class FakeRecognizerFactory implements RecognizerFactory {

    final List<FakeRecognizer> created = new CopyOnWriteArrayList<>();

    @Override
    public StreamingRecognizer create() {
        FakeRecognizer recognizer = new FakeRecognizer();
        created.add(recognizer);
        return recognizer;
    }

    @Override
    public String engine() {
        return "fake";
    }

    @Override
    public String model() {
        return "fake-model";
    }

    @Override
    public float sampleRate() {
        return 16000;
    }

    @Override
    public void close() {
    }

    static final class FakeRecognizer implements StreamingRecognizer {

        private static final String[] WORDS = {"hello", "hello world"};

        volatile boolean closed;
        private int chunks;

        @Override
        public boolean acceptAudio(byte[] pcm, int length) {
            if (closed) {
                throw new IllegalStateException("recognizer used after close");
            }
            chunks++;
            return chunks == 3;
        }

        @Override
        public String partialResult() {
            return WORDS[Math.min(chunks, WORDS.length) - 1];
        }

        @Override
        public String result() {
            chunks = 0;
            return "hello world";
        }

        @Override
        public String finalResult() {
            return chunks > 0 ? "goodbye" : "";
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
