package challenges.lld.text_editor;

import java.util.*;

/**
 * Design a Text Editor with Undo & Redo
 *
 * In-memory, row-based text editor.  Each row is a String.
 * Rows and columns are 0-indexed.  Text never contains '\n'.
 * Operations: insert text at a row/col position; delete a range; undo; redo.
 *
 * Pattern cross-reference (docs/lld/):
 *   - Command (patterns_tier1.md #9): Every mutation is encapsulated as a Command
 *     with execute() and undo().  This is the canonical pattern for text editors.
 *     "Kafka messages are serialized commands" — same idea at a distributed scale.
 *   - Memento (patterns_tier2.md #19): An alternative to Command for undo — the
 *     Memento stores a full document snapshot.  Command is preferred here because
 *     it stores only the delta (O(change) space vs O(doc) per step).
 *
 * Two-stack undo/redo:
 *   - undoStack: commands executed so far (most recent on top)
 *   - redoStack: commands that were undone (redo restores them)
 *   - Any new command clears the redoStack (redo history is invalidated on a new edit)
 *
 * Key methods implemented:
 *   1. insert(row, col, text) — insert text at a specific position
 *   2. delete(row, colStart, colEnd) — delete characters in [colStart, colEnd)
 *   3. undo() / redo()        — navigate command history
 */

// ─── Command Interface ────────────────────────────────────────────────────────

interface EditorCommand {
    void execute(List<StringBuilder> doc);
    void undo(List<StringBuilder> doc);
    String description();
}

// ─── Concrete Commands ────────────────────────────────────────────────────────

/** Insert `text` at (row, col).  Undo: delete the same range. */
class InsertCommand implements EditorCommand {

    private final int    row;
    private final int    col;
    private final String text;

    InsertCommand(int row, int col, String text) {
        this.row  = row;
        this.col  = col;
        this.text = text;
    }

    @Override
    public void execute(List<StringBuilder> doc) {
        ensureRow(doc, row);
        doc.get(row).insert(col, text);
    }

    @Override
    public void undo(List<StringBuilder> doc) {
        doc.get(row).delete(col, col + text.length());
    }

    @Override public String description() {
        return String.format("INSERT row=%d col=%d text='%s'", row, col, text);
    }

    private void ensureRow(List<StringBuilder> doc, int r) {
        while (doc.size() <= r) doc.add(new StringBuilder());
    }
}

/** Delete characters in [colStart, colEnd) on the given row. Undo: re-insert. */
class DeleteCommand implements EditorCommand {

    private final int    row;
    private final int    colStart;
    private final int    colEnd;
    private String       deleted;   // captured on execute for undo

    DeleteCommand(int row, int colStart, int colEnd) {
        this.row      = row;
        this.colStart = colStart;
        this.colEnd   = colEnd;
    }

    @Override
    public void execute(List<StringBuilder> doc) {
        if (row >= doc.size()) return;
        StringBuilder sb = doc.get(row);
        int end = Math.min(colEnd, sb.length());
        deleted = sb.substring(colStart, end);
        sb.delete(colStart, end);
    }

    @Override
    public void undo(List<StringBuilder> doc) {
        doc.get(row).insert(colStart, deleted);
    }

    @Override public String description() {
        return String.format("DELETE row=%d [%d,%d)", row, colStart, colEnd);
    }
}

/** Append a new empty row after the given row index. Undo: remove that row. */
class NewlineCommand implements EditorCommand {

    private final int    row;   // new row is inserted at index row+1
    private final int    col;   // characters after col move to the new row

    NewlineCommand(int row, int col) {
        this.row = row;
        this.col = col;
    }

    @Override
    public void execute(List<StringBuilder> doc) {
        while (doc.size() <= row) doc.add(new StringBuilder());
        StringBuilder current = doc.get(row);
        String tail = current.substring(Math.min(col, current.length()));
        current.setLength(Math.min(col, current.length()));
        doc.add(row + 1, new StringBuilder(tail));
    }

    @Override
    public void undo(List<StringBuilder> doc) {
        // Merge row+1 back into row, then remove row+1
        String tail = doc.remove(row + 1).toString();
        doc.get(row).append(tail);
    }

