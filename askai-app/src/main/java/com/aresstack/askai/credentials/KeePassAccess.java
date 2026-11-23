package com.aresstack.askai.credentials;

import java.io.IOException;
import java.util.List;

/**
 * What the settings page and the setup wizard need from KeePass: connect (pair) and list entries, read an entry's URL.
 * The production implementation is {@link AskAiKeePass}; tests fake it. Every method except {@link #paired()} blocks.
 */
public interface KeePassAccess {

    /** Whether a pairing key is stored (no KeePass call, so also on the EDT). */
    boolean paired();

    /** Connects when needed (KeePass shows a one-time password) and lists the entry titles. */
    List<String> titles() throws IOException;

    /** The URL field of an entry ("" when it has none), read without a pairing dialog. */
    String location(String title) throws IOException;

    /** The entry's password (the API key), read without a pairing dialog; the caller clears it. */
    char[] apiKey(String title) throws IOException;

    /** Forgets the pairing (the entry in KeePass stays). */
    void unpair();

    /** Stands in where no KeePass is wired (tests, tools): every call says so. */
    KeePassAccess NONE = new KeePassAccess() {
        @Override
        public boolean paired() {
            return false;
        }

        @Override
        public List<String> titles() throws IOException {
            throw new IOException("KeePass is not available here");
        }

        @Override
        public String location(String title) throws IOException {
            throw new IOException("KeePass is not available here");
        }

        @Override
        public char[] apiKey(String title) throws IOException {
            throw new IOException("KeePass is not available here");
        }

        @Override
        public void unpair() {
        }
    };
}
