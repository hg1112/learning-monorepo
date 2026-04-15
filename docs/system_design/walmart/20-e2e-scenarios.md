# End-to-End Scenarios — Sponsored Products Platform

## Overview

These scenarios trace complete business flows across all 16 services, from advertiser action through shopper interaction. Each scenario includes the full service call chain, data lineage, and failure handling. Updated to cover the full 19-repo picture including the Feast feature pipeline and Triton inference layer.

---

## Scenario 1: Advertiser Launches New Campaign → First Ad Served

**Business Flow:** An advertiser creates a new Sponsored Product campaign via the AI agent, the campaign gets indexed, and the first shopper sees the ad.

### Phase A — Campaign Creation

```mermaid
sequenceDiagram
    actor Advertiser
    participant marty_fe as marty-fe (AI Agent UI)
    participant marty as marty (LangGraph)
    participant llm as LLM Gateway (Azure OpenAI)
    participant darpa as darpa (Campaign Mgmt)

    Advertiser->>marty_fe: POST /chatmarty\n"Create SP campaign for item 12345\n$50/day budget, Electronics"
    marty_fe->>marty: POST /chatmarty (forward)
    marty->>marty: IdMapperNode: map item "12345" → item_id
    marty->>marty: RouterAgentNode: route to ArgoAgentNode
    marty->>llm: POST /v1/chat/completions (o3-mini)\nextract: item_id, budget, category
    llm-->>marty: structured campaign params
    marty->>darpa: POST /api/v1/campaigns\n{name, type:SP, daily_budget:50, items:[12345]}
    darpa->>darpa: validate + persist to Azure SQL
    darpa-->>marty: {campaign_id: 9001, status: DRAFT}
    marty->>darpa: POST /api/v1/campaigns/9001/adgroups
    darpa-->>marty: {ad_group_id: 801}
    marty->>darpa: POST /api/v1/adgroups/801/additems
    darpa-->>marty: {ad_item_id: 5001}
    marty->>llm: generate success response
    llm-->>marty: "Campaign created! ID 9001..."
    marty-->>marty_fe: SSE stream: "Campaign created! ID 9001..."
    marty_fe-->>Advertiser: display confirmation
```

### Phase B — Campaign Indexing

```mermaid
sequenceDiagram
    participant darpa as darpa
    participant kafka as Kafka (campaign-events)
    participant radpub as radpub-v2
    participant iro as IRO (Item Enrichment)
    participant solr as Solr / Vespa
    participant cassandra as Cassandra (eligibility)

    darpa->>kafka: publish CampaignMessage\n{campaign_id:9001, ad_item_id:5001, event:ACTIVATED}
    kafka->>radpub: consume processCampaignMessage-in-0
    radpub->>iro: GET /item/12345 (enrich with title, category, image)
    iro-->>radpub: {title, price, category, imageUrl}
    radpub->>radpub: build AdItemSolrDoc\n(merge campaign + item + IRO data)
    radpub->>solr: SolrClient.add(AdItemSolrDoc)
    solr-->>radpub: 200 OK
    radpub->>solr: SolrClient.commit()
    radpub->>cassandra: INSERT ad_item_eligibility\n(campaign_id, ad_item_id, eligible=true)
    Note over radpub: ad is now searchable
```

### Phase C — Shopper Sees Ad

