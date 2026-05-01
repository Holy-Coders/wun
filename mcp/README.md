# wun-mcp

MCP server exposing Wun developer tools to MCP-aware LLM clients
(Claude Desktop, Cursor, Cline, Continue, etc.). Lets an agent
inspect a Wun project and run scaffolding without shelling out by
hand.

## Tools

| name              | does                                                                                  |
|-------------------|---------------------------------------------------------------------------------------|
| `wun_status`      | per-component coverage matrix across web/iOS/Android                                   |
| `wun_doctor`      | env check (java/clojure/swift/gradle/node/bb)                                          |
| `wun_add_component` | scaffold cljc + iOS + Android + registry splices                                     |
| `wun_add_screen`  | scaffold a new screen .cljc                                                           |
| `wun_add_intent`  | scaffold a new intent .cljc                                                           |

## Resources

Read-only documentation the agent can pull on demand:

- `wun://CLAUDE.md` — project orientation
- `wun://docs/architecture/head-and-cache` — LiveView/Hotwire mapping
- `wun://skills/<name>` — every file under `skills/` is exposed as a resource

## Install

```bash
cd mcp && npm install
```

## Wire into Claude Desktop

`~/Library/Application Support/Claude/claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "wun": {
      "command": "node",
      "args": ["/absolute/path/to/wun/mcp/server.mjs"]
    }
  }
}
```

Restart Claude Desktop. The Wun tools appear in the tools picker.

## Wire into Cursor / Cline

Same config shape; both honour `command + args`. See each tool's
own MCP docs for the exact config file location.

## Notes

- All tool calls run with `WUN_NO_AUTO_UPGRADE=1` so the auto-upgrade
  prompt doesn't block agent runs.
- Tool outputs are ANSI-stripped before returning so MCP clients that
  don't render escape codes show clean text.
- The server speaks stdio per the MCP spec; HTTP/SSE transport is a
  later iteration if you want network-attached agent runtimes.
