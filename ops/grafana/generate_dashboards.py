"""Generates the three PharmaVigil Grafana dashboards (ops/grafana/dashboards/*.json).

Why a generator: the panels share units, layout and the data-source reference, and the JSON stays reviewable
when it is produced from one short description. Run `python3 ops/grafana/generate_dashboards.py` after changing
a panel; the metric names are documented in docs/OBSERVABILITY.md.
"""

import json
from pathlib import Path

OUT = Path(__file__).parent / "dashboards"
DS = {"type": "prometheus", "uid": "prometheus"}
NO_CAUSATION = "Spontaneous reports do not prove that a drug caused a reaction."


def target(expr, legend="", ref="A"):
    return {"datasource": DS, "expr": expr, "legendFormat": legend, "refId": ref}


def stat(title, expr, unit="short", x=0, y=0, w=6, h=4, decimals=None, desc="", no_value="No data"):
    p = {
        "type": "stat", "title": title, "description": desc, "datasource": DS,
        "gridPos": {"x": x, "y": y, "w": w, "h": h},
        "targets": [target(expr)],
        "fieldConfig": {"defaults": {"unit": unit, "noValue": no_value,
                                     "color": {"mode": "fixed", "fixedColor": "text"}},
                        "overrides": []},
        "options": {"reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False},
                    "colorMode": "none", "graphMode": "none", "textMode": "value", "justifyMode": "auto"},
    }
    if decimals is not None:
        p["fieldConfig"]["defaults"]["decimals"] = decimals
    return p


def series(title, targets, unit="short", x=0, y=0, w=12, h=8, desc="", bars=False):
    return {
        "type": "timeseries", "title": title, "description": desc, "datasource": DS,
        "gridPos": {"x": x, "y": y, "w": w, "h": h},
        "targets": [target(e, legend, chr(ord("A") + i)) for i, (e, legend) in enumerate(targets)],
        "fieldConfig": {"defaults": {
            "unit": unit, "color": {"mode": "palette-classic"},
            "custom": {"lineWidth": 2, "fillOpacity": 0 if not bars else 80, "drawStyle": "bars" if bars else "line",
                       "showPoints": "never", "spanNulls": False}}, "overrides": []},
        "options": {"legend": {"displayMode": "list", "placement": "bottom", "showLegend": True},
                    "tooltip": {"mode": "multi", "sort": "desc"}},
    }


def text(title, content, x=0, y=0, w=24, h=3):
    return {"type": "text", "title": title, "gridPos": {"x": x, "y": y, "w": w, "h": h},
            "options": {"mode": "markdown", "content": content}}


def dashboard(uid, title, description, panels, tags):
    for i, p in enumerate(panels, start=1):
        p["id"] = i
    return {
        "uid": uid, "title": title, "description": description, "tags": ["pharmavigil", *tags],
        "timezone": "utc", "schemaVersion": 39, "version": 1, "editable": False, "refresh": "30s",
        "time": {"from": "now-1h", "to": "now"}, "templating": {"list": []}, "annotations": {"list": []},
        "panels": panels,
    }


def p95(metric, by=""):
    group = f"le, {by}" if by else "le"
    return f"histogram_quantile(0.95, sum by ({group}) (rate({metric}_bucket[5m])))"


# Current lag per partition; the Kafka client reports each topic tag twice (dots and underscores), keep one.
LAG = 'kafka_consumer_fetch_manager_records_lag{topic=~".+[.].+"}'
BANNER = "**Not for clinical or regulatory use.** " + NO_CAUSATION + " Synthetic patients only."

pipeline = dashboard("pv-pipeline", "PharmaVigil · Pipeline health",
                     "Reports in, time through the Kafka pipeline, backlogs, JMS queues and regulator answers.", [
    text("", BANNER, h=2),
    stat("Reports accepted / min", 'sum(rate(pv_reports_received_total{kind!="duplicate"}[5m])) * 60', x=0, y=2,
         decimals=1, desc="New reports and follow-ups accepted by intake-gateway (resends excluded)."),
    stat("Intake → coded, p95", p95("pv_report_intake_to_coded_seconds"), unit="s", x=6, y=2, decimals=2,
         desc="From intake-gateway's acceptance to the end of the coding stage in case-service."),
    stat("Max Kafka consumer lag", f"max({LAG})", x=12, y=2,
         desc="Messages the furthest-behind consumer partition still has to read."),
    stat("Outbox backlog", "sum(pv_outbox_backlog)", x=18, y=2,
         desc="Rows written but not yet published to Kafka, all services. Grows while Kafka is down."),
    series("Reports per minute by source", [
        ('sum by (source, kind) (rate(pv_reports_received_total[5m])) * 60', "{{source}} {{kind}}")],
        x=0, y=6),
    series("Stage time p95", [(p95("pv_pipeline_stage_seconds", "stage"), "{{stage}}")], unit="s", x=12, y=6,
           desc="Time to process one message per stage (dedup, coding, triage, narrative, submission-status)."),
    series("Stage failures per minute", [
        ('sum by (stage) (rate(pv_pipeline_stage_seconds_count{outcome="error"}[5m])) * 60', "{{stage}}")],
        x=0, y=14, desc="Failed attempts; after three retries a message goes to its .dlq topic."),
    series("Kafka consumer lag", [
        (f"max by (service, topic) ({LAG})", "{{service}} {{topic}}")],
        x=12, y=14, desc="Current lag per consumed topic (largest partition)."),
    series("Outbox backlog by service", [("pv_outbox_backlog", "{{service}}")], x=0, y=22),
    series("JMS queue depth", [("pv_jms_queue_depth", "{{queue}}")], x=12, y=22,
           desc="regulatory.outbound, regulatory.ack and the broker DLQ, browsed by submission-gateway every 15 s."),
    series("Regulator answers per hour", [
        ("sum by (outcome) (increase(pv_regulator_answers_total[1h]))", "{{outcome}}")], x=0, y=30, w=24, bars=True,
        desc="ACK and NACK answers from the mock regulator."),
], ["pipeline"])

