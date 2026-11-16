package com.aresstack.askai.localruntime.onnx;

/** {@code tokenizer.json} nutzt eine Form, die der Client nicht kennt; die Meldung nennt sie. */
final class TokenizerFormatException extends Exception {

    private static final long serialVersionUID = 1L;

    TokenizerFormatException(String message) {
        super(message);
    }
}
