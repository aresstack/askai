package com.aresstack.askai.research.book;

import com.aresstack.askai.research.outline.Outline;
import com.aresstack.askai.research.outline.OutlineJson;
import com.aresstack.askai.research.store.FileOutlineStore;
import com.aresstack.askai.research.transition.CorruptProjectFileException;

/**
 * The outline approved at {@code APPROVE_OUTLINE} (D4): what the Document phase reads, verified against the hash of
 * the valid outline pin, never the working copy.
 */
public final class ApprovedOutline {

    private final OutlinePin pin;
    private final Outline outline;

    private ApprovedOutline(OutlinePin pin, Outline outline) {
        this.pin = pin;
        this.outline = outline;
    }

    /**
     * @return {@code null} while no committed outline pin exists
     * @throws CorruptProjectFileException when the manifest is damaged or the approved revision does not match it
     */
    public static ApprovedOutline read(BookManifest manifest, FileOutlineStore store,
                                       BookManifest.CommitCheck commits) throws CorruptProjectFileException {
        OutlinePin pin = manifest.validOutlinePin(commits);
        if (pin == null || store == null) {
            return null;
        }
        String content = store.approvedContent(pin.getApprovedRevision());
        if (content == null || !FileOutlineStore.sha256(content).equals(pin.getContentHash())) {
            throw new CorruptProjectFileException(manifest.file(), "The approved outline revision "
                    + pin.getApprovedRevision() + " does not match the book manifest.");
        }
        return new ApprovedOutline(pin, OutlineJson.read(content));
    }

    public OutlinePin getPin() {
        return pin;
    }

    public int getRevision() {
        return pin.getApprovedRevision();
    }

    public Outline getOutline() {
        return outline;
    }
}
