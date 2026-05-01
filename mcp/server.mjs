#!/usr/bin/env node
// Wun MCP server. Exposes Wun developer tools to MCP-aware LLM
// clients (Claude Desktop, Cursor, Cline, etc.) so an agent can
// inspect a Wun project and run scaffolding without shelling out.
//
// Communicates over stdio per the Model Context Protocol spec
// (https://modelcontextprotocol.io). Tools are deliberately narrow:
// each maps to a single `wun` CLI subcommand or read-only inspection.
//
// Install:
//   cd mcp && npm install
//   node server.mjs                       # stdio transport
//
// Wire into Claude Desktop (~/Library/Application Support/Claude/claude_desktop_config.json):
//   {
//     "mcpServers": {
//       "wun": {
//         "command": "node",
//         "args": ["/absolute/path/to/wun/mcp/server.mjs"]
//       }
//     }
//   }

import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import {
  CallToolRequestSchema,
  ListToolsRequestSchema,
  ListResourcesRequestSchema,
  ReadResourceRequestSchema,
} from "@modelcontextprotocol/sdk/types.js";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";
import { readFile, readdir } from "node:fs/promises";

const __filename = fileURLToPath(import.meta.url);
const REPO_ROOT  = resolve(dirname(__filename), "..");
const WUN_BIN    = resolve(REPO_ROOT, "bin/wun");

// ---------------------------------------------------------------------------
// Helpers

function runWun(args, env = {}) {
  return new Promise((res) => {
    const proc = spawn(WUN_BIN, args, {
      cwd: REPO_ROOT,
      env: { ...process.env, WUN_NO_AUTO_UPGRADE: "1", ...env },
    });
    let stdout = "";
    let stderr = "";
    proc.stdout.on("data", (b) => (stdout += b));
    proc.stderr.on("data", (b) => (stderr += b));
    proc.on("close", (code) => res({ code, stdout, stderr }));
  });
}

