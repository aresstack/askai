package com.aresstack.askai.history;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Persistence round-trips, listing order and attachment storage for {@link ChatHistoryStore}. */
public class ChatHistoryStoreTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private ChatHistoryStore store() {
        return new ChatHistoryStore(folder.getRoot());
    }

    private static ChatRecord chat(String id, long createdAt, String userText) {
        ChatRecord record = new ChatRecord(id, createdAt);
        record.getMessages().add(new ChatMessageRecord(
                ChatMessageRecord.ROLE_USER, userText, createdAt, null, new ArrayList<AttachmentRecord>()));
        record.getMessages().add(new ChatMessageRecord(
                ChatMessageRecord.ROLE_ASSISTANT, "reply", createdAt + 1, "gemma", null));
        return record;
    }

    @Test
    public void savesAndLoadsRoundTrip() {
        ChatHistoryStore store = store();
        ChatRecord record = chat("id-1", 1000, "hello");
        record.setTitle("hello");
        record.setModel("gemma");
        store.save(record);

        ChatRecord loaded = store.load("id-1");
        assertNotNull(loaded);
        assertEquals("hello", loaded.getTitle());
        assertEquals("gemma", loaded.getModel());
        assertEquals(2, loaded.getMessages().size());
        assertTrue(loaded.getMessages().get(0).isUser());
        assertEquals("reply", loaded.getMessages().get(1).getText());
        assertEquals("gemma", loaded.getMessages().get(1).getModel());
    }

    @Test
    public void listIsNewestFirstAndSkipsEmpty() {
        ChatHistoryStore store = store();
        store.save(chat("old", 1000, "a"));
        store.save(chat("new", 5000, "b"));
        store.save(new ChatRecord("empty", 3000)); // no messages -> not persisted

        List<ChatRecord> list = store.list();
        assertEquals(2, list.size());
        assertEquals("new", list.get(0).getId());
        assertEquals("old", list.get(1).getId());
    }

    @Test
    public void emptyChatIsDeletedOnSave() {
        ChatHistoryStore store = store();
        store.save(chat("id-x", 1000, "a"));
        assertNotNull(store.load("id-x"));
        ChatRecord emptied = new ChatRecord("id-x", 1000);
        store.save(emptied);
        assertNull(store.load("id-x"));
    }

    @Test
    public void storesAttachmentWithMetadata() throws Exception {
        ChatHistoryStore store = store();
        File png = folder.newFile("pic.png");
        BufferedImage image = new BufferedImage(20, 12, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(image, "png", png);

        AttachmentRecord record = store.storeAttachment("chat-1", png, "image/png");
        assertNotNull(record);
        assertEquals("pic.png", record.getFileName());
        assertEquals("image/png", record.getMediaType());
        assertEquals(20, record.getWidth());
        assertEquals(12, record.getHeight());
        assertTrue(record.getChecksum().length() == 64);
        assertNotNull(store.attachmentFile("chat-1", record.getStoredName()));
    }

    @Test
    public void deleteRemovesChatAndAttachments() throws Exception {
        ChatHistoryStore store = store();
        store.save(chat("id-del", 1000, "a"));
        File png = folder.newFile("p.png");
        ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", png);
        AttachmentRecord record = store.storeAttachment("id-del", png, "image/png");

        store.delete("id-del");
        assertNull(store.load("id-del"));
        assertNull(store.attachmentFile("id-del", record.getStoredName()));
    }

    @Test
    public void differentBytesProduceDifferentChecksums() throws Exception {
        ChatHistoryStore store = store();
        File a = folder.newFile("a.png");
        File b = folder.newFile("b.png");
        ImageIO.write(solid(8, 8, 0xFF0000), "png", a);
        ImageIO.write(solid(8, 8, 0x00FF00), "png", b);
        AttachmentRecord ra = store.storeAttachment("c", a, "image/png");
        AttachmentRecord rb = store.storeAttachment("c", b, "image/png");
        assertTrue(!ra.getChecksum().equals(rb.getChecksum()));
    }

    private static BufferedImage solid(int w, int h, int rgb) {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                image.setRGB(x, y, rgb);
            }
        }
        return image;
    }
}
