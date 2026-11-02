package com.aresstack.askai.audio.transfer;

import java.util.ArrayList;
import java.util.List;

/** One profile inside the transfer envelope. {@code builtIn} is advisory only — imports always force false. */
public final class TransferProfile {

    public String id;
    public String name;
    public boolean builtIn;
    public List<TransferBlock> blocks = new ArrayList<TransferBlock>();

    public TransferProfile() {
    }

    public TransferProfile(String id, String name, boolean builtIn, List<TransferBlock> blocks) {
        this.id = id;
        this.name = name;
        this.builtIn = builtIn;
        this.blocks = blocks == null ? new ArrayList<TransferBlock>() : blocks;
    }
}
