package com.aresstack.askai.localruntime.onnx;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Reads the flat files of an installed ONNX model directory. */
final class OnnxModelFiles {

    private OnnxModelFiles() {
    }

    static HfTokenizer tokenizer(Path directory) throws IOException {
        Path file = directory.resolve(OnnxModelDescriptor.LOCAL_TOKENIZER);
        if (!Files.isRegularFile(file)) {
            throw new IOException("tokenizer.json missing in " + directory);
        }
        try {
            return HfTokenizer.load(file);
        } catch (TokenizerFormatException unsupported) {
            throw new IOException("tokenizer.json not usable: " + unsupported.getMessage(), unsupported);
        }
    }

    /** {@code pad_token_id} from config.json (BERT 0, XLM-R 1); 0 when absent. */
    static long padId(Path directory) throws IOException {
        Map<String, Object> config = Json.readIfPresent(directory.resolve(OnnxModelDescriptor.LOCAL_CONFIG));
        return Json.integer(config.get("pad_token_id"), 0);
    }
}
