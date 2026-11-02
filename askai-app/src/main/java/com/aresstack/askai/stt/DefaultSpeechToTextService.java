package com.aresstack.askai.stt;

import com.aresstack.askai.audio.format.SupportedAudioFormats;
import com.aresstack.askai.config.AppConfiguration;
import com.aresstack.askai.config.AppConfigurationRepository;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Default {@link SpeechToTextService}: validates the request locally (existence, format, size and
 * whether speech-to-text is enabled at all), then delegates to the backend adapter selected by the
 * persisted {@link SpeechToTextConfiguration}. Configuration is re-read per call so settings changes
 * apply without a restart. Runs off the EDT; cancellation aborts the in-flight HTTP request.
 */
public final class DefaultSpeechToTextService implements SpeechToTextService {

    private final AppConfigurationRepository configurationRepository;
    private final ExecutorService executor;

    public DefaultSpeechToTextService(AppConfigurationRepository configurationRepository) {
        this.configurationRepository = configurationRepository;
        this.executor = Executors.newCachedThreadPool(new DaemonThreadFactory());
    }

    /** @return the file-chooser extensions the MVP accepts. */
    public static String[] supportedExtensions() {
        return SupportedAudioFormats.extensionArray();
    }

    @Override
    public Task transcribe(final TranscriptionRequest request, final TranscriptionListener listener) {
        final AppConfiguration appConfiguration = configurationRepository.load();
        final SpeechToTextConfiguration configuration = appConfiguration.getSpeechToTextConfiguration();
        final OllamaSpeechToTextClient client = new OllamaSpeechToTextClient(
                appConfiguration.getOllamaBaseUrl(), configuration.getTimeoutSeconds());
        final AtomicBoolean cancelled = new AtomicBoolean(false);

        final Future<?> future = executor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    validate(request, configuration);
                    String text = client.transcribe(effectiveRequest(request, configuration));
                    if (!cancelled.get()) {
                        listener.onTranscription(text);
                    }
                } catch (Exception ex) {
                    if (cancelled.get()) {
                        listener.onError(new SpeechToTextException("Transcription cancelled."));
                    } else {
                        listener.onError(ex);
                    }
                }
            }
        });

        return new Task() {
            @Override
            public void cancel() {
                cancelled.set(true);
                client.abort();
                future.cancel(true);
            }
        };
    }

    /** Fills request gaps from the persisted configuration (language, context prompt). */
    private TranscriptionRequest effectiveRequest(TranscriptionRequest request,
                                                  SpeechToTextConfiguration configuration) {
        String language = request.getLanguage().length() > 0
                ? request.getLanguage() : configuration.getLanguage();
        String prompt = request.getPrompt().length() > 0
                ? request.getPrompt() : configuration.getPrompt();
        return new TranscriptionRequest(request.getAudioFile(), request.getModelName(), language, prompt);
    }

    private void validate(TranscriptionRequest request, SpeechToTextConfiguration configuration)
            throws SpeechToTextException {
        if (!configuration.isEnabled()) {
            throw new SpeechToTextException(
                    "Speech-to-Text is disabled. Enable it under Configuration > Connections.");
        }
        File file = request.getAudioFile();
        if (file == null || !file.isFile()) {
            throw new SpeechToTextException("The selected audio file does not exist.");
        }
        String extension = SupportedAudioFormats.extensionOf(file.getName());
        if (!SupportedAudioFormats.extensions().contains(extension)) {
            throw new SpeechToTextException("Unsupported audio format \"" + extension + "\". "
                    + "Supported formats: " + join(SupportedAudioFormats.extensions()) + ".");
        }
        long maxBytes = configuration.getMaxFileSizeMb() * 1024L * 1024L;
        if (file.length() > maxBytes) {
            throw new SpeechToTextException("The audio file is too large ("
                    + (file.length() / (1024L * 1024L)) + " MB). The configured limit is "
                    + configuration.getMaxFileSizeMb() + " MB.");
        }
        if (request.getModelName().length() == 0) {
            throw new SpeechToTextException("No STT model configured and no chat model selected. "
                    + "Set a Speech-to-Text model under Configuration > Connections.");
        }
    }

    private static String join(List<String> values) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(values.get(i));
        }
        return builder.toString();
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "askai-stt-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