```mermaid
sequenceDiagram
    actor Shopper
    participant spade as midas-spade
    participant buddy as sp-buddy
    participant spector as midas-spector (gRPC)
    participant abram as abram
    participant solr as Solr/Vespa
    participant sparkle as sparkle
    participant davinci as davinci
    participant feast as sp-adserver-feast
    participant cassandra as Cassandra
    participant triton as element-davinci (Triton H100)

    Shopper->>spade: POST /v3/sp/getAds\n{query:"laptop", position:SEARCH_TOP, limit:4}
    par parallel async fan-out
        spade->>spector: gRPC SpBidsV2Rpc.GetBids\n{query, items:[...]}
        spade->>abram: POST /v3/sp/search\n{query, context}
        spade->>davinci: POST /v3/vector\n{item_ids:[...]}
        spade->>buddy: GET /v1/budgets/batch
    end

    abram->>solr: query("laptop", SP, limit:100)
    solr-->>abram: 85 candidate ad items

    par scoring
        abram->>sparkle: POST /v3/scores\n{candidates:[85 items]}
        sparkle->>feast: getOnlineFeaturesAsyncProto(item_ids, "ad_relevance_model_features")
        feast->>cassandra: SELECT feature_name,value FROM midas.item_features\nWHERE entity_key IN (85 item_ids)
        cassandra-->>feast: feature rows
        feast-->>sparkle: OnlineFeaturesResponse proto

        davinci->>cassandra: L3 cache GET (vector keys)
        cassandra-->>davinci: partial hit (60/85)
        davinci->>triton: gRPC ModelInfer ensemble_ttb_emb\n{25 missing items}
        triton-->>davinci: float[25][512] embeddings
        davinci->>cassandra: L3 write-back (25 new vectors)
    end

    sparkle-->>abram: relevance scores [0.0, 1.0]
    abram->>abram: TSP auction:\n  score = pCTR × bid × relevance\n  2nd price = next_highest_bid + $0.01
    spector-->>spade: bid amounts per item
    buddy-->>spade: budget statuses (campaign 9001: ACTIVE)
    abram-->>spade: ranked top-4 ads

    spade-->>Shopper: AdResponse\n[4 ads with impression/click tracking URLs]
```

### Phase D — Shopper Clicks

```mermaid
sequenceDiagram
    actor Shopper
    participant crs as sp-crs (Click Redirect)
    participant cassandra as Cassandra (dedup)
    participant kafka as Kafka (SOX cluster)
    participant buddy as sp-buddy

    Shopper->>crs: GET /track?adId=5001&clickId=abc123&bid=0.45
    crs->>cassandra: SELECT click_id FROM click_dedup\nWHERE click_id='abc123' TTL 900s
    cassandra-->>crs: NOT FOUND (new click)
    crs->>cassandra: INSERT click_id='abc123' USING TTL 900
    crs->>kafka: publish ClickEvent\n{ad_item_id:5001, campaign_id:9001, amount:$0.45}
    kafka->>buddy: consume click-event
    buddy->>buddy: UPDATE daily_spend += $0.45\ncheck daily_limit ($50)
    crs-->>Shopper: 302 redirect → walmart.com/product/12345
```

---

## Scenario 2: A/B Experiment for New Budget Pacing Algorithm

**Business Flow:** DS team creates an experiment to test a new budget pacing strategy across 10% of campaigns.

```mermaid
sequenceDiagram
    participant ops as DS Team (Ops)
    participant ace as sp-ace
    participant azure_sql as Azure SQL
    participant buddy as sp-buddy
    participant spade as midas-spade

    ops->>ace: POST /v1/budget/experiments\n{name:"pacing_v2_test", traffic_split:0.10,\nvariant:"pacing_v2", control:"pacing_v1"}
    ace->>azure_sql: INSERT experiment (DRAFT)
    ace-->>ops: {experiment_id:77, status:DRAFT}
    ops->>ace: POST /v1/budget/experiments/77/review
    ace->>azure_sql: UPDATE status=UNDER_REVIEW
    ops->>ace: PUT /v1/budget/experiments/77/activate
    ace->>azure_sql: UPDATE status=RUNNING
    ace->>buddy: gRPC BuddyClient.assignBuckets\n(campaign_ids → variant:pacing_v2 | control:pacing_v1)
    buddy->>azure_sql: UPDATE campaign_budget SET bucket_key=... (10% → pacing_v2)

    Note over spade,buddy: At serving time...
    spade->>ace: GET /v1/adserving/experiments/active
    ace-->>spade: [experiment:77, bucket_key → variant_map]
    spade->>buddy: GET /v1/budgets/{campaignId}?bucket=pacing_v2
    buddy->>buddy: apply pacing_v2 algorithm for 10% traffic
```

