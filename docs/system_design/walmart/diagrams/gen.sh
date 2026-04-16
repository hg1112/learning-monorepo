#!/bin/bash
set -e
cd "$(dirname "$0")"

strip() { sed '/^```/d; /^$/d'; }

echo "Generating d1 — 4-plane architecture..."
gemini -p "Output ONLY raw SVG code, no markdown fences, no explanation, starting with <svg. Size 1200x600 white background Arial font flat design. Four horizontal colored bands stacked vertically each 115px tall with 12px gaps. Each band has a vertical label on left side and service boxes inside. Band 1 y=10 fill=#e8f0fe stroke=#0055aa: label Advertiser Plane, 3 white rounded boxes: marty (AI Agent LangGraph), darpa (Campaign CRUD 121 routes), sp-ace (AB Experiments). Band 2 y=137 fill=#e8f5e9 stroke=#2e7d32: label Indexing Pipeline, boxes: radpub-v2, arrow labeled Kafka, Solr Vespa, Cassandra. Band 3 y=264 fill=#fbe9e7 stroke=#bf360c: label Ad Serving Core, boxes: swag, arrow, midas-spade, three fan-out arrows to midas-spector abram davinci. Band 4 y=391 fill=#f3e5f5 stroke=#6a1b9a: label Click and Budget, boxes: sp-crs (dedup TTL 900s), arrow, sp-buddy (Redis pacing). Right side bold text: less than 50ms p99. Downward arrows between bands center." 2>&1 | strip > d1.svg
echo "  d1: $(wc -c < d1.svg) bytes"

echo "Generating d2 — Ad serving sequence..."
gemini -p "Output ONLY raw SVG code no markdown no explanation starting with <svg. Size 1200x600 white background Arial. Swimlane sequence diagram. 6 lanes each 180px wide labeled at top: Shopper midas-spade midas-spector abram davinci sp-buddy. Vertical dashed grey separators. Blue horizontal arrows with arrowheads and labels: (1) Shopper to spade search request y=120, (2) spade fans out to spector abram davinci sp-buddy simultaneously y=170 label parallel async, (3) abram self-box query Solr 100 candidates y=280, (4) abram to davinci score vectors y=340, (5) davinci self-box 4-level cache H100 y=390, (6) abram self-box TSP auction y=450, (7) spade back to Shopper AdResponse y=530. Bottom text: approx 50ms wall clock in italic grey." 2>&1 | strip > d2.svg
echo "  d2: $(wc -c < d2.svg) bytes"

echo "Generating d3 — Manual vs TROAS chart..."
gemini -p "Output ONLY raw SVG code no markdown no explanation starting with <svg. Size 1200x600 white background Arial. Line chart. X axis 0 to 30 days labeled Days. Y axis 0 to 6 labeled ROAS. Horizontal dashed red line at y=4 labeled Target ROAS 4.0. Two lines: (1) Manual Bidding grey dashed flat at ROAS 2.0 from day 1 to 30 labeled missed opportunity, (2) TROAS blue solid line starting at 1.5 curving up steeply reaching 4.0 by day 8 then stabilizing flat at 4.0. Light blue shading between the two lines labeled revenue gap. Clean chart no gridlines light axis lines title at top: Manual Bidding vs TROAS Performance." 2>&1 | strip > d3.svg
echo "  d3: $(wc -c < d3.svg) bytes"

echo "Generating d4 — TROAS service map..."
gemini -p "Output ONLY raw SVG code no markdown no explanation starting with <svg. Size 1200x600 white background Arial. Horizontal flow diagram. Six blue rounded rect service boxes evenly spaced at y=200 each 150px wide 60px tall white fill blue border. Labels: darpa, radpub-v2, midas-spade, midas-spector, abram, finalCPC. Arrows between each box. Below each arrow a small grey italic label: Kafka campaign-events, Solr Vespa index, gRPC troasNode, TroasBidParams, clamp 0.20 to 5.00. Above each box a small orange tag showing the TROAS field: targetRoas, biddingStrategy=TROAS, troas flag+bbid, 3 Caffeine caches, PRPC formula. Title at top: TROAS Data Flow Across 6 Services." 2>&1 | strip > d4.svg
echo "  d4: $(wc -c < d4.svg) bytes"

echo "Generating d5 — Phase state machine..."
gemini -p "Output ONLY raw SVG code no markdown no explanation starting with <svg. Size 1200x600 white background Arial. State machine diagram. Five rounded rect nodes arranged: INITIALIZATION top-left blue, LEARNING center-left amber, LEARNING_PAUSED center-right red, OPTIMIZATION center green, TRANSITION bottom-right purple. Arrows: INITIALIZATION to LEARNING, LEARNING to LEARNING_PAUSED bidirectional labeled budget depleted and budget restored, LEARNING to OPTIMIZATION labeled sufficient pCVR plus pVPC data, OPTIMIZATION to TRANSITION labeled experiment applied. Small formula text below each node: INITIALIZATION roasBid=bidPrime x dpf x pf, LEARNING Thompson Sampling, LEARNING_PAUSED no bid emitted, OPTIMIZATION pRPCBid=(pVPC x pCVR)/(targetRoas x beta), TRANSITION roasBid wind-down. Clean flat design." 2>&1 | strip > d5.svg
echo "  d5: $(wc -c < d5.svg) bytes"

