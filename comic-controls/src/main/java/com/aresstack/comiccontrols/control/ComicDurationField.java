package com.aresstack.comiccontrols.control;

import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A duration as a number spinner plus a unit choice (seconds / minutes / hours) — instead of a free text
 * field the user has to fill with "45m" or "300" by heart. Reads and writes the Go/Ollama duration
 * syntax ({@code 45m}, {@code 2h}, {@code 300s}, a bare number = seconds) and plain second counts.
 */
public class ComicDurationField extends JPanel {

    private static final long serialVersionUID = 1L;

    private static final String[] UNITS = {"seconds", "minutes", "hours"};
    private static final String[] SUFFIXES = {"s", "m", "h"};
    private static final long[] FACTORS = {1L, 60L, 3600L};
    private static final Pattern DURATION = Pattern.compile("\\s*(\\d+)\\s*(ms|s|m|h)?\\s*");

    private final JSpinner amount;
    private final JComboBox<String> unit = ComicControls.<String>comboBox(UNITS);

    /** A field allowing 0 … {@code maximum} of the chosen unit. */
    public ComicDurationField(int maximum) {
        super(new GridBagLayout());
        setOpaque(false);
        amount = ComicControls.spinner(new SpinnerNumberModel(0, 0, maximum, 1));
        ComicControls.fitColumns(amount, 5);
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = 0;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(0, 0, 0, ComicForm.INLINE_GAP);
        add(amount, c);
        c.insets = new Insets(0, 0, 0, 0);
        add(unit, c);
    }

    /** The duration in seconds. */
    public long getSeconds() {
        return ((Number) amount.getValue()).longValue() * FACTORS[unit.getSelectedIndex()];
    }

    /** Shows {@code seconds} in the largest unit that divides it evenly. */
    public void setSeconds(long seconds) {
        int index = 0;
        for (int i = FACTORS.length - 1; i > 0; i--) {
            if (seconds > 0 && seconds % FACTORS[i] == 0) {
                index = i;
                break;
            }
        }
        unit.setSelectedIndex(index);
        amount.setValue((int) Math.min(Integer.MAX_VALUE, seconds / FACTORS[index]));
    }

    /** The duration in Go/Ollama syntax ({@code 45m}, {@code 2h}, {@code 30s}). */
    public String getText() {
        return amount.getValue() + SUFFIXES[unit.getSelectedIndex()];
    }

    /**
     * Reads {@code 45m}, {@code 2h}, {@code 300s}, {@code 300} (seconds) or {@code 1500ms} (rounded to
     * seconds); anything else leaves the field unchanged.
     */
    public void setText(String text) {
        if (text == null) {
            return;
        }
        Matcher matcher = DURATION.matcher(text);
        if (!matcher.matches()) {
            return;
        }
        long value = Long.parseLong(matcher.group(1));
        String suffix = matcher.group(2);
        if (suffix == null) {
            suffix = "s";
        } else if ("ms".equals(suffix)) {
            value = (value + 500L) / 1000L;
            suffix = "s";
        }
        int index = java.util.Arrays.asList(SUFFIXES).indexOf(suffix);
        unit.setSelectedIndex(index);
        amount.setValue((int) Math.min(Integer.MAX_VALUE, value));
    }

    /** Notified on every change of the amount or the unit. */
    public void addChangeListener(final javax.swing.event.ChangeListener listener) {
        amount.addChangeListener(listener);
        unit.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent event) {
                listener.stateChanged(new javax.swing.event.ChangeEvent(ComicDurationField.this));
            }
        });
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        amount.setEnabled(enabled);
        unit.setEnabled(enabled);
    }

    /** The spinner, for tests and tooltips. */
    public JSpinner getAmountSpinner() {
        return amount;
    }

    /** The unit choice, for tests and tooltips. */
    public JComboBox<String> getUnitCombo() {
        return unit;
    }
}
