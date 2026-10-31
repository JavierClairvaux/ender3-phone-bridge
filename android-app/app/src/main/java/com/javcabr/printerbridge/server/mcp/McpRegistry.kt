package com.javcabr.printerbridge.server.mcp

/**
 * The one list of tool groups. Adding a batch of tools = one new file in this package plus one line here.
 * Tool names must be unique across groups (checked when the registry is built).
 */
class McpRegistry(ctx: ToolContext) {
    val tools: List<McpTool> = GROUPS.flatMap { it.tools(ctx) }.also { all ->
        val dup = all.groupBy { it.name }.filterValues { it.size > 1 }.keys
        require(dup.isEmpty()) { "duplicate MCP tool names: $dup" }
    }

    fun find(name: String) = tools.firstOrNull { it.name == name }

    companion object {
        /** Bumped when a tool's name, arguments or result shape changes incompatibly. */
        const val SCHEMA_VERSION = 1

        private val GROUPS: List<ToolGroup> = listOf(
            CoreTools,
            FoundationTools,
            // batch 1: HeatMotionTools, batch 2: AsyncTools, ... (one file each)
        )
    }
}
