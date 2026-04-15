# Chapter 4 — marty (AI Advertiser Agent)

## 1. Overview

**marty** is an **AI-powered conversational agent** for Walmart advertisers. Built on LangGraph and Azure OpenAI (via Walmart's Element LLM Gateway), it allows advertisers to query their campaign performance, item sales data, and other insights using natural language. It uses a state machine workflow to route queries to specialized sub-agents (Argo for performance metrics, Walsa for seller/items data).

- **Domain:** AI Conversational Interface for Advertisers
- **Tech:** Python 3.11, FastAPI, LangGraph 0.5+, LangChain 0.3+, Azure OpenAI (o3-mini)
- **WCNP Namespace:** `unified-ads`
- **Port:** 8080
- **DNS:** `marty.dev.labs-ads.walmart.com`

---

## 2. Architecture Diagram

```mermaid
graph LR
  subgraph marty["marty (FastAPI + LangGraph)"]
    CHAT_EP["/chatmarty\nPOST"]
    WEATHER_EP["/ask_weather\nGET"]
    HEALTH_EP["/health\nGET"]

    WORKFLOW["LangGraph State Machine\n(graph_setup.py)"]

    ID_MAPPER["IdMapperNode\nMap user_id → ADVERTISER/SELLER IDs"]
    ROUTER["RouterAgentNode\nKeyword routing\n(performance | selling)"]
    ARGO["ArgoAgentNode\nPerformance metrics\n(Q1/Q2 sales, ratings)"]
    WALSA["WalsaAgentNode\nSeller items data"]
    RESP_EVENT["ResponseEventNode\nEvent logging"]
    RESP_GEN["ResponseGeneratorNode\nLLM response compilation"]

    MCP_AGENT["MCP Agent\n(weather tools via MultiServerMCPClient)"]
    LLM_CLIENT["WalmartLLMAccess\n(Azure OpenAI o3-mini)"]

    CHAT_EP --> WORKFLOW
    WORKFLOW --> ID_MAPPER
    ID_MAPPER --> ROUTER
    ROUTER -->|"performance"| ARGO
    ROUTER -->|"selling/items"| WALSA
    ROUTER -->|"default"| RESP_GEN
    ARGO --> RESP_EVENT
    WALSA --> RESP_EVENT
    RESP_EVENT --> RESP_GEN
    RESP_GEN --> LLM_CLIENT

    WEATHER_EP --> MCP_AGENT
    MCP_AGENT --> LLM_CLIENT
  end

  AZURE_OPENAI["Azure OpenAI Gateway\n(Element LLM)\nhttps://wmtllmgateway.stage.walmart.com"]
  WEATHER_MCP["Weather MCP Server\n(sample-weather-forecast-mcp)"]
  DARPA["darpa\n(Campaign data — future)"]
  SR["Service Registry\n(RSA-signed auth)"]

  LLM_CLIENT -->|"HTTPS POST (RSA signed headers)"| AZURE_OPENAI
  MCP_AGENT -->|"HTTP MCP protocol"| WEATHER_MCP
  CHAT_EP -.->|"future integration"| DARPA
  CHAT_EP -->|"Auth via"| SR
```

---

## 3. API / Interface

| Method | Path | Auth | Request | Response |
|--------|------|------|---------|----------|
| POST | `/chatmarty` | Service Registry | `{user_id: str, message: str}` | `{response: str}` |
| GET | `/ask_weather` | Service Registry | `?city=<city>` | `{weather_data}` |
| POST | `/ask_echo` | Service Registry | Any JSON body | Echo body |
| GET | `/health` | None (local) | — | `{status: "ok"}` |

**Service Registry Headers (prod):**
- `WM_CONSUMER.ID` — Consumer ID
- `WM_SVC.NAME: APM0009468-MARTY`
- `WM_SEC.AUTH_SIGNATURE` — RSA-PKCS1-SHA256 signed
- `WM_SEC.TIMESTAMP`

---

## 4. Data Model (In-Memory State)

```mermaid
erDiagram
  CHAT_SESSION ||--|| AGENT_STATE : "holds"
  AGENT_STATE ||--o{ AGENT_DATA : "collects"
  AGENT_STATE ||--o{ RESPONSE_EVENT : "logs"

  CHAT_REQUEST {
    string user_id
    string message
  }

  AGENT_STATE {
    string user_id
    map internal_ids
    string input
    string next_agent
    map agent_data
    list agent_conversations
    string final_response
    list chat_history
    list response_events
    any performance_data
    any items_sold
  }

  AGENT_DATA {
    string name
    string data
  }

  ID_TYPE {
    enum ADVERTISER
    enum SELLER
    enum UNKNOWN
  }
```

---

## 5. LangGraph Workflow

```mermaid
graph TD
  START(["START"]) --> ID_MAPPER["IdMapperNode\nMap user_id to internal IDs"]
  ID_MAPPER --> ROUTER["RouterAgentNode\nKeyword-based routing"]

  ROUTER -->|"input contains 'performance' or 'stats'"| ARGO["ArgoAgentNode\nPerformance metrics"]
  ROUTER -->|"input contains 'selling' or 'items'"| WALSA["WalsaAgentNode\nSeller items"]
  ROUTER -->|"default"| RESP_GEN["ResponseGeneratorNode\nCompile final answer"]

  ARGO --> RESP_EVENT["ResponseEventNode\nLog interaction"]
  WALSA --> RESP_EVENT
  RESP_EVENT --> RESP_GEN

  RESP_GEN --> END(["END\nreturn final_response"])
```

---

## 6. Inter-Service Dependencies

```mermaid
graph TD
  advertiser["Advertiser\n(browser/client)"]
  marty["marty"]
  llm_gateway["Azure OpenAI LLM Gateway\n(Element AI)"]
  weather_mcp["Weather MCP Server\n(sample MCP)"]
  service_registry["Service Registry\n(auth validation)"]

  advertiser -->|"POST /chatmarty {user_id, message}"| marty
  marty -->|"HTTPS POST (RSA signed)\n/wmtllmgateway/v1/openai\nmodel: o3-mini"| llm_gateway
  marty -->|"HTTP MCP transport\n/mcp (weather tools)"| weather_mcp
  marty -->|"Auth headers validation"| service_registry
```

---

## 7. Configuration

| Config Key | Description |
|-----------|-------------|
| `weather_mcp_url` | MCP server URL for weather tools |
| `/etc/secrets/dev/llmgateway-api-key` | API key for LLM Gateway |
| `SSL_CERT_FILE` | Walmart CA bundle path |
| `APM0009468-MARTY` | Service Registry application key |
| `runtime.context.appName` | `marty` |

**Akeyless secret path:** `/Prod/WCNP/homeoffice/GEC-LabsAccessWPA` → `dev/llmgateway-api-key`

---

## 8. Example Scenario — Advertiser Queries Performance

```mermaid
sequenceDiagram
  actor Advertiser
  participant MARTY as marty /chatmarty
  participant GRAPH as LangGraph State Machine
  participant ID_MAP as IdMapperNode
  participant ROUTER as RouterAgentNode
  participant ARGO as ArgoAgentNode
  participant LLM as Azure OpenAI (o3-mini)

  Advertiser->>MARTY: POST /chatmarty\n{user_id: "adv-001", message: "How is my campaign performance?"}

  MARTY->>GRAPH: Invoke workflow {user_id, input}

  GRAPH->>ID_MAP: Map user_id "adv-001"
  ID_MAP-->>GRAPH: {internal_ids: {ADVERTISER: "a-12345", SELLER: "s-67890"}}

  GRAPH->>ROUTER: Route message
  Note over ROUTER: Keyword: "performance" detected
  ROUTER-->>GRAPH: next_agent = "argo_agent"

  GRAPH->>ARGO: Execute with advertiser_id="a-12345"
  ARGO-->>GRAPH: {performance_data: {Q1_sales: "$45K", Q2_sales: "$62K", ROAS: 3.2}}

  GRAPH->>LLM: Compile response using performance_data\n"Your Q2 sales of $62K represent 38% growth..."
  LLM-->>GRAPH: final_response (natural language)

  GRAPH-->>MARTY: {final_response: "Your Q2 sales..."}
  MARTY-->>Advertiser: 200 {response: "Your Q2 sales of $62K represent 38% growth vs Q1..."}
```
