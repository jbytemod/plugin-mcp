package de.xbrowniecodez.jbytemod.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import de.xbrowniecodez.jbytemod.plugin.ArchiveInfo;
import de.xbrowniecodez.jbytemod.plugin.ArchiveType;
import de.xbrowniecodez.jbytemod.plugin.JvmProcess;
import de.xbrowniecodez.jbytemod.plugin.PluginContext;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.util.Printer;
import org.objectweb.asm.util.CheckClassAdapter;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceClassVisitor;
import org.objectweb.asm.util.TraceMethodVisitor;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

final class McpTools {
    private static final Gson GSON = new Gson();
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;
    private static final int MAX_TEXT_LENGTH = 2_000_000;
    private static final int MAX_CLASS_FILE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_RESOURCE_BYTES = 32 * 1024 * 1024;
    private static final int MAX_RESOURCE_CHUNK_BYTES = 1024 * 1024;

    private final PluginContext context;
    private final McpWorkspace workspace;
    private final Object mutationLock = new Object();

    McpTools(PluginContext context, McpWorkspace workspace) {
        this.context = context;
        this.workspace = workspace;
    }

    String getVersion() {
        return context.getApplicationVersion();
    }

    JsonObject list(boolean modern) {
        JsonObject result = new JsonObject();
        JsonArray tools = new JsonArray();
        JsonObject openFileProperties = new JsonObject();
        openFileProperties.add("path", stringProperty("Absolute or working-directory-relative path to a JAR, class, or APK file."));
        tools.add(tool("open_file",
                "Open a local JAR, class, or APK in JByteMod. This replaces the current archive.",
                schema(openFileProperties, "path"), false, true, true));

        JsonObject saveFileProperties = new JsonObject();
        saveFileProperties.add("path", stringProperty("Absolute or working-directory-relative output path."));
        tools.add(tool("save_file",
                "Save the active archive or runtime class dump to a local file. Existing files are overwritten.",
                schema(saveFileProperties, "path"), false, true, true));

        tools.add(tool("list_jvms", "List local JVM processes that JByteMod can attach to.",
                schema(new JsonObject()), true, true));

        JsonObject attachProperties = new JsonObject();
        attachProperties.add("pid", stringProperty("PID returned by list_jvms."));
        tools.add(tool("attach_jvm", "Attach JByteMod to a running local JVM and load its classes.",
                schema(attachProperties, "pid"), false, false, false));
        tools.add(tool("refresh_attached_jvm",
                "Reload classes from the attached JVM, discarding unapplied in-memory edits.",
                schema(new JsonObject()), false, true, true));
        tools.add(tool("apply_changes",
                "Redefine modified classes in the attached JVM. JVM class-redefinition restrictions apply.",
                schema(new JsonObject()), false, true, false));
        JsonObject frozenProperties = new JsonObject();
        frozenProperties.add("frozen", booleanProperty(
                "True to freeze the attached JVM process; false to resume it."));
        tools.add(tool("set_attached_jvm_frozen",
                "Freeze or resume the entire attached JVM process.",
                schema(frozenProperties, "frozen"), false, false, true));
        tools.add(tool("detach_jvm",
                "Detach from the attached JVM without stopping it. Loaded classes remain available as a local snapshot.",
                schema(new JsonObject()), false, true, false));
        tools.add(tool("terminate_attached_jvm",
                "Immediately terminate the attached JVM process. Loaded classes remain available as a local snapshot.",
                schema(new JsonObject()), false, true, false));

        tools.add(tool("archive_summary", "Show information about the archive currently open in JByteMod.",
                schema(new JsonObject()), true, true));

        tools.add(tool("list_changes", "List classes changed since the archive was opened, saved, refreshed, or applied.",
                schema(new JsonObject()), true, true));
        JsonObject diffClassProperties = new JsonObject();
        diffClassProperties.add("class", stringProperty("Changed JVM internal or dotted class name."));
        tools.add(tool("diff_class", "Compare a class with its clean baseline and report bytecode and structural differences.",
                schema(diffClassProperties, "class"), true, true));
        JsonObject transactionProperties = new JsonObject();
        transactionProperties.add("description", stringProperty("Optional description for the grouped changes."));
        tools.add(tool("begin_transaction", "Begin grouping MCP bytecode edits into one undoable transaction.",
                schema(transactionProperties), false, true, false));
        tools.add(tool("commit_transaction", "Commit the active MCP transaction as one undo history entry.",
                schema(new JsonObject()), false, true, false));
        tools.add(tool("rollback_transaction", "Restore all classes changed by the active MCP transaction.",
                schema(new JsonObject()), false, true, false));
        tools.add(tool("undo_change", "Undo the most recent MCP edit or committed transaction.",
                schema(new JsonObject()), false, true, false));
        tools.add(tool("redo_change", "Redo the most recently undone MCP edit or transaction.",
                schema(new JsonObject()), false, true, false));
        JsonObject discardProperties = new JsonObject();
        discardProperties.add("class", stringProperty("Optional class to restore. Omit to restore every changed class."));
        tools.add(tool("discard_changes", "Restore one class or all classes to the clean baseline.",
                schema(discardProperties), false, true, false));
        JsonObject hotSwapProperties = new JsonObject();
        hotSwapProperties.add("class", stringProperty("Optional changed class to validate. Omit to validate all changes."));
        tools.add(tool("validate_hotswap", "Check changes against standard JVM class-redefinition restrictions.",
                schema(hotSwapProperties), true, true));

        JsonObject listResourceProperties = pagedProperties();
        listResourceProperties.add("query", stringProperty("Optional case-insensitive resource-path filter."));
        tools.add(tool("list_resources", "List non-class entries in the active archive.",
                schema(listResourceProperties), true, true));
        JsonObject resourceProperties = new JsonObject();
        resourceProperties.add("path", stringProperty("Archive entry path using forward slashes."));
        JsonObject getResourceProperties = resourceProperties.deepCopy();
        getResourceProperties.add("offset", integerProperty("Zero-based byte offset. Defaults to 0.", 0, null));
        getResourceProperties.add("length", integerProperty(
                "Maximum bytes to return. Defaults to 1048576.", 1, MAX_RESOURCE_CHUNK_BYTES));
        tools.add(tool("get_resource", "Return a chunk of an archive resource as Base64 with a UTF-8 preview when practical.",
                schema(getResourceProperties, "path"), true, true));
        JsonObject resourceDataProperties = resourceProperties.deepCopy();
        resourceDataProperties.add("dataBase64", stringProperty("Base64-encoded resource bytes."));
        tools.add(tool("add_resource", "Add a new non-class entry to the active archive.",
                schema(resourceDataProperties, "path", "dataBase64"), false, true, false));
        tools.add(tool("replace_resource", "Replace an existing non-class archive entry.",
                schema(resourceDataProperties, "path", "dataBase64"), false, true, true));
        tools.add(tool("delete_resource", "Delete a non-class entry from the active archive.",
                schema(resourceProperties, "path"), false, true, true));
        tools.add(tool("get_manifest", "Return META-INF/MANIFEST.MF as text.",
                schema(new JsonObject()), true, true));
        JsonObject manifestProperties = new JsonObject();
        manifestProperties.add("content", stringProperty("Complete UTF-8 manifest content."));
        tools.add(tool("edit_manifest", "Add or replace META-INF/MANIFEST.MF from text.",
                schema(manifestProperties, "content"), false, true, true));

        JsonObject exportClassProperties = new JsonObject();
        exportClassProperties.add("class", stringProperty("JVM internal or dotted class name."));
        exportClassProperties.add("path", stringProperty("Output .class path, or an existing directory."));
        tools.add(tool("export_class", "Write one current class file to disk.",
                schema(exportClassProperties, "class", "path"), false, true, true));
        JsonObject exportPackageProperties = new JsonObject();
        exportPackageProperties.add("package", stringProperty("JVM internal or dotted package name."));
        exportPackageProperties.add("path", stringProperty("Output JAR path."));
        exportPackageProperties.add("includeSubpackages", booleanProperty(
                "Whether to include child packages. Defaults to true."));
        tools.add(tool("export_package", "Write classes from a loaded package to a JAR.",
                schema(exportPackageProperties, "package", "path"), false, true, true));

        JsonObject listClassProperties = new JsonObject();
        listClassProperties.add("query", stringProperty("Optional case-insensitive class name filter."));
        listClassProperties.add("offset", integerProperty("Zero-based result offset.", 0, null));
        listClassProperties.add("limit", integerProperty("Maximum number of classes to return.", 1, MAX_LIMIT));
        tools.add(tool("list_classes", "List class names from the active archive.",
                schema(listClassProperties), true, true));

        JsonObject searchMemberProperties = pagedProperties();
        searchMemberProperties.add("query", stringProperty(
                "Case-insensitive text matched against class, member name, and descriptor."));
        searchMemberProperties.add("kind", enumProperty("Member kind. Defaults to any.",
                "any", "field", "method"));
        tools.add(tool("search_members", "Search fields and methods across the active archive.",
                schema(searchMemberProperties, "query"), true, true));

        JsonObject searchConstantProperties = pagedProperties();
        searchConstantProperties.add("query", stringProperty(
                "Case-insensitive text matched against LDC constant values."));
        searchConstantProperties.add("valueType", enumProperty(
                "Optional constant type filter. Defaults to any.",
                "any", "string", "int", "long", "float", "double", "type"));
        tools.add(tool("search_constants", "Search LDC constants across the active archive.",
                schema(searchConstantProperties, "query"), true, true));

        JsonObject referenceProperties = pagedProperties();
        referenceProperties.add("kind", enumProperty("Reference kind.", "class", "field", "method"));
        referenceProperties.add("owner", stringProperty("Target JVM internal or dotted class name."));
        referenceProperties.add("name", stringProperty("Target field or method name."));
        referenceProperties.add("descriptor", stringProperty(
                "Optional exact JVM field or method descriptor."));
        tools.add(tool("find_references", "Find class, field, or method references in bytecode.",
                schema(referenceProperties, "kind", "owner"), true, true));

        JsonObject classProperties = new JsonObject();
        classProperties.add("class", stringProperty("JVM internal or dotted class name."));
        tools.add(tool("describe_class", "Show class metadata, fields, and method signatures.",
                schema(classProperties, "class"), true, true));
        tools.add(tool("class_hierarchy", "Show loaded ancestors and subtypes of a class.",
                schema(classProperties, "class"), true, true));
        JsonObject verifyClassProperties = classProperties.deepCopy();
        verifyClassProperties.add("dataflow", booleanProperty(
                "Verify method data flow in addition to class structure. Defaults to true."));
        tools.add(tool("verify_class", "Validate a class with ASM and return diagnostics.",
                schema(verifyClassProperties, "class"), true, true));
        tools.add(tool("get_class_file", "Return the current class file as base64 for external bytecode editing.",
                schema(classProperties, "class"), true, true));

        JsonObject replaceClassProperties = classProperties.deepCopy();
        replaceClassProperties.add("classFileBase64", stringProperty(
                "Base64-encoded replacement class file. Its internal name must match class."));
        tools.add(tool("replace_class", "Replace a class in the in-memory archive from class-file bytes.",
                schema(replaceClassProperties, "class", "classFileBase64"), false, true, true));

        JsonObject renameClassProperties = classProperties.deepCopy();
        renameClassProperties.add("newName", stringProperty("New JVM internal or dotted class name."));
        tools.add(tool("rename_class", "Rename a class and update loaded bytecode references and descriptors.",
                schema(renameClassProperties, "class", "newName"), false, true, false));

        JsonObject renameMethodProperties = new JsonObject();
        renameMethodProperties.add("class", stringProperty("Declaring JVM internal or dotted class name."));
        renameMethodProperties.add("method", stringProperty("Current method name."));
        renameMethodProperties.add("descriptor", stringProperty("Exact JVM method descriptor."));
        renameMethodProperties.add("newName", stringProperty("New method name."));
        tools.add(tool("rename_method", "Rename a method and update matching calls in all loaded classes.",
                schema(renameMethodProperties, "class", "method", "descriptor", "newName"), false, true, false));

        JsonObject renameFieldProperties = new JsonObject();
        renameFieldProperties.add("class", stringProperty("Declaring JVM internal or dotted class name."));
        renameFieldProperties.add("field", stringProperty("Current field name."));
        renameFieldProperties.add("descriptor", stringProperty("Exact JVM field descriptor."));
        renameFieldProperties.add("newName", stringProperty("New field name."));
        tools.add(tool("rename_field", "Rename a field and update matching references in all loaded classes.",
                schema(renameFieldProperties, "class", "field", "descriptor", "newName"), false, true, false));

        JsonObject accessProperties = new JsonObject();
        accessProperties.add("target", enumProperty("Target kind.", "class", "field", "method"));
        accessProperties.add("class", stringProperty("Declaring JVM internal or dotted class name."));
        accessProperties.add("name", stringProperty("Field or method name; omit for a class."));
        accessProperties.add("descriptor", stringProperty("Field or method descriptor; omit for a class."));
        accessProperties.add("access", integerProperty("Complete ASM access-flag value.", 0, Integer.MAX_VALUE));
        tools.add(tool("set_access_flags", "Replace access flags on a class, field, or method.",
                schema(accessProperties, "target", "class", "access"), false, true, true));

        JsonObject addFieldProperties = new JsonObject();
        addFieldProperties.add("class", stringProperty("Declaring JVM internal or dotted class name."));
        addFieldProperties.add("field", stringProperty("New field name."));
        addFieldProperties.add("descriptor", stringProperty("JVM field descriptor."));
        addFieldProperties.add("access", integerProperty("ASM access-flag value. Defaults to 1 (public).", 0, Integer.MAX_VALUE));
        addFieldProperties.add("signature", stringProperty("Optional generic signature."));
        addFieldProperties.add("valueType", enumProperty("Optional ConstantValue type.",
                "none", "string", "int", "long", "float", "double"));
        addFieldProperties.add("value", stringProperty("ConstantValue encoded as text."));
        tools.add(tool("add_field", "Add a field to a class. This is not compatible with standard HotSwap.",
                schema(addFieldProperties, "class", "field", "descriptor"), false, true, false));
        tools.add(tool("remove_field", "Remove a field from a class. This is not compatible with standard HotSwap.",
                schema(renameFieldProperties, "class", "field", "descriptor"), false, true, false));

        JsonObject addMethodProperties = new JsonObject();
        addMethodProperties.add("class", stringProperty("Declaring JVM internal or dotted class name."));
        addMethodProperties.add("method", stringProperty("New method name."));
        addMethodProperties.add("descriptor", stringProperty("JVM method descriptor."));
        addMethodProperties.add("access", integerProperty("ASM access-flag value. Defaults to 1 (public).", 0, Integer.MAX_VALUE));
        addMethodProperties.add("signature", stringProperty("Optional generic signature."));
        tools.add(tool("add_method", "Add a method with a valid default return body.",
                schema(addMethodProperties, "class", "method", "descriptor"), false, true, false));
        tools.add(tool("remove_method", "Remove a method from a class. This is not compatible with standard HotSwap.",
                schema(renameMethodProperties, "class", "method", "descriptor"), false, true, false));

        JsonObject copyMethodProperties = new JsonObject();
        copyMethodProperties.add("sourceClass", stringProperty("Class containing the source method."));
        copyMethodProperties.add("targetClass", stringProperty("Class that will receive the copied method."));
        copyMethodProperties.add("method", stringProperty("Source method name."));
        copyMethodProperties.add("descriptor", stringProperty("Source method descriptor."));
        copyMethodProperties.add("newName", stringProperty("Optional name for the copied method."));
        tools.add(tool("copy_method", "Copy a complete method between loaded classes.",
                schema(copyMethodProperties, "sourceClass", "targetClass", "method", "descriptor"),
                false, true, false));

        JsonObject replaceBodyProperties = copyMethodProperties.deepCopy();
        replaceBodyProperties.add("targetMethod", stringProperty("Target method name."));
        replaceBodyProperties.add("targetDescriptor", stringProperty("Target method descriptor."));
        tools.add(tool("replace_method_body", "Replace a method body from another loaded method with the same descriptor.",
                schema(replaceBodyProperties, "sourceClass", "targetClass", "method", "descriptor",
                        "targetMethod", "targetDescriptor"), false, true, false));

        JsonObject metadataProperties = classProperties.deepCopy();
        metadataProperties.add("superClass", stringProperty("Optional new superclass; use an empty string for none."));
        metadataProperties.add("signature", stringProperty("Optional generic signature; use an empty string to clear."));
        metadataProperties.add("sourceFile", stringProperty("Optional SourceFile; use an empty string to clear."));
        metadataProperties.add("interfaces", arrayProperty("Complete list of JVM internal or dotted interface names.",
                stringProperty("Interface name.")));
        tools.add(tool("edit_class_metadata", "Edit superclass, interfaces, signature, or source-file metadata.",
                schema(metadataProperties, "class"), false, true, false));

        JsonObject methodProperties = new JsonObject();
        methodProperties.add("class", stringProperty("JVM internal or dotted class name."));
        methodProperties.add("method", stringProperty("Method name."));
        methodProperties.add("descriptor", stringProperty("JVM method descriptor, used to select an overload."));
        JsonObject methodSchema = schema(methodProperties, "class", "method", "descriptor");
        tools.add(tool("get_method_bytecode", "Render a method as readable JVM bytecode.",
                methodSchema, true, true));
        JsonObject methodCallProperties = methodProperties.deepCopy();
        methodCallProperties.add("direction", enumProperty(
                "Calls to return. Defaults to both.", "incoming", "outgoing", "both"));
        methodCallProperties.add("offset", integerProperty("Zero-based result offset.", 0, null));
        methodCallProperties.add("limit", integerProperty("Maximum calls to return.", 1, MAX_LIMIT));
        tools.add(tool("method_calls", "Show incoming and outgoing calls for a method.",
                schema(methodCallProperties, "class", "method", "descriptor"), true, true));
        JsonObject callGraphProperties = methodProperties.deepCopy();
        callGraphProperties.add("direction", enumProperty(
                "Call traversal direction. Defaults to both.", "incoming", "outgoing", "both"));
        callGraphProperties.add("depth", integerProperty(
                "Maximum traversal depth. Defaults to 2.", 1, 5));
        callGraphProperties.add("includeExternal", booleanProperty(
                "Include referenced methods that are not present in the active archive. Defaults to false."));
        callGraphProperties.add("maxNodes", integerProperty(
                "Maximum number of graph nodes. Defaults to 200.", 1, MAX_LIMIT));
        tools.add(tool("get_call_graph",
                "Build a multi-level call graph with individual call-site instruction indices.",
                schema(callGraphProperties, "class", "method", "descriptor"), true, true));
        JsonObject decompileMethodProperties = methodProperties.deepCopy();
        decompileMethodProperties.add("decompiler", decompilerProperty());
        tools.add(tool("decompile_method", "Decompile one method with a JByteMod decompiler.",
                schema(decompileMethodProperties, "class", "method", "descriptor"), true, true));

        JsonObject listInstructionProperties = methodProperties.deepCopy();
        listInstructionProperties.add("offset", integerProperty("Zero-based instruction offset.", 0, null));
        listInstructionProperties.add("limit", integerProperty("Maximum number of instructions to return.", 1, MAX_LIMIT));
        tools.add(tool("list_instructions", "List structured ASM instructions and their current indices for direct editing.",
                schema(listInstructionProperties, "class", "method", "descriptor"), true, true));

        JsonObject editInstructionProperties = methodProperties.deepCopy();
        editInstructionProperties.add("operation", enumProperty(
                "Edit operation. instruction is required except for remove.",
                "replace", "insert_before", "insert_after", "remove"));
        editInstructionProperties.add("instructionIndex", integerProperty(
                "Current instruction index used as the replacement/removal target or insertion anchor.", 0, null));
        editInstructionProperties.add("instruction", instructionProperty());
        tools.add(tool("edit_instruction",
                "Replace, insert, or remove one real ASM bytecode instruction in the in-memory method.",
                schema(editInstructionProperties, "class", "method", "descriptor", "operation", "instructionIndex"),
                false, true, false));

        JsonObject listConstantProperties = methodProperties.deepCopy();
        listConstantProperties.add("offset", integerProperty("Zero-based constant offset.", 0, null));
        listConstantProperties.add("limit", integerProperty("Maximum number of constants to return.", 1, MAX_LIMIT));
        tools.add(tool("list_constants", "List LDC constants and their instruction indices in a method.",
                schema(listConstantProperties, "class", "method", "descriptor"), true, true));

        JsonObject replaceConstantProperties = methodProperties.deepCopy();
        replaceConstantProperties.add("instructionIndex", integerProperty(
                "Instruction index of the LDC constant returned by list_constants.", 0, null));
        replaceConstantProperties.add("valueType", enumProperty(
                "Replacement constant type.", "string", "int", "long", "float", "double", "type"));
        replaceConstantProperties.add("value", stringProperty(
                "Replacement value as text. For type, use a JVM type descriptor."));
        tools.add(tool("replace_constant", "Replace one LDC constant in a method in the in-memory archive.",
                schema(replaceConstantProperties, "class", "method", "descriptor", "instructionIndex",
                        "valueType", "value"), false, true, true));

        tools.add(tool("get_control_flow_graph",
                "Return normal and exception control-flow edges for a method.", methodSchema, true, true));
        tools.add(tool("find_dead_code",
                "Find unreachable real instructions in a method using ASM data-flow analysis.",
                methodSchema, true, true));
        JsonObject stackProperties = methodProperties.deepCopy();
        stackProperties.add("offset", integerProperty("Zero-based instruction offset.", 0, null));
        stackProperties.add("limit", integerProperty("Maximum analyzed instructions to return.", 1, MAX_LIMIT));
        tools.add(tool("analyze_stack_frames",
                "Show inferred local and operand-stack values before each instruction.",
                schema(stackProperties, "class", "method", "descriptor"), true, true));
        tools.add(tool("find_overrides",
                "Find loaded declarations that a method overrides and loaded methods that override it.",
                methodSchema, true, true));

        JsonObject implementationProperties = classProperties.deepCopy();
        implementationProperties.add("method", stringProperty("Optional method name to resolve on each implementation."));
        implementationProperties.add("descriptor", stringProperty("Exact descriptor required when method is supplied."));
        implementationProperties.add("offset", integerProperty("Zero-based result offset.", 0, null));
        implementationProperties.add("limit", integerProperty("Maximum results to return.", 1, MAX_LIMIT));
        tools.add(tool("find_implementations",
                "Find loaded concrete implementations of a class or interface and optionally resolve a method.",
                schema(implementationProperties, "class"), true, true));

        tools.add(tool("find_entry_points",
                "Find loaded main, agent, JavaFX, and other common JVM entry-point methods.",
                schema(pagedProperties()), true, true));

        JsonObject patternProperties = pagedProperties();
        patternProperties.add("pattern", stringProperty(
                "Opcode-name sequence separated by spaces or commas, for example ALOAD GETFIELD ARETURN."));
        patternProperties.add("class", stringProperty("Optional class-name filter."));
        tools.add(tool("search_instruction_pattern",
                "Find contiguous real-instruction opcode sequences across loaded methods.",
                schema(patternProperties, "pattern"), true, true));

        JsonObject detectionProperties = pagedProperties();
        detectionProperties.add("class", stringProperty("Optional class-name filter."));
        tools.add(tool("detect_reflection_usage",
                "Find reflection, method-handle, dynamic-proxy, and invokedynamic usage.",
                schema(detectionProperties), true, true));
        tools.add(tool("detect_native_methods", "Find methods declared with ACC_NATIVE.",
                schema(detectionProperties), true, true));

        JsonObject compareProperties = new JsonObject();
        compareProperties.add("firstClass", stringProperty("First loaded class."));
        compareProperties.add("secondClass", stringProperty("Second loaded class."));
        tools.add(tool("compare_classes",
                "Compare class metadata, fields, methods, and current bytecode.",
                schema(compareProperties, "firstClass", "secondClass"), true, true));

        JsonObject decompileClassProperties = new JsonObject();
        decompileClassProperties.add("class", stringProperty("JVM internal or dotted class name."));
        decompileClassProperties.add("decompiler", decompilerProperty());
        tools.add(tool("decompile_class", "Decompile a class with a JByteMod decompiler.",
                schema(decompileClassProperties, "class"), true, true));

        tools.add(tool("select_class", "Select a class in the JByteMod UI.",
                schema(classProperties, "class"), false, true));
        tools.add(tool("select_method", "Select a method in the JByteMod UI.",
                methodSchema, false, true));

        result.add("tools", tools);
        if (modern) {
            result.addProperty("ttlMs", 60_000);
            result.addProperty("cacheScope", "private");
        }
        return result;
    }

