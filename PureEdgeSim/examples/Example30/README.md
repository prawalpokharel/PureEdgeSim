# Example 30: placing a service graph by what depends on each subtask

## What it shows

How an orchestrator can use the shape of a service graph when edge devices
fail. In PureEdgeSim a subtask that fails takes down every subtask that waits
for it (`FAILED_BECAUSE_DEPENDENCY_FAILED`), so losing the first stage of a
pipeline costs the whole request, while losing the last one costs one result.
`Example30CriticalityOrchestrator` weighs the estimated delay of each candidate
against the risk that the candidate fails, scaled by how critical the subtask
is:

```text
cost = estimated delay / latency budget + criticality * risk
```

Cloud virtual machines and edge data centers carry no risk. An edge device
carries 0.25, plus up to 0.5 when it runs on a battery (more as the battery
empties), plus 0.5 when it moves, capped at 1. The orchestrator does not know
about crashes in advance.

The algorithm name in the parameters file selects how criticality is measured,
so one run of the scenario compares them on the same fleet and workload:

| Algorithm | Criticality of a subtask |
| --- | --- |
| `LATENCY_GREEDY` | none: lowest delay fraction, the estimator of Example 19 |
| `RELIABLE_INNER` | a subtask that any other subtask waits for may only go to a node without risk, when one is allowed |
| `DOWNSTREAM_REACH` | the share of the graph that transitively waits for it |
| `CRITICALITY` | the Centrality-Entropy Index of `Example30CriticalityIndex` |
| `CRITICALITY_C`, `CRITICALITY_H`, `CRITICALITY_G` | one term of that index alone |
| `TRADE_OFF`, `ROUND_ROBIN` | the default orchestrator |

Every algorithm, the default ones included, decides once per subtask: the
orchestrator clears the cached placement after each decision, as Example 9
does.

The index is `0.40 C + 0.35 H + 0.25 R`. `C` is a composite of PageRank,
betweenness, degree and closeness on the subtask graph (edges from a subtask to
what it depends on), `H` the normalized entropy of the subtask's recent
total time over latency budget (a failure counts as a full budget), and `R` a
governance term from the application type (1 hard real-time, 0.5 soft
real-time, 0 otherwise). It comes from Pokharel, "Centrality-Entropy Index",
FTC 2026, and the IEMCON 2026 scheduling study.

## The scenario

The files in `settings/` describe a 1000 by 1000 meters map with four small
edge data centers (4 cores at 20000 MIPS), one cloud, and three kinds of
devices: phones that send requests and can compute (40%), gateways that only
compute and are faster than an edge data center (30%), and sensors that only
send requests (30%). Three service graphs share the fleet:

| Application | Class | Graph | Requests per device per minute |
| --- | --- | --- | --- |
| Video analytics | Soft real-time | the 7-subtask pipeline of Example 9 | 2 |
| Health monitoring | Hard real-time | ingest, three analyses, fuse, alert | 4 |
| Telemetry | Non real-time | collect, clean, then store and model, report | 1 |

`example30.scenario` picks one of three device files:

- `battery`: phones and gateways run on small batteries, starting between 20
  and 100 percent, and die when they empty;
- `mobility`: phones and gateways walk around the map on mains power;
- `crash`: everything is fixed and mains powered, and
  `Example30FailureSimulationManager` crashes every gateway inside a random
  150 m disk at 120, 240 and 360 s, and takes one edge data center down from
  300 s to 360 s. Phones and sensors do not crash, so every request that fails
  fails because of where its subtasks ran.

PureEdgeSim already fails a task whose requesting device is out of range of
its destination when the task arrives. `Example30FailureSimulationManager` adds
the same check when the result comes back from a moving helper device, so a
helper that walks away while it computes loses the result. Both checks apply to
every algorithm.

## Run it

From the repository root, after `mvn clean install -DskipTests`:

```bash
mvn exec:java -Dexec.mainClass="examples.Example30" -Dexample30.scenario=crash -Dexample30.devices=100 -Dexample30.seed=1 -Dexample30.algorithms=CRITICALITY
```

`-Dexample30.out=results.jsonl` appends the metrics of each run to a file as
one JSON line: requests generated and failed, failure reasons, makespan,
energy by layer, dead devices and where the subtasks ran.
