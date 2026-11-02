package com.aresstack.askai.speech;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Preflight separates server/endpoint availability from audio-model capability, and caches. */
public class SpeechToTextReadinessServiceTest {

    private final AudioModelResolver.AudioModelResolution ready =
            AudioModelResolver.AudioModelResolution.resolved("voxtral:latest");

    @Test
    public void readyWhenServerEndpointAndModelAllGood() {
        FakeProbe probe = new FakeProbe("0.1.29", true);
        SpeechToTextReadinessService service = new SpeechToTextReadinessService(probe, req -> ready);
        assertEquals(ReadinessStatus.READY, service.check("Automatic"));
    }

    @Test
    public void serverUnreachable() {
        FakeProbe probe = new FakeProbe(null, true); // version() throws
        SpeechToTextReadinessService service = new SpeechToTextReadinessService(probe, req -> ready);
        assertEquals(ReadinessStatus.SERVER_UNREACHABLE, service.check("Automatic"));
    }

    @Test
    public void endpointMissing() {
        FakeProbe probe = new FakeProbe("0.1.10", false);
        SpeechToTextReadinessService service = new SpeechToTextReadinessService(probe, req -> ready);
        assertEquals(ReadinessStatus.SERVER_ENDPOINT_UNAVAILABLE, service.check("Automatic"));
    }

    @Test
    public void mapsModelResolutionStatuses() {
        FakeProbe probe = new FakeProbe("0.1.29", true);
        assertEquals(ReadinessStatus.NO_AUDIO_MODEL, new SpeechToTextReadinessService(probe,
                req -> resolution(AudioModelResolver.AudioModelResolution.Status.NO_AUDIO_MODEL)).check(""));
        assertEquals(ReadinessStatus.MODEL_CAPABILITY_UNKNOWN, new SpeechToTextReadinessService(probe,
                req -> resolution(AudioModelResolver.AudioModelResolution.Status.CAPABILITY_UNKNOWN)).check(""));
        assertEquals(ReadinessStatus.MODEL_NOT_AUDIO_CAPABLE, new SpeechToTextReadinessService(probe,
                req -> resolution(AudioModelResolver.AudioModelResolution.Status.NOT_AUDIO_CAPABLE)).check(""));
    }

    @Test
    public void cachesUntilInvalidated() {
        FakeProbe probe = new FakeProbe("0.1.29", true);
        SpeechToTextReadinessService service = new SpeechToTextReadinessService(probe, req -> ready);
        service.check("Automatic");
        service.check("Automatic");
        assertEquals("endpoint probed once while cached", 1, probe.endpointCalls);
        service.invalidate();
        service.check("Automatic");
        assertEquals("re-probed after invalidate", 2, probe.endpointCalls);
    }

    private static AudioModelResolver.AudioModelResolution resolution(
            AudioModelResolver.AudioModelResolution.Status status) {
        return new AudioModelResolver.AudioModelResolution(status, "", "");
    }

    private static final class FakeProbe implements ServerProbe {
        private final String version;
        private final boolean endpoint;
        int endpointCalls;

        FakeProbe(String version, boolean endpoint) {
            this.version = version;
            this.endpoint = endpoint;
        }

        public String serverKey() {
            return "http://localhost:11434";
        }

        public String version() throws Exception {
            if (version == null) {
                throw new Exception("connection refused");
            }
            return version;
        }

        public boolean endpointAvailable() {
            endpointCalls++;
            return endpoint;
        }
    }
}