    JsonObject call(JsonObject params) {
        String name = requiredString(params, "name");
        JsonObject arguments = params.has("arguments") && params.get("arguments").isJsonObject()
                ? params.getAsJsonObject("arguments") : new JsonObject();
        try {
            JsonElement output = switch (name) {
                case "open_file" -> openFile(arguments);
                case "save_file" -> saveFile(arguments);
                case "list_jvms" -> listJvms();
                case "attach_jvm" -> attachJvm(arguments);
                case "refresh_attached_jvm" -> refreshAttachedJvm();
                case "apply_changes" -> applyChanges();
                case "set_attached_jvm_frozen" -> setAttachedJvmFrozen(arguments);
                case "detach_jvm" -> detachJvm();
                case "terminate_attached_jvm" -> terminateAttachedJvm();
                case "archive_summary" -> archiveSummary();
                case "list_changes" -> listChanges();
                case "diff_class" -> diffClass(arguments);
                case "begin_transaction" -> beginTransaction(arguments);
                case "commit_transaction" -> historyResult(workspace.commitTransaction());
                case "rollback_transaction" -> historyResult(workspace.rollbackTransaction());
                case "undo_change" -> historyResult(workspace.undo());
                case "redo_change" -> historyResult(workspace.redo());
                case "discard_changes" -> discardChanges(arguments);
                case "validate_hotswap" -> validateHotSwap(arguments);
                case "list_resources" -> listResources(arguments);
                case "get_resource" -> getResource(arguments);
                case "add_resource" -> putResource(arguments, false);
                case "replace_resource" -> putResource(arguments, true);
                case "delete_resource" -> deleteResource(arguments);
                case "get_manifest" -> getManifest();
                case "edit_manifest" -> editManifest(arguments);
                case "export_class" -> exportClass(arguments);
                case "export_package" -> exportPackage(arguments);
                case "list_classes" -> listClasses(arguments);
                case "search_members" -> searchMembers(arguments);
                case "search_constants" -> searchConstants(arguments);
                case "find_references" -> findReferences(arguments);
                case "describe_class" -> describeClass(arguments);
                case "class_hierarchy" -> classHierarchy(arguments);
                case "verify_class" -> verifyClass(arguments);
                case "get_class_file" -> classFile(arguments);
                case "replace_class" -> replaceClass(arguments);
                case "rename_class" -> renameClass(arguments);
                case "rename_method" -> renameMethod(arguments);
                case "rename_field" -> renameField(arguments);
                case "set_access_flags" -> setAccessFlags(arguments);
                case "add_field" -> addField(arguments);
                case "remove_field" -> removeField(arguments);
                case "add_method" -> addMethod(arguments);
                case "remove_method" -> removeMethod(arguments);
                case "copy_method" -> copyMethod(arguments);
                case "replace_method_body" -> replaceMethodBody(arguments);
                case "edit_class_metadata" -> editClassMetadata(arguments);
                case "get_method_bytecode" -> methodBytecode(arguments);
                case "method_calls" -> methodCalls(arguments);
                case "get_call_graph" -> callGraph(arguments);
                case "decompile_class" -> decompileClass(arguments);
                case "decompile_method" -> decompileMethod(arguments);
                case "list_instructions" -> listInstructions(arguments);
                case "edit_instruction" -> editInstruction(arguments);
                case "list_constants" -> listConstants(arguments);
                case "replace_constant" -> replaceConstant(arguments);
                case "get_control_flow_graph" -> controlFlowGraph(arguments);
                case "find_dead_code" -> findDeadCode(arguments);
                case "analyze_stack_frames" -> analyzeStackFrames(arguments);
                case "find_overrides" -> findOverrides(arguments);
                case "find_implementations" -> findImplementations(arguments);
                case "find_entry_points" -> findEntryPoints(arguments);
                case "search_instruction_pattern" -> searchInstructionPattern(arguments);
                case "detect_reflection_usage" -> detectReflectionUsage(arguments);
                case "detect_native_methods" -> detectNativeMethods(arguments);
                case "compare_classes" -> compareClasses(arguments);
                case "select_class" -> selectClass(arguments);
                case "select_method" -> selectMethod(arguments);
                default -> throw new IllegalArgumentException("Unknown tool: " + name);
            };
            return toolResult(output, false);
        } catch (IllegalArgumentException exception) {
            return toolResult(new com.google.gson.JsonPrimitive(exception.getMessage()), true);
        } catch (Exception exception) {
            String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            return toolResult(new com.google.gson.JsonPrimitive(message), true);
        }
    }

    private JsonObject openFile(JsonObject arguments) throws Exception {
        String path = requiredString(arguments, "path");
        context.openFile(path);
        workspace.reset(context.getCurrentFile());
        JsonObject result = archiveSummary();
        result.addProperty("path", path);
        result.addProperty("opened", true);
        return result;
    }

    private JsonObject saveFile(JsonObject arguments) throws Exception {
        String outputPath = context.saveFile(requiredString(arguments, "path"));
        workspace.markClean(context.getCurrentFile());
        JsonObject result = new JsonObject();
        result.addProperty("path", outputPath);
        result.addProperty("saved", true);
        return result;
    }

    private JsonObject listJvms() {
        List<JvmProcess> processes = context.listJvmProcesses();
        JsonArray items = new JsonArray();
        for (JvmProcess process : processes) {
            JsonObject item = new JsonObject();
            item.addProperty("pid", process.pid());
            item.addProperty("displayName", process.displayName());
            items.add(item);
        }
        JsonObject result = new JsonObject();
        result.addProperty("total", processes.size());
        result.add("processes", items);
        return result;
    }

    private JsonObject attachJvm(JsonObject arguments) throws Exception {
        String pid = requiredString(arguments, "pid");
        context.attachToJvm(pid);
        workspace.reset(context.getCurrentFile());
        JsonObject result = archiveSummary();
        result.addProperty("pid", pid);
        result.addProperty("attached", true);
        return result;
    }

    private JsonObject refreshAttachedJvm() throws Exception {
        context.refreshAttachedJvm();
        workspace.reset(context.getCurrentFile());
        JsonObject result = archiveSummary();
        result.addProperty("refreshed", true);
        return result;
    }

    private JsonObject applyChanges() throws Exception {
        int changedClasses = context.applyChangesToAttachedJvm();
        workspace.markClean(context.getCurrentFile());
        JsonObject result = new JsonObject();
        result.addProperty("changedClasses", changedClasses);
        result.addProperty("applied", true);
        return result;
    }

    private JsonObject setAttachedJvmFrozen(JsonObject arguments) throws Exception {
        boolean frozen = requiredBoolean(arguments, "frozen");
        context.setAttachedJvmFrozen(frozen);
        JsonObject result = new JsonObject();
        result.addProperty("frozen", frozen);
        return result;
    }

    private JsonObject detachJvm() throws Exception {
        context.detachFromAttachedJvm();
        workspace.reset(context.getCurrentFile());
        JsonObject result = archiveSummary();
        result.addProperty("detached", true);
        result.addProperty("snapshotAvailable", true);
        return result;
    }

    private JsonObject terminateAttachedJvm() throws Exception {
        context.terminateAttachedJvm();
        JsonObject result = new JsonObject();
        result.addProperty("terminated", true);
        result.addProperty("snapshotAvailable", true);
        return result;
    }

    private JsonObject archiveSummary() {
        ArchiveInfo archive = archive();
        JsonObject result = new JsonObject();
        result.addProperty("type", switch (archive.type()) {
            case REMOTE_JVM -> "remote JVM";
            case CURRENT_JVM -> "current JVM";
            case CLASS -> "class";
            case ARCHIVE -> "archive";
            case NONE -> throw new IllegalArgumentException("No archive is open in JByteMod");
        });
        result.addProperty("classCount", classes().size());
        result.addProperty("resourceCount", archive.resourceCount());
        result.addProperty("source", archive.source());
        if (context.getSelectedNode() != null) {
            result.addProperty("selectedClass", context.getSelectedNode().name);
        }
        if (context.getSelectedMethod() != null) {
            result.addProperty("selectedMethod", context.getSelectedMethod().name + context.getSelectedMethod().desc);
        }
        return result;
    }

    private JsonObject listChanges() {
        List<McpWorkspace.ChangeInfo> changes = workspace.changes();
        JsonArray items = new JsonArray();
        for (McpWorkspace.ChangeInfo change : changes) {
            JsonObject item = new JsonObject();
            item.addProperty("class", change.className());
            item.addProperty("kind", change.kind());
            addNullable(item, "originalSha256", change.originalSha256());
            addNullable(item, "currentSha256", change.currentSha256());
            items.add(item);
        }
        JsonObject result = new JsonObject();
        result.addProperty("total", changes.size());
        result.addProperty("transactionActive", workspace.transactionActive());
        result.addProperty("undoCount", workspace.undoCount());
        result.addProperty("redoCount", workspace.redoCount());
        result.add("changes", items);
        return result;
    }