---

## Scenario 3: ML Vector Cache Miss — Full 4-Level Chain

**Business Flow:** A newly listed item (no cached vectors) enters an auction, triggering full L1→L2→L3→L4 cache miss chain.

```mermaid
sequenceDiagram
    participant spade as midas-spade
    participant davinci as davinci (WebFlux)
    participant l1 as L1: Caffeine Cache (in-process)
    participant l2 as L2: MeghaCache (distributed)
    participant l3 as L3: Cassandra (multi-region)
    participant triton as L4: element-davinci (Triton H100)
    participant blob as Azure Blob (model weights)

    Note over triton,blob: On startup (once)
    triton->>blob: load models from wasbs://ss-inference-models/prod_inference_models/
    blob-->>triton: ensemble_ttb_emb, relevance_v1, universal_r1, multimodal_v2...

    spade->>davinci: POST /v3/vector {item_id: "new_item_999"}
    davinci->>l1: GET vector("new_item_999")
    l1-->>davinci: MISS (not in local cache)
    davinci->>l2: GET vector("new_item_999")
    l2-->>davinci: MISS (not in distributed cache)
    davinci->>l3: CQL SELECT vectors WHERE item_id='new_item_999'
    l3-->>davinci: MISS (new item, no row)
    davinci->>triton: gRPC ModelInfer\n{model:"ensemble_ttb_emb",\n inputs:[{item_text, item_title, item_desc}]}
    triton->>triton: tokenizer → TTB encoder → embedding pool
    triton-->>davinci: float[512] embedding (H100, ~8ms)
    davinci->>l3: INSERT INTO vector_cache\n(item_id, embedding, ts) USING TTL 86400
    davinci->>l2: SET vector("new_item_999") TTL=3600
    davinci->>l1: PUT vector("new_item_999") (Caffeine, max=10k)
    davinci-->>spade: VectorResponse {item_id, embedding[512]}

    Note over spade,davinci: Next request for same item: L1 HIT (~10μs)
```

---

## Scenario 4: Budget Exhaustion Mid-Day

**Business Flow:** A campaign exhausts its $50 daily budget at 2pm, triggering automatic pause and midnight rollover.

```mermaid
sequenceDiagram
    participant kafka as Kafka (click-events SOX)
    participant buddy as sp-buddy (Kafka Streams)
    participant azure_sql as Azure SQL
    participant darpa as darpa
    participant spector as midas-spector

    loop Every click event
        kafka->>buddy: consume ClickEvent {campaign_id:9001, amount:$0.45}
        buddy->>buddy: Kafka Streams: accumulate daily_spend
        buddy->>azure_sql: UPDATE daily_campaign_budget\nSET daily_spend = daily_spend + 0.45\nWHERE campaign_id = 9001
    end

    Note over buddy: daily_spend >= daily_limit ($50)
    buddy->>azure_sql: UPDATE campaign_budget_status\nSET status='BUDGET_EXHAUSTED'\nWHERE campaign_id=9001
    buddy->>darpa: POST /internal/campaigns/9001/pause\n{reason: BUDGET_EXHAUSTED}
    darpa->>azure_sql: UPDATE campaign SET status='PAUSED'
    darpa->>kafka: publish CampaignMessage {event: PAUSED}

    Note over spector: At next serving request
    spector->>buddy: GET /v1/budgets/9001
    buddy-->>spector: {status: BUDGET_EXHAUSTED}
    spector->>spector: exclude campaign_id=9001 from bid response

    Note over buddy: At midnight (00:00 UTC)
    buddy->>buddy: scheduled rollover job
    buddy->>azure_sql: UPDATE daily_campaign_budget\nSET daily_spend=0, status='ACTIVE'\nWHERE date < TODAY
    buddy->>darpa: POST /internal/campaigns/9001/resume
    darpa->>azure_sql: UPDATE campaign SET status='ACTIVE'
    darpa->>kafka: publish CampaignMessage {event: ACTIVATED}
```

