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

/** Loads a local Vosk model once and creates a {@link Recognizer} per session. */
public class VoskRecognizerFactory implements RecognizerFactory {

    private static final Logger log =
            LoggerFactory.getLogger(VoskRecognizerFactory.class);

    private final Model model;
    private final String modelName;
    private final float sampleRate;
    private final ObjectMapper mapper;

    public VoskRecognizerFactory(
            Path modelPath,
            float sampleRate,
            ObjectMapper mapper) throws IOException {

        if (!Files.isDirectory(modelPath)) {
            throw new IllegalStateException(
                    "Vosk model not found: " + modelPath.toAbsolutePath()
                    + " (run scripts/download-model.sh or set speech.model-path)");
        }

        LibVosk.setLogLevel(LogLevel.WARNINGS);

        long start = System.nanoTime();
        this.model = new Model(modelPath.toString());
        log.info("Loaded Vosk model {} in {} ms",
                modelPath.toAbsolutePath(),
                (System.nanoTime() - start) / 1_000_000);

        this.modelName = modelPath.getFileName().toString();
        this.sampleRate = sampleRate;
        this.mapper = mapper;
    }

    @Override
    public StreamingRecognizer create() throws IOException {
        return new VoskStreamingRecognizer(new Recognizer(model, sampleRate));
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
