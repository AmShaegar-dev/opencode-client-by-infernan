// Мок-сервер OpenCode V2 на Node.js без зависимостей.
// Порт 4096 на всех интерфейсах (локально и из эмулятора через 10.0.2.2).
const http = require("http");
const crypto = require("crypto");

const PORT = Number(process.env.MOCK_PORT || 4096);
const PASSWORD = "testpass123";
const AUTH = "Basic " + Buffer.from("opencode:" + PASSWORD).toString("base64");

const now = () => Date.now() / 1000;
const sessions = new Map();
const order = [];
const prompts = new Map();
/** Сохранённые аккаунты провайдеров: ключи и OAuth-токены. */
const credentials = [];
/** Начатые OAuth-попытки: attemptID -> {integrationID, mode, created, status, message, code}. */
const oauthAttempts = new Map();
/** Выключенные модели (в реальном сервере это `providers.<id>.models.<model>.disabled`). */
const disabledModels = new Set(["legacy-model"]);

const configPath = "/root/.config/opencode/opencode.json";
let configFile = JSON.stringify(
  {
    "$schema": "https://opencode.ai/config.json",
    "providers": {
      "mock": {
        "name": "Mock Provider",
        "package": "@opencode/ai/providers/openai-compatible",
        "models": { "legacy-model": { "name": "Mock Legacy (выключена)", "disabled": true } },
      },
    },
  },
  null,
  2
) + "\n";

function syncDisabledFromConfig() {
  try {
    const root = JSON.parse(configFile);
    const providers = root.providers || {};
    const next = new Set();
    for (const provider of Object.values(providers)) {
      const models = provider && provider.models ? provider.models : {};
      for (const [id, model] of Object.entries(models)) {
        if (model && model.disabled === true) next.add(id);
      }
    }
    disabledModels.clear();
    for (const id of next) disabledModels.add(id);
  } catch {
    /* оставляем прежнее состояние */
  }
}
syncDisabledFromConfig();

/** Завершённые shell-команды: id -> {output, status}. */
const shellRuns = new Map();

function addCredential(integrationID, method, label) {
  const credential = {
    id: "cred_" + crypto.randomBytes(5).toString("hex"),
    integrationID,
    method,
    label: label || (method === "oauth" ? "Browser account" : "API key"),
    type: method,
    active: true,
    created: now(),
  };
  credentials.push(credential);
  return credential;
}

function connectionJson(credential) {
  return {
    id: credential.id,
    label: credential.label,
    method: credential.method,
    type: credential.type,
  };
}

function newSession(title, model) {
  const id = "ses_" + crypto.randomBytes(5).toString("hex");
  const t = now();
  const s = {
    id,
    projectID: "proj_mock",
    title,
    model: model || { id: "free-model", providerID: "mock" },
    cost: 0,
    time: { created: t, updated: t },
    location: { directory: "/root/project" },
    messages: [],
  };
  sessions.set(id, s);
  order.unshift(id);
  return s;
}

function sessionJson(s) {
  return {
    id: s.id,
    projectID: s.projectID,
    title: s.title,
    model: s.model,
    cost: s.cost,
    time: s.time,
    location: s.location,
  };
}

function assistantMessage(sid, text, completed) {
  const t = now();
  const time = { created: t };
  if (completed) time.completed = t;
  return {
    id: "msg_a_" + crypto.randomBytes(4).toString("hex"),
    type: "assistant",
    time,
    agent: "build",
    model: { id: "free-model", providerID: "mock" },
    content: [
      { type: "reasoning", text: "Размышление mock-модели над запросом." },
      { type: "text", text },
      {
        type: "tool",
        id: "tool_1",
        name: "bash",
        state: {
          status: completed ? "completed" : "running",
          input: { command: "echo mock" },
          content: [{ type: "text", text: "mock output" }],
        },
        time: { created: t },
      },
    ],
  };
}