echo "Generating d6 — Thompson Sampling beta curves..."
gemini -p "Output ONLY raw SVG code no markdown no explanation starting with <svg. Size 1200x600 white background Arial. Statistical chart showing 4 smooth Beta distribution curves. X axis labeled Bid Price from 0.20 to 0.80. Y axis labeled Probability Density 0 to 3.5. Four curves each with a vertical dashed line at their price: (1) Beta(4,8) at 0.20 blue narrow peak at 0.33, (2) Beta(5,9) at 0.40 green narrow peak, (3) Beta(6,7) at 0.60 orange rightward lean tallest, (4) Beta(2,6) at 0.80 red wide flat high uncertainty. Legend top right. Two annotation boxes bottom: alpha up tighter distribution exploit, beta up shifts left avoid. Title: LEARNING Phase - Thompson Sampling." 2>&1 | strip > d6.svg
echo "  d6: $(wc -c < d6.svg) bytes"

echo "Generating d7 — Bid formula decision tree..."
gemini -p "Output ONLY raw SVG code no markdown no explanation starting with <svg. Size 1200x600 white background Arial monospace font for formulas. Top-down decision tree. Root node at top center: TroasBidParams branch on phase. Five branches below each colored: INITIALIZATION blue leaf roasBid=bidPrime x dpf x pf, LEARNING amber leaf Thompson Sampling sample Beta select max price, LEARNING_PAUSED red leaf No bid item excluded, OPTIMIZATION-PRPC green leaf pRPCBid=(pVPC x pCVR)/(targetRoas x beta) troasBid=w1 x roasBid + w2 x pRPCBid, OPTIMIZATION-Unified purple leaf gain=coeff x (pCVR-mu)/sigma unifiedBid=roasBid x (1+clip(gain)). All four non-paused leaves merge into bottom node: finalCPC=clamp([0.20 to 5.00] 4dp) in dark box." 2>&1 | strip > d7.svg
echo "  d7: $(wc -c < d7.svg) bytes"

echo "Generating d8 — Config resolution waterfall..."
gemini -p "Output ONLY raw SVG code no markdown no explanation starting with <svg. Size 1200x600 white background Arial. Vertical waterfall with 4 levels stacked top to bottom getting slightly wider. Level 1 blue fill #e8f0fe: Request Params troasNode - sub-items AdGroupBidParamsCache ItemBidParamsCache with fallback (adGroupId,itemId) to (adGroupId,0) to (0,0) DayPartBidParamsCache. Level 2 green fill #e8f5e9: Placement Config CSV Azure Blob newly launched campaigns. Level 3 amber fill #fff3e0: CCM Defaults troas.default.* keys. Level 4 grey fill #f5f5f5: Hard-coded Java defaults bidBound 0.20 to 5.00. Red gate badge between Level 1 and 2: checkVersion() mismatch goes to Level 3. Left side arrow labeled first match wins pointing down." 2>&1 | strip > d8.svg
echo "  d8: $(wc -c < d8.svg) bytes"

echo "Generating d9 — TROAS E2E sequence..."
gemini -p "Output ONLY raw SVG code no markdown no explanation starting with <svg. Size 1200x600 white background Arial. Swimlane sequence diagram 7 lanes: darpa, radpub-v2, midas-spade, midas-spector, abram, Triton H100, Shopper. Numbered arrows: 1 darpa to Kafka to radpub-v2 campaign-events biddingStrategy=TROAS y=100, 2 Shopper to spade search y=150, 3 spade to spector gRPC BidRequest+troasNode y=200, 4 spector self-box load 3 Caffeine caches y=250, 5 spector to abram AuctionRequest+TroasBidParams y=310, 6 abram to Triton pCVR+pVPC inference y=370, 7 abram self-box finalCPC=clamp y=430, 8 abram to spade to Shopper Top-N ads y=490. Blue arrows grey dashed lane separators." 2>&1 | strip > d9.svg
echo "  d9: $(wc -c < d9.svg) bytes"

echo "Generating d10 — Experiment state machine..."
gemini -p "Output ONLY raw SVG code no markdown no explanation starting with <svg. Size 1200x600 white background Arial. State machine top half. Nodes: SCHEDULED grey, IN_PROGRESS blue, LEARNING_PAUSED red, READY_TO_REVIEW amber, APPLIED green terminal double-border, ENDED grey terminal, CANCELED dark red terminal. Arrows: SCHEDULED to IN_PROGRESS, IN_PROGRESS to LEARNING_PAUSED bidirectional budget depleted restored, IN_PROGRESS to READY_TO_REVIEW N days elapsed, READY_TO_REVIEW to APPLIED advertiser applies, READY_TO_REVIEW to ENDED declines, SCHEDULED IN_PROGRESS READY_TO_REVIEW all have arrow to CANCELED. Bottom half: 3-step horizontal flow labeled On APPLY: box Control migrated to TROAS arrow Test campaign archived arrow phase=OPTIMIZATION begins." 2>&1 | strip > d10.svg
echo "  d10: $(wc -c < d10.svg) bytes"

echo "All done."
ls -lh d*.svg
