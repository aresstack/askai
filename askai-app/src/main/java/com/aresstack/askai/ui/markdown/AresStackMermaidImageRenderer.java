package com.aresstack.askai.ui.markdown;

import com.aresstack.Mermaid;

import java.awt.image.BufferedImage;

/** Adapt the AresStack Mermaid API to the UI rendering port. */
public final class AresStackMermaidImageRenderer implements MermaidImageRenderer {

    @Override
    public BufferedImage render(String diagramCode, int width) {
        return Mermaid.renderToImage(diagramCode, width);
    }
}