    private JsonObject diffClass(JsonObject arguments) {
        String className = requiredString(arguments, "class").replace('.', '/');
        byte[] originalBytes = workspace.originalBytes(className);
        ClassNode current = classes().get(className);
        if (originalBytes == null && current == null) {
            throw new IllegalArgumentException("Class not found in the current or original archive: " + className);
        }
        ClassNode original = originalBytes == null ? null : context.readClass(originalBytes);
        byte[] currentBytes = current == null ? null : context.getClassBytes(current);
        List<String> issues = hotSwapIssues(original, current);

        JsonObject result = new JsonObject();
        result.addProperty("class", className);
        result.addProperty("kind", original == null ? "added" : current == null ? "removed"
                : java.util.Arrays.equals(originalBytes, currentBytes) ? "unchanged" : "modified");
        result.addProperty("hotSwapCompatible", issues.isEmpty());
        result.add("structuralChanges", GSON.toJsonTree(issues));
        if (original != null) {
            result.addProperty("originalByteLength", originalBytes.length);
            result.addProperty("originalBytecode", limit(classText(original)));
        }
        if (current != null) {
            result.addProperty("currentByteLength", currentBytes.length);
            result.addProperty("currentBytecode", limit(classText(current)));
        }
        return result;
    }

    private JsonObject beginTransaction(JsonObject arguments) {
        String description = optionalString(arguments, "description", "MCP transaction");
        workspace.beginTransaction(description);
        JsonObject result = new JsonObject();
        result.addProperty("active", true);
        result.addProperty("description", description);
        return result;
    }

    private JsonObject discardChanges(JsonObject arguments) throws Exception {
        String className = optionalString(arguments, "class", "").replace('.', '/');
        return historyResult(workspace.discard(className.isBlank() ? Set.of() : Set.of(className)));
    }

    private JsonObject validateHotSwap(JsonObject arguments) {
        String requestedClass = optionalString(arguments, "class", "").replace('.', '/');
        Set<String> names = new LinkedHashSet<>();
        if (!requestedClass.isBlank()) {
            names.add(requestedClass);
        } else {
            for (McpWorkspace.ChangeInfo change : workspace.changes()) {
                names.add(change.className());
            }
        }

        boolean compatible = true;
        JsonArray checkedClasses = new JsonArray();
        for (String name : names) {
            byte[] originalBytes = workspace.originalBytes(name);
            ClassNode original = originalBytes == null ? null : context.readClass(originalBytes);
            ClassNode current = classes().get(name);
            if (original == null && current == null) {
                throw new IllegalArgumentException("Class not found in the current or original archive: " + name);
            }
            List<String> issues = hotSwapIssues(original, current);
            compatible &= issues.isEmpty();
            JsonObject item = new JsonObject();
            item.addProperty("class", name);
            item.addProperty("compatible", issues.isEmpty());
            item.add("issues", GSON.toJsonTree(issues));
            checkedClasses.add(item);
        }

        JsonObject result = new JsonObject();
        result.addProperty("compatible", compatible);
        result.addProperty("checkedClassCount", names.size());
        result.add("classes", checkedClasses);
        return result;
    }

    private JsonObject listResources(JsonObject arguments) {
        String query = optionalString(arguments, "query", "").toLowerCase(Locale.ROOT);
        Page page = page(arguments);
        for (String path : context.getResourceNames()) {
            if (!query.isEmpty() && !path.toLowerCase(Locale.ROOT).contains(query)) {
                continue;
            }
            byte[] bytes = context.getResource(path);
            JsonObject item = new JsonObject();
            item.addProperty("path", path);
            item.addProperty("byteLength", bytes == null ? 0 : bytes.length);
            page.add(item);
        }
        return page.result("resources");
    }

    private JsonObject getResource(JsonObject arguments) {
        String path = requiredString(arguments, "path");
        byte[] bytes = context.getResource(path);
        if (bytes == null) {
            throw new IllegalArgumentException("Resource not found: " + path);
        }
        int requestedOffset = optionalInt(arguments, "offset", 0, 0, Integer.MAX_VALUE);
        int offset = Math.min(requestedOffset, bytes.length);
        int length = optionalInt(arguments, "length", MAX_RESOURCE_CHUNK_BYTES, 1, MAX_RESOURCE_CHUNK_BYTES);
        int end = Math.min(offset + length, bytes.length);
        byte[] chunk = Arrays.copyOfRange(bytes, offset, end);
        JsonObject result = resourceResult(path, bytes);
        result.addProperty("offset", offset);
        result.addProperty("returnedByteLength", chunk.length);
        result.addProperty("hasMore", end < bytes.length);
        result.addProperty("dataBase64", Base64.getEncoder().encodeToString(chunk));
        String preview = textPreview(chunk);
        if (preview != null) {
            result.addProperty("textPreview", preview);
        }
        return result;
    }

    private JsonObject putResource(JsonObject arguments, boolean replace) {
        String path = requiredString(arguments, "path");
        byte[] current = context.getResource(path);
        if (replace && current == null) {
            throw new IllegalArgumentException("Resource not found: " + path);
        }
        if (!replace && current != null) {
            throw new IllegalArgumentException("Resource already exists: " + path);
        }
        byte[] bytes = decodeBase64(arguments, "dataBase64", MAX_RESOURCE_BYTES);
        context.putResource(path, bytes);
        JsonObject result = resourceResult(path, bytes);
        result.addProperty(replace ? "replaced" : "added", true);
        return result;
    }

    private JsonObject deleteResource(JsonObject arguments) {
        String path = requiredString(arguments, "path");
        if (!context.removeResource(path)) {
            throw new IllegalArgumentException("Resource not found: " + path);
        }
        JsonObject result = new JsonObject();
        result.addProperty("path", path);
        result.addProperty("deleted", true);
        return result;
    }

    private JsonObject getManifest() {
        byte[] bytes = context.getResource("META-INF/MANIFEST.MF");
        if (bytes == null) {
            throw new IllegalArgumentException("The active archive has no manifest");
        }
        JsonObject result = resourceResult("META-INF/MANIFEST.MF", bytes);
        result.addProperty("content", limit(new String(bytes, StandardCharsets.UTF_8)));
        return result;
    }

