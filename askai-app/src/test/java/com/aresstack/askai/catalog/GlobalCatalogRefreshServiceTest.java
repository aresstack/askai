package com.aresstack.askai.catalog;

import com.aresstack.audio.profile.AudioProcessingProfile;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The global refresh loads each catalog independently, notifies subscribers, and never runs twice at once. */
public class GlobalCatalogRefreshServiceTest {

    private static final Consumer<Runnable> INLINE = new Consumer<Runnable>() {
        public void run(Runnable r) { r.run(); }
        public void accept(Runnable r) { r.run(); }
    };

    private static final AudioProcessingProfile PROFILE =
            new AudioProcessingProfile("id1", "Clean", false, new ArrayList<com.aresstack.audio.profile.AudioBlockDefinition>());

    @Test
    public void loadsAllCatalogsAndNotifiesSubscribers() throws Exception {
        GlobalCatalogRefreshService service = new GlobalCatalogRefreshService(
                loader(asList("m1", "m2")), loader(asList("audio1")), loader(asList(PROFILE)), INLINE);

        AtomicBoolean started = new AtomicBoolean();
        AtomicReference<GlobalCatalogSnapshot> received = new AtomicReference<GlobalCatalogSnapshot>();
        CountDownLatch done = new CountDownLatch(1);
        service.subscribe(new GlobalCatalogRefreshService.Listener() {
            public void onRefreshStarted() { started.set(true); }
            public void onCatalogRefreshed(GlobalCatalogSnapshot snapshot) {
                received.set(snapshot);
                done.countDown();
            }
        });

        assertTrue(service.refresh());
        assertTrue(done.await(5, TimeUnit.SECONDS));

        assertTrue(started.get());
        GlobalCatalogSnapshot snapshot = received.get();
        assertTrue(snapshot.isModelsLoaded());
        assertEquals(asList("m1", "m2"), snapshot.getChatModels());
        assertTrue(snapshot.isAudioModelsLoaded());
        assertEquals(asList("audio1"), snapshot.getAudioModels());
        assertTrue(snapshot.isProfilesLoaded());
        assertEquals(1, snapshot.getAudioProfiles().size());
        assertFalse(snapshot.hasFailures());
    }

    @Test
    public void aFailingCatalogDoesNotDiscardTheOthers() throws Exception {
        GlobalCatalogRefreshService service = new GlobalCatalogRefreshService(
                new GlobalCatalogRefreshService.CatalogLoader<String>() {
                    public List<String> load() throws Exception { throw new RuntimeException("boom"); }
                },
                loader(asList("audio1")), loader(asList(PROFILE)), INLINE);

        GlobalCatalogSnapshot snapshot = refreshAndWait(service);

        assertFalse("models failed", snapshot.isModelsLoaded());
        assertTrue("failed model list is empty, not applied", snapshot.getChatModels().isEmpty());
        assertTrue("audio models still loaded", snapshot.isAudioModelsLoaded());
        assertTrue("profiles still loaded", snapshot.isProfilesLoaded());
        assertTrue(snapshot.hasFailures());
    }

    @Test
    public void doesNotStartASecondRefreshWhileOneIsRunning() throws Exception {
        final CountDownLatch block = new CountDownLatch(1);
        GlobalCatalogRefreshService service = new GlobalCatalogRefreshService(
                new GlobalCatalogRefreshService.CatalogLoader<String>() {
                    public List<String> load() throws Exception {
                        block.await();
                        return Collections.emptyList();
                    }
                },
                loader(Collections.<String>emptyList()), loader(Collections.<AudioProcessingProfile>emptyList()),
                INLINE);

        assertTrue("first refresh starts", service.refresh());
        assertFalse("second refresh is refused while the first runs", service.refresh());
        block.countDown();
    }

    private static GlobalCatalogSnapshot refreshAndWait(GlobalCatalogRefreshService service) throws Exception {
        final AtomicReference<GlobalCatalogSnapshot> received = new AtomicReference<GlobalCatalogSnapshot>();
        final CountDownLatch done = new CountDownLatch(1);
        service.subscribe(new GlobalCatalogRefreshService.Listener() {
            public void onRefreshStarted() { }
            public void onCatalogRefreshed(GlobalCatalogSnapshot snapshot) {
                received.set(snapshot);
                done.countDown();
            }
        });
        service.refresh();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        return received.get();
    }

    private static <T> GlobalCatalogRefreshService.CatalogLoader<T> loader(final List<T> value) {
        return new GlobalCatalogRefreshService.CatalogLoader<T>() {
            public List<T> load() {
                return value;
            }
        };
    }
}
