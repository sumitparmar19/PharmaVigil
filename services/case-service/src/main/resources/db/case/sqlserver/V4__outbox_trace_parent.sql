-- W3C traceparent of the request that wrote the outbox row (null without tracing); the relay restores it while
-- publishing so a trace continues through Kafka (common/outbox/TraceParent).
ALTER TABLE outbox_event ADD trace_parent VARCHAR(64) NULL;