---

## Scenario 5: Advertiser Queries Performance via AI Agent

**Business Flow:** Advertiser asks the AI agent "How are my campaigns performing this week?" in natural language.

```mermaid
sequenceDiagram
    actor Advertiser
    participant marty_fe as marty-fe
    participant marty as marty (LangGraph)
    participant llm as LLM Gateway (Azure OpenAI o3-mini)
    participant darpa as darpa (Reports API)
    participant azure_sql as Azure SQL (metrics)

    Advertiser->>marty_fe: POST /chatmarty\n"How are my campaigns performing this week?"
    marty_fe->>marty: POST /chatmarty (forward + stream)
    marty->>marty: IdMapperNode:\n  extract advertiser_id from session
    marty->>marty: RouterAgentNode:\n  classify → ArgoAgentNode (reporting query)
    marty->>llm: classify intent + extract params\n{intent: "campaign_report", period: "this_week",\nadvertiser_id: 123}
    llm-->>marty: structured query params
    marty->>darpa: GET /api/v1/campaigns?advertiser_id=123
    darpa-->>marty: [{campaign_id:9001, name:"Laptops Q1"}, ...]
    marty->>darpa: GET /api/v1/campaigns/9001/reports\n?start=2026-03-17&end=2026-03-24
    darpa->>azure_sql: SELECT impressions, clicks, spend, conversions\nFROM campaign_metrics\nWHERE campaign_id=9001 AND date BETWEEN ...
    azure_sql-->>darpa: [{date, impressions, clicks, spend, conversions}]
    darpa-->>marty: performance data JSON
    marty->>llm: POST /v1/chat/completions\n"Summarize this campaign data in plain English:\n{performance_data}"
    llm-->>marty: "Your 'Laptops Q1' campaign had 45,000 impressions,\n320 clicks (0.71% CTR), spent $38.20, and drove\n12 conversions ($3.18 CPA). Performance is strong."
    marty-->>marty_fe: SSE stream response
    marty_fe-->>Advertiser: display formatted summary
```

---

## Scenario 6: Feature Materialization Pipeline (Batch)

**Business Flow:** Nightly batch job computes fresh ML features from BigQuery and materializes them to Cassandra for online serving.

```mermaid
sequenceDiagram
    participant looper as Looper (scheduler)
    participant concord as Concord (CI/CD)
    participant dataproc as Google Dataproc (PySpark)
    participant bq_src as BigQuery (ads event source)
    participant gcs as GCS (Parquet staging)
    participant bq_out as BigQuery (feature output)
    participant cass as Cassandra (midas keyspace)
    participant feast_svc as sp-adserver-feast

    looper->>concord: trigger batch_feature_generation (LOGICAL_TIME=2026-03-24)
    concord->>dataproc: create cluster\n(3 cores driver, 28 executors × 20GB)
    dataproc->>bq_src: spark.read.bigquery("ads_events_2026_03_24")
    bq_src-->>dataproc: 200M+ ad event rows
    dataproc->>dataproc: @batch_feature_generator:\n  compute item_features, cnsldv2_features,\n  items_rate, pvpc, item_quality features
    dataproc->>gcs: spark.write.parquet("gs://.../staging/2026-03-24/")
    dataproc->>bq_out: spark.write.bigquery("item_features_20260324")
    dataproc->>cass: fs.materialize(feature_views, end_date=LOGICAL_TIME)\n  via wmart-efs-featurestore-sdk\n  TTL = 15778476s (~182 days)\n  100 partitions
    cass-->>dataproc: materialization complete
    concord->>dataproc: delete cluster
    Note over feast_svc,cass: Online serving now uses fresh features
    feast_svc->>cass: CQL SELECT feature_name,value FROM midas.item_features\nWHERE entity_key=? (uses new data)
```

