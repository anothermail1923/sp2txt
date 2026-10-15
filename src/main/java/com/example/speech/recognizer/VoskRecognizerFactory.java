package com.example.speech.recognizer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Loads a local Vosk model once and creates a {@link Recognizer} per session. */
public class VoskRecognizerFactory implements RecognizerFactory {

    private static final Logger log =
            LoggerFactory.getLogger(VoskRecognizerFactory.class);

    private final Model model;
    private final String modelName;
    private final float sampleRate;
    private final ObjectMapper mapper;

    /**
     * Recognizers built ahead of time. Building one takes 200-400 ms with the small model,
     * which the user would otherwise wait for before every dictation. Used ones are closed,
     * never reused: Recognizer.reset() leaves stray words from the previous dictation.
     */
    private final BlockingQueue<Recognizer> warm;
    private final ExecutorService builder = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "vosk-warmup");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean closed;

    /** @param warmCount recognizers to keep ready (0: build each one on demand) */
    public VoskRecognizerFactory(Path modelPath, float sampleRate, ObjectMapper mapper, int warmCount) throws IOException {

        if (!Files.isDirectory(modelPath)) {
            throw new IllegalStateException(
                    "Vosk model not found: " + modelPath.toAbsolutePath()
                    + " (run scripts/download-model.sh or set speech.model-path)");
        }

        LibVosk.setLogLevel(LogLevel.WARNINGS);

        long start = System.nanoTime();
        this.model = new Model(modelPath.toString());
        log.info("Loaded Vosk model {} in {} ms", modelPath.toAbsolutePath(), (System.nanoTime() - start) / 1_000_000);

        this.modelName = modelPath.getFileName().toString();
        this.sampleRate = sampleRate;
        this.mapper = mapper;
        this.warm = new ArrayBlockingQueue<>(Math.max(1, warmCount));
        for (int i = 0; i < warmCount; i++) refill();
    }

    @Override
    public StreamingRecognizer create() throws IOException {
        Recognizer ready = warm.poll();
        if (ready == null) {
            ready = new Recognizer(model, sampleRate); // none built yet: build one now
        }
        if (warm.remainingCapacity() > 0 && !closed) refill();
        return new VoskStreamingRecognizer(ready);
    }

    /** Builds one recognizer in the background for a later session. */
    private void refill() {
        builder.execute(() -> {
            if (closed || warm.remainingCapacity() == 0) return;
            try {
                Recognizer recognizer = new Recognizer(model, sampleRate);
                if (closed || !warm.offer(recognizer)) recognizer.close();
            } catch (IOException | RuntimeException e) {
                log.warn("Could not pre-build a recognizer: {}", e.toString());
            }
        });
    }

    @Override
    public String engine() {
        return "vosk";
    }

    @Override
    public String model() {
        return modelName;
    }

    @Override
    public float sampleRate() {
        return sampleRate;
    }

    @Override
    public void close() {
        closed = true;
        builder.shutdown();
        try {
            builder.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Recognizer spare;
        while ((spare = warm.poll()) != null) spare.close();
        model.close();
    }

    private final class VoskStreamingRecognizer implements StreamingRecognizer {

        private final Recognizer recognizer;

        private VoskStreamingRecognizer(Recognizer recognizer) {
            this.recognizer = recognizer;
        }

        @Override
        public boolean acceptAudio(byte[] pcm, int length) {
            return recognizer.acceptWaveForm(pcm, length);
        }

        @Override
        public String partialResult() {
            return field(recognizer.getPartialResult(), "partial");
        }

        @Override
        public String result() {
            return field(recognizer.getResult(), "text");
        }

        @Override
        public String finalResult() {
            return field(recognizer.getFinalResult(), "text");
        }

        @Override
        public void close() {
            recognizer.close();
        }

        private String field(String json, String name) {
            try {
                return mapper.readTree(json).path(name).asText("");
            } catch (JsonProcessingException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
