# Pinterest Staff MLE — Study Plan
**May 21 – June 24, 2026 | 2+ hrs/day**

**Role:** Staff Machine Learning Engineer, Programmatic Ads  
**Target:** End of June interview

**Setup:** Download [Criteo CTR dataset sample](https://www.kaggle.com/competitions/criteo-display-ad-challenge/data) (use `train.csv`, first 500k rows). All notebooks in `challenges/mle/notebooks/`.

---

## Daily Rhythm
```
0:00–0:30   Active recall — explain yesterday's topic out loud before opening notes
0:30–1:00   Read / study (paper + resources)
1:00–2:30   Code in the day's notebook
```

---

## Week 1: ML Foundations (May 21–27)
*Goal: Rebuild intuition by implementing fundamentals from scratch.*

| Day | Date | Topic | Notebook | Paper of the Day |
|-----|------|-------|----------|-----------------|
| 1 | May 21 | Probability & MLE | `week1_foundations/day1_probability_mle.ipynb` | [A Few Useful Things to Know About ML — Domingos (2012)](https://dl.acm.org/doi/10.1145/2347736.2347755) |
| 2 | May 22 | Logistic Regression from scratch | `week1_foundations/day2_logistic_regression.ipynb` | [Logistic Regression in Rare Events Data — King & Zeng (2001)](https://gking.harvard.edu/files/0s.pdf) |
| 3 | May 23 | Decision Trees & Gradient Boosting | `week1_foundations/day3_gradient_boosting.ipynb` | [Greedy Function Approximation: A Gradient Boosting Machine — Friedman (2001)](https://statweb.stanford.edu/~jhf/ftp/trebst.pdf) |
| 4 | May 24 | Neural Networks & Backprop | `week1_foundations/day4_neural_nets_backprop.ipynb` | [Learning Representations by Back-propagating Errors — Rumelhart et al. (1986)](https://www.nature.com/articles/323533a0) |
| 5 | May 25 | Evaluation Metrics | `week1_foundations/day5_evaluation_metrics.ipynb` | [The Relationship Between Precision-Recall and ROC Curves — Davis & Goadrich (2006)](https://dl.acm.org/doi/10.1145/1143844.1143874) |
| 6 | May 26 | Attention & Transformers | `week1_foundations/day6_attention_transformers.ipynb` | [Attention Is All You Need — Vaswani et al. (2017)](https://arxiv.org/abs/1706.03762) |
| 7 | May 27 | Capstone: Criteo benchmark | `week1_foundations/day7_capstone_criteo.ipynb` | *Review week — no new paper* |

**Week 1 Deliverable:** Notebook comparing LR vs LightGBM vs 2-layer MLP on Criteo — AUC, log-loss, calibration curve for each.

---

## Week 2: Ads ML Domain (May 28 – June 3)
*Goal: Connect domain concepts to code. Understand the ads stack end-to-end.*

| Day | Date | Topic | Notebook | Paper of the Day |
|-----|------|-------|----------|-----------------|
| 1 | May 28 | Ads Ecosystem + EDA | `week2_ads_ml/day1_ads_ecosystem_eda.ipynb` | [Ad Click Prediction: A View from the Trenches — McMahan et al., Google (2013)](https://dl.acm.org/doi/10.1145/2487575.2488200) ⭐ |
| 2 | May 29 | Feature Engineering for Ads | `week2_ads_ml/day2_feature_engineering.ipynb` | [Practical Lessons from Predicting Clicks on Ads at Facebook — He et al. (2014)](https://dl.acm.org/doi/10.1145/2648584.2648589) ⭐ |
| 3 | May 30 | FTRL & Online Learning | `week2_ads_ml/day3_ctr_papers_impl.ipynb` | [Predicting Clicks on Ads at Pinterest — Agarwal et al. (2014)](https://dl.acm.org/doi/10.1145/2623330.2623562) ⭐ |
| 4 | May 31 | Wide & Deep, DeepFM | `week2_ads_ml/day4_wide_and_deep.ipynb` | [Wide & Deep Learning for Recommender Systems — Cheng et al. (2016)](https://arxiv.org/abs/1606.07792) |
| 5 | Jun 1 | Calibration | `week2_ads_ml/day5_calibration.ipynb` | [Predicting Good Probabilities with Supervised Learning — Niculescu-Mizil & Caruana (2005)](https://dl.acm.org/doi/10.1145/1102351.1102430) |
| 6 | Jun 2 | Budget Pacing (PID) | `week2_ads_ml/day6_budget_pacing.ipynb` | [Feedback Control of Real-Time Display Advertising — Xu et al. (2015)](https://dl.acm.org/doi/10.1145/2783258.2788614) ⭐ |
| 7 | Jun 3 | RTB Auction Simulator | `week2_ads_ml/day7_rtb_simulator.ipynb` | [Real-Time Bidding Algorithms for Performance-Based Display Ad Allocation — Zhang et al. (2012)](https://dl.acm.org/doi/10.1145/2339530.2339604) ⭐ |

**Week 2 Deliverable:** Working RTB simulator with calibrated CTR model + PID budget pacer.

---

## Week 3: ML Systems Design (June 4–10)
*Goal: Design and code production ML system skeletons. Practice 45-min design answers.*

| Day | Date | Topic | Notebook | Paper of the Day |
|-----|------|-------|----------|-----------------|
| 1 | Jun 4 | Feature Pipeline Design | `week3_systems/day1_feature_pipeline.ipynb` | [Machine Learning: The High-Interest Credit Card of Technical Debt — Sculley et al. (2014)](https://dl.acm.org/doi/10.1145/2685048.2685071) ⭐ |
| 2 | Jun 5 | Model Serving & Latency | `week3_systems/day2_model_serving.ipynb` | [Clipper: A Low-Latency Online Prediction Serving System — Crankshaw et al. (2017)](https://www.usenix.org/conference/nsdi17/technical-sessions/presentation/crankshaw) |
| 3 | Jun 6 | CTR System End-to-End Skeleton | `week3_systems/day3_ctr_system_skeleton.ipynb` | [Hidden Technical Debt in Machine Learning Systems — Sculley et al., NIPS (2015)](https://papers.nips.cc/paper/2015/hash/86df7dcfd896fcaf2674f757a2463eba-Abstract.html) |
| 4 | Jun 7 | RTB System Design | `week3_systems/day4_rtb_system.ipynb` | [Display Advertising with Real-Time Bidding (RTB) and Behavioural Targeting — Zhang et al. (2014)](https://arxiv.org/abs/1410.0735) |
| 5 | Jun 8 | Multi-Advertiser Pacing | `week3_systems/day5_multi_advertiser_pacing.ipynb` | [Online Allocation and Pacing for Advertising Campaigns — Agarwal et al., LinkedIn (2014)](https://dl.acm.org/doi/10.1145/2623330.2623366) |
| 6 | Jun 9 | Monitoring & Drift Detection | `week3_systems/day6_monitoring.ipynb` | [Failing Loudly: An Empirical Study of Methods for Detecting Dataset Shift — Rabanser et al. (2019)](https://arxiv.org/abs/1810.11953) |
| 7 | Jun 10 | Full Mock System Design | `week3_systems/day7_mock_design.ipynb` | *Practice day — no new paper* |

**Week 3 Deliverable:** 45-min verbal answer to "Design Pinterest's programmatic ads ranking system."

---

## Week 4: Experimentation & Optimization (June 11–17)
*Goal: Design and simulate ads experiments. Understand constrained bidding math.*

| Day | Date | Topic | Notebook | Paper of the Day |
|-----|------|-------|----------|-----------------|
| 1 | Jun 11 | A/B Testing Fundamentals | `week4_experimentation/day1_ab_testing.ipynb` | [Controlled Experiments on the Web — Kohavi et al. (2009)](https://www.researchgate.net/publication/220154032_Controlled_experiments_on_the_web_survey_and_practical_guide) ⭐ |
| 2 | Jun 12 | Marketplace Interference | `week4_experimentation/day2_marketplace_interference.ipynb` | [Detecting Network Effects — Eckles et al. (2017)](https://dl.acm.org/doi/10.1145/3097983.3098192) |
| 3 | Jun 13 | Variance Reduction (CUPED) | `week4_experimentation/day3_variance_reduction.ipynb` | [Improving Sensitivity of Online Experiments (CUPED) — Deng et al., Microsoft (2013)](https://dl.acm.org/doi/10.1145/2433396.2433413) |
| 4 | Jun 14 | Constrained Bid Optimization | `week4_experimentation/day4_constrained_optimization.ipynb` | [Optimal Real-Time Bidding for Display Advertising — Zhang et al. (2014)](https://dl.acm.org/doi/10.1145/2623330.2623633) ⭐ |
| 5 | Jun 15 | Explore-Exploit & Bandits | `week4_experimentation/day5_explore_exploit.ipynb` | [A Contextual-Bandit Approach to Personalized News Article Recommendation — Li et al. (2010)](https://dl.acm.org/doi/10.1145/1772690.1772758) |
| 6 | Jun 16 | Lift Testing & Incrementality | `week4_experimentation/day6_lift_testing.ipynb` | [Estimating Causal Effects Using Geo Experiments — Vaver & Koehler, Google (2011)](https://research.google/pubs/pub38355/) |
| 7 | Jun 17 | Full Experiment Design Practice | `week4_experimentation/day7_review.ipynb` | *Practice day — no new paper* |

**Week 4 Deliverable:** Full experiment design for "test a new bidding algorithm" — written out with sample size calculation.

---

## Week 5: Mock Interviews & Consolidation (June 18–24)
*Goal: Simulate real interview pressure. Identify and fix remaining gaps.*

| Day | Date | Focus | Activity |
|-----|------|-------|----------|
| Wed | Jun 18 | Mock #1 | "Design Pinterest's programmatic ads ranking system from scratch." 45 min, no notes. |
| Thu | Jun 19 | Mock #2 | "Optimize bids in real-time under a daily budget constraint." Walk through math + system. |
| Fri | Jun 20 | Behavioral prep | Write 5 staff-level stories: technical direction under ambiguity, cross-team alignment, production incident, tradeoff defended, mentoring |
| Sat | Jun 21 | Weak areas | Re-run whichever week's notebook felt shakiest |
| Sun | Jun 22 | Mock #3 | Debug scenario: "CTR AUC is great offline but ads underspend. Why?" (calibration failure) |
| Mon | Jun 23 | Mock #4 | "A new ad exchange wants to integrate. How do you evaluate their signal quality?" |
| Tue | Jun 24 | Final day | Light review only. Re-read capstone notebooks. No new material. |

---

## Must-Read Papers (⭐ = highest priority)

| Priority | Paper | Why |
|----------|-------|-----|
| ⭐⭐⭐ | McMahan et al. (2013) — Google CTR | Foundation of online ads ML |
| ⭐⭐⭐ | He et al. (2014) — Facebook CTR | GBDT+LR trick, data freshness insight |
| ⭐⭐⭐ | Agarwal et al. (2014) — Pinterest CTR | This is literally the company you're interviewing at |
| ⭐⭐ | Xu et al. (2015) — Budget Pacing | PID controller for ad spend |
| ⭐⭐ | Zhang et al. (2012) — RTB Algorithms | Optimal bidding theory |
| ⭐⭐ | Kohavi et al. (2009) — Controlled Experiments | A/B testing bible |
| ⭐⭐ | Zhang et al. (2014) — Optimal RTB | Lagrangian bidding math |
| ⭐⭐ | Sculley et al. (2014) — ML Technical Debt | Will come up in staff-level design |

---

## Mock Interview Questions Bank

### System Design
1. Design Pinterest's programmatic ads ranking system from scratch
2. How would you optimize bids in real-time under a daily budget constraint?
3. A new ad exchange wants to integrate — how do you evaluate their signal quality before going live?
4. Design a budget pacing system for 10,000 concurrent advertisers
5. How would you detect and handle distribution shift in a CTR model?

### Debug / Analysis
6. CTR model AUC is 0.80 offline but ads significantly underspend in production — why?
7. A new campaign launched but won zero auctions in the first hour — diagnose
8. CTR model was retrained yesterday and revenue dropped 5% — what happened?

### Experimentation
9. Design an A/B test for a new bidding algorithm. Walk through every decision.
10. We want to measure the true incrementality of our ads — how?
11. Two experiments ran simultaneously and both showed positive results. But combined, revenue was flat. Explain.

### Optimization Math
12. Derive the optimal bid under a budget constraint (Lagrangian approach)
13. How does Thompson sampling work? Implement it for a cold-start ad
14. What's the tradeoff between bid shading and win rate?

---

## Staff-Level Behavioral Stories to Prepare
Prepare 2-minute spoken answers for each:
1. Drove technical direction under significant ambiguity
2. Aligned multiple teams on a controversial technical decision
3. Owned a production incident — what happened, how you fixed it, what you changed
4. Made a significant technical tradeoff and defended it to leadership
5. Mentored someone or raised the bar on the team

---

## Your Edge: Data Engineering Background
In every system design answer, go deeper than pure ML candidates on:
- **Feature pipeline reliability** — SLAs, backfills, schema evolution
- **Data quality** — missing rates, label correctness, training/serving skew
- **Infrastructure tradeoffs** — batch vs. streaming feature computation
- **Operational concerns** — monitoring, alerting, incident response

These are the things ML researchers gloss over. Own them.