function stripAnsi(s) {
  // ESC[…m for SGR; the CLI loves these. Strip them so MCP clients
  // don't render raw escape codes in chat.
  return s.replace(/\x1b\[[0-9;]*m/g, "");
}

// ---------------------------------------------------------------------------
// Tools

const tools = [
  {
    name: "wun_status",
    description:
      "Show the per-platform component coverage matrix for the current Wun monorepo. Use this when a user asks 'which components are implemented on iOS / Android / web?' or wants to find what falls back to WebFrame.",
    inputSchema: { type: "object", properties: {}, additionalProperties: false },
    async run() {
      const { stdout, code } = await runWun(["status"]);
      return { content: [{ type: "text", text: stripAnsi(stdout) }], isError: code !== 0 };
    },
  },
  {
    name: "wun_doctor",
    description:
      "Verify the local dev environment (java, clojure, swift, gradle, node, bb) and confirm the wun monorepo layout is intact.",
    inputSchema: { type: "object", properties: {}, additionalProperties: false },
    async run() {
      const { stdout, code } = await runWun(["doctor"]);
      return { content: [{ type: "text", text: stripAnsi(stdout) }], isError: code !== 0 };
    },
  },
  {
    name: "wun_add_component",
    description:
      "Scaffold a multi-platform component (cljc declaration + iOS Swift renderer + Android Kotlin renderer + registry splices). Use only when the user has explicitly asked for a new component; this writes files into the working tree.",
    inputSchema: {
      type: "object",
      properties: {
        name: {
          type: "string",
          pattern: "^[a-z][a-z0-9-]*\\/[A-Za-z][A-Za-z0-9-]*$",
          description: "Namespaced keyword in `ns/Name` form (e.g. 'myapp/Card').",
        },
      },
      required: ["name"],
      additionalProperties: false,
    },
    async run(args) {
      const { stdout, code } = await runWun(["add", "component", args.name]);
      return { content: [{ type: "text", text: stripAnsi(stdout) }], isError: code !== 0 };
    },
  },
  {
    name: "wun_add_screen",
    description:
      "Scaffold a new screen .cljc file with a starter render fn. Writes one file under wun-shared/src/<ns>/.",
    inputSchema: {
      type: "object",
      properties: {
        name: {
          type: "string",
          pattern: "^[a-z][a-z0-9-]*\\/[a-z][a-z0-9-]*$",
          description: "Namespaced keyword in `ns/name` form (e.g. 'myapp/profile').",
        },
      },
      required: ["name"],
      additionalProperties: false,
    },
    async run(args) {
      const { stdout, code } = await runWun(["add", "screen", args.name]);
      return { content: [{ type: "text", text: stripAnsi(stdout) }], isError: code !== 0 };
    },
  },
  {
    name: "wun_add_intent",
    description:
      "Scaffold a new intent .cljc with a Malli schema and a placeholder morph.",
    inputSchema: {
      type: "object",
      properties: {
        name: {
          type: "string",
          pattern: "^[a-z][a-z0-9-]*\\/[a-z][a-z0-9-]*$",
          description: "Namespaced keyword in `ns/name` form (e.g. 'myapp/log-in').",
        },
      },
      required: ["name"],
      additionalProperties: false,
    },
    async run(args) {
      const { stdout, code } = await runWun(["add", "intent", args.name]);
      return { content: [{ type: "text", text: stripAnsi(stdout) }], isError: code !== 0 };
    },
  },
];

// ---------------------------------------------------------------------------
// Resources -- read-only documentation surfaces the agent can pull on demand

const resources = [
  {
    uri: "wun://docs/architecture/head-and-cache",
    name: "Architecture: head + hot-cache + offline",
    mimeType: "text/markdown",
    file: "docs/architecture/head-and-cache.md",
  },
  {
    uri: "wun://CLAUDE.md",
    name: "Wun project orientation for coding agents",
    mimeType: "text/markdown",
    file: "CLAUDE.md",
  },
];

async function listSkills() {
  const dir = resolve(REPO_ROOT, "skills");
  let entries = [];
  try { entries = await readdir(dir); } catch { return []; }
  return entries
    .filter((f) => f.endsWith(".md") && f !== "README.md")
    .map((f) => ({
      uri: `wun://skills/${f.replace(/\.md$/, "")}`,
      name: `Skill: ${f.replace(/\.md$/, "")}`,
      mimeType: "text/markdown",
      file: `skills/${f}`,
    }));
}

// ---------------------------------------------------------------------------
// Server

const server = new Server(
  { name: "wun-mcp", version: "0.1.0" },
  { capabilities: { tools: {}, resources: {} } },
);

server.setRequestHandler(ListToolsRequestSchema, async () => ({
  tools: tools.map(({ name, description, inputSchema }) => ({
    name, description, inputSchema,
  })),
}));

server.setRequestHandler(CallToolRequestSchema, async (req) => {
  const tool = tools.find((t) => t.name === req.params.name);
  if (!tool) {
    return { content: [{ type: "text", text: `Unknown tool: ${req.params.name}` }], isError: true };
  }
  return tool.run(req.params.arguments ?? {});
});

server.setRequestHandler(ListResourcesRequestSchema, async () => {
  const skills = await listSkills();
  return {
    resources: [...resources, ...skills].map(({ uri, name, mimeType }) => ({
      uri, name, mimeType,
    })),
  };
});

server.setRequestHandler(ReadResourceRequestSchema, async (req) => {
  const all = [...resources, ...(await listSkills())];
  const r = all.find((x) => x.uri === req.params.uri);
  if (!r) throw new Error(`Unknown resource: ${req.params.uri}`);
  const text = await readFile(resolve(REPO_ROOT, r.file), "utf8");
  return { contents: [{ uri: r.uri, mimeType: r.mimeType, text }] };
});

// ---------------------------------------------------------------------------

const transport = new StdioServerTransport();
await server.connect(transport);
process.stderr.write("[wun-mcp] connected over stdio\n");