function models() {
  return [
    {
      id: "free-model", modelID: "free-model", providerID: "mock", name: "Mock Free Model",
      enabled: true, cost: [{ input: 0, output: 0, cache: { read: 0, write: 0 } }],
      limit: { context: 128000, output: 8192 },
      variants: [{ id: "low" }, { id: "medium" }, { id: "high" }],
      capabilities: {}, time: { released: 0 }, status: "active",
    },
    {
      id: "paid-model", modelID: "paid-model", providerID: "mock", name: "Mock Paid Model",
      enabled: true, cost: [{ input: 3, output: 15, cache: { read: 0.3, write: 3.75 } }],
      limit: { context: 200000, output: 8192 }, variants: [{ id: "high" }], capabilities: {}, time: { released: 0 }, status: "active",
    },
    {
      id: "claude-sonnet-5-5", modelID: "claude-sonnet-5-5", providerID: "anthropic", name: "Claude Sonnet 5.5",
      enabled: true, cost: [{ input: 3, output: 15, cache: { read: 0.3, write: 3.75 } }],
      limit: { context: 200000, output: 64000 }, variants: [], capabilities: {}, time: { released: 0 }, status: "active",
    },
    {
      id: "legacy-model", modelID: "legacy-model", providerID: "mock", name: "Mock Legacy (выключена)",
      enabled: true, cost: [{ input: 0, output: 0, cache: { read: 0, write: 0 } }],
      limit: { context: 8000, output: 2048 }, variants: [], capabilities: {}, time: { released: 0 }, status: "deprecated",
    },
    // Сервер отдаёт только включённые модели; выключенные видны в opencode.json.
  ].filter((model) => !disabledModels.has(model.id));
}

function integrations() {
  const connections = (integrationID) =>
    credentials.filter((c) => c.integrationID === integrationID).map(connectionJson);
  return [
    {
      id: "mock", name: "Mock Provider",
      methods: [{ id: "key", type: "key", label: "API key" }],
      connections: connections("mock"),
    },
    {
      id: "anthropic", name: "Anthropic",
      methods: [
        { id: "key", type: "key", label: "API key" },
        { id: "claude", type: "oauth", label: "Claude Pro" },
      ],
      connections: connections("anthropic"),
    },
    {
      id: "opencode", name: "OpenCode Go",
      methods: [
        { id: "key", type: "key", label: "API key" },
        { id: "go", type: "oauth", label: "Войти через OpenCode" },
      ],
      connections: connections("opencode"),
    },
  ];
}

function send(res, status, payload) {
  const body = payload === undefined ? "" : JSON.stringify(payload);
  res.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Content-Length": Buffer.byteLength(body),
    "Cache-Control": "no-store",
  });
  res.end(body);
}

