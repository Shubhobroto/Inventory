/** Backend values (application.properties) unless marked; some are slowed down on purpose so each step can be followed. */
export const CONFIG = {
  // Demo only: how long a message takes to travel through Kafka, so you can watch it move.
  hopMs: 4_500,
  // Demo only: how long a consumer visibly spends on one message.
  processMs: 900,
  // Backend polls every 200 ms; slowed so the outbox row is visible before it is sent.
  outboxPollMs: 1_000,
  // Backend saga timeout is 60 s, checked every 10 s; shortened so the timeout story finishes in under a minute.
  sagaTimeoutMs: 30_000,
  timeoutCheckMs: 2_000,
  retry: { maxAttempts: 3, initialMs: 500, multiplier: 2 },
} as const;

export type SystemConfig = typeof CONFIG;
