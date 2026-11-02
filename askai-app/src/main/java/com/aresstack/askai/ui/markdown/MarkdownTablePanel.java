package com.aresstack.askai.ui.markdown;

import com.vladsch.flexmark.ext.tables.TableBlock;
import com.vladsch.flexmark.ext.tables.TableCell;
import com.vladsch.flexmark.ext.tables.TableRow;
import com.vladsch.flexmark.util.ast.Node;

import javax.swing.BorderFactory;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.JTableHeader;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;

/** Render a Markdown table as a non-editable Swing table. */
final class MarkdownTablePanel extends JScrollPane {

    MarkdownTablePanel(TableBlock tableBlock, MarkdownTheme theme) {
        JTable table = new JTable(createModel(tableBlock));
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setRowSelectionAllowed(true);
        table.setColumnSelectionAllowed(false);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFont(theme.getBodyFont());
        table.setForeground(theme.getForeground());
        table.setGridColor(theme.getSeparatorColor());
        table.setShowGrid(true);
        table.setIntercellSpacing(new Dimension(8, 4));
        table.setRowHeight(table.getFontMetrics(table.getFont()).getHeight() + 8);
        JTableHeader header = table.getTableHeader();
        header.setFont(theme.getBodyFont().deriveFont(java.awt.Font.BOLD));
        fitColumns(table);

        setViewportView(table);
        setBorder(BorderFactory.createLineBorder(theme.getSeparatorColor()));
        setHorizontalScrollBarPolicy(HORIZONTAL_SCROLLBAR_AS_NEEDED);
        setVerticalScrollBarPolicy(VERTICAL_SCROLLBAR_NEVER);
        int height = header.getPreferredSize().height + table.getRowHeight() * Math.max(1, table.getRowCount()) + 4;
        setPreferredSize(new Dimension(400, Math.min(320, height)));
    }

    private static DefaultTableModel createModel(TableBlock tableBlock) {
        List<List<String>> rows = collectRows(tableBlock);
        int columnCount = Math.max(1, maxColumnCount(rows));
        Object[] columns = new Object[columnCount];
        int firstDataRow = 0;
        if (!rows.isEmpty()) {
            List<String> header = rows.get(0);
            for (int column = 0; column < columnCount; column++) {
                columns[column] = column < header.size() ? header.get(column) : "";
            }
            firstDataRow = 1;
        }

        Object[][] data = new Object[Math.max(0, rows.size() - firstDataRow)][columnCount];
        for (int row = firstDataRow; row < rows.size(); row++) {
            List<String> values = rows.get(row);
            for (int column = 0; column < columnCount; column++) {
                data[row - firstDataRow][column] = column < values.size() ? values.get(column) : "";
            }
        }
        return new DefaultTableModel(data, columns) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
    }

    private static List<List<String>> collectRows(Node root) {
        List<List<String>> rows = new ArrayList<List<String>>();
        collectRows(root, rows);
        return rows;
    }

    private static void collectRows(Node node, List<List<String>> rows) {
        if (node instanceof TableRow) {
            List<String> cells = new ArrayList<String>();
            Node cell = node.getFirstChild();
            while (cell != null) {
                if (cell instanceof TableCell) {
                    cells.add(plainText(cell).trim());
                }
                cell = cell.getNext();
            }
            rows.add(cells);
            return;
        }
        Node child = node.getFirstChild();
        while (child != null) {
            collectRows(child, rows);
            child = child.getNext();
        }
    }

    private static String plainText(Node node) {
        if (!node.hasChildren()) {
            return node.getChars().toString();
        }
        StringBuilder text = new StringBuilder();
        Node child = node.getFirstChild();
        while (child != null) {
            text.append(plainText(child));
            child = child.getNext();
        }
        return text.toString();
    }

    private static int maxColumnCount(List<List<String>> rows) {
        int maximum = 0;
        for (List<String> row : rows) {
            maximum = Math.max(maximum, row.size());
        }
        return maximum;
    }

    private static void fitColumns(JTable table) {
        for (int column = 0; column < table.getColumnCount(); column++) {
            int width = table.getTableHeader().getDefaultRenderer()
                    .getTableCellRendererComponent(table, table.getColumnName(column), false, false, -1, column)
                    .getPreferredSize().width + 18;
            for (int row = 0; row < table.getRowCount(); row++) {
                width = Math.max(width, table.prepareRenderer(table.getCellRenderer(row, column), row, column)
                        .getPreferredSize().width + 18);
            }
            table.getColumnModel().getColumn(column).setPreferredWidth(Math.min(420, width));
        }
    }
}