function handle(req, res, body) {
  const url = new URL(req.url, "http://localhost");
  const path = url.pathname;
  const method = req.method;
  let json = null;
  if (body.trim()) {
    try { json = JSON.parse(body); } catch { json = null; }
  }

  if (req.headers.authorization !== AUTH) return send(res, 401, { message: "Unauthorized" });

  if (method === "GET" && path === "/api/info") {
    return send(res, 200, { version: "2.0.0-mock", name: "opencode" });
  }

  if (method === "GET" && path === "/api/session") {
    const search = (url.searchParams.get("search") || "").toLowerCase();
    const data = order
      .map((id) => sessions.get(id))
      .filter((s) => s && (!search || s.title.toLowerCase().includes(search)))
      .map(sessionJson);
    return send(res, 200, { data, cursor: { previous: null, next: null } });
  }

  if (method === "POST" && path === "/api/session") {
    const title = (json && json.title) || "Новый чат";
    const model = json && json.model ? { id: json.model.id, providerID: json.model.providerID } : null;
    const s = newSession(title, model);
    return send(res, 200, { data: sessionJson(s) });
  }

  let m = path.match(/^\/api\/session\/([^/]+)\/message$/);
  if (method === "GET" && m) {
    const s = sessions.get(m[1]);
    if (!s) return send(res, 404, { message: "Session not found" });
    if (s.messages.length && prompts.has(s.id)) {
      const last = [...s.messages].reverse().find((x) => x.type === "assistant");
      if (last) {
        const done = now() - prompts.get(s.id) >= 2;
        const time = { created: last.time.created };
        if (done || last.time.completed) time.completed = last.time.completed || now();
        last.time = time;
      }
    }
    let data = s.messages.slice();
    if (url.searchParams.get("order") === "desc") data = data.reverse();
    return send(res, 200, { data, cursor: { previous: null, next: null } });
  }

  m = path.match(/^\/api\/session\/([^/]+)\/prompt$/);
  if (method === "POST" && m) {
    const s = sessions.get(m[1]);
    if (!s) return send(res, 404, { message: "Session not found" });
    const text = (json && json.text) || "";
    const t = now();
    s.messages.push({ id: "msg_u_" + crypto.randomBytes(4).toString("hex"), type: "user", time: { created: t }, text });
    s.messages.push(assistantMessage(s.id, "Эхо: " + text, false));
    prompts.set(s.id, t);
    s.time.updated = t;
    if (s.title === "Новый чат") s.title = text.slice(0, 40);
    return send(res, 200, { data: { id: "inbox_mock" } });
  }

  m = path.match(/^\/api\/session\/([^/]+)\/interrupt$/);
  if (method === "POST" && m) {
    const s = sessions.get(m[1]);
    if (s) {
      const last = [...s.messages].reverse().find((x) => x.type === "assistant");
      if (last) last.time = { created: last.time.created, completed: now() };
    }
    return send(res, 204);
  }

  m = path.match(/^\/api\/session\/([^/]+)\/model$/);
  if (method === "POST" && m) {
    const s = sessions.get(m[1]);
    if (s && json && json.model) s.model = { id: json.model.id, providerID: json.model.providerID };
    return send(res, 204);
  }

  m = path.match(/^\/api\/session\/([^/]+)$/);
  if (method === "PATCH" && m) {
    const s = sessions.get(m[1]);
    if (s && json && json.title) s.title = json.title;
    return send(res, 204);
  }
  if (method === "DELETE" && m) {
    sessions.delete(m[1]);
    const i = order.indexOf(m[1]);
    if (i >= 0) order.splice(i, 1);
    return send(res, 204);
  }

  if (method === "GET" && path === "/api/command") {
    return send(res, 200, {
      data: [
        { name: "compact", description: "Сжать контекст сессии" },
        { name: "init", description: "Создать AGENTS.md" },
      ],
      location: { directory: "/root/project" },
    });
  }

  if (method === "GET" && path === "/api/agent") {
    return send(res, 200, {
      data: [
        { id: "build", name: "Build", mode: "primary", hidden: false, permissions: {}, request: {} },
        { id: "plan", name: "Plan", mode: "primary", hidden: false, permissions: {}, request: {} },
      ],
      location: { directory: "/root/project" },
    });
  }

  m = path.match(/^\/api\/session\/([^/]+)\/agent$/);
  if (method === "POST" && m) return send(res, 204);

  m = path.match(/^\/api\/session\/([^/]+)\/command$/);
  if (method === "POST" && m) return send(res, 204);

  if (method === "GET" && path === "/api/model") {
    return send(res, 200, { data: models(), location: { directory: "/root/project" } });
  }

  if (method === "GET" && path === "/api/experimental/usage/limits") {
    const reset = Date.now() + 86400000;
    return send(res, 200, {
      location: { directory: "/root/project" },
      data: {
        workspace: { id: "ws_mock", name: "Mock Workspace", usageURL: "https://opencode.ai/console" },
        go: {
          rolling: { percent: 42, limited: false, resetsAt: reset },
          weekly: { percent: 70, limited: false, resetsAt: reset },
          monthly: { percent: 100, limited: true, resetsAt: reset },
        },
        budgets: [{ id: "b1", limit: 20, spent: 7.5, resetsAt: reset }],
      },
    });
  }

  m = path.match(/^\/api\/session\/([^/]+)\/diff$/);
  if (method === "GET" && m) {
    return send(res, 200, {
      data: [
        {
          file: "app/src/main/java/ru/opencode/mobile/MainActivity.kt",
          patch: "--- a/MainActivity.kt\n+++ b/MainActivity.kt\n@@ -1,2 +1,3 @@\n package ru.opencode.mobile\n+// mock change\n",
          additions: 1,
          deletions: 0,
          status: "modified",
        },
      ],
    });
  }

  if (method === "GET" && path === "/api/provider") {
    const data = [
      { id: "mock", name: "Mock Provider", integrationID: "mock", activation: "auto", package: "@opencode/ai/providers/openai-compatible" },
      { id: "anthropic", name: "Anthropic", integrationID: "anthropic", activation: "auto", package: "@opencode/ai/providers/anthropic" },
      { id: "opencode", name: "OpenCode Zen", integrationID: "opencode", activation: "auto", package: "@opencode/ai/providers/opencode" },
    ];
    return send(res, 200, { data, location: { directory: "/root/project" } });
  }

  if (method === "GET" && path === "/api/integration") {
    return send(res, 200, { data: integrations(), location: { directory: "/root/project" } });
  }

  m = path.match(/^\/api\/integration\/([^/]+)$/);
  if (method === "GET" && m) {
    const integration = integrations().find((item) => item.id === m[1]);
    if (!integration) return send(res, 404, { message: "Integration not found" });
    return send(res, 200, { data: integration, location: { directory: "/root/project" } });
  }

  m = path.match(/^\/api\/integration\/([^/]+)\/connect\/key$/);
  if (method === "POST" && m) {
    if (!json || typeof json.key !== "string" || !json.key.trim()) {
      return send(res, 400, { message: "key required" });
    }
    const credential = addCredential(m[1], "key", json.label);
    return send(res, 200, { data: connectionJson(credential) });
  }

  m = path.match(/^\/api\/integration\/([^/]+)\/connect\/oauth$/);
  if (method === "POST" && m) {
    const integration = integrations().find((item) => item.id === m[1]);
    if (!integration) return send(res, 404, { message: "Integration not found" });
    const methodID = (json && json.methodID) || "oauth";
    const method = integration.methods.find((item) => item.id === methodID || item.type === methodID);
    if (!method || method.type !== "oauth") {
      return send(res, 400, { message: "OAuth method not found" });
    }
    // `claude` требует ручной код, `go` завершается сам (как локальный callback).
    const mode = methodID === "claude" ? "code" : "auto";
    const attemptID = "att_" + crypto.randomBytes(5).toString("hex");
    const state = "state_" + crypto.randomBytes(4).toString("hex");
    oauthAttempts.set(attemptID, {
      integrationID: m[1],
      methodID,
      mode,
      created: now(),
      status: "pending",
      message: "",
      code: null,
      label: (json && json.label) || method.label,
    });
    const query = new URLSearchParams({
      client_id: "mock-client",
      state,
      redirect_uri: "http://127.0.0.1:19876/callback",
      response_type: "code",
    });
    return send(res, 200, {
      data: {
        attemptID,
        url: `https://example.com/mock-oauth/authorize?${query.toString()}`,
        instructions:
          mode === "code"
            ? "Скопируйте код со страницы провайдера и вставьте его в приложение. Демо-код: 123456."
            : "Подтвердите вход в браузере — результат вернётся на сервер автоматически.",
        mode,
        time: { created: now() },
      },
      location: { directory: "/root/project" },
    });
  }

  m = path.match(/^\/api\/integration\/([^/]+)\/connect\/oauth\/([^/]+)$/);
  if (method === "GET" && m) {
    const attempt = oauthAttempts.get(m[2]);
    if (!attempt || attempt.integrationID !== m[1]) {
      return send(res, 404, { message: "OAuth attempt not found" });
    }
    // Автоматический callback «приходит» через 2 секунды.
    if (attempt.mode === "auto" && attempt.status === "pending" && now() - attempt.created >= 2) {
      attempt.status = "complete";
      addCredential(attempt.integrationID, "oauth", attempt.label);
    }
    return send(res, 200, {
      data: {
        status: attempt.status,
        message: attempt.message,
        time: { created: attempt.created, updated: now() },
      },
      location: { directory: "/root/project" },
    });
  }
  if (method === "DELETE" && m) {
    oauthAttempts.delete(m[2]);
    return send(res, 204);
  }

  m = path.match(/^\/api\/integration\/([^/]+)\/connect\/oauth\/([^/]+)\/complete$/);
  if (method === "POST" && m) {
    const attempt = oauthAttempts.get(m[2]);
    if (!attempt || attempt.integrationID !== m[1]) {
      return send(res, 404, { message: "OAuth attempt not found" });
    }
    const code = (json && json.code) || "";
    if (attempt.mode === "code" && code.trim() !== "123456") {
      attempt.status = "failed";
      attempt.message = "Неверный код. Демо-код: 123456.";
      return send(res, 400, { message: attempt.message });
    }
    attempt.code = code;
    attempt.status = "complete";
    addCredential(attempt.integrationID, "oauth", attempt.label);
    return send(res, 200, {
      data: { status: attempt.status, time: { created: attempt.created, updated: now() } },
    });
  }

  if (method === "GET" && path === "/api/credential") {
    return send(res, 200, { data: credentials });
  }

  m = path.match(/^\/api\/credential\/([^/]+)\/activate$/);
  if (method === "POST" && m) {
    const credential = credentials.find((item) => item.id === m[1]);
    if (!credential) return send(res, 404, { message: "Credential not found" });
    credentials.forEach((item) => {
      if (item.integrationID === credential.integrationID) item.active = false;
    });
    credential.active = true;
    return send(res, 204);
  }

  m = path.match(/^\/api\/credential\/([^/]+)$/);
  if (method === "DELETE" && m) {
    const index = credentials.findIndex((item) => item.id === m[1]);
    if (index >= 0) credentials.splice(index, 1);
    return send(res, 204);
  }

  if (method === "POST" && path === "/api/location/reload") return send(res, 204);

  m = path.match(/^\/api\/experimental\/session\/([^/]+)\/export$/);
  if (method === "GET" && m) {
    const s = sessions.get(m[1]);
    if (!s) return send(res, 404, { message: "Session not found" });
    return send(res, 200, { data: { info: sessionJson(s), messages: s.messages.slice() } });
  }

  if (method === "POST" && path === "/api/experimental/session/import") {
    if (!json || !json.info) return send(res, 400, { message: "info required" });
    const title = json.info.title || "Imported";
    const model = json.info.model ? { id: json.info.model.id, providerID: json.info.model.providerID } : null;
    const s = newSession(title + " (import)", model);
    s.messages = Array.isArray(json.messages) ? json.messages.slice() : [];
    return send(res, 200, { data: sessionJson(s) });
  }

  // --- Конфигурация через HTTP API (без отдельного SSH) ---------------
  if (method === "GET" && path === "/api/config") {
    let info = {};
    try { info = JSON.parse(configFile); } catch { /* пусто */ }
    return send(res, 200, [{ type: "document", path: configPath, info }]);
  }

  if (method === "POST" && path === "/api/experimental/fs/write") {
    const target = url.searchParams.get("path") || configPath;
    configFile = body;
    syncDisabledFromConfig();
    return send(res, 200, { data: { path: target }, location: { directory: "/root/project" } });
  }

  if (method === "POST" && path === "/api/shell") {
    const command = (json && json.command) || "";
    let output = "";
    if (command.includes("getent passwd")) {
      output = configPath + "\n";
    } else if (/^cat\s/.test(command.trim())) {
      output = configFile;
    } else if (command.includes("df -Pk")) {
      output = "FREE=10485760\nUSED=12345\n";
    } else if (command.includes("CLEARED")) {
      output = "CLEARED\n";
    } else if (command.includes("find") && command.includes("opencode-uploads")) {
      output = "photo.png\nreport.pdf\n";
    } else if (command.includes("rm -f --")) {
      output = "";
    }
    const base64 = command.match(/printf '%s' '([^']*)'/);
    if (base64) {
      try {
        configFile = Buffer.from(base64[1], "base64").toString("utf8");
        syncDisabledFromConfig();
      } catch {
        output = "base64: invalid input\n";
      }
    }
    const id = "sh_" + crypto.randomBytes(5).toString("hex");
    shellRuns.set(id, { output, status: "exited" });
    return send(res, 200, {
      location: { directory: "/root/project" },
      data: {
        id,
        status: "exited",
        command,
        cwd: "/root/project",
        shell: "sh",
        file: `/tmp/${id}.log`,
        metadata: {},
        time: { started: Date.now(), completed: Date.now() },
        exit: 0,
      },
    });
  }

  m = path.match(/^\/api\/shell\/([^/]+)\/output$/);
  if (method === "GET" && m) {
    const run = shellRuns.get(m[1]);
    if (!run) return send(res, 404, { message: "Shell not found" });
    return send(res, 200, {
      location: { directory: "/root/project" },
      data: {
        output: run.output,
        cursor: run.output.length,
        size: run.output.length,
        truncated: false,
      },
    });
  }

  m = path.match(/^\/api\/shell\/([^/]+)$/);
  if (method === "GET" && m) {
    const run = shellRuns.get(m[1]);
    if (!run) return send(res, 404, { message: "Shell not found" });
    return send(res, 200, {
      location: { directory: "/root/project" },
      data: {
        id: m[1],
        status: run.status,
        command: "",
        cwd: "/root/project",
        shell: "sh",
        file: `/tmp/${m[1]}.log`,
        metadata: {},
        time: { started: Date.now(), completed: Date.now() },
        exit: 0,
      },
    });
  }

  return send(res, 404, { message: `Not found: ${method} ${path}` });
}