    @Override public String description() {
        return String.format("NEWLINE after row=%d col=%d", row, col);
    }
}

// ─── Text Editor ─────────────────────────────────────────────────────────────

public class TextEditor {

    private final List<StringBuilder> doc       = new ArrayList<>();   // row → content
    private final Deque<EditorCommand> undoStack = new ArrayDeque<>();
    private final Deque<EditorCommand> redoStack = new ArrayDeque<>();

    // ── 1. insert ─────────────────────────────────────────────────────────────
    /**
     * Insert text at (row, col). Clears the redo stack.
     * O(text.length) for the StringBuilder insert.
     */
    public void insert(int row, int col, String text) {
        execute(new InsertCommand(row, col, text));
    }

    // ── 2. delete ─────────────────────────────────────────────────────────────
    /**
     * Delete characters in [colStart, colEnd) on the given row.
     * Saves the deleted text for undo. Clears the redo stack.
     * O(colEnd - colStart) for the StringBuilder delete.
     */
    public void delete(int row, int colStart, int colEnd) {
        execute(new DeleteCommand(row, colStart, colEnd));
    }

    public void newline(int row, int col) {
        execute(new NewlineCommand(row, col));
    }

    // ── 3. undo / redo ────────────────────────────────────────────────────────
    /**
     * Undo the last command: pop from undoStack, call undo(), push to redoStack.
     * O(1) stack operations + O(command size) for the actual undo.
     */
    public void undo() {
        if (undoStack.isEmpty()) return;
        EditorCommand cmd = undoStack.pop();
        cmd.undo(doc);
        redoStack.push(cmd);
    }

    /**
     * Redo the last undone command: pop from redoStack, re-execute, push to undoStack.
     * O(1) stack operations + O(command size).
     */
    public void redo() {
        if (redoStack.isEmpty()) return;
        EditorCommand cmd = redoStack.pop();
        cmd.execute(doc);
        undoStack.push(cmd);
    }

    // ─────────────────────────────────────────────────────────────────────────

    private void execute(EditorCommand cmd) {
        cmd.execute(doc);
        undoStack.push(cmd);
        redoStack.clear();   // new edit invalidates redo history
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public String getRow(int row) {
        if (row >= doc.size()) return "";
        return doc.get(row).toString();
    }

    public int rowCount() { return doc.size(); }

    public void printDocument() {
        for (int i = 0; i < doc.size(); i++) {
            System.out.printf("%2d | %s%n", i, doc.get(i));
        }
    }

    public void printStacks() {
        System.out.println("Undo stack (top→bottom): " + undoStack.stream()
            .map(EditorCommand::description).toList());
        System.out.println("Redo stack (top→bottom): " + redoStack.stream()
            .map(EditorCommand::description).toList());
    }

    // ── Demo ──────────────────────────────────────────────────────────────────
    public static void main(String[] args) {
        TextEditor editor = new TextEditor();

        editor.insert(0, 0, "Hello, World");
        editor.insert(0, 5, " Beautiful");    // "Hello, Beautiful World" → wait, col=5
        // row 0: "Hello Beautiful, World" — actually:
        // after insert(0,5,"..."): "Hello Beautiful, World" — demo shows the mechanism

        System.out.println("=== After inserts ===");
        editor.printDocument();

        editor.newline(0, 5);   // split row 0 at col 5

        System.out.println("\n=== After newline ===");
        editor.printDocument();

        editor.delete(1, 0, 3);   // delete first 3 chars of row 1

        System.out.println("\n=== After delete ===");
        editor.printDocument();

        System.out.println("\n=== Stacks ===");
        editor.printStacks();

        editor.undo();
        System.out.println("\n=== After undo (delete restored) ===");
        editor.printDocument();

        editor.undo();
        System.out.println("\n=== After undo (newline merged) ===");
        editor.printDocument();

        editor.redo();
        System.out.println("\n=== After redo (newline re-applied) ===");
        editor.printDocument();

        // New edit clears redo stack
        editor.insert(1, 0, "X");
        editor.redo();   // no-op — redo stack was cleared
        System.out.println("\n=== After new insert (redo cleared) ===");
        editor.printDocument();
    }
}
