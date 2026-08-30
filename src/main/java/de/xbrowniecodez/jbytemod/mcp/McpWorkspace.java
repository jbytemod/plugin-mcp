package de.xbrowniecodez.jbytemod.mcp;

import de.xbrowniecodez.jbytemod.plugin.PluginContext;
import org.objectweb.asm.tree.ClassNode;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class McpWorkspace {
    private static final int MAX_HISTORY = 50;

    private final PluginContext context;
    private final Map<String, byte[]> baseline = new HashMap<>();
    private final Deque<HistoryEntry> undo = new ArrayDeque<>();
    private final Deque<HistoryEntry> redo = new ArrayDeque<>();
    private Transaction transaction;

    McpWorkspace(PluginContext context) {
        this.context = context;
    }

    synchronized void reset(Map<String, ClassNode> classes) {
        baseline.clear();
        for (Map.Entry<String, ClassNode> entry : classes.entrySet()) {
            baseline.put(entry.getKey(), context.getClassBytes(entry.getValue()));
        }
        undo.clear();
        redo.clear();
        transaction = null;
    }

    synchronized void markClean(Map<String, ClassNode> classes) {
        reset(classes);
    }

    synchronized <T> T mutate(String description, Set<String> classNames, Mutation<T> mutation) throws Exception {
        Map<String, State> before = snapshot(classNames);
        T result;
        try {
            result = mutation.run();
        } catch (Exception | Error exception) {
            restore(before);
            throw exception;
        }

        Map<String, State> after = snapshot(classNames);
        if (same(before, after)) {
            return result;
        }
        if (transaction != null) {
            for (Map.Entry<String, State> entry : before.entrySet()) {
                transaction.before.putIfAbsent(entry.getKey(), entry.getValue());
            }
            transaction.classNames.addAll(classNames);
        } else {
            push(new HistoryEntry(description, before, after));
        }
        return result;
    }

    synchronized void beginTransaction(String description) {
        if (transaction != null) {
            throw new IllegalStateException("A transaction is already active");
        }
        transaction = new Transaction(description == null || description.isBlank()
                ? "MCP transaction" : description);
    }

    synchronized HistoryResult commitTransaction() {
        if (transaction == null) {
            throw new IllegalStateException("No transaction is active");
        }
        Transaction current = transaction;
        transaction = null;
        Map<String, State> after = snapshot(current.classNames);
        if (!same(current.before, after)) {
            push(new HistoryEntry(current.description, current.before, after));
        }
        return new HistoryResult(current.description, current.classNames.size(), undo.size(), redo.size());
    }

    synchronized HistoryResult rollbackTransaction() {
        if (transaction == null) {
            throw new IllegalStateException("No transaction is active");
        }
        Transaction current = transaction;
        transaction = null;
        restore(current.before);
        return new HistoryResult(current.description, current.before.size(), undo.size(), redo.size());
    }

    synchronized HistoryResult undo() {
        if (transaction != null) {
            throw new IllegalStateException("Commit or roll back the active transaction first");
        }
        HistoryEntry entry = undo.pollFirst();
        if (entry == null) {
            throw new IllegalStateException("There are no MCP changes to undo");
        }
        restore(entry.before);
        redo.addFirst(entry);
        return new HistoryResult(entry.description, entry.before.size(), undo.size(), redo.size());
    }

    synchronized HistoryResult redo() {
        if (transaction != null) {
            throw new IllegalStateException("Commit or roll back the active transaction first");
        }
        HistoryEntry entry = redo.pollFirst();
        if (entry == null) {
            throw new IllegalStateException("There are no MCP changes to redo");
        }
        restore(entry.after);
        undo.addFirst(entry);
        return new HistoryResult(entry.description, entry.after.size(), undo.size(), redo.size());
    }

    synchronized HistoryResult discard(Set<String> names) throws Exception {
        Set<String> targets = new LinkedHashSet<>();
        if (names == null || names.isEmpty()) {
            for (ChangeInfo change : changes()) {
                targets.add(change.className());
            }
        } else {
            targets.addAll(names);
        }
        if (targets.isEmpty()) {
            return new HistoryResult("Discard changes", 0, undo.size(), redo.size());
        }
        return mutate("Discard changes", targets, () -> {
            Map<String, State> original = new LinkedHashMap<>();
            for (String name : targets) {
                byte[] bytes = baseline.get(name);
                original.put(name, new State(bytes == null ? null : bytes.clone()));
            }
            restore(original);
            return new HistoryResult("Discard changes", targets.size(), undo.size(), redo.size());
        });
    }

    synchronized List<ChangeInfo> changes() {
        Map<String, ClassNode> classes = context.getCurrentFile();
        Set<String> names = new LinkedHashSet<>(baseline.keySet());
        names.addAll(classes.keySet());
        List<ChangeInfo> result = new ArrayList<>();
        for (String name : names.stream().sorted().toList()) {
            byte[] original = baseline.get(name);
            ClassNode currentNode = classes.get(name);
            byte[] current = currentNode == null ? null : context.getClassBytes(currentNode);
            if (Arrays.equals(original, current)) {
                continue;
            }
            String kind = original == null ? "added" : current == null ? "removed" : "modified";
            result.add(new ChangeInfo(name, kind, original == null ? null : sha256(original),
                    current == null ? null : sha256(current)));
        }
        return result;
    }

    synchronized byte[] originalBytes(String className) {
        byte[] bytes = baseline.get(className);
        return bytes == null ? null : bytes.clone();
    }

    synchronized boolean transactionActive() {
        return transaction != null;
    }

    synchronized int undoCount() {
        return undo.size();
    }

    synchronized int redoCount() {
        return redo.size();
    }

    private Map<String, State> snapshot(Set<String> classNames) {
        Map<String, State> states = new LinkedHashMap<>();
        Map<String, ClassNode> classes = context.getCurrentFile();
        for (String name : classNames) {
            ClassNode classNode = classes.get(name);
            states.put(name, new State(classNode == null ? null : context.getClassBytes(classNode)));
        }
        return states;
    }

    private void restore(Map<String, State> states) {
        Map<String, ClassNode> classes = context.getCurrentFile();
        for (Map.Entry<String, State> entry : states.entrySet()) {
            classes.remove(entry.getKey());
            byte[] bytes = entry.getValue().bytes;
            if (bytes != null) {
                ClassNode restored = context.readClass(bytes);
                classes.put(restored.name, restored);
            }
        }
        context.updateTree();
    }

    private void push(HistoryEntry entry) {
        undo.addFirst(entry);
        while (undo.size() > MAX_HISTORY) {
            undo.removeLast();
        }
        redo.clear();
    }

    private static boolean same(Map<String, State> first, Map<String, State> second) {
        if (!first.keySet().equals(second.keySet())) {
            return false;
        }
        for (String name : first.keySet()) {
            if (!Arrays.equals(first.get(name).bytes, second.get(name).bytes)) {
                return false;
            }
        }
        return true;
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @FunctionalInterface
    interface Mutation<T> {
        T run() throws Exception;
    }

    record ChangeInfo(String className, String kind, String originalSha256, String currentSha256) {
    }

    record HistoryResult(String description, int classCount, int undoCount, int redoCount) {
    }

    private record State(byte[] bytes) {
        private State {
            bytes = bytes == null ? null : bytes.clone();
        }
    }

    private record HistoryEntry(String description, Map<String, State> before, Map<String, State> after) {
    }

    private static final class Transaction {
        private final String description;
        private final Map<String, State> before = new LinkedHashMap<>();
        private final Set<String> classNames = new LinkedHashSet<>();

        private Transaction(String description) {
            this.description = description;
        }
    }
}
