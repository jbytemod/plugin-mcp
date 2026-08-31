# JByteMod MCP Plugin

An MCP server for [JByteMod Remastered](https://github.com/apkreader/JByteMod-Remastered). It allows MCP-compatible clients to inspect and modify the archive currently loaded in JByteMod.

## Supported tools

### Files and JVM attachment

| Tool | Description |
| --- | --- |
| `archive_summary` | Show the archive type, source, class/resource counts, and current selection. |
| `open_file` | Open a local JAR, class, or APK in the current JByteMod window. |
| `save_file` | Save the active archive, class, or attached-JVM class dump to a local file. |
| `export_class` | Write one current class file to disk. |
| `export_package` | Write a package and optionally its subpackages to a JAR. |
| `list_jvms` | List running local JVM processes that are available for attachment. |
| `attach_jvm` | Attach JByteMod to a process by PID and load its runtime classes. |
| `refresh_attached_jvm` | Reload classes from the attached JVM and discard unapplied in-memory edits. |
| `apply_changes` | Redefine modified classes in the attached JVM. |
| `set_attached_jvm_frozen` | Freeze or resume the entire attached JVM process. |
| `terminate_attached_jvm` | Terminate the attached JVM while retaining its loaded classes as a local snapshot. |

### Change tracking and safety

| Tool | Description |
| --- | --- |
| `list_changes` | List classes changed since the current clean baseline. |
| `diff_class` | Compare a class with its original bytecode and structural schema. |
| `begin_transaction` | Group several MCP edits into one undoable transaction. |
| `commit_transaction` | Commit the active transaction to the undo history. |
| `rollback_transaction` | Restore everything changed during the active transaction. |
| `undo_change` | Undo the latest MCP edit or transaction. |
| `redo_change` | Redo the latest undone MCP edit or transaction. |
| `discard_changes` | Restore one class or every changed class to the clean baseline. |
| `validate_hotswap` | Check changes against JVM class-redefinition restrictions before applying them. |

### Archive resources and manifest

| Tool | Description |
| --- | --- |
| `list_resources` | Search and page through non-class entries in the active archive. |
| `get_resource` | Read a paged byte range of an archive resource as Base64 with a text preview. |
| `add_resource` | Add a non-class entry to the active archive. |
| `replace_resource` | Replace an existing non-class archive entry. |
| `delete_resource` | Delete a non-class entry from the active archive. |
| `get_manifest` | Read `META-INF/MANIFEST.MF` as text. |
| `edit_manifest` | Add or replace the manifest from UTF-8 text. |

### Discovery and navigation

| Tool | Description |
| --- | --- |
| `list_classes` | Search and page through the classes in the active archive. |
| `search_members` | Search fields and methods by class name, member name, or descriptor. |
| `search_constants` | Search LDC constants throughout the active archive. |
| `find_references` | Find bytecode references to classes, fields, and methods. |
| `describe_class` | Show class metadata, interfaces, fields, and method signatures. |
| `class_hierarchy` | Show loaded ancestors and direct or transitive subtypes. |
| `method_calls` | Find incoming and outgoing calls for a method. |
| `get_call_graph` | Build a bounded callers/callees graph with exact call-site instruction indices. |
| `find_overrides` | Find loaded ancestor declarations and overriding methods. |
| `find_implementations` | Find concrete implementations of a class or interface and resolve methods. |
| `find_entry_points` | Find main, Java-agent, and JavaFX entry points. |
| `select_class` | Select a class in the JByteMod UI. |
| `select_method` | Select a method in the JByteMod UI. |

### Class and member editing

| Tool | Description |
| --- | --- |
| `get_class_file` | Export the current bytes of a class as Base64. |
| `replace_class` | Replace a class in memory using a Base64-encoded class file. |
| `rename_class` | Rename a class and update references and descriptors throughout the loaded archive. |
| `rename_method` | Rename a declared method and update matching calls throughout the loaded archive. |
| `rename_field` | Rename a declared field and update matching references throughout the loaded archive. |
| `set_access_flags` | Replace the ASM access flags on a class, field, or method. |
| `add_field` | Add a field, including an optional constant value. |
| `remove_field` | Remove a field from a class. |
| `add_method` | Add a method with a valid default return body. |
| `remove_method` | Remove a method from a class. |
| `copy_method` | Copy a complete method between loaded classes. |
| `replace_method_body` | Replace a method body with another loaded method that has the same descriptor. |
| `edit_class_metadata` | Change a class's superclass, interfaces, signature, or source-file metadata. |
| `list_instructions` | Return structured ASM instructions and their current indices. |
| `edit_instruction` | Replace, insert, or remove an instruction. |
| `list_constants` | List the LDC constants in a method. |
| `replace_constant` | Replace a string, number, or type constant. |

### Analysis and decompilation

| Tool | Description |
| --- | --- |
| `verify_class` | Validate class structure and method data flow with ASM. |
| `get_method_bytecode` | Render a method as readable JVM bytecode. |
| `get_control_flow_graph` | Return normal and exception control-flow edges for a method. |
| `find_dead_code` | Find unreachable instructions using ASM data-flow analysis. |
| `analyze_stack_frames` | Show inferred local and operand-stack values at each instruction. |
| `search_instruction_pattern` | Search for contiguous opcode sequences across loaded methods. |
| `detect_reflection_usage` | Find reflection, method-handle, proxy, Unsafe, and invokedynamic usage. |
| `detect_native_methods` | Find methods declared with `ACC_NATIVE`. |
| `compare_classes` | Compare metadata, fields, methods, and current bytecode for two classes. |
| `decompile_class` | Decompile a class with any available JByteMod decompiler. |
| `decompile_method` | Decompile an individual method when supported by the selected decompiler. |

### Decompilers

The decompilation tools expose every decompiler provided by JByteMod:

- CFR
- Procyon
- Vineflower
- JD-Core
- Koffee
- ASMifier

### Bytecode editing

`edit_instruction` supports regular zero-operand instructions as well as integer, local-variable, type, field, method, jump, LDC, IINC, and MULTIANEWARRAY instructions. Jump targets refer to label instruction indices returned by `list_instructions`.

Whole-class replacement is also supported through `get_class_file` and `replace_class`, which makes it possible to edit a class with an external ASM-based tool and load the result back into JByteMod. Refactoring commands update matching references in every loaded class and participate in transactions, undo, redo, diffs, and change tracking.

Changes are made to JByteMod's in-memory archive. They are not written to disk automatically. For an attached process, use `validate_hotswap` before `apply_changes`. Standard JVM redefinition restrictions still apply, so structural tools such as adding or removing fields or methods, changing inheritance, and renaming classes cannot normally be applied to an already loaded class. They remain useful for archives that will be saved to disk.

Use `save_file` to write the current archive to disk or dump the classes loaded from an attached JVM into a JAR. Existing output files are overwritten.

Resource tools operate on JAR and APK entries that are not class files. Large resources can be read in chunks with `offset` and `length`; writes accept Base64 and are limited to 32 MiB per entry. `export_class` and `export_package` write selected bytecode without saving the complete active archive.

`set_attached_jvm_frozen` pauses the entire target process, including its UI and agent connection. Resume it before refreshing classes or applying changes. `terminate_attached_jvm` automatically resumes a frozen target before terminating it.

## Installation

Download the plugin JAR from the [releases page](https://github.com/jbytemod/plugin-mcp/releases) and place it in JByteMod's `plugins` directory:

- Windows: `%APPDATA%\JByteMod-Remastered\plugins`
- macOS: `~/Library/Application Support/JByteMod-Remastered/plugins`
- Linux: `~/JByteMod-Remastered/plugins`

Restart JByteMod after copying the plugin. The plugin will appear as **MCP Server** in the plugin menu.

## Activity dashboard

Open **Plugins > MCP Server** to view the live MCP activity dashboard. It shows:

- Whether the server is running and its current endpoint.
- Recent MCP requests and tool calls, including their client, result, and duration.
- MCP clients observed by the server, their reported version, last activity, and request count.
- The number of successful MCP bytecode edits made since the archive was opened, saved, refreshed, attached, or applied.

The dashboard also provides controls for starting or stopping the server, changing its preferred port, and clearing the activity history. It is modeless, so it can remain open while working in JByteMod.

## Connecting

The server starts with JByteMod and listens at:

```text
http://127.0.0.1:8765/mcp
```

Add that URL as an HTTP MCP server in your client. For clients using JSON configuration, the entry generally looks like this:

```json
{
  "mcpServers": {
    "jbytemod": {
      "url": "http://127.0.0.1:8765/mcp"
    }
  }
}
```

The exact configuration format depends on the MCP client. The server only accepts connections from the local machine.

The preferred port and server state can be changed from the activity dashboard. The settings are kept across restarts. If the preferred port is already used by another JByteMod instance, the plugin automatically uses the next available port and shows the actual endpoint in the dashboard and the log.

To override the saved port at launch, use:

```text
-Djbytemod.mcp.port=9000
```

You can also start or stop the server from the plugin menu.

## Building from source

JByteMod 2.11.0, JDK 21, and Maven are required. The matching JByteMod API artifact must be installed in your local Maven repository first:

```sh
git clone https://github.com/apkreader/JByteMod-Remastered.git
git clone https://github.com/jbytemod/plugin-mcp.git

mvn -f JByteMod-Remastered/pom.xml -pl jbytemod-api -am install -DskipTests
mvn -f plugin-mcp/pom.xml package
```

The plugin will be written to:

```text
plugin-mcp/target/plugin-mcp-1.0.0.jar
```
