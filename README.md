# JByteMod MCP Plugin

An MCP server for [JByteMod Remastered](https://github.com/apkreader/JByteMod-Remastered). It allows MCP-compatible clients to inspect and modify the archive currently loaded in JByteMod.

## Supported tools

| Tool | Description |
| --- | --- |
| `archive_summary` | Show the archive type, source, class/resource counts, and current selection. |
| `list_classes` | Search and page through the classes in the active archive. |
| `describe_class` | Show class metadata, interfaces, fields, and method signatures. |
| `get_class_file` | Export the current bytes of a class as Base64. |
| `replace_class` | Replace a class in memory using a Base64-encoded class file. |
| `get_method_bytecode` | Render a method as readable JVM bytecode. |
| `decompile_class` | Decompile a class with any available JByteMod decompiler. |
| `decompile_method` | Decompile an individual method when supported by the selected decompiler. |
| `list_instructions` | Return structured ASM instructions and their current indices. |
| `edit_instruction` | Replace, insert, or remove an instruction. |
| `list_constants` | List the LDC constants in a method. |
| `replace_constant` | Replace a string, number, or type constant. |
| `select_class` | Select a class in the JByteMod UI. |
| `select_method` | Select a method in the JByteMod UI. |

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

Whole-class replacement is also supported through `get_class_file` and `replace_class`, which makes it possible to edit a class with an external ASM-based tool and load the result back into JByteMod.

Changes are made to JByteMod's in-memory archive. They are not written to disk or applied to an attached JVM until you explicitly save or apply them in JByteMod.

## Installation

Download the plugin JAR from the [releases page](https://github.com/jbytemod/plugin-mcp/releases) and place it in JByteMod's `plugins` directory:

- Windows: `%APPDATA%\JByteMod-Remastered\plugins`
- macOS: `~/Library/Application Support/JByteMod-Remastered/plugins`
- Linux: `~/JByteMod-Remastered/plugins`

Restart JByteMod after copying the plugin. The plugin will appear as **MCP Server** in the plugin menu.

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

To use a different port, launch JByteMod with:

```text
-Djbytemod.mcp.port=9000
```

You can also start or stop the server from the plugin menu.

## Building from source

JDK 21 and Maven are required. The matching JByteMod API artifact must be installed in your local Maven repository first:

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