    private JsonObject editManifest(JsonObject arguments) {
        byte[] bytes = requiredStringAllowEmpty(arguments, "content").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_RESOURCE_BYTES) {
            throw new IllegalArgumentException("Manifest exceeds " + MAX_RESOURCE_BYTES + " bytes");
        }
        context.putResource("META-INF/MANIFEST.MF", bytes);
        JsonObject result = resourceResult("META-INF/MANIFEST.MF", bytes);
        result.addProperty("modified", true);
        return result;
    }

    private JsonObject exportClass(JsonObject arguments) throws Exception {
        ClassNode classNode = findClass(requiredString(arguments, "class"));
        Path requested = Path.of(requiredString(arguments, "path")).toAbsolutePath().normalize();
        Path output = Files.isDirectory(requested) ? requested.resolve(classNode.name + ".class") : requested;
        if (!output.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".class")) {
            output = Path.of(output + ".class");
        }
        createParentDirectories(output);
        byte[] bytes = context.getClassBytes(classNode);
        Files.write(output, bytes);
        JsonObject result = new JsonObject();
        result.addProperty("class", classNode.name);
        result.addProperty("path", output.toString());
        result.addProperty("byteLength", bytes.length);
        result.addProperty("exported", true);
        return result;
    }

    private JsonObject exportPackage(JsonObject arguments) throws Exception {
        String packageName = normalizePackageName(requiredString(arguments, "package"));
        boolean includeSubpackages = optionalBoolean(arguments, "includeSubpackages", true);
        List<ClassNode> matches = sortedClasses().stream()
                .filter(classNode -> belongsToPackage(classNode.name, packageName, includeSubpackages))
                .toList();
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("No loaded classes found in package: " + packageName);
        }
        Path output = Path.of(requiredString(arguments, "path")).toAbsolutePath().normalize();
        if (!output.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            output = Path.of(output + ".jar");
        }
        createParentDirectories(output);
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(output))) {
            for (ClassNode classNode : matches) {
                jar.putNextEntry(new JarEntry(classNode.name + ".class"));
                jar.write(context.getClassBytes(classNode));
                jar.closeEntry();
            }
        }
        JsonObject result = new JsonObject();
        result.addProperty("package", packageName);
        result.addProperty("path", output.toString());
        result.addProperty("classCount", matches.size());
        result.addProperty("includeSubpackages", includeSubpackages);
        result.addProperty("exported", true);
        return result;
    }

    private static JsonObject historyResult(McpWorkspace.HistoryResult history) {
        JsonObject result = new JsonObject();
        result.addProperty("description", history.description());
        result.addProperty("classCount", history.classCount());
        result.addProperty("undoCount", history.undoCount());
        result.addProperty("redoCount", history.redoCount());
        return result;
    }

    private static List<String> hotSwapIssues(ClassNode original, ClassNode current) {
        List<String> issues = new ArrayList<>();
        if (original == null) {
            issues.add("Class was added");
            return issues;
        }
        if (current == null) {
            issues.add("Class was removed");
            return issues;
        }
        if (!original.name.equals(current.name)) issues.add("Class name changed");
        if (original.version != current.version) issues.add("Class-file version changed");
        if (original.access != current.access) issues.add("Class access flags changed");
        if (!Objects.equals(original.superName, current.superName)) issues.add("Superclass changed");
        if (!new HashSet<>(original.interfaces).equals(new HashSet<>(current.interfaces))) {
            issues.add("Implemented interfaces changed");
        }
        if (!fieldSchema(original).equals(fieldSchema(current))) issues.add("Field schema changed");
        if (!methodSchema(original).equals(methodSchema(current))) issues.add("Method schema changed");
        if (!Objects.equals(original.nestHostClass, current.nestHostClass)
                || !new HashSet<>(orEmpty(original.nestMembers)).equals(new HashSet<>(orEmpty(current.nestMembers)))) {
            issues.add("Nest membership changed");
        }
        if (!new HashSet<>(orEmpty(original.permittedSubclasses))
                .equals(new HashSet<>(orEmpty(current.permittedSubclasses)))) {
            issues.add("Permitted subclasses changed");
        }
        List<String> originalRecords = original.recordComponents == null ? List.of()
                : original.recordComponents.stream().map(component -> component.name + component.descriptor).sorted().toList();
        List<String> currentRecords = current.recordComponents == null ? List.of()
                : current.recordComponents.stream().map(component -> component.name + component.descriptor).sorted().toList();
        if (!originalRecords.equals(currentRecords)) issues.add("Record components changed");
        return issues;
    }

    private static List<String> fieldSchema(ClassNode classNode) {
        return classNode.fields.stream()
                .map(field -> field.name + " " + field.desc + " " + field.access)
                .sorted().toList();
    }

    private static List<String> methodSchema(ClassNode classNode) {
        return classNode.methods.stream()
                .map(method -> method.name + method.desc + " " + method.access)
                .sorted().toList();
    }

    private static <T> List<T> orEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static String classText(ClassNode classNode) {
        StringWriter output = new StringWriter();
        PrintWriter writer = new PrintWriter(output);
        classNode.accept(new TraceClassVisitor(null, new Textifier(), writer));
        writer.flush();
        return output.toString();
    }

    private JsonObject listClasses(JsonObject arguments) {
        String query = optionalString(arguments, "query", "").replace('.', '/').toLowerCase(Locale.ROOT);
        int offset = optionalInt(arguments, "offset", 0, 0, Integer.MAX_VALUE);
        int limit = optionalInt(arguments, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT);
        List<String> names = new ArrayList<>(classes().keySet());
        names.removeIf(name -> !name.toLowerCase(Locale.ROOT).contains(query));
        names.sort(String::compareTo);

        int from = Math.min(offset, names.size());
        int to = Math.min(from + limit, names.size());
        JsonObject result = new JsonObject();
        result.addProperty("total", names.size());
        result.addProperty("offset", from);
        result.addProperty("hasMore", to < names.size());
        result.add("classes", GSON.toJsonTree(names.subList(from, to)));
        return result;
    }

    private JsonObject searchMembers(JsonObject arguments) {
        String query = requiredString(arguments, "query").toLowerCase(Locale.ROOT);
        String kind = optionalString(arguments, "kind", "any").toLowerCase(Locale.ROOT);
        if (!Set.of("any", "field", "method").contains(kind)) {
            throw new IllegalArgumentException("Unsupported member kind: " + kind);
        }
        Page page = page(arguments);
        for (ClassNode classNode : sortedClasses()) {
            if (!"method".equals(kind)) {
                for (FieldNode field : classNode.fields) {
                    if (matches(query, classNode.name, field.name, field.desc)) {
                        JsonObject item = new JsonObject();
                        item.addProperty("kind", "field");
                        item.addProperty("class", classNode.name);
                        item.addProperty("name", field.name);
                        item.addProperty("descriptor", field.desc);
                        item.addProperty("access", field.access);
                        page.add(item);
                    }
                }
            }
            if (!"field".equals(kind)) {
                for (MethodNode method : classNode.methods) {
                    if (matches(query, classNode.name, method.name, method.desc)) {
                        JsonObject item = new JsonObject();
                        item.addProperty("kind", "method");
                        item.addProperty("class", classNode.name);
                        item.addProperty("name", method.name);
                        item.addProperty("descriptor", method.desc);
                        item.addProperty("access", method.access);
                        page.add(item);
                    }
                }
            }
        }
        return page.result("members");
    }

    private JsonObject searchConstants(JsonObject arguments) {
        String query = requiredString(arguments, "query").toLowerCase(Locale.ROOT);
        String valueType = optionalString(arguments, "valueType", "any").toLowerCase(Locale.ROOT);
        if (!Set.of("any", "string", "int", "long", "float", "double", "type").contains(valueType)) {
            throw new IllegalArgumentException("Unsupported constant type: " + valueType);
        }
        Page page = page(arguments);
        for (ClassNode classNode : sortedClasses()) {
            for (MethodNode method : classNode.methods) {
                for (int index = 0; index < method.instructions.size(); index++) {
                    AbstractInsnNode instruction = method.instructions.get(index);
                    if (!(instruction instanceof LdcInsnNode ldc)) {
                        continue;
                    }
                    JsonObject item = constant(index, ldc.cst);
                    if (!("any".equals(valueType) || valueType.equals(item.get("valueType").getAsString()))
                            || !item.get("value").getAsString().toLowerCase(Locale.ROOT).contains(query)) {
                        continue;
                    }
                    item.addProperty("class", classNode.name);
                    item.addProperty("method", method.name);
                    item.addProperty("descriptor", method.desc);
                    page.add(item);
                }
            }
        }
        return page.result("constants");
    }

    private JsonObject findReferences(JsonObject arguments) {
        String kind = requiredString(arguments, "kind").toLowerCase(Locale.ROOT);
        if (!Set.of("class", "field", "method").contains(kind)) {
            throw new IllegalArgumentException("Unsupported reference kind: " + kind);
        }
        String owner = requiredString(arguments, "owner").replace('.', '/');
        String name = optionalString(arguments, "name", "");
        String descriptor = optionalString(arguments, "descriptor", "");
        if (!"class".equals(kind) && name.isBlank()) {
            throw new IllegalArgumentException("name is required for " + kind + " references");
        }

        Page page = page(arguments);
        for (ClassNode classNode : sortedClasses()) {
            for (MethodNode method : classNode.methods) {
                for (int index = 0; index < method.instructions.size(); index++) {
                    AbstractInsnNode instruction = method.instructions.get(index);
                    boolean match = switch (kind) {
                        case "field" -> instruction instanceof FieldInsnNode field
                                && owner.equals(field.owner) && name.equals(field.name)
                                && (descriptor.isBlank() || descriptor.equals(field.desc));
                        case "method" -> instruction instanceof MethodInsnNode call
                                && owner.equals(call.owner) && name.equals(call.name)
                                && (descriptor.isBlank() || descriptor.equals(call.desc));
                        default -> referencesClass(instruction, owner);
                    };
                    if (match) {
                        page.add(referenceLocation(classNode, method, index, instruction));
                    }
                }
            }
        }
        return page.result("references");
    }

    private JsonObject classHierarchy(JsonObject arguments) {
        ClassNode root = findClass(requiredString(arguments, "class"));
        Map<String, ClassNode> available = classes();
        JsonObject result = new JsonObject();
        result.addProperty("class", root.name);
        addNullable(result, "superName", root.superName);
        result.add("interfaces", GSON.toJsonTree(root.interfaces));

        JsonArray ancestors = new JsonArray();
        ArrayDeque<String> pending = new ArrayDeque<>();
        if (root.superName != null) {
            pending.add(root.superName);
        }
        pending.addAll(root.interfaces);
        Set<String> visited = new HashSet<>();
        while (!pending.isEmpty()) {
            String name = pending.removeFirst();
            if (!visited.add(name)) {
                continue;
            }
            ClassNode ancestor = available.get(name);
            JsonObject item = new JsonObject();
            item.addProperty("name", name);
            item.addProperty("loaded", ancestor != null);
            ancestors.add(item);
            if (ancestor != null) {
                if (ancestor.superName != null) {
                    pending.addLast(ancestor.superName);
                }
                pending.addAll(ancestor.interfaces);
            }
        }
        result.add("ancestors", ancestors);

        List<String> directSubtypes = directSubtypes(root.name, available);
        result.add("directSubtypes", GSON.toJsonTree(directSubtypes));
        Set<String> allSubtypes = new java.util.TreeSet<>();
        pending.addAll(directSubtypes);
        while (!pending.isEmpty()) {
            String name = pending.removeFirst();
            if (allSubtypes.add(name)) {
                pending.addAll(directSubtypes(name, available));
            }
        }
        result.add("allSubtypes", GSON.toJsonTree(allSubtypes));
        return result;
    }

    private JsonObject verifyClass(JsonObject arguments) {
        ClassNode classNode = findClass(requiredString(arguments, "class"));
        boolean dataflow = optionalBoolean(arguments, "dataflow", true);
        StringWriter diagnostics = new StringWriter();
        boolean valid = true;
        try {
            classNode.accept(new CheckClassAdapter(new ClassWriter(0), dataflow));
        } catch (Throwable throwable) {
            valid = false;
            throwable.printStackTrace(new PrintWriter(diagnostics));
        }
        JsonObject result = new JsonObject();
        result.addProperty("class", classNode.name);
        result.addProperty("valid", valid);
        result.addProperty("dataflow", dataflow);
        result.addProperty("diagnostics", limit(diagnostics.toString()));
        return result;
    }

    private JsonObject methodCalls(JsonObject arguments) {
        ClassNode targetClass = findClass(requiredString(arguments, "class"));
        MethodNode targetMethod = findMethod(targetClass, requiredString(arguments, "method"),
                requiredString(arguments, "descriptor"));
        String direction = optionalString(arguments, "direction", "both").toLowerCase(Locale.ROOT);
        if (!Set.of("incoming", "outgoing", "both").contains(direction)) {
            throw new IllegalArgumentException("Unsupported call direction: " + direction);
        }

        Page page = page(arguments);
        if (!"outgoing".equals(direction)) {
            for (ClassNode classNode : sortedClasses()) {
                for (MethodNode method : classNode.methods) {
                    for (int index = 0; index < method.instructions.size(); index++) {
                        AbstractInsnNode instruction = method.instructions.get(index);
                        if (instruction instanceof MethodInsnNode call
                                && targetClass.name.equals(call.owner)
                                && targetMethod.name.equals(call.name)
                                && targetMethod.desc.equals(call.desc)) {
                            page.add(methodCall("incoming", classNode, method, index,
                                    call.owner, call.name, call.desc, opcodeName(call.getOpcode())));
                        }
                    }
                }
            }
        }
        if (!"incoming".equals(direction)) {
            for (int index = 0; index < targetMethod.instructions.size(); index++) {
                AbstractInsnNode instruction = targetMethod.instructions.get(index);
                if (instruction instanceof MethodInsnNode call) {
                    page.add(methodCall("outgoing", targetClass, targetMethod, index,
                            call.owner, call.name, call.desc, opcodeName(call.getOpcode())));
                } else if (instruction instanceof InvokeDynamicInsnNode dynamic) {
                    page.add(methodCall("outgoing", targetClass, targetMethod, index,
                            null, dynamic.name, dynamic.desc, "INVOKEDYNAMIC"));
                }
            }
        }
        return page.result("calls");
    }

    private JsonObject callGraph(JsonObject arguments) {
        ClassNode rootClass = findClass(requiredString(arguments, "class"));
        MethodNode rootMethod = findMethod(rootClass, requiredString(arguments, "method"),
                requiredString(arguments, "descriptor"));
        String direction = optionalString(arguments, "direction", "both").toLowerCase(Locale.ROOT);
        if (!Set.of("incoming", "outgoing", "both").contains(direction)) {
            throw new IllegalArgumentException("Unsupported call direction: " + direction);
        }
        int maximumDepth = optionalInt(arguments, "depth", 2, 1, 5);
        boolean includeExternal = optionalBoolean(arguments, "includeExternal", false);
        int maximumNodes = optionalInt(arguments, "maxNodes", 200, 1, MAX_LIMIT);

        CallGraphIndex index = buildCallGraphIndex();
        MethodKey root = new MethodKey(rootClass.name, rootMethod.name, rootMethod.desc);
        LinkedHashMap<MethodKey, Integer> nodes = new LinkedHashMap<>();
        LinkedHashSet<CallSite> edges = new LinkedHashSet<>();
        ArrayDeque<GraphVisit> pending = new ArrayDeque<>();
        nodes.put(root, 0);
        pending.add(new GraphVisit(root, 0));
        boolean truncated = false;

        while (!pending.isEmpty()) {
            GraphVisit visit = pending.removeFirst();
            if (visit.depth() >= maximumDepth) {
                continue;
            }
            if (!"incoming".equals(direction)) {
                for (CallSite edge : index.outgoing().getOrDefault(visit.method(), List.of())) {
                    if (!includeExternal && !index.methods().containsKey(edge.target())) {
                        continue;
                    }
                    if (!nodes.containsKey(edge.target()) && nodes.size() >= maximumNodes) {
                        truncated = true;
                        continue;
                    }
                    edges.add(edge);
                    if (addGraphNode(nodes, edge.target(), visit.depth() + 1)) {
                        if (index.methods().containsKey(edge.target())) {
                            pending.addLast(new GraphVisit(edge.target(), visit.depth() + 1));
                        }
                    }
                }
            }
            if (!"outgoing".equals(direction)) {
                for (CallSite edge : index.incoming().getOrDefault(visit.method(), List.of())) {
                    if (!nodes.containsKey(edge.source()) && nodes.size() >= maximumNodes) {
                        truncated = true;
                        continue;
                    }
                    edges.add(edge);
                    if (addGraphNode(nodes, edge.source(), visit.depth() + 1)) {
                        pending.addLast(new GraphVisit(edge.source(), visit.depth() + 1));
                    }
                }
            }
        }

        JsonArray nodeArray = new JsonArray();
        for (Map.Entry<MethodKey, Integer> entry : nodes.entrySet()) {
            nodeArray.add(callGraphNode(entry.getKey(), index.methods().get(entry.getKey()), entry.getValue()));
        }
        JsonArray edgeArray = new JsonArray();
        for (CallSite edge : edges) {
            edgeArray.add(callGraphEdge(edge));
        }

        JsonObject result = new JsonObject();
        result.add("root", methodReference(root));
        result.addProperty("direction", direction);
        result.addProperty("depth", maximumDepth);
        result.addProperty("includeExternal", includeExternal);
        result.addProperty("maxNodes", maximumNodes);
        result.addProperty("nodeCount", nodes.size());
        result.addProperty("edgeCount", edges.size());
        result.addProperty("truncated", truncated);
        result.addProperty("resolution", "static bytecode references");
        result.add("nodes", nodeArray);
        result.add("edges", edgeArray);
        return result;
    }

    private CallGraphIndex buildCallGraphIndex() {
        LinkedHashMap<MethodKey, MethodOwner> methods = new LinkedHashMap<>();
        LinkedHashMap<MethodKey, List<CallSite>> outgoing = new LinkedHashMap<>();
        LinkedHashMap<MethodKey, List<CallSite>> incoming = new LinkedHashMap<>();

        for (ClassNode classNode : sortedClasses()) {
            for (MethodNode method : classNode.methods) {
                methods.put(new MethodKey(classNode.name, method.name, method.desc),
                        new MethodOwner(classNode, method));
            }
        }
        for (Map.Entry<MethodKey, MethodOwner> entry : methods.entrySet()) {
            MethodKey source = entry.getKey();
            MethodNode method = entry.getValue().method();
            for (int index = 0; index < method.instructions.size(); index++) {
                AbstractInsnNode instruction = method.instructions.get(index);
                if (instruction instanceof MethodInsnNode call) {
                    addCallSite(outgoing, incoming, new CallSite(source,
                            new MethodKey(call.owner, call.name, call.desc), index,
                            opcodeName(call.getOpcode()), false));
                } else if (instruction instanceof InvokeDynamicInsnNode dynamic) {
                    LinkedHashSet<MethodKey> targets = new LinkedHashSet<>();
                    for (Object argument : dynamic.bsmArgs) {
                        collectDynamicCallTargets(argument, targets);
                    }
                    for (MethodKey target : targets) {
                        addCallSite(outgoing, incoming, new CallSite(source, target, index,
                                "INVOKEDYNAMIC", true));
                    }
                }
            }
        }
        return new CallGraphIndex(methods, outgoing, incoming);
    }

    private static boolean addGraphNode(Map<MethodKey, Integer> nodes, MethodKey method, int depth) {
        Integer previousDepth = nodes.putIfAbsent(method, depth);
        return previousDepth == null;
    }

    private static void addCallSite(Map<MethodKey, List<CallSite>> outgoing,
                                    Map<MethodKey, List<CallSite>> incoming, CallSite callSite) {
        outgoing.computeIfAbsent(callSite.source(), ignored -> new ArrayList<>()).add(callSite);
        incoming.computeIfAbsent(callSite.target(), ignored -> new ArrayList<>()).add(callSite);
    }

    private static void collectDynamicCallTargets(Object value, Set<MethodKey> targets) {
        if (value instanceof Handle handle) {
            if (handle.getTag() >= Opcodes.H_INVOKEVIRTUAL && handle.getTag() <= Opcodes.H_INVOKEINTERFACE) {
                targets.add(new MethodKey(handle.getOwner(), handle.getName(), handle.getDesc()));
            }
        } else if (value instanceof ConstantDynamic dynamic) {
            for (int index = 0; index < dynamic.getBootstrapMethodArgumentCount(); index++) {
                collectDynamicCallTargets(dynamic.getBootstrapMethodArgument(index), targets);
            }
        }
    }

    private static JsonObject callGraphNode(MethodKey method, MethodOwner loadedMethod, int depth) {
        JsonObject result = methodReference(method);
        result.addProperty("depth", depth);
        result.addProperty("loaded", loadedMethod != null);
        if (loadedMethod != null) {
            result.addProperty("access", loadedMethod.method().access);
            result.addProperty("instructionCount", loadedMethod.method().instructions.size());
        }
        return result;
    }

    private static JsonObject callGraphEdge(CallSite edge) {
        JsonObject result = new JsonObject();
        result.add("source", methodReference(edge.source()));
        result.add("target", methodReference(edge.target()));
        result.addProperty("instructionIndex", edge.instructionIndex());
        result.addProperty("opcode", edge.opcode());
        result.addProperty("invokedynamic", edge.invokeDynamic());
        return result;
    }

    private static JsonObject methodReference(MethodKey method) {
        JsonObject result = new JsonObject();
        result.addProperty("class", method.owner());
        result.addProperty("method", method.name());
        result.addProperty("descriptor", method.descriptor());
        return result;
    }

    private JsonObject describeClass(JsonObject arguments) {
        ClassNode classNode = findClass(requiredString(arguments, "class"));
        JsonObject result = new JsonObject();
        result.addProperty("name", classNode.name);
        result.addProperty("version", classNode.version);
        result.addProperty("access", classNode.access);
        addNullable(result, "signature", classNode.signature);
        addNullable(result, "superName", classNode.superName);
        result.add("interfaces", GSON.toJsonTree(classNode.interfaces));

        JsonArray fields = new JsonArray();
        for (FieldNode field : classNode.fields) {
            JsonObject item = new JsonObject();
            item.addProperty("name", field.name);
            item.addProperty("descriptor", field.desc);
            item.addProperty("access", field.access);
            addNullable(item, "signature", field.signature);
            if (field.value != null) {
                item.addProperty("value", String.valueOf(field.value));
            }
            fields.add(item);
        }
        result.add("fields", fields);

        JsonArray methods = new JsonArray();
        for (MethodNode method : classNode.methods) {
            JsonObject item = new JsonObject();
            item.addProperty("name", method.name);
            item.addProperty("descriptor", method.desc);
            item.addProperty("access", method.access);
            addNullable(item, "signature", method.signature);
            item.add("exceptions", GSON.toJsonTree(method.exceptions));
            item.addProperty("instructionCount", method.instructions == null ? 0 : method.instructions.size());
            methods.add(item);
        }
        result.add("methods", methods);
        return result;
    }

    private JsonElement classFile(JsonObject arguments) {
        ClassNode classNode = findClass(requiredString(arguments, "class"));
        return new com.google.gson.JsonPrimitive(Base64.getEncoder().encodeToString(
                context.getClassBytes(classNode)));
    }

    private JsonObject replaceClass(JsonObject arguments) throws Exception {
        String className = requiredString(arguments, "class").replace('.', '/');
        ClassNode previous = findClass(className);
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(requiredString(arguments, "classFileBase64"));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("classFileBase64 is not valid base64");
        }
        if (bytes.length > MAX_CLASS_FILE_BYTES) {
            throw new IllegalArgumentException("Replacement class exceeds " + MAX_CLASS_FILE_BYTES + " bytes");
        }

        ClassNode replacement;
        try {
            replacement = context.readClass(bytes);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Replacement is not a valid class file: " + exception.getMessage());
        }
        if (!className.equals(replacement.name)) {
            throw new IllegalArgumentException("Replacement class name " + replacement.name
                    + " does not match " + className);
        }

        workspace.mutate("Replace class " + className, Set.of(className), () -> {
            synchronized (mutationLock) {
                context.replaceClass(previous, replacement);
            }
            return null;
        });

        JsonObject result = new JsonObject();
        result.addProperty("class", className);
        result.addProperty("byteLength", bytes.length);
        result.addProperty("fieldCount", replacement.fields.size());
        result.addProperty("methodCount", replacement.methods.size());
        result.addProperty("modified", true);
        return result;
    }

    private JsonObject renameClass(JsonObject arguments) throws Exception {
        String oldName = findClass(requiredString(arguments, "class")).name;
        String newName = normalizeClassName(requiredString(arguments, "newName"));
        if (classes().containsKey(newName)) {
            throw new IllegalArgumentException("A class named " + newName + " already exists");
        }
        Set<String> affected = new LinkedHashSet<>(classes().keySet());
        affected.add(newName);
        workspace.mutate("Rename class " + oldName + " to " + newName, affected, () -> {
            remapAll(new Remapper() {
                @Override
                public String map(String internalName) {
                    return oldName.equals(internalName) ? newName : internalName;
                }
            });
            return null;
        });
        JsonObject result = new JsonObject();
        result.addProperty("previousName", oldName);
        result.addProperty("class", newName);
        result.addProperty("updatedClassCount", affected.size());
        result.addProperty("modified", true);
        return result;
    }

    private JsonObject renameMethod(JsonObject arguments) throws Exception {
        ClassNode owner = findClass(requiredString(arguments, "class"));
        String oldName = requiredString(arguments, "method");
        String descriptor = requiredString(arguments, "descriptor");
        findMethod(owner, oldName, descriptor);
        String newName = requiredString(arguments, "newName");
        if (oldName.startsWith("<") || newName.startsWith("<")) {
            throw new IllegalArgumentException("Constructors and class initializers cannot be renamed");
        }
        if (owner.methods.stream().anyMatch(method -> method.name.equals(newName) && method.desc.equals(descriptor))) {
            throw new IllegalArgumentException("Method already exists: " + newName + descriptor);
        }
        Set<String> affected = new LinkedHashSet<>(classes().keySet());
        workspace.mutate("Rename method " + owner.name + "." + oldName + descriptor, affected, () -> {
            remapAll(new Remapper() {
                @Override
                public String mapMethodName(String declaringOwner, String name, String methodDescriptor) {
                    return owner.name.equals(declaringOwner) && oldName.equals(name)
                            && descriptor.equals(methodDescriptor) ? newName : name;
                }
            });
            return null;
        });
        JsonObject result = new JsonObject();
        result.addProperty("class", owner.name);
        result.addProperty("previousName", oldName);
        result.addProperty("method", newName);
        result.addProperty("descriptor", descriptor);
        result.addProperty("modified", true);
        return result;
    }

    private JsonObject renameField(JsonObject arguments) throws Exception {
        ClassNode owner = findClass(requiredString(arguments, "class"));
        String oldName = requiredString(arguments, "field");
        String descriptor = requiredString(arguments, "descriptor");
        findField(owner, oldName, descriptor);
        String newName = requiredString(arguments, "newName");
        if (owner.fields.stream().anyMatch(field -> field.name.equals(newName) && field.desc.equals(descriptor))) {
            throw new IllegalArgumentException("Field already exists: " + newName + " " + descriptor);
        }
        Set<String> affected = new LinkedHashSet<>(classes().keySet());
        workspace.mutate("Rename field " + owner.name + "." + oldName + " " + descriptor, affected, () -> {
            remapAll(new Remapper() {
                @Override
                public String mapFieldName(String declaringOwner, String name, String fieldDescriptor) {
                    return owner.name.equals(declaringOwner) && oldName.equals(name)
                            && descriptor.equals(fieldDescriptor) ? newName : name;
                }
            });
            return null;
        });
        JsonObject result = new JsonObject();
        result.addProperty("class", owner.name);
        result.addProperty("previousName", oldName);
        result.addProperty("field", newName);
        result.addProperty("descriptor", descriptor);
        result.addProperty("modified", true);
        return result;
    }

    private JsonObject setAccessFlags(JsonObject arguments) throws Exception {
        ClassNode classNode = findClass(requiredString(arguments, "class"));
        String target = requiredString(arguments, "target").toLowerCase(Locale.ROOT);
        int access = requiredInt(arguments, "access", 0, Integer.MAX_VALUE);
        int[] previous = new int[1];
        String member;
        switch (target) {
            case "class" -> member = classNode.name;
            case "field" -> member = findField(classNode, requiredString(arguments, "name"),
                    requiredString(arguments, "descriptor")).name;
            case "method" -> member = findMethod(classNode, requiredString(arguments, "name"),
                    requiredString(arguments, "descriptor")).name;
            default -> throw new IllegalArgumentException("Unsupported target: " + target);
        }
        workspace.mutate("Set access flags on " + classNode.name + "." + member,
                Set.of(classNode.name), () -> {
                    switch (target) {
                        case "class" -> {
                            previous[0] = classNode.access;
                            classNode.access = access;
                        }
                        case "field" -> {
                            FieldNode field = findField(classNode, requiredString(arguments, "name"),
                                    requiredString(arguments, "descriptor"));
                            previous[0] = field.access;
                            field.access = access;
                        }
                        case "method" -> {
                            MethodNode method = findMethod(classNode, requiredString(arguments, "name"),
                                    requiredString(arguments, "descriptor"));
                            previous[0] = method.access;
                            method.access = access;
                        }
                    }
                    context.updateTree();
                    return null;
                });
        JsonObject result = new JsonObject();
        result.addProperty("target", target);
        result.addProperty("class", classNode.name);
        result.addProperty("previousAccess", previous[0]);
        result.addProperty("access", access);
        result.addProperty("modified", previous[0] != access);
        return result;
    }

    private JsonObject addField(JsonObject arguments) throws Exception {
        ClassNode classNode = findClass(requiredString(arguments, "class"));
        String name = requiredString(arguments, "field");
        String descriptor = validFieldDescriptor(requiredString(arguments, "descriptor"));
        if (classNode.fields.stream().anyMatch(field -> field.name.equals(name) && field.desc.equals(descriptor))) {
            throw new IllegalArgumentException("Field already exists: " + name + " " + descriptor);
        }
        int access = optionalInt(arguments, "access", Opcodes.ACC_PUBLIC, 0, Integer.MAX_VALUE);
        String signature = nullableOptionalString(arguments, "signature");
        Object value = parseFieldValue(arguments);
        workspace.mutate("Add field " + classNode.name + "." + name,
                Set.of(classNode.name), () -> {
                    classNode.fields.add(new FieldNode(access, name, descriptor, signature, value));
                    context.updateTree();
                    return null;
                });
        JsonObject result = new JsonObject();
        result.addProperty("class", classNode.name);
        result.addProperty("field", name);
        result.addProperty("descriptor", descriptor);
        result.addProperty("access", access);
        result.addProperty("modified", true);
        return result;
    }

    private JsonObject removeField(JsonObject arguments) throws Exception {
        ClassNode classNode = findClass(requiredString(arguments, "class"));
        FieldNode field = findField(classNode, requiredString(arguments, "field"),
                requiredString(arguments, "descriptor"));
        workspace.mutate("Remove field " + classNode.name + "." + field.name,
                Set.of(classNode.name), () -> {
                    classNode.fields.remove(field);
                    context.updateTree();
                    return null;
                });
        JsonObject result = new JsonObject();
        result.addProperty("class", classNode.name);
        result.addProperty("field", field.name);
        result.addProperty("descriptor", field.desc);
        result.addProperty("removed", true);
        return result;
    }

    private JsonObject addMethod(JsonObject arguments) throws Exception {
        ClassNode classNode = findClass(requiredString(arguments, "class"));
        String name = requiredString(arguments, "method");
        String descriptor = validMethodDescriptor(requiredString(arguments, "descriptor"));
        if ("<init>".equals(name)) {
            throw new IllegalArgumentException("Use copy_method to add a valid constructor");
        }
        if (classNode.methods.stream().anyMatch(method -> method.name.equals(name) && method.desc.equals(descriptor))) {
            throw new IllegalArgumentException("Method already exists: " + name + descriptor);
        }
        int access = optionalInt(arguments, "access", Opcodes.ACC_PUBLIC, 0, Integer.MAX_VALUE);
        String signature = nullableOptionalString(arguments, "signature");
        MethodNode method = defaultMethod(access, name, descriptor, signature);
        workspace.mutate("Add method " + classNode.name + "." + name + descriptor,
                Set.of(classNode.name), () -> {
                    classNode.methods.add(method);
                    context.updateTree();
                    return null;
                });
        JsonObject result = new JsonObject();
        result.addProperty("class", classNode.name);
        result.addProperty("method", name);
        result.addProperty("descriptor", descriptor);
        result.addProperty("access", access);
        result.addProperty("modified", true);
        return result;
    }

    private JsonObject removeMethod(JsonObject arguments) throws Exception {
        ClassNode classNode = findClass(requiredString(arguments, "class"));
        MethodNode method = findMethod(classNode, requiredString(arguments, "method"),
                requiredString(arguments, "descriptor"));
        workspace.mutate("Remove method " + classNode.name + "." + method.name + method.desc,
                Set.of(classNode.name), () -> {
                    classNode.methods.remove(method);
                    context.updateTree();
                    return null;
                });
        JsonObject result = new JsonObject();
        result.addProperty("class", classNode.name);
        result.addProperty("method", method.name);
        result.addProperty("descriptor", method.desc);
        result.addProperty("removed", true);
        return result;
    }

    private JsonObject copyMethod(JsonObject arguments) throws Exception {
        ClassNode sourceClass = findClass(requiredString(arguments, "sourceClass"));
        ClassNode targetClass = findClass(requiredString(arguments, "targetClass"));
        MethodNode source = findMethod(sourceClass, requiredString(arguments, "method"),
                requiredString(arguments, "descriptor"));
        String newName = optionalString(arguments, "newName", source.name);
        if (targetClass.methods.stream().anyMatch(method -> method.name.equals(newName) && method.desc.equals(source.desc))) {
            throw new IllegalArgumentException("Target method already exists: " + newName + source.desc);
        }
        MethodNode copy = cloneMethod(source, newName);
        workspace.mutate("Copy method to " + targetClass.name + "." + newName + source.desc,
                Set.of(targetClass.name), () -> {
                    targetClass.methods.add(copy);
                    context.updateTree();
                    return null;
                });
        JsonObject result = new JsonObject();
        result.addProperty("sourceClass", sourceClass.name);
        result.addProperty("targetClass", targetClass.name);
        result.addProperty("method", newName);
        result.addProperty("descriptor", source.desc);
        result.addProperty("modified", true);
        return result;
    }

    private JsonObject replaceMethodBody(JsonObject arguments) throws Exception {
        ClassNode sourceClass = findClass(requiredString(arguments, "sourceClass"));
        ClassNode targetClass = findClass(requiredString(arguments, "targetClass"));
        MethodNode source = findMethod(sourceClass, requiredString(arguments, "method"),
                requiredString(arguments, "descriptor"));
        MethodNode target = findMethod(targetClass, requiredString(arguments, "targetMethod"),
                requiredString(arguments, "targetDescriptor"));
        if (!source.desc.equals(target.desc)) {
            throw new IllegalArgumentException("Source and target method descriptors must match");
        }
        MethodNode copy = cloneMethod(source, target.name);
        workspace.mutate("Replace body of " + targetClass.name + "." + target.name + target.desc,
                Set.of(targetClass.name), () -> {
                    target.instructions = copy.instructions;
                    target.tryCatchBlocks = copy.tryCatchBlocks;
                    target.localVariables = copy.localVariables;
                    target.visibleLocalVariableAnnotations = copy.visibleLocalVariableAnnotations;
                    target.invisibleLocalVariableAnnotations = copy.invisibleLocalVariableAnnotations;
                    target.maxStack = copy.maxStack;
                    target.maxLocals = copy.maxLocals;
                    context.methodModified(targetClass, target);
                    return null;
                });
        JsonObject result = new JsonObject();
        result.addProperty("source", sourceClass.name + "." + source.name + source.desc);
        result.addProperty("target", targetClass.name + "." + target.name + target.desc);
        result.addProperty("instructionCount", target.instructions.size());
        result.addProperty("modified", true);
        return result;
    }

    private JsonObject editClassMetadata(JsonObject arguments) throws Exception {
        ClassNode classNode = findClass(requiredString(arguments, "class"));
        workspace.mutate("Edit metadata for " + classNode.name, Set.of(classNode.name), () -> {
            if (arguments.has("superClass")) {
                classNode.superName = nullableClassName(arguments.get("superClass").getAsString());
            }
            if (arguments.has("signature")) {
                classNode.signature = emptyToNull(arguments.get("signature").getAsString());
            }
            if (arguments.has("sourceFile")) {
                classNode.sourceFile = emptyToNull(arguments.get("sourceFile").getAsString());
            }
            if (arguments.has("interfaces")) {
                classNode.interfaces = stringArray(arguments, "interfaces").stream()
                        .map(McpTools::normalizeClassName).toList();
            }
            context.updateTree();
            return null;
        });
        JsonObject result = new JsonObject();
        result.addProperty("class", classNode.name);
        addNullable(result, "superClass", classNode.superName);
        addNullable(result, "signature", classNode.signature);
        addNullable(result, "sourceFile", classNode.sourceFile);
        result.add("interfaces", GSON.toJsonTree(classNode.interfaces));
        result.addProperty("modified", true);
        return result;
    }

    private void remapAll(Remapper remapper) {
        Map<String, ClassNode> current = classes();
        List<ClassNode> remapped = new ArrayList<>(current.size());
        for (ClassNode classNode : current.values()) {
            ClassNode copy = new ClassNode();
            classNode.accept(new ClassRemapper(copy, remapper));
            remapped.add(copy);
        }
        current.clear();
        for (ClassNode classNode : remapped) {
            current.put(classNode.name, classNode);
        }
        context.updateTree();
    }

    private JsonObject listInstructions(JsonObject arguments) {
        ClassNode classNode = findClass(requiredString(arguments, "class"));
        MethodNode method = findMethod(classNode, requiredString(arguments, "method"),
                requiredString(arguments, "descriptor"));
        int offset = optionalInt(arguments, "offset", 0, 0, Integer.MAX_VALUE);
        int limit = optionalInt(arguments, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT);
        int size = method.instructions.size();
        int from = Math.min(offset, size);
        int to = Math.min(from + limit, size);

        JsonArray instructions = new JsonArray();
        for (int index = from; index < to; index++) {
            instructions.add(instruction(method, index, method.instructions.get(index)));
        }
        JsonObject result = new JsonObject();
        result.addProperty("total", size);
        result.addProperty("offset", from);
        result.addProperty("hasMore", to < size);
        result.add("instructions", instructions);
        return result;
    }

    private JsonObject editInstruction(JsonObject arguments) throws Exception {
        ClassNode classNode = findClass(requiredString(arguments, "class"));
        MethodNode method = findMethod(classNode, requiredString(arguments, "method"),
                requiredString(arguments, "descriptor"));
        String operation = requiredString(arguments, "operation").toLowerCase(Locale.ROOT);
        int instructionIndex = requiredInt(arguments, "instructionIndex", 0, Integer.MAX_VALUE);
        if (instructionIndex >= method.instructions.size()) {
            throw new IllegalArgumentException("instructionIndex is outside the method");
        }
        AbstractInsnNode anchor = method.instructions.get(instructionIndex);
        JsonObject previous = instruction(method, instructionIndex, anchor);
        AbstractInsnNode edited = workspace.mutate(
                operation + " instruction in " + classNode.name + "." + method.name + method.desc,
                Set.of(classNode.name), () -> {
                    synchronized (mutationLock) {
                        return switch (operation) {
                            case "replace" -> {
                                requireRealInstruction(anchor, "replace");
                                AbstractInsnNode replacement = createInstruction(method,
                                        requiredObject(arguments, "instruction"));
                                copyTypeAnnotations(anchor, replacement);
                                method.instructions.set(anchor, replacement);
                                yield replacement;
                            }
                            case "insert_before" -> {
                                AbstractInsnNode inserted = createInstruction(method,
                                        requiredObject(arguments, "instruction"));
                                method.instructions.insertBefore(anchor, inserted);
                                yield inserted;
                            }
                            case "insert_after" -> {
                                AbstractInsnNode inserted = createInstruction(method,
                                        requiredObject(arguments, "instruction"));
                                method.instructions.insert(anchor, inserted);
                                yield inserted;
                            }
                            case "remove" -> {
                                requireRealInstruction(anchor, "remove");
                                method.instructions.remove(anchor);
                                yield null;
                            }
                            default -> throw new IllegalArgumentException("Unsupported operation: " + operation);
                        };
                    }
                });
        context.methodModified(classNode, method);

        JsonObject result = new JsonObject();
        result.addProperty("class", classNode.name);
        result.addProperty("method", method.name + method.desc);
        result.addProperty("operation", operation);
        result.add("previous", previous);
        if (edited != null) {
            int newIndex = method.instructions.indexOf(edited);
            result.add("instruction", instruction(method, newIndex, edited));
        }
        result.addProperty("instructionCount", method.instructions.size());
        result.addProperty("modified", true);
        return result;
    }

    private JsonObject listConstants(JsonObject arguments) {
        ClassNode classNode = findClass(requiredString(arguments, "class"));
        MethodNode method = findMethod(classNode, requiredString(arguments, "method"),
                requiredString(arguments, "descriptor"));
        int offset = optionalInt(arguments, "offset", 0, 0, Integer.MAX_VALUE);
        int limit = optionalInt(arguments, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT);

        List<JsonObject> constants = new ArrayList<>();
        for (int index = 0; index < method.instructions.size(); index++) {
            AbstractInsnNode instruction = method.instructions.get(index);
            if (instruction instanceof LdcInsnNode ldc) {
                constants.add(constant(index, ldc.cst));
            }
        }

        int from = Math.min(offset, constants.size());
        int to = Math.min(from + limit, constants.size());
        JsonArray page = new JsonArray();
        constants.subList(from, to).forEach(page::add);
        JsonObject result = new JsonObject();
        result.addProperty("total", constants.size());
        result.addProperty("offset", from);
        result.addProperty("hasMore", to < constants.size());
        result.add("constants", page);
        return result;
    }

    private JsonObject replaceConstant(JsonObject arguments) throws Exception {
        ClassNode classNode = findClass(requiredString(arguments, "class"));
        MethodNode method = findMethod(classNode, requiredString(arguments, "method"),
                requiredString(arguments, "descriptor"));
        int instructionIndex = requiredInt(arguments, "instructionIndex", 0, Integer.MAX_VALUE);
        if (instructionIndex >= method.instructions.size()) {
            throw new IllegalArgumentException("instructionIndex is outside the method");
        }
        AbstractInsnNode instruction = method.instructions.get(instructionIndex);
        if (!(instruction instanceof LdcInsnNode ldc)) {
            throw new IllegalArgumentException("Instruction " + instructionIndex + " is not an LDC constant");
        }

        String valueType = requiredString(arguments, "valueType").toLowerCase(Locale.ROOT);
        String value = requiredStringAllowEmpty(arguments, "value");
        Object replacement = parseConstant(valueType, value);
        JsonObject previous = constant(instructionIndex, ldc.cst);
        workspace.mutate("Replace constant in " + classNode.name + "." + method.name + method.desc,
                Set.of(classNode.name), () -> {
                    synchronized (mutationLock) {
                        ldc.cst = replacement;
                    }
                    return null;
                });
        context.methodModified(classNode, method);

        JsonObject result = new JsonObject();
        result.addProperty("class", classNode.name);
        result.addProperty("method", method.name + method.desc);
        result.add("previous", previous);
        result.add("replacement", constant(instructionIndex, replacement));
        result.addProperty("modified", true);
        return result;
    }

    private JsonObject controlFlowGraph(JsonObject arguments) {
        ClassNode classNode = findClass(requiredString(arguments, "class"));
        MethodNode method = findMethod(classNode, requiredString(arguments, "method"),
                requiredString(arguments, "descriptor"));
        Map<Integer, Set<Integer>> normalEdges = new LinkedHashMap<>();
        Map<Integer, Set<Integer>> exceptionEdges = new LinkedHashMap<>();
        Frame<BasicValue>[] frames = analyze(classNode, method, normalEdges, exceptionEdges);

        JsonArray nodes = new JsonArray();
        for (int index = 0; index < method.instructions.size(); index++) {
            JsonObject node = instruction(method, index, method.instructions.get(index));
            node.addProperty("reachable", frames[index] != null);
            node.add("successors", GSON.toJsonTree(normalEdges.getOrDefault(index, Set.of())));
            node.add("exceptionSuccessors", GSON.toJsonTree(exceptionEdges.getOrDefault(index, Set.of())));
            nodes.add(node);
        }
        JsonObject result = new JsonObject();
        result.addProperty("class", classNode.name);
        result.addProperty("method", method.name);
        result.addProperty("descriptor", method.desc);
        result.addProperty("instructionCount", method.instructions.size());
        result.addProperty("normalEdgeCount", edgeCount(normalEdges));
        result.addProperty("exceptionEdgeCount", edgeCount(exceptionEdges));
        result.add("nodes", nodes);
        return result;
    }

    private JsonObject findDeadCode(JsonObject arguments) {
        ClassNode classNode = findClass(requiredString(arguments, "class"));
        MethodNode method = findMethod(classNode, requiredString(arguments, "method"),
                requiredString(arguments, "descriptor"));
        Frame<BasicValue>[] frames = analyze(classNode, method, null, null);
        JsonArray unreachable = new JsonArray();
        for (int index = 0; index < frames.length; index++) {
            AbstractInsnNode insn = method.instructions.get(index);
            if (frames[index] == null && insn.getOpcode() >= 0) {
                unreachable.add(instruction(method, index, insn));
            }
        }
        JsonObject result = new JsonObject();
        result.addProperty("class", classNode.name);
        result.addProperty("method", method.name);
        result.addProperty("descriptor", method.desc);
        result.addProperty("unreachableCount", unreachable.size());
        result.add("instructions", unreachable);
        return result;
    }

    private JsonObject analyzeStackFrames(JsonObject arguments) {
        ClassNode classNode = findClass(requiredString(arguments, "class"));
        MethodNode method = findMethod(classNode, requiredString(arguments, "method"),
                requiredString(arguments, "descriptor"));
        Frame<BasicValue>[] frames = analyze(classNode, method, null, null);
        int offset = optionalInt(arguments, "offset", 0, 0, Integer.MAX_VALUE);
        int limit = optionalInt(arguments, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT);
        int from = Math.min(offset, frames.length);
        int to = Math.min(from + limit, frames.length);
        JsonArray items = new JsonArray();
        for (int index = from; index < to; index++) {
            JsonObject item = instruction(method, index, method.instructions.get(index));
            Frame<BasicValue> frame = frames[index];
            item.addProperty("reachable", frame != null);
            if (frame != null) {
                JsonArray locals = new JsonArray();
                for (int local = 0; local < frame.getLocals(); local++) {
                    locals.add(frameValue(frame.getLocal(local)));
                }
                JsonArray stack = new JsonArray();
                for (int slot = 0; slot < frame.getStackSize(); slot++) {
                    stack.add(frameValue(frame.getStack(slot)));
                }
                item.add("locals", locals);
                item.add("stack", stack);
            }
            items.add(item);
        }
        JsonObject result = new JsonObject();
        result.addProperty("total", frames.length);
        result.addProperty("offset", from);
        result.addProperty("hasMore", to < frames.length);
        result.add("frames", items);
        return result;
    }

    private JsonObject findOverrides(JsonObject arguments) {
        ClassNode owner = findClass(requiredString(arguments, "class"));
        MethodNode target = findMethod(owner, requiredString(arguments, "method"),
                requiredString(arguments, "descriptor"));
        Map<String, ClassNode> available = classes();
        JsonArray overriddenDeclarations = new JsonArray();
        for (String ancestorName : ancestorNames(owner, available)) {
            ClassNode ancestor = available.get(ancestorName);
            if (ancestor == null) {
                continue;
            }
            ancestor.methods.stream()
                    .filter(method -> method.name.equals(target.name) && method.desc.equals(target.desc))
                    .findFirst()
                    .ifPresent(method -> overriddenDeclarations.add(memberResult(ancestor, method)));
        }

        JsonArray overridingMethods = new JsonArray();
        for (ClassNode candidate : sortedClasses()) {
            if (candidate == owner || !isSubtype(candidate, owner.name, available)) {
                continue;
            }
            candidate.methods.stream()
                    .filter(method -> method.name.equals(target.name) && method.desc.equals(target.desc))
                    .findFirst()
                    .ifPresent(method -> overridingMethods.add(memberResult(candidate, method)));
        }
        JsonObject result = new JsonObject();
        result.addProperty("class", owner.name);
        result.addProperty("method", target.name);
        result.addProperty("descriptor", target.desc);
        result.add("overrides", overriddenDeclarations);
        result.add("overriddenBy", overridingMethods);
        return result;
    }

    private JsonObject findImplementations(JsonObject arguments) {
        ClassNode root = findClass(requiredString(arguments, "class"));
        String methodName = nullableOptionalString(arguments, "method");
        String descriptor = nullableOptionalString(arguments, "descriptor");
        if ((methodName == null) != (descriptor == null)) {
            throw new IllegalArgumentException("method and descriptor must be supplied together");
        }
        if (descriptor != null) {
            validMethodDescriptor(descriptor);
        }
        Map<String, ClassNode> available = classes();
        Page page = page(arguments);
        for (ClassNode candidate : sortedClasses()) {
            if ((candidate.access & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT)) != 0
                    || (!candidate.name.equals(root.name) && !isSubtype(candidate, root.name, available))) {
                continue;
            }
            JsonObject item = new JsonObject();
            item.addProperty("class", candidate.name);
            item.addProperty("access", candidate.access);
            if (methodName != null) {
                MethodOwner resolved = resolveMethod(candidate, methodName, descriptor, available, new HashSet<>());
                item.addProperty("implementsMethod", resolved != null
                        && (resolved.method().access & Opcodes.ACC_ABSTRACT) == 0);
                if (resolved != null) {
                    item.addProperty("methodOwner", resolved.owner().name);
                    item.addProperty("methodAccess", resolved.method().access);
                }
            }
            page.add(item);
        }
        return page.result("implementations");
    }

    private JsonObject findEntryPoints(JsonObject arguments) {
        Page page = page(arguments);
        for (ClassNode classNode : sortedClasses()) {
            for (MethodNode method : classNode.methods) {
                String kind = entryPointKind(method);
                if (kind != null) {
                    JsonObject item = memberResult(classNode, method);
                    item.addProperty("kind", kind);
                    page.add(item);
                }
            }
        }
        return page.result("entryPoints");
    }

    private JsonObject searchInstructionPattern(JsonObject arguments) {
        String[] pattern = Arrays.stream(requiredString(arguments, "pattern").toUpperCase(Locale.ROOT)
                        .split("[\\s,]+"))
                .filter(value -> !value.isBlank())
                .toArray(String[]::new);
        if (pattern.length == 0) {
            throw new IllegalArgumentException("pattern must contain at least one opcode name");
        }
        for (String opcode : pattern) {
            if (!isOpcodeName(opcode)) {
                throw new IllegalArgumentException("Unknown JVM opcode name: " + opcode);
            }
        }
        String classFilter = nullableOptionalString(arguments, "class");
        if (classFilter != null) {
            classFilter = classFilter.replace('.', '/').toLowerCase(Locale.ROOT);
        }
        Page page = page(arguments);
        for (ClassNode classNode : sortedClasses()) {
            if (classFilter != null && !classNode.name.toLowerCase(Locale.ROOT).contains(classFilter)) {
                continue;
            }
            for (MethodNode method : classNode.methods) {
                List<Integer> realIndices = new ArrayList<>();
                List<String> opcodes = new ArrayList<>();
                for (int index = 0; index < method.instructions.size(); index++) {
                    int opcode = method.instructions.get(index).getOpcode();
                    if (opcode >= 0) {
                        realIndices.add(index);
                        opcodes.add(opcodeName(opcode));
                    }
                }
                for (int start = 0; start <= opcodes.size() - pattern.length; start++) {
                    boolean matches = true;
                    for (int part = 0; part < pattern.length; part++) {
                        if (!pattern[part].equals(opcodes.get(start + part))) {
                            matches = false;
                            break;
                        }
                    }
                    if (matches) {
                        JsonObject item = memberResult(classNode, method);
                        item.addProperty("startInstructionIndex", realIndices.get(start));
                        item.addProperty("endInstructionIndex", realIndices.get(start + pattern.length - 1));
                        page.add(item);
                    }
                }
            }
        }
        return page.result("matches");
    }

    private JsonObject detectReflectionUsage(JsonObject arguments) {
        String classFilter = nullableOptionalString(arguments, "class");
        if (classFilter != null) {
            classFilter = classFilter.replace('.', '/').toLowerCase(Locale.ROOT);
        }
        Page page = page(arguments);
        for (ClassNode classNode : sortedClasses()) {
            if (classFilter != null && !classNode.name.toLowerCase(Locale.ROOT).contains(classFilter)) {
                continue;
            }
            for (MethodNode method : classNode.methods) {
                for (int index = 0; index < method.instructions.size(); index++) {
                    AbstractInsnNode insn = method.instructions.get(index);
                    JsonObject item = null;
                    if (insn instanceof MethodInsnNode call && isDynamicApi(call.owner, call.name)) {
                        item = referenceLocation(classNode, method, index, insn);
                        item.addProperty("kind", reflectionKind(call.owner));
                        item.addProperty("target", call.owner + "." + call.name + call.desc);
                    } else if (insn instanceof InvokeDynamicInsnNode dynamic) {
                        item = referenceLocation(classNode, method, index, insn);
                        item.addProperty("kind", "invokedynamic");
                        item.addProperty("target", dynamic.name + dynamic.desc);
                        item.addProperty("bootstrapMethod", dynamic.bsm.toString());
                    }
                    if (item != null) {
                        page.add(item);
                    }
                }
            }
        }
        return page.result("usages");
    }

    private JsonObject detectNativeMethods(JsonObject arguments) {
        String classFilter = nullableOptionalString(arguments, "class");
        if (classFilter != null) {
            classFilter = classFilter.replace('.', '/').toLowerCase(Locale.ROOT);
        }
        Page page = page(arguments);
        for (ClassNode classNode : sortedClasses()) {
            if (classFilter != null && !classNode.name.toLowerCase(Locale.ROOT).contains(classFilter)) {
                continue;
            }
            for (MethodNode method : classNode.methods) {
                if ((method.access & Opcodes.ACC_NATIVE) != 0) {
                    page.add(memberResult(classNode, method));
                }
            }
        }
        return page.result("nativeMethods");
    }

    private JsonObject compareClasses(JsonObject arguments) {
        ClassNode first = findClass(requiredString(arguments, "firstClass"));
        ClassNode second = findClass(requiredString(arguments, "secondClass"));
        Set<String> firstFields = new TreeSet<>();
        Set<String> secondFields = new TreeSet<>();
        first.fields.forEach(field -> firstFields.add(field.name + " " + field.desc + " access=" + field.access));
        second.fields.forEach(field -> secondFields.add(field.name + " " + field.desc + " access=" + field.access));
        Set<String> firstMethods = new TreeSet<>();
        Set<String> secondMethods = new TreeSet<>();
        first.methods.forEach(method -> firstMethods.add(method.name + method.desc + " access=" + method.access));
        second.methods.forEach(method -> secondMethods.add(method.name + method.desc + " access=" + method.access));
        byte[] firstBytes = context.getClassBytes(first);
        byte[] secondBytes = context.getClassBytes(second);

        JsonObject metadata = new JsonObject();
        addComparison(metadata, "version", first.version, second.version);
        addComparison(metadata, "access", first.access, second.access);
        addComparison(metadata, "superName", first.superName, second.superName);
        addComparison(metadata, "signature", first.signature, second.signature);
        addComparison(metadata, "interfaces", first.interfaces, second.interfaces);

        JsonObject result = new JsonObject();
        result.addProperty("firstClass", first.name);
        result.addProperty("secondClass", second.name);
        result.addProperty("bytecodeEqual", Arrays.equals(firstBytes, secondBytes));
        result.addProperty("firstByteLength", firstBytes.length);
        result.addProperty("secondByteLength", secondBytes.length);
        result.add("metadata", metadata);
        result.add("fieldsOnlyInFirst", GSON.toJsonTree(difference(firstFields, secondFields)));
        result.add("fieldsOnlyInSecond", GSON.toJsonTree(difference(secondFields, firstFields)));
        result.add("methodsOnlyInFirst", GSON.toJsonTree(difference(firstMethods, secondMethods)));
        result.add("methodsOnlyInSecond", GSON.toJsonTree(difference(secondMethods, firstMethods)));
        return result;
    }

    private JsonElement methodBytecode(JsonObject arguments) {
        ClassNode classNode = findClass(requiredString(arguments, "class"));
        MethodNode method = findMethod(classNode, requiredString(arguments, "method"),
                requiredString(arguments, "descriptor"));
        Textifier textifier = new Textifier();
        method.accept(new TraceMethodVisitor(textifier));
        StringWriter output = new StringWriter();
        textifier.print(new PrintWriter(output));
        return new com.google.gson.JsonPrimitive(limit(output.toString()));
    }

    private JsonElement decompileClass(JsonObject arguments) {
        ClassNode classNode = findClass(requiredString(arguments, "class"));
        String decompiler = optionalString(arguments, "decompiler", "cfr").toLowerCase(Locale.ROOT);
        requireDecompiler(decompiler);
        return new com.google.gson.JsonPrimitive(limit(context.decompile(classNode, null, decompiler)));
    }

    private JsonElement decompileMethod(JsonObject arguments) {
        ClassNode classNode = findClass(requiredString(arguments, "class"));
        MethodNode method = findMethod(classNode, requiredString(arguments, "method"),
                requiredString(arguments, "descriptor"));
        String decompiler = optionalString(arguments, "decompiler", "cfr").toLowerCase(Locale.ROOT);
        requireDecompiler(decompiler);
        return new com.google.gson.JsonPrimitive(limit(context.decompile(classNode, method, decompiler)));
    }

    private JsonObject selectClass(JsonObject arguments) throws Exception {
        ClassNode classNode = findClass(requiredString(arguments, "class"));
        context.selectClass(classNode);
        JsonObject result = new JsonObject();
        result.addProperty("selected", classNode.name);
        return result;
    }

    private JsonObject selectMethod(JsonObject arguments) throws Exception {
        ClassNode classNode = findClass(requiredString(arguments, "class"));
        MethodNode method = findMethod(classNode, requiredString(arguments, "method"),
                requiredString(arguments, "descriptor"));
        context.selectMethod(classNode, method);
        JsonObject result = new JsonObject();
        result.addProperty("selected", classNode.name + "#" + method.name + method.desc);
        return result;
    }

    private static JsonObject instruction(MethodNode method, int index, AbstractInsnNode instruction) {
        JsonObject result = new JsonObject();
        result.addProperty("instructionIndex", index);
        int opcode = instruction.getOpcode();
        if (opcode >= 0) {
            result.addProperty("opcode", opcode);
            result.addProperty("opcodeName", opcode < Printer.OPCODES.length
                    ? Printer.OPCODES[opcode] : "UNKNOWN");
        }

        if (instruction instanceof InsnNode) {
            result.addProperty("kind", "insn");
        } else if (instruction instanceof IntInsnNode intInsn) {
            result.addProperty("kind", "int");
            result.addProperty("operand", intInsn.operand);
        } else if (instruction instanceof VarInsnNode varInsn) {
            result.addProperty("kind", "var");
            result.addProperty("var", varInsn.var);
        } else if (instruction instanceof TypeInsnNode typeInsn) {
            result.addProperty("kind", "type");
            result.addProperty("descriptor", typeInsn.desc);
        } else if (instruction instanceof FieldInsnNode fieldInsn) {
            result.addProperty("kind", "field");
            result.addProperty("owner", fieldInsn.owner);
            result.addProperty("name", fieldInsn.name);
            result.addProperty("descriptor", fieldInsn.desc);
        } else if (instruction instanceof MethodInsnNode methodInsn) {
            result.addProperty("kind", "method");
            result.addProperty("owner", methodInsn.owner);
            result.addProperty("name", methodInsn.name);
            result.addProperty("descriptor", methodInsn.desc);
            result.addProperty("isInterface", methodInsn.itf);
        } else if (instruction instanceof JumpInsnNode jumpInsn) {
            result.addProperty("kind", "jump");
            result.addProperty("targetInstructionIndex", method.instructions.indexOf(jumpInsn.label));
        } else if (instruction instanceof LdcInsnNode ldcInsn) {
            result.addProperty("kind", "ldc");
            JsonObject value = constant(index, ldcInsn.cst);
            result.addProperty("value", ldcInsn.cst instanceof Type type
                    ? type.getDescriptor() : String.valueOf(ldcInsn.cst));
            result.add("valueType", value.get("valueType"));
            result.addProperty("editable", value.get("editable").getAsBoolean());
            return result;
        } else if (instruction instanceof IincInsnNode iincInsn) {
            result.addProperty("kind", "iinc");
            result.addProperty("var", iincInsn.var);
            result.addProperty("increment", iincInsn.incr);
        } else if (instruction instanceof MultiANewArrayInsnNode multiArrayInsn) {
            result.addProperty("kind", "multianewarray");
            result.addProperty("descriptor", multiArrayInsn.desc);
            result.addProperty("dimensions", multiArrayInsn.dims);
        } else if (instruction instanceof LabelNode) {
            result.addProperty("kind", "label");
        } else if (instruction instanceof LineNumberNode lineNumber) {
            result.addProperty("kind", "line");
            result.addProperty("line", lineNumber.line);
            result.addProperty("startInstructionIndex", method.instructions.indexOf(lineNumber.start));
        } else if (instruction instanceof InvokeDynamicInsnNode invokeDynamic) {
            result.addProperty("kind", "invokedynamic");
            result.addProperty("name", invokeDynamic.name);
            result.addProperty("descriptor", invokeDynamic.desc);
            result.addProperty("bootstrapMethod", String.valueOf(invokeDynamic.bsm));
        } else if (instruction instanceof TableSwitchInsnNode tableSwitch) {
            result.addProperty("kind", "tableswitch");
            result.addProperty("minimum", tableSwitch.min);
            result.addProperty("maximum", tableSwitch.max);
            result.addProperty("defaultTargetInstructionIndex", method.instructions.indexOf(tableSwitch.dflt));
            result.add("targetInstructionIndices", labelIndices(method, tableSwitch.labels));
        } else if (instruction instanceof LookupSwitchInsnNode lookupSwitch) {
            result.addProperty("kind", "lookupswitch");
            result.add("keys", GSON.toJsonTree(lookupSwitch.keys));
            result.addProperty("defaultTargetInstructionIndex", method.instructions.indexOf(lookupSwitch.dflt));
            result.add("targetInstructionIndices", labelIndices(method, lookupSwitch.labels));
        } else {
            result.addProperty("kind", "frame");
        }
        result.addProperty("editable", opcode >= 0
                && !(instruction instanceof InvokeDynamicInsnNode)
                && !(instruction instanceof TableSwitchInsnNode)
                && !(instruction instanceof LookupSwitchInsnNode));
        return result;
    }

    private static JsonArray labelIndices(MethodNode method, List<LabelNode> labels) {
        JsonArray result = new JsonArray();
        labels.forEach(label -> result.add(method.instructions.indexOf(label)));
        return result;
    }

    private static AbstractInsnNode createInstruction(MethodNode method, JsonObject specification) {
        String kind = requiredString(specification, "kind").toLowerCase(Locale.ROOT);
        int opcode = switch (kind) {
            case "ldc" -> org.objectweb.asm.Opcodes.LDC;
            case "iinc" -> org.objectweb.asm.Opcodes.IINC;
            case "multianewarray" -> org.objectweb.asm.Opcodes.MULTIANEWARRAY;
            default -> requiredInt(specification, "opcode", 0, Printer.OPCODES.length - 1);
        };
        return switch (kind) {
            case "insn" -> new InsnNode(opcode);
            case "int" -> new IntInsnNode(opcode,
                    requiredInt(specification, "operand", Integer.MIN_VALUE, Integer.MAX_VALUE));
            case "var" -> new VarInsnNode(opcode,
                    requiredInt(specification, "var", 0, Integer.MAX_VALUE));
            case "type" -> new TypeInsnNode(opcode, requiredString(specification, "descriptor"));
            case "field" -> new FieldInsnNode(opcode, requiredString(specification, "owner"),
                    requiredString(specification, "name"), requiredString(specification, "descriptor"));
            case "method" -> new MethodInsnNode(opcode, requiredString(specification, "owner"),
                    requiredString(specification, "name"), requiredString(specification, "descriptor"),
                    optionalBoolean(specification, "isInterface", opcode == org.objectweb.asm.Opcodes.INVOKEINTERFACE));
            case "jump" -> new JumpInsnNode(opcode, findLabel(method,
                    requiredInt(specification, "targetInstructionIndex", 0, Integer.MAX_VALUE)));
            case "ldc" -> new LdcInsnNode(parseConstant(
                    requiredString(specification, "valueType").toLowerCase(Locale.ROOT),
                    requiredStringAllowEmpty(specification, "value")));
            case "iinc" -> new IincInsnNode(requiredInt(specification, "var", 0, Integer.MAX_VALUE),
                    requiredInt(specification, "increment", Integer.MIN_VALUE, Integer.MAX_VALUE));
            case "multianewarray" -> new MultiANewArrayInsnNode(requiredString(specification, "descriptor"),
                    requiredInt(specification, "dimensions", 1, 255));
            default -> throw new IllegalArgumentException("Unsupported instruction kind: " + kind);
        };
    }

    private static LabelNode findLabel(MethodNode method, int instructionIndex) {
        if (instructionIndex >= method.instructions.size()
                || !(method.instructions.get(instructionIndex) instanceof LabelNode label)) {
            throw new IllegalArgumentException("targetInstructionIndex must point to an existing label");
        }
        return label;
    }

    private static void requireRealInstruction(AbstractInsnNode instruction, String operation) {
        if (instruction.getOpcode() < 0) {
            throw new IllegalArgumentException("Cannot " + operation + " a label, frame, or line marker");
        }
    }

    private static void copyTypeAnnotations(AbstractInsnNode source, AbstractInsnNode target) {
        target.visibleTypeAnnotations = source.visibleTypeAnnotations;
        target.invisibleTypeAnnotations = source.invisibleTypeAnnotations;
    }

    private static JsonObject constant(int instructionIndex, Object value) {
        JsonObject result = new JsonObject();
        result.addProperty("instructionIndex", instructionIndex);
        if (value instanceof String string) {
            result.addProperty("valueType", "string");
            result.addProperty("value", string);
            result.addProperty("editable", true);
        } else if (value instanceof Integer integer) {
            result.addProperty("valueType", "int");
            result.addProperty("value", integer);
            result.addProperty("editable", true);
        } else if (value instanceof Long longValue) {
            result.addProperty("valueType", "long");
            result.addProperty("value", Long.toString(longValue));
            result.addProperty("editable", true);
        } else if (value instanceof Float floatValue) {
            result.addProperty("valueType", "float");
            result.addProperty("value", Float.toString(floatValue));
            result.addProperty("editable", true);
        } else if (value instanceof Double doubleValue) {
            result.addProperty("valueType", "double");
            result.addProperty("value", Double.toString(doubleValue));
            result.addProperty("editable", true);
        } else if (value instanceof Type type) {
            result.addProperty("valueType", "type");
            result.addProperty("value", type.getDescriptor());
            result.addProperty("editable", true);
        } else {
            result.addProperty("valueType", value == null ? "null" : value.getClass().getSimpleName());
            result.addProperty("value", String.valueOf(value));
            result.addProperty("editable", false);
        }
        return result;
    }

    private static Object parseConstant(String valueType, String value) {
        try {
            return switch (valueType) {
                case "string" -> value;
                case "int" -> Integer.parseInt(value);
                case "long" -> Long.parseLong(value);
                case "float" -> Float.parseFloat(value);
                case "double" -> Double.parseDouble(value);
                case "type" -> Type.getType(value);
                default -> throw new IllegalArgumentException("Unsupported valueType: " + valueType);
            };
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid " + valueType + " value: " + value);
        }
    }

    private static byte[] decodeBase64(JsonObject arguments, String name, int maximumBytes) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(requiredStringAllowEmpty(arguments, name));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(name + " is not valid base64");
        }
        if (bytes.length > maximumBytes) {
            throw new IllegalArgumentException(name + " exceeds " + maximumBytes + " decoded bytes");
        }
        return bytes;
    }

    private static JsonObject resourceResult(String path, byte[] bytes) {
        JsonObject result = new JsonObject();
        result.addProperty("path", path);
        result.addProperty("byteLength", bytes.length);
        return result;
    }

    private static String textPreview(byte[] bytes) {
        int length = Math.min(bytes.length, 16 * 1024);
        String text = new String(bytes, 0, length, StandardCharsets.UTF_8);
        if (text.indexOf('\0') >= 0 || text.indexOf('\uFFFD') >= 0) {
            return null;
        }
        int suspicious = 0;
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            if (Character.isISOControl(value) && value != '\n' && value != '\r' && value != '\t') {
                suspicious++;
            }
        }
        return suspicious > Math.max(2, text.length() / 100) ? null : text;
    }

    private static void createParentDirectories(Path output) throws Exception {
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private static String normalizePackageName(String packageName) {
        String normalized = packageName.trim().replace('.', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty() || normalized.contains("//") || normalized.contains(";")
                || normalized.contains("[")) {
            throw new IllegalArgumentException("Invalid package name: " + packageName);
        }
        return normalized;
    }

    private static boolean belongsToPackage(String className, String packageName, boolean includeSubpackages) {
        int separator = className.lastIndexOf('/');
        String actualPackage = separator < 0 ? "" : className.substring(0, separator);
        return includeSubpackages
                ? actualPackage.equals(packageName) || actualPackage.startsWith(packageName + "/")
                : actualPackage.equals(packageName);
    }

    private static JsonObject pagedProperties() {
        JsonObject properties = new JsonObject();
        properties.add("offset", integerProperty("Zero-based result offset.", 0, null));
        properties.add("limit", integerProperty("Maximum number of results to return.", 1, MAX_LIMIT));
        return properties;
    }

    private Page page(JsonObject arguments) {
        return new Page(
                optionalInt(arguments, "offset", 0, 0, Integer.MAX_VALUE),
                optionalInt(arguments, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT));
    }

    private List<ClassNode> sortedClasses() {
        List<ClassNode> result = new ArrayList<>(classes().values());
        result.sort((left, right) -> left.name.compareTo(right.name));
        return result;
    }

    private static boolean matches(String query, String... values) {
        for (String value : values) {
            if (value != null && value.toLowerCase(Locale.ROOT).contains(query)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> directSubtypes(String name, Map<String, ClassNode> available) {
        List<String> result = new ArrayList<>();
        for (ClassNode candidate : available.values()) {
            if (name.equals(candidate.superName) || candidate.interfaces.contains(name)) {
                result.add(candidate.name);
            }
        }
        result.sort(String::compareTo);
        return result;
    }

    private static JsonObject referenceLocation(ClassNode owner, MethodNode method, int index,
                                                AbstractInsnNode instruction) {
        JsonObject result = new JsonObject();
        result.addProperty("sourceClass", owner.name);
        result.addProperty("sourceMethod", method.name);
        result.addProperty("sourceDescriptor", method.desc);
        result.addProperty("instructionIndex", index);
        result.addProperty("opcode", opcodeName(instruction.getOpcode()));
        return result;
    }

    private static JsonObject methodCall(String direction, ClassNode sourceClass, MethodNode sourceMethod,
                                         int index, String targetOwner, String targetName,
                                         String targetDescriptor, String opcode) {
        JsonObject result = new JsonObject();
        result.addProperty("direction", direction);
        result.addProperty("sourceClass", sourceClass.name);
        result.addProperty("sourceMethod", sourceMethod.name);
        result.addProperty("sourceDescriptor", sourceMethod.desc);
        result.addProperty("instructionIndex", index);
        addNullable(result, "targetOwner", targetOwner);
        result.addProperty("targetName", targetName);
        result.addProperty("targetDescriptor", targetDescriptor);
        result.addProperty("opcode", opcode);
        return result;
    }

    private static String opcodeName(int opcode) {
        return opcode >= 0 && opcode < Printer.OPCODES.length && Printer.OPCODES[opcode] != null
                ? Printer.OPCODES[opcode] : Integer.toString(opcode);
    }

    private static boolean referencesClass(AbstractInsnNode instruction, String className) {
        if (instruction instanceof TypeInsnNode type) {
            return className.equals(type.desc) || descriptorReferences(type.desc, className);
        }
        if (instruction instanceof FieldInsnNode field) {
            return className.equals(field.owner) || descriptorReferences(field.desc, className);
        }
        if (instruction instanceof MethodInsnNode method) {
            return className.equals(method.owner) || descriptorReferences(method.desc, className);
        }
        if (instruction instanceof InvokeDynamicInsnNode dynamic) {
            if (descriptorReferences(dynamic.desc, className)
                    || constantReferences(dynamic.bsm, className)) {
                return true;
            }
            for (Object argument : dynamic.bsmArgs) {
                if (constantReferences(argument, className)) {
                    return true;
                }
            }
            return false;
        }
        if (instruction instanceof MultiANewArrayInsnNode array) {
            return descriptorReferences(array.desc, className);
        }
        return instruction instanceof LdcInsnNode ldc && constantReferences(ldc.cst, className);
    }

    private static boolean constantReferences(Object value, String className) {
        if (value instanceof Type type) {
            return typeReferences(type, className);
        }
        if (value instanceof Handle handle) {
            return className.equals(handle.getOwner()) || descriptorReferences(handle.getDesc(), className);
        }
        if (value instanceof ConstantDynamic dynamic) {
            if (descriptorReferences(dynamic.getDescriptor(), className)
                    || constantReferences(dynamic.getBootstrapMethod(), className)) {
                return true;
            }
            for (int i = 0; i < dynamic.getBootstrapMethodArgumentCount(); i++) {
                if (constantReferences(dynamic.getBootstrapMethodArgument(i), className)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean descriptorReferences(String descriptor, String className) {
        try {
            Type type = descriptor.startsWith("(") ? Type.getMethodType(descriptor) : Type.getType(descriptor);
            return typeReferences(type, className);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static boolean typeReferences(Type type, String className) {
        return switch (type.getSort()) {
            case Type.OBJECT -> className.equals(type.getInternalName());
            case Type.ARRAY -> typeReferences(type.getElementType(), className);
            case Type.METHOD -> {
                if (typeReferences(type.getReturnType(), className)) {
                    yield true;
                }
                boolean found = false;
                for (Type argument : type.getArgumentTypes()) {
                    if (typeReferences(argument, className)) {
                        found = true;
                        break;
                    }
                }
                yield found;
            }
            default -> false;
        };
    }

    private static final class Page {
        private final int requestedOffset;
        private final int limit;
        private final JsonArray items = new JsonArray();
        private int total;

        private Page(int requestedOffset, int limit) {
            this.requestedOffset = requestedOffset;
            this.limit = limit;
        }

        private void add(JsonObject item) {
            if (total >= requestedOffset && items.size() < limit) {
                items.add(item);
            }
            total++;
        }

        private JsonObject result(String name) {
            JsonObject result = new JsonObject();
            result.addProperty("total", total);
            result.addProperty("offset", Math.min(requestedOffset, total));
            result.addProperty("hasMore", total > requestedOffset + items.size());
            result.add(name, items);
            return result;
        }
    }

    private ArchiveInfo archive() {
        ArchiveInfo archive = context.getArchiveInfo();
        if (archive.type() == ArchiveType.NONE) {
            throw new IllegalArgumentException("No archive is open in JByteMod");
        }
        return archive;
    }

    private Map<String, ClassNode> classes() {
        archive();
        return context.getCurrentFile();
    }

    private ClassNode findClass(String name) {
        String internalName = name.replace('.', '/');
        ClassNode classNode = classes().get(internalName);
        if (classNode == null) {
            throw new IllegalArgumentException("Class not found: " + internalName);
        }
        return classNode;
    }

    private static MethodNode findMethod(ClassNode classNode, String name, String descriptor) {
        return classNode.methods.stream()
                .filter(method -> method.name.equals(name) && method.desc.equals(descriptor))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Method not found: " + classNode.name + "#" + name + descriptor));
    }

    private static FieldNode findField(ClassNode classNode, String name, String descriptor) {
        return classNode.fields.stream()
                .filter(field -> field.name.equals(name) && field.desc.equals(descriptor))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Field not found: " + classNode.name + "#" + name + " " + descriptor));
    }

    private static Frame<BasicValue>[] analyze(ClassNode owner, MethodNode method,
                                                Map<Integer, Set<Integer>> normalEdges,
                                                Map<Integer, Set<Integer>> exceptionEdges) {
        Analyzer<BasicValue> analyzer;
        if (normalEdges == null && exceptionEdges == null) {
            analyzer = new Analyzer<>(new BasicInterpreter());
        } else {
            analyzer = new Analyzer<>(new BasicInterpreter()) {
                @Override
                protected void newControlFlowEdge(int instructionIndex, int successorIndex) {
                    if (normalEdges != null) {
                        normalEdges.computeIfAbsent(instructionIndex, ignored -> new TreeSet<>())
                                .add(successorIndex);
                    }
                }

                @Override
                protected boolean newControlFlowExceptionEdge(int instructionIndex, int successorIndex) {
                    if (exceptionEdges != null) {
                        exceptionEdges.computeIfAbsent(instructionIndex, ignored -> new TreeSet<>())
                                .add(successorIndex);
                    }
                    return true;
                }
            };
        }
        try {
            return analyzer.analyze(owner.name, method);
        } catch (AnalyzerException exception) {
            throw new IllegalArgumentException("ASM analysis failed for " + owner.name + "."
                    + method.name + method.desc + ": " + exception.getMessage());
        }
    }

    private static int edgeCount(Map<Integer, Set<Integer>> edges) {
        return edges.values().stream().mapToInt(Set::size).sum();
    }

    private static String frameValue(BasicValue value) {
        if (value == null) {
            return "null";
        }
        if (value == BasicValue.UNINITIALIZED_VALUE) {
            return "uninitialized";
        }
        if (value == BasicValue.RETURNADDRESS_VALUE) {
            return "returnAddress";
        }
        Type type = value.getType();
        return type == null ? value.toString() : type.getDescriptor();
    }

    private static Set<String> ancestorNames(ClassNode root, Map<String, ClassNode> available) {
        Set<String> result = new LinkedHashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        if (root.superName != null) {
            pending.add(root.superName);
        }
        pending.addAll(root.interfaces);
        while (!pending.isEmpty()) {
            String name = pending.removeFirst();
            if (!result.add(name)) {
                continue;
            }
            ClassNode node = available.get(name);
            if (node != null) {
                if (node.superName != null) {
                    pending.addLast(node.superName);
                }
                pending.addAll(node.interfaces);
            }
        }
        return result;
    }

    private static boolean isSubtype(ClassNode candidate, String target, Map<String, ClassNode> available) {
        return candidate.name.equals(target) || ancestorNames(candidate, available).contains(target);
    }

    private static MethodOwner resolveMethod(ClassNode start, String name, String descriptor,
                                             Map<String, ClassNode> available, Set<String> visited) {
        if (start == null || !visited.add(start.name)) {
            return null;
        }
        for (MethodNode method : start.methods) {
            if (method.name.equals(name) && method.desc.equals(descriptor)) {
                return new MethodOwner(start, method);
            }
        }
        MethodOwner inherited = resolveMethod(available.get(start.superName), name, descriptor, available, visited);
        if (inherited != null) {
            return inherited;
        }
        for (String interfaceName : start.interfaces) {
            inherited = resolveMethod(available.get(interfaceName), name, descriptor, available, visited);
            if (inherited != null) {
                return inherited;
            }
        }
        return null;
    }

    private static JsonObject memberResult(ClassNode owner, MethodNode method) {
        JsonObject item = new JsonObject();
        item.addProperty("class", owner.name);
        item.addProperty("method", method.name);
        item.addProperty("descriptor", method.desc);
        item.addProperty("access", method.access);
        return item;
    }

    private static String entryPointKind(MethodNode method) {
        boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
        boolean isPublic = (method.access & Opcodes.ACC_PUBLIC) != 0;
        if (isPublic && isStatic && "main".equals(method.name)
                && "([Ljava/lang/String;)V".equals(method.desc)) {
            return "main";
        }
        if (isStatic && ("premain".equals(method.name) || "agentmain".equals(method.name))
                && ("(Ljava/lang/String;)V".equals(method.desc)
                || "(Ljava/lang/String;Ljava/lang/instrument/Instrumentation;)V".equals(method.desc))) {
            return method.name;
        }
        if (isPublic && !isStatic && "start".equals(method.name)
                && "(Ljavafx/stage/Stage;)V".equals(method.desc)) {
            return "javafx";
        }
        return null;
    }

    private static boolean isOpcodeName(String name) {
        for (String opcode : Printer.OPCODES) {
            if (name.equals(opcode)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDynamicApi(String owner, String name) {
        if (owner.startsWith("java/lang/reflect/") || owner.startsWith("java/lang/invoke/")) {
            return true;
        }
        if ("sun/misc/Unsafe".equals(owner) || "jdk/internal/misc/Unsafe".equals(owner)) {
            return true;
        }
        return "java/lang/Class".equals(owner)
                && ("forName".equals(name) || name.startsWith("getDeclared")
                || name.startsWith("getMethod") || name.startsWith("getField")
                || "newInstance".equals(name));
    }

    private static String reflectionKind(String owner) {
        if (owner.startsWith("java/lang/invoke/")) {
            return "method-handle";
        }
        if (owner.endsWith("Unsafe")) {
            return "unsafe";
        }
        if ("java/lang/reflect/Proxy".equals(owner)) {
            return "dynamic-proxy";
        }
        return "reflection";
    }

    private static void addComparison(JsonObject result, String name, Object first, Object second) {
        JsonObject comparison = new JsonObject();
        comparison.add("first", GSON.toJsonTree(first));
        comparison.add("second", GSON.toJsonTree(second));
        comparison.addProperty("equal", Objects.equals(first, second));
        result.add(name, comparison);
    }

    private static List<String> difference(Set<String> left, Set<String> right) {
        List<String> result = new ArrayList<>(left);
        result.removeAll(right);
        return result;
    }

    private record MethodOwner(ClassNode owner, MethodNode method) {
    }

    private record MethodKey(String owner, String name, String descriptor) {
    }

    private record CallSite(MethodKey source, MethodKey target, int instructionIndex,
                            String opcode, boolean invokeDynamic) {
    }

    private record GraphVisit(MethodKey method, int depth) {
    }

    private record CallGraphIndex(Map<MethodKey, MethodOwner> methods,
                                  Map<MethodKey, List<CallSite>> outgoing,
                                  Map<MethodKey, List<CallSite>> incoming) {
    }

    private static String normalizeClassName(String name) {
        String normalized = name.trim().replace('.', '/');
        if (normalized.isEmpty() || normalized.startsWith("/") || normalized.endsWith("/")
                || normalized.contains(";") || normalized.contains("[")) {
            throw new IllegalArgumentException("Invalid class name: " + name);
        }
        return normalized;
    }

    private static String nullableClassName(String name) {
        return name == null || name.isBlank() ? null : normalizeClassName(name);
    }

    private static String validFieldDescriptor(String descriptor) {
        try {
            Type type = Type.getType(descriptor);
            if (type.getSort() == Type.METHOD || type.getSort() == Type.VOID) {
                throw new IllegalArgumentException();
            }
            return descriptor;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid JVM field descriptor: " + descriptor);
        }
    }

    private static String validMethodDescriptor(String descriptor) {
        try {
            Type.getMethodType(descriptor);
            return descriptor;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid JVM method descriptor: " + descriptor);
        }
    }

    private static MethodNode defaultMethod(int access, String name, String descriptor, String signature) {
        MethodNode method = new MethodNode(access, name, descriptor, signature, null);
        if ((access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
            return method;
        }

        Type returnType = Type.getReturnType(descriptor);
        switch (returnType.getSort()) {
            case Type.VOID -> method.instructions.add(new InsnNode(Opcodes.RETURN));
            case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> {
                method.instructions.add(new InsnNode(Opcodes.ICONST_0));
                method.instructions.add(new InsnNode(Opcodes.IRETURN));
            }
            case Type.FLOAT -> {
                method.instructions.add(new InsnNode(Opcodes.FCONST_0));
                method.instructions.add(new InsnNode(Opcodes.FRETURN));
            }
            case Type.LONG -> {
                method.instructions.add(new InsnNode(Opcodes.LCONST_0));
                method.instructions.add(new InsnNode(Opcodes.LRETURN));
            }
            case Type.DOUBLE -> {
                method.instructions.add(new InsnNode(Opcodes.DCONST_0));
                method.instructions.add(new InsnNode(Opcodes.DRETURN));
            }
            default -> {
                method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
                method.instructions.add(new InsnNode(Opcodes.ARETURN));
            }
        }
        method.maxStack = Math.max(1, returnType.getSize());
        method.maxLocals = (access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        for (Type argument : Type.getArgumentTypes(descriptor)) {
            method.maxLocals += argument.getSize();
        }
        return method;
    }

    private static MethodNode cloneMethod(MethodNode source, String name) {
        MethodNode copy = new MethodNode(source.access, name, source.desc, source.signature,
                source.exceptions == null ? null : source.exceptions.toArray(String[]::new));
        source.accept(copy);
        copy.name = name;
        return copy;
    }

    private static Object parseFieldValue(JsonObject arguments) {
        String valueType = optionalString(arguments, "valueType", "none").toLowerCase(Locale.ROOT);
        if ("none".equals(valueType)) {
            return null;
        }
        String value = requiredStringAllowEmpty(arguments, "value");
        try {
            return switch (valueType) {
                case "string" -> value;
                case "int" -> Integer.valueOf(value);
                case "long" -> Long.valueOf(value);
                case "float" -> Float.valueOf(value);
                case "double" -> Double.valueOf(value);
                default -> throw new IllegalArgumentException("Unsupported valueType: " + valueType);
            };
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid " + valueType + " value: " + value);
        }
    }

    private static JsonObject tool(String name, String description, JsonObject inputSchema,
                                   boolean readOnly, boolean idempotent) {
        return tool(name, description, inputSchema, readOnly, false, idempotent);
    }

    private static JsonObject tool(String name, String description, JsonObject inputSchema,
                                   boolean readOnly, boolean destructive, boolean idempotent) {
        JsonObject tool = new JsonObject();
        tool.addProperty("name", name);
        tool.addProperty("description", description);
        tool.add("inputSchema", inputSchema);
        JsonObject annotations = new JsonObject();
        annotations.addProperty("readOnlyHint", readOnly);
        annotations.addProperty("destructiveHint", destructive);
        annotations.addProperty("idempotentHint", idempotent);
        annotations.addProperty("openWorldHint", false);
        tool.add("annotations", annotations);
        return tool;
    }

    private static JsonObject schema(JsonObject properties, String... required) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.addProperty("additionalProperties", false);
        if (required.length > 0) {
            schema.add("required", GSON.toJsonTree(required));
        }
        return schema;
    }

    private JsonObject decompilerProperty() {
        return enumProperty("Decompiler to use. Defaults to cfr.",
                context.getDecompilerIds().toArray(String[]::new));
    }

    private void requireDecompiler(String decompiler) {
        if (!context.getDecompilerIds().contains(decompiler)) {
            throw new IllegalArgumentException("Unsupported decompiler: " + decompiler
                    + ". Available decompilers: " + String.join(", ", context.getDecompilerIds()));
        }
    }

    private static JsonObject stringProperty(String description) {
        JsonObject property = new JsonObject();
        property.addProperty("type", "string");
        property.addProperty("description", description);
        return property;
    }

    private static JsonObject enumProperty(String description, String... values) {
        JsonObject property = stringProperty(description);
        property.add("enum", GSON.toJsonTree(values));
        return property;
    }

    private static JsonObject integerProperty(String description, Integer minimum, Integer maximum) {
        JsonObject property = new JsonObject();
        property.addProperty("type", "integer");
        property.addProperty("description", description);
        if (minimum != null) {
            property.addProperty("minimum", minimum);
        }
        if (maximum != null) {
            property.addProperty("maximum", maximum);
        }
        return property;
    }

    private static JsonObject booleanProperty(String description) {
        JsonObject property = new JsonObject();
        property.addProperty("type", "boolean");
        property.addProperty("description", description);
        return property;
    }

    private static JsonObject arrayProperty(String description, JsonObject itemSchema) {
        JsonObject property = new JsonObject();
        property.addProperty("type", "array");
        property.addProperty("description", description);
        property.add("items", itemSchema);
        return property;
    }

    private static JsonObject instructionProperty() {
        JsonObject properties = new JsonObject();
        properties.add("kind", enumProperty("ASM node kind.",
                "insn", "int", "var", "type", "field", "method", "jump", "ldc", "iinc",
                "multianewarray"));
        properties.add("opcode", integerProperty("Numeric JVM opcode. Not needed for ldc, iinc, or multianewarray.",
                0, Printer.OPCODES.length - 1));
        properties.add("operand", integerProperty("Operand for int instructions.", Integer.MIN_VALUE, Integer.MAX_VALUE));
        properties.add("var", integerProperty("Local-variable index for var and iinc instructions.", 0, null));
        properties.add("owner", stringProperty("Internal owner name for field and method instructions."));
        properties.add("name", stringProperty("Member name for field and method instructions."));
        properties.add("descriptor", stringProperty("JVM descriptor or type operand."));
        properties.add("isInterface", booleanProperty("Whether a method instruction targets an interface."));
        properties.add("targetInstructionIndex", integerProperty("Index of an existing label for a jump.", 0, null));
        properties.add("valueType", enumProperty("LDC value type.",
                "string", "int", "long", "float", "double", "type"));
        properties.add("value", stringProperty("LDC value encoded as text."));
        properties.add("increment", integerProperty("Increment for an iinc instruction.",
                Integer.MIN_VALUE, Integer.MAX_VALUE));
        properties.add("dimensions", integerProperty("Dimensions for a multianewarray instruction.", 1, 255));
        return schema(properties, "kind");
    }

    private static JsonObject toolResult(JsonElement output, boolean error) {
        String text = output.isJsonPrimitive() && output.getAsJsonPrimitive().isString()
                ? output.getAsString() : GSON.toJson(output);
        JsonObject content = new JsonObject();
        content.addProperty("type", "text");
        content.addProperty("text", text);
        JsonArray contents = new JsonArray();
        contents.add(content);
        JsonObject result = new JsonObject();
        result.add("content", contents);
        if (output.isJsonObject()) {
            result.add("structuredContent", output);
        }
        result.addProperty("isError", error);
        return result;
    }

    private static String requiredString(JsonObject object, String name) {
        if (object == null || !object.has(name) || !object.get(name).isJsonPrimitive()
                || !object.get(name).getAsJsonPrimitive().isString()
                || object.get(name).getAsString().isBlank()) {
            throw new IllegalArgumentException("Missing required string: " + name);
        }
        return object.get(name).getAsString();
    }

    private static JsonObject requiredObject(JsonObject object, String name) {
        if (object == null || !object.has(name) || !object.get(name).isJsonObject()) {
            throw new IllegalArgumentException("Missing required object: " + name);
        }
        return object.getAsJsonObject(name);
    }

    private static String requiredStringAllowEmpty(JsonObject object, String name) {
        if (object == null || !object.has(name) || !object.get(name).isJsonPrimitive()
                || !object.get(name).getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Missing required string: " + name);
        }
        return object.get(name).getAsString();
    }

    private static String optionalString(JsonObject object, String name, String defaultValue) {
        return object.has(name) && object.get(name).isJsonPrimitive()
                ? object.get(name).getAsString() : defaultValue;
    }

    private static String nullableOptionalString(JsonObject object, String name) {
        return emptyToNull(optionalString(object, name, ""));
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static List<String> stringArray(JsonObject object, String name) {
        if (!object.has(name) || !object.get(name).isJsonArray()) {
            throw new IllegalArgumentException(name + " must be an array of strings");
        }
        List<String> result = new ArrayList<>();
        for (JsonElement element : object.getAsJsonArray(name)) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException(name + " must be an array of strings");
            }
            result.add(element.getAsString());
        }
        return result;
    }

    private static int optionalInt(JsonObject object, String name, int defaultValue, int minimum, int maximum) {
        int value;
        try {
            value = object.has(name) ? object.get(name).getAsInt() : defaultValue;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
        }
        return value;
    }

    private static int requiredInt(JsonObject object, String name, int minimum, int maximum) {
        if (!object.has(name)) {
            throw new IllegalArgumentException("Missing required integer: " + name);
        }
        return optionalInt(object, name, 0, minimum, maximum);
    }

    private static boolean requiredBoolean(JsonObject object, String name) {
        if (object == null || !object.has(name) || !object.get(name).isJsonPrimitive()
                || !object.get(name).getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException("Missing required boolean: " + name);
        }
        return object.get(name).getAsBoolean();
    }

    private static boolean optionalBoolean(JsonObject object, String name, boolean defaultValue) {
        if (!object.has(name)) {
            return defaultValue;
        }
        try {
            return object.get(name).getAsBoolean();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(name + " must be a boolean");
        }
    }

    private static void addNullable(JsonObject object, String name, String value) {
        if (value != null) {
            object.addProperty(name, value);
        }
    }

    private static String limit(String text) {
        if (text.length() <= MAX_TEXT_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_TEXT_LENGTH) + "\n\n[Output truncated by JByteMod MCP]";
    }
}