---

## Cross-Cutting Data Lineage

```mermaid
flowchart LR
    subgraph Creation["Campaign Creation"]
        A1["Advertiser"] -->|"POST /chatmarty"| A2["marty/marty-fe"]
        A2 -->|"REST"| A3["darpa\nAzure SQL"]
        A3 -->|"Kafka: campaign-events"| A4["radpub-v2\nSolr/Vespa"]
    end

    subgraph FeaturePipeline["Feature Pipeline (Nightly)"]
        B1["BigQuery\nAds Events"] -->|"PySpark"| B2["element-adserver-feast\nDataproc"]
        B2 -->|"Feast SDK"| B3["Cassandra\nmidas keyspace\nTTL 182d"]
    end

    subgraph Serving["Ad Serving (Real-time, <100ms)"]
        C1["Shopper"] -->|"search"| C2["midas-spade\norchestrator"]
        C2 -->|"gRPC"| C3["midas-spector\nbids"]
        C2 -->|"REST"| C4["abram\nTSP auction"]
        C4 -->|"query"| C5["Solr/Vespa"]
        C4 -->|"Feast SDK"| C6["sp-adserver-feast"]
        C6 -->|"CQL"| B3
        C4 -->|"REST"| C7["sparkle\nscoring"]
        C2 -->|"REST"| C8["davinci\n4-level cache"]
        C8 -->|"gRPC Triton"| C9["element-davinci\nH100 inference"]
    end

    subgraph ClickBudget["Click & Budget"]
        D1["Shopper clicks"] -->|"GET /track"| D2["sp-crs\ndedup"]
        D2 -->|"Kafka SOX"| D3["sp-buddy\nbudget pacing"]
        D3 -->|"Azure SQL"| D4["budget state"]
    end

    subgraph Experimentation["Experimentation"]
        E1["DS Team"] -->|"POST /experiments"| E2["sp-ace"]
        E2 -->|"gRPC"| E3["sp-buddy\nbucket assignment"]
        E3 -.->|"bucket_key"| C2
    end

    A4 -->|"ad candidates"| C5
    D3 -.->|"budget eligibility"| C4
    C8 -.->|"miss → L4"| C9
```

---

## Failure Scenarios & Mitigation

| Failure | Impact | Detection | Mitigation |
|---------|--------|-----------|------------|
| element-davinci pod down | Davinci L4 cache misses → fallback to L3 (Cassandra) | Triton /v2/health/ready | davinci circuit-breaker; serve from L3 cache |
| Cassandra partition unavailable | Feature fetch failures → sparkle/davinci errors | CQL timeout | sp-adserver-feast fallback: empty features → default scores |
| Solr index lag (radpub-v2 delay) | New campaigns not served | Solr commit latency metric | radpub-v2 commit retry; DLQ for failed messages |
| sp-buddy unavailable | Serving continues without budget filter | Health endpoint | midas-spade: fail-open for budget (assume ACTIVE) |
| Kafka SOX cluster down | Click events not processed | Consumer lag metric | sp-crs: local buffer + replay endpoint |
| darpa Oracle → Azure SQL failover | Campaign CRUD degraded | JDBC connection pool | Hibernate retry + Circuit breaker |
| LLM Gateway timeout | marty chat hangs | OpenAI response timeout (60s) | LangGraph timeout node → graceful error response |
| Daily rollover job failure | Campaigns remain exhausted after midnight | Alerting via xMatters | Manual re-trigger via sp-buddy /v1/housekeeping endpoint |

---

*Generated by Wibey CLI — `claude-sonnet-4-6-thinking` — March 2026*
