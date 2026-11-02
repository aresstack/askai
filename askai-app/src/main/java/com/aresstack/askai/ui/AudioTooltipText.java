package com.aresstack.askai.ui;

import com.aresstack.audio.pipeline.AudioBlockDescriptor;
import com.aresstack.audio.pipeline.AudioBlockRegistry;
import com.aresstack.audio.pipeline.AudioDspHelp;
import com.aresstack.audio.pipeline.AudioParameterChoice;
import com.aresstack.audio.pipeline.AudioParameterDescriptor;
import com.aresstack.audio.pipeline.AudioParameterType;
import com.aresstack.audio.profile.AudioBlockDefinition;
import com.aresstack.audio.profile.AudioBlockType;

import java.util.List;

/** Build compact HTML tooltips for DSP blocks and their descriptor-driven parameter editors. */
final class AudioTooltipText {

    private static final int TOOLTIP_WIDTH = 340;

    private AudioTooltipText() {
    }

    /** Return a tooltip that explains one pipeline block and its current state. */
    static String block(AudioBlockDefinition block) {
        if (block == null) {
            return null;
        }
        AudioBlockDescriptor descriptor = AudioBlockRegistry.getInstance().descriptor(block.getType());
        StringBuilder body = new StringBuilder();
        body.append(AudioDspHelp.blockDescription(block.getType()));
        body.append("<br><br><b>Category:</b> ").append(escape(descriptor.getCategory().getDisplayName()));
        body.append("<br><b>Status:</b> ").append(block.isEnabled() ? "Enabled" : "Bypassed");
        body.append("<br><b>Current:</b> ").append(escape(descriptor.summarize(block)));
        return html(descriptor.getDisplayName(), body.toString());
    }

    /** Return a tooltip that explains a block type in the function selector. */
    static String blockType(AudioBlockType type) {
        if (type == null) {
            return null;
        }
        return html(type.getDisplayName(), escape(AudioDspHelp.blockDescription(type)));
    }

    /** Return a tooltip that explains one parameter and includes its accepted values. */
    static String parameter(AudioBlockType type, AudioParameterDescriptor parameter) {
        if (parameter == null) {
            return null;
        }
        StringBuilder body = new StringBuilder();
        body.append(escape(AudioDspHelp.parameterDescription(type, parameter.getKey())));
        appendAcceptedValues(body, parameter);
        body.append("<br><b>Default:</b> ").append(escape(displayValue(parameter, parameter.getDefaultValue())));
        return html(parameter.getLabel(), body.toString());
    }

    /** Return a tooltip that presents a validation message before the normal parameter help. */
    static String validation(String message, String normalHelp) {
        if (message == null || message.trim().isEmpty()) {
            return normalHelp;
        }
        StringBuilder body = new StringBuilder();
        body.append("<b>Validation:</b> ").append(escape(message));
        if (normalHelp != null && normalHelp.startsWith("<html>")) {
            String stripped = stripHtmlWrapper(normalHelp);
            body.append("<hr>").append(stripped);
            return "<html><body style='width:" + TOOLTIP_WIDTH + "px'>" + body + "</body></html>";
        }
        if (normalHelp != null && !normalHelp.trim().isEmpty()) {
            body.append("<hr>").append(escape(normalHelp));
        }
        return html("Invalid setting", body.toString());
    }

    private static void appendAcceptedValues(StringBuilder body, AudioParameterDescriptor parameter) {
        AudioParameterType type = parameter.getType();
        if (type == AudioParameterType.INTEGER || type == AudioParameterType.DECIMAL) {
            body.append("<br><b>Range:</b> ")
                    .append(escape(number(parameter.getMinimum())))
                    .append(" to ")
                    .append(escape(number(parameter.getMaximum())));
            if (parameter.getStep() > 0.0d) {
                body.append(" (step ").append(escape(number(parameter.getStep()))).append(')');
            }
            return;
        }
        if (type == AudioParameterType.BOOLEAN) {
            body.append("<br><b>Values:</b> enabled or disabled");
            return;
        }
        if (type == AudioParameterType.CHOICE) {
            body.append("<br><b>Choices:</b> ");
            List<AudioParameterChoice> choices = parameter.getChoices();
            for (int i = 0; i < choices.size(); i++) {
                if (i > 0) {
                    body.append(", ");
                }
                body.append(escape(choices.get(i).getLabel()));
            }
            return;
        }
        body.append("<br><b>Format:</b> free text as described above");
    }

    private static String displayValue(AudioParameterDescriptor parameter, String value) {
        if (parameter.getType() == AudioParameterType.CHOICE) {
            List<AudioParameterChoice> choices = parameter.getChoices();
            for (int i = 0; i < choices.size(); i++) {
                if (choices.get(i).getValue().equals(value)) {
                    return choices.get(i).getLabel();
                }
            }
        }
        return value;
    }

    private static String number(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value)) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    private static String html(String title, String body) {
        return "<html><body style='width:" + TOOLTIP_WIDTH + "px'><b>" + escape(title)
                + "</b><br>" + body + "</body></html>";
    }

    private static String stripHtmlWrapper(String html) {
        int bodyStart = html.indexOf('>');
        bodyStart = html.indexOf('>', bodyStart + 1);
        int bodyEnd = html.lastIndexOf("</body>");
        if (bodyStart >= 0 && bodyEnd > bodyStart) {
            return html.substring(bodyStart + 1, bodyEnd);
        }
        return escape(html);
    }

    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;")
                .replace("\n", "<br>");
    }
}