const seed = newSession("Приветственный чат", { id: "free-model", providerID: "mock" });
seed.messages.push({ id: "msg_u_seed", type: "user", time: { created: now() - 60 }, text: "Привет! Что ты умеешь?" });
seed.messages.push({
  id: "msg_a_seed",
  type: "assistant",
  time: { created: now() - 59, completed: now() - 58 },
  agent: "build",
  model: { id: "free-model", providerID: "mock" },
  content: [
    { type: "text", text: "Я mock-сервер OpenCode. Могу читать файлы, запускать команды и писать код." },
    {
      type: "tool",
      id: "tool_seed",
      name: "bash",
      state: { status: "completed", input: { command: "uname -a" }, content: [{ type: "text", text: "Linux mock 6.0" }] },
      time: { created: now() - 58 },
    },
  ],
});

const server = http.createServer((req, res) => {
  let body = "";
  req.setEncoding("utf8");
  req.on("data", (chunk) => { body += chunk; });
  req.on("end", () => {
    try {
      handle(req, res, body);
    } catch (e) {
      try { send(res, 500, { message: String(e && e.message) }); } catch { /* ignore */ }
    }
  });
});

server.listen(PORT, "0.0.0.0", () => {
  console.log(`MOCK STARTED on 0.0.0.0:${PORT}`);
});