acceptance = ('sum(increase(pv_review_decisions_total{{field="{f}",outcome="accepted"}}[$__range])) / '
              'sum(increase(pv_review_decisions_total{{field="{f}",outcome=~"accepted|corrected"}}[$__range]))')
ai = dashboard("pv-ai-quality", "PharmaVigil · AI quality",
               "How often reviewers keep the models' suggestions, model latency and how often cases fall back to "
               "manual work.", [
    text("", BANNER + "\n\nAI outputs are suggestions; a reviewer confirms every code, seriousness and duplicate "
         "flag. Acceptance is a feedback signal from reviewers, not a measured accuracy. With the synthetic test "
         "bundle (model names start with `fixture:`) these numbers mean nothing.", h=3),
    stat("MedDRA coder: kept by reviewers", acceptance.format(f="PT"), unit="percentunit", x=0, y=3, w=8,
         decimals=1, desc="Accepted / (accepted + corrected) code suggestions in the selected time range."),
    stat("Seriousness: kept by reviewers", acceptance.format(f="SERIOUSNESS"), unit="percentunit", x=8, y=3, w=8,
         decimals=1),
    stat("Duplicate flag: kept by reviewers", acceptance.format(f="DUPLICATE"), unit="percentunit", x=16, y=3,
         w=8, decimals=1),
    series("Reviewer decisions per hour", [
        ("sum by (field, outcome) (increase(pv_review_decisions_total[1h]))", "{{field}} {{outcome}}")],
        x=0, y=7, bars=True),
    series("Model inference p95", [(p95("pv_model_inference_seconds", "model"), "{{model}}")], unit="s", x=12, y=7,
           desc="ONNX Runtime time per call in case-service."),
    series("Coding results per hour", [
        ('sum by (result) (increase(pv_pipeline_outcome_total{stage="coding"}[1h]))', "{{result}}")],
        x=0, y=15, bars=True, desc="NEEDS_MANUAL_CODING rises when the coder is unsure or unavailable."),
    series("Triage results per hour", [
        ('sum by (result) (increase(pv_pipeline_outcome_total{stage="triage"}[1h]))', "{{result}}")],
        x=12, y=15, bars=True, desc="UNKNOWN when the seriousness model is unavailable."),
], ["ai"])

signals = dashboard("pv-signals", "PharmaVigil · Safety signals",
                    "The signal snapshot served to the pharmacy counter and how often a dispense shows a warning.", [
    text("", "**" + NO_CAUSATION + "** Signals are statistical associations in public FAERS reports, shown as "
         "evidence to discuss, never as a verdict. Not for clinical or regulatory use.", h=2),
    stat("Drug–event pairs in the snapshot", "max(pv_signals_snapshot_pairs)", x=0, y=2, w=8,
         desc="Empty until a pv-ml export-signals folder is loaded.", no_value="No snapshot loaded"),
    stat("Snapshot age", "time() - max(pv_signals_snapshot_loaded)", unit="s", x=8, y=2, w=8,
         no_value="No snapshot loaded"),
    stat("Lookups with a warning", 'sum(increase(pv_signals_lookups_total{result="warning"}[$__range])) / '
         'sum(increase(pv_signals_lookups_total[$__range]))', unit="percentunit", x=16, y=2, w=8, decimals=1,
         desc="Share of counter lookups (one per dispense) that returned at least one signal above the EB05 rule."),
    series("Counter lookups per minute", [
        ("sum by (result) (rate(pv_signals_lookups_total[5m])) * 60", "{{result}}")], x=0, y=6, w=24,
        desc="warning: at least one signal; none: no signal above the threshold; no_data: no snapshot loaded."),
], ["signals"])

if __name__ == "__main__":
    OUT.mkdir(exist_ok=True)
    for name, d in [("pipeline-health", pipeline), ("ai-quality", ai), ("safety-signals", signals)]:
        (OUT / f"{name}.json").write_text(json.dumps(d, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        print(f"wrote {name}.json ({len(d['panels'])} panels)")
