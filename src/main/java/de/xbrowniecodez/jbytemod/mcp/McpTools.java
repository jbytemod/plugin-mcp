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
import org.objectweb.asm.Type;
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
import org.objectweb.asm.util.TraceMethodVisitor;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class McpTools {
    private static final Gson GSON = new Gson();
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;
    private static final int MAX_TEXT_LENGTH = 2_000_000;
    private static final int MAX_CLASS_FILE_BYTES = 8 * 1024 * 1024;

    private final PluginContext context;
    private final Object mutationLock = new Object();

    McpTools(PluginContext context) {
        this.context = context;
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

        tools.add(tool("archive_summary", "Show information about the archive currently open in JByteMod.",
                schema(new JsonObject()), true, true));

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
                case "archive_summary" -> archiveSummary();
                case "list_classes" -> listClasses(arguments);
                case "search_members" -> searchMembers(arguments);
                case "search_constants" -> searchConstants(arguments);
                case "find_references" -> findReferences(arguments);
                case "describe_class" -> describeClass(arguments);
                case "class_hierarchy" -> classHierarchy(arguments);
                case "verify_class" -> verifyClass(arguments);
                case "get_class_file" -> classFile(arguments);
                case "replace_class" -> replaceClass(arguments);
                case "get_method_bytecode" -> methodBytecode(arguments);
                case "method_calls" -> methodCalls(arguments);
                case "decompile_class" -> decompileClass(arguments);
                case "decompile_method" -> decompileMethod(arguments);
                case "list_instructions" -> listInstructions(arguments);
                case "edit_instruction" -> editInstruction(arguments);
                case "list_constants" -> listConstants(arguments);
                case "replace_constant" -> replaceConstant(arguments);
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
        JsonObject result = archiveSummary();
        result.addProperty("path", path);
        result.addProperty("opened", true);
        return result;
    }

    private JsonObject saveFile(JsonObject arguments) throws Exception {
        String outputPath = context.saveFile(requiredString(arguments, "path"));
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
        JsonObject result = archiveSummary();
        result.addProperty("pid", pid);
        result.addProperty("attached", true);
        return result;
    }

    private JsonObject refreshAttachedJvm() throws Exception {
        context.refreshAttachedJvm();
        JsonObject result = archiveSummary();
        result.addProperty("refreshed", true);
        return result;
    }

    private JsonObject applyChanges() throws Exception {
        int changedClasses = context.applyChangesToAttachedJvm();
        JsonObject result = new JsonObject();
        result.addProperty("changedClasses", changedClasses);
        result.addProperty("applied", true);
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

    private JsonObject replaceClass(JsonObject arguments) {
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

        synchronized (mutationLock) {
            context.replaceClass(previous, replacement);
        }

        JsonObject result = new JsonObject();
        result.addProperty("class", className);
        result.addProperty("byteLength", bytes.length);
        result.addProperty("fieldCount", replacement.fields.size());
        result.addProperty("methodCount", replacement.methods.size());
        result.addProperty("modified", true);
        return result;
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

    private JsonObject editInstruction(JsonObject arguments) {
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
        AbstractInsnNode edited = null;

        synchronized (mutationLock) {
            switch (operation) {
                case "replace" -> {
                    requireRealInstruction(anchor, "replace");
                    edited = createInstruction(method, requiredObject(arguments, "instruction"));
                    copyTypeAnnotations(anchor, edited);
                    method.instructions.set(anchor, edited);
                }
                case "insert_before" -> {
                    edited = createInstruction(method, requiredObject(arguments, "instruction"));
                    method.instructions.insertBefore(anchor, edited);
                }
                case "insert_after" -> {
                    edited = createInstruction(method, requiredObject(arguments, "instruction"));
                    method.instructions.insert(anchor, edited);
                }
                case "remove" -> {
                    requireRealInstruction(anchor, "remove");
                    method.instructions.remove(anchor);
                }
                default -> throw new IllegalArgumentException("Unsupported operation: " + operation);
            }
        }
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

    private JsonObject replaceConstant(JsonObject arguments) {
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
        synchronized (mutationLock) {
            ldc.cst = replacement;
        }
        context.methodModified(classNode, method);

        JsonObject result = new JsonObject();
        result.addProperty("class", classNode.name);
        result.addProperty("method", method.name + method.desc);
        result.add("previous", previous);
        result.add("replacement", constant(instructionIndex, replacement));
        result.addProperty("modified", true);
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
