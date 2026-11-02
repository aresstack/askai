package com.aresstack.askai.history;

/**
 * Persisted metadata for one image attachment of a user message.  The bytes live in a separate
 * file under the chat's attachment directory ({@link #storedName}); this record only carries the
 * information needed to restore the thumbnail and re-send the image.
 */
public final class AttachmentRecord {

    private String id;
    private String fileName;
    private String mediaType;
    private long size;
    private int width;
    private int height;
    private String checksum;
    private String storedName;

    /** Gson. */
    public AttachmentRecord() {
    }

    public AttachmentRecord(String id, String fileName, String mediaType, long size,
                            int width, int height, String checksum, String storedName) {
        this.id = id;
        this.fileName = fileName;
        this.mediaType = mediaType;
        this.size = size;
        this.width = width;
        this.height = height;
        this.checksum = checksum;
        this.storedName = storedName;
    }

    public String getId() { return id; }
    public String getFileName() { return fileName; }
    public String getMediaType() { return mediaType; }
    public long getSize() { return size; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public String getChecksum() { return checksum; }

    /** File name of the stored copy inside the chat's attachment directory. */
    public String getStoredName() { return storedName; }
}
