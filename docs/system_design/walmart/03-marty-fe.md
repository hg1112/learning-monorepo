# Chapter 3 — marty-fe (AI Advertiser Agent — Frontend Interface)

## 1. Overview

**marty-fe** is the **frontend-facing variant** of the marty AI agent. As of the current codebase analysis, `marty-fe` is a duplicate clone of `marty` (shares identical git origin: `https://gecgithub01.walmart.com/labs-ads/marty.git` and commit history). This suggests it is intended to serve as the user-facing deployment of the AI agent layer, potentially with a distinct deployment configuration, feature flags, or routing rules for production front-end traffic vs. internal/API traffic.

**Important note:** All technical implementation details are identical to `marty` (Chapter 4). This chapter documents the intended differentiation and any deployment-specific notes.

---

## 2. Current State vs. Intended Architecture

```mermaid
graph LR
  subgraph current["Current State (Identical Codebases)"]
    marty_repo["marty repo\n(labs-ads/marty.git)"]
    marty_fe_repo["marty-fe repo\n(clone of labs-ads/marty.git)"]
    marty_repo -.->|"same code"| marty_fe_repo
  end

  subgraph intended["Intended Architecture (Assumed)"]
    marty_be["marty (Backend)\nInternal API / Admin tooling\nService-to-service calls"]
    marty_fe_svc["marty-fe (Frontend)\nAdvertiser-facing chatbot\nPublic-facing endpoints\nUI/UX integration"]
    marty_be <-->|"API calls"| marty_fe_svc
  end
```

---

## 3. Differentiation Factors (Assumed / Expected)

| Aspect | marty | marty-fe |
|--------|-------|----------|
| **Traffic source** | Internal / service-to-service | Advertiser-facing (UI) |
| **Auth** | Service Registry (server-to-server) | Service Registry (user-delegated) |
| **WCNP namespace** | `unified-ads` | `unified-ads` (TBD) |
| **Rate limits** | Internal SLA | Consumer-grade SLA |
| **Feature flags** | Full feature set | User-safe feature set |
| **MCP tools** | Full toolchain | Filtered tools |

---

## 4. Architecture (Shared with marty)

```mermaid
graph LR
  subgraph marty_fe["marty-fe (FastAPI + LangGraph — same as marty)"]
    CHAT_EP["/chatmarty\nPOST"]
    HEALTH_EP["/health\nGET"]
    WORKFLOW["LangGraph State Machine"]
    LLM_CLIENT["Azure OpenAI Gateway"]

    CHAT_EP --> WORKFLOW
    WORKFLOW --> LLM_CLIENT
  end

  ADVERTISER_UI["Advertiser Portal\n(Browser / Mobile)"]
  LLM_GATEWAY["Azure OpenAI\n(Element LLM Gateway)"]

  ADVERTISER_UI -->|"POST /chatmarty"| CHAT_EP
  LLM_CLIENT -->|"HTTPS RSA-signed"| LLM_GATEWAY
```

---

## 5. API / Interface

Identical to marty (see Chapter 4):
- `POST /chatmarty`
- `GET /ask_weather`
- `GET /health`

---

## 6. Data Flow (Identical to marty)

```mermaid
sequenceDiagram
  actor Advertiser as Advertiser\n(via UI)
  participant MARTY_FE as marty-fe
  participant GRAPH as LangGraph Workflow
  participant LLM as Azure OpenAI

  Advertiser->>MARTY_FE: POST /chatmarty\n{user_id, message: "Show me my top items"}
  MARTY_FE->>GRAPH: Invoke state machine
  GRAPH->>LLM: Compile answer via o3-mini
  LLM-->>GRAPH: Natural language response
  GRAPH-->>MARTY_FE: final_response
  MARTY_FE-->>Advertiser: 200 {response: "Your top items are..."}
```

---

## 7. Notes

- **Code parity:** Until divergence occurs, marty-fe mirrors marty exactly
- **Future differentiation:** The FE variant is expected to handle UI-specific concerns (session management, persona customization, chat history persistence)
- **Recommendation:** Consider splitting `marty-fe` into its own dedicated repo once it diverges with FE-specific logic (React frontend, WebSocket support, or BFF pattern)

For all implementation details, refer to **Chapter 4 — marty**.
