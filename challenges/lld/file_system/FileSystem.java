package challenges.lld.file_system;

import java.util.*;

/**
 * Design a File System — in-memory Unix shell supporting mkdir, pwd, cd (with wildcard *)
 *
 * Pattern cross-reference (docs/lld/):
 *   - Composite (patterns_tier2.md #15): Directory tree where every node is either
 *     a leaf (file) or a composite (directory containing more nodes).
 *     Same interface regardless of depth — cd traversal is uniform.
 *   - State (patterns_tier1.md #7): The shell cursor holds current-directory state.
 *     cd advances the state; pwd reads it.
 *
 * Data structure:
 *   - Each Directory holds a TreeMap<String, Directory> children — sorted iteration
 *     needed for wildcard matching (lexicographic).
 *   - Trie-like tree rooted at "/" — paths are split on "/" and walked segment by segment.
 *
 * Key methods implemented:
 *   1. mkdir <path>  — create all intermediate dirs (like `mkdir -p`)
 *   2. cd <path>     — supports absolute/relative paths, "..", "." and wildcard "*"
 *                      * matches any single directory name (not recursive)
 *   3. pwd           — return the absolute path of the current directory
 */

// ─── Directory Node (Composite) ───────────────────────────────────────────────

class Directory {

    final String name;
    final Directory parent;
    // TreeMap: sorted keys for predictable wildcard matching
    final TreeMap<String, Directory> children = new TreeMap<>();

    Directory(String name, Directory parent) {
        this.name   = name;
        this.parent = parent;
    }

    boolean isRoot() { return parent == null; }

    /** Absolute path string for this node — O(depth). */
    String absolutePath() {
        if (isRoot()) return "/";
        StringBuilder sb = new StringBuilder();
        buildPath(sb);
        return sb.toString();
    }

    private void buildPath(StringBuilder sb) {
        if (parent == null) return;
        parent.buildPath(sb);
        sb.append('/').append(name);
    }
}

// ─── Shell ────────────────────────────────────────────────────────────────────

public class FileSystem {

    private final Directory root;
    private Directory       cwd;    // current working directory (State pattern)

    public FileSystem() {
        root = new Directory("", null);   // root node has empty name, no parent
        cwd  = root;
    }

    // ── 1. mkdir ──────────────────────────────────────────────────────────────
    /**
     * Creates all directories along the path (equivalent to mkdir -p).
     * Absolute path starts with '/'; relative path is resolved from cwd.
     *
     * O(path_length * log(children_per_node))
     */
    public void mkdir(String path) {
        Directory node = resolvePath(path, false, false);
        // resolvePath with create=true handles the actual node creation
        resolvePath(path, true, false);
    }

    // ── 2. cd ─────────────────────────────────────────────────────────────────
    /**
     * Changes cwd.  Segments are resolved left-to-right.
     * Special segments:
     *   "."  → stay in current directory
     *   ".." → go to parent (root's parent is root)
     *   "*"  → match any ONE directory among children (lexicographic first on tie)
     *
     * Throws IllegalArgumentException if path cannot be resolved.
     *
     * O(segments * log(children_per_node))
     */
    public void cd(String path) {
        cwd = resolvePath(path, false, true);
    }

    // ── 3. pwd ────────────────────────────────────────────────────────────────
    /** Returns absolute path of cwd. O(depth). */
    public String pwd() {
        return cwd.absolutePath();
    }

    // ── Core path resolution ──────────────────────────────────────────────────
    /**
     * Walks the directory tree segment by segment.
     *
     * @param path     the path string to resolve
     * @param create   if true, create missing intermediate directories (for mkdir)
     * @param wildcard if true, resolve "*" segments as wildcard matches (for cd)
     * @return the resolved Directory node
     */
    private Directory resolvePath(String path, boolean create, boolean wildcard) {
        if (path == null || path.isEmpty())
            throw new IllegalArgumentException("Empty path");

        // Absolute vs relative start
        Directory node = path.startsWith("/") ? root : cwd;

        String[] segments = path.split("/");
        for (String seg : segments) {
            if (seg.isEmpty() || seg.equals(".")) continue;   // leading slash or "."

            if (seg.equals("..")) {
                // ".." from root stays at root
                node = node.isRoot() ? node : node.parent;
                continue;
            }

            if (wildcard && seg.equals("*")) {
                // "*" matches any single child — first in sorted order
                if (node.children.isEmpty())
                    throw new IllegalArgumentException(
                        "No directories to match '*' under: " + node.absolutePath());
                node = node.children.firstEntry().getValue();
                continue;
            }

            // Normal segment
            Directory child = node.children.get(seg);
            if (child == null) {
                if (!create)
                    throw new IllegalArgumentException(
                        "Directory not found: " + seg + " in " + node.absolutePath());
                child = new Directory(seg, node);
                node.children.put(seg, child);
            }
            node = child;
        }
        return node;
    }

    // ── Demo ──────────────────────────────────────────────────────────────────
    public static void main(String[] args) {
        FileSystem fs = new FileSystem();

        fs.mkdir("/home/alice/docs");
        fs.mkdir("/home/bob/music");
        fs.mkdir("/var/log");

        System.out.println(fs.pwd());                  // /

        fs.cd("/home/alice");
        System.out.println(fs.pwd());                  // /home/alice

        fs.cd("docs");
        System.out.println(fs.pwd());                  // /home/alice/docs

        fs.cd("../..");
        System.out.println(fs.pwd());                  // /home

        // Wildcard cd: * from /home matches first child alphabetically ("alice" < "bob")
        fs.cd("*");
        System.out.println(fs.pwd());                  // /home/alice

        // Absolute wildcard
        fs.cd("/home/*/docs");
        System.out.println(fs.pwd());                  // /home/alice/docs

        // mkdir relative to cwd
        fs.cd("/");
        fs.mkdir("tmp/cache");
        fs.cd("tmp/*");
        System.out.println(fs.pwd());                  // /tmp/cache
    }
}
