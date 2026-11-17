import { Scheduler } from "./clock";
import { CONFIG, type SystemConfig } from "./config";
import { CUSTOMERS, PRODUCTS, productById, rupees } from "./catalog";
import type {
  AppEvent,
  Endpoint,
  Envelope,
  LedgerEntry,
  Level,
  LogEntry,
  LogSource,
  MessageType,
  Order,
  OrderStatus,
  OutboxRow,
  Packet,
  Payment,
  Reservation,
  SagaStatus,
  ServiceName,
  SystemEvent,
} from "./types";

export const TOPICS = {
  inventoryCommands: "inventory.commands",
  inventoryEvents: "inventory.events",
  paymentCommands: "payment.commands",
  paymentEvents: "payment.events",
} as const;

const CONSUMER: Record<string, ServiceName> = {
  [TOPICS.inventoryCommands]: "inventory",
  [TOPICS.inventoryEvents]: "order",
  [TOPICS.paymentCommands]: "payment",
  [TOPICS.paymentEvents]: "order",
};

const IN_PROGRESS: SagaStatus[] = ["AWAITING_INVENTORY", "AWAITING_PAYMENT", "COMPENSATING"];
const SERVICES: ServiceName[] = ["order", "inventory", "payment"];

export interface PlaceOrderInput {
  idempotencyKey: string;
  customerId: string;
  productId: string;
  quantity: number;
}

export interface PlaceOrderResult {
  order: Order;
  created: boolean;
  conflict?: boolean;
}

class DeserializationError extends Error {}

/**
 * The order, inventory and payment services from the Spring Boot project, wired through Kafka, running in the browser.
 * Each service keeps its own data, writes messages through a transactional outbox, deduplicates with an inbox,
 * and changes state only with compare-and-set updates. The order service orchestrates the saga.
 */
export class OrderSystem {
  readonly clock = new Scheduler();
  readonly orders = new Map<string, Order>();
  readonly outbox: Record<ServiceName, OutboxRow[]> = { order: [], inventory: [], payment: [] };
  readonly inbox: Record<ServiceName, Set<string>> = { order: new Set(), inventory: new Set(), payment: new Set() };
  readonly stock = new Map(PRODUCTS.map((p) => [p.id, p.stock]));
  readonly reservations = new Map<string, Reservation>();
  readonly wallets = new Map(CUSTOMERS.map((c) => [c.id, c.balance]));
  readonly payments = new Map<string, Payment>();
  readonly ledger: LedgerEntry[] = [];
  readonly packets: Packet[] = [];
  readonly deadLetters: Packet[] = [];
  readonly logs: LogEntry[] = [];
  readonly events: { t: number; event: AppEvent }[] = [];
  readonly online: Record<ServiceName, boolean> = { order: true, inventory: true, payment: true };
  brokerUp = true;
  version = 0;
  redeliverNext = false;
  corruptNext = false;

  private readonly byKey = new Map<string, string>();
  private readonly waiting: Record<ServiceName, Packet[]> = { order: [], inventory: [], payment: [] };
  private readonly blockedCount: Record<ServiceName, number> = { order: 0, inventory: 0, payment: 0 };
  private readonly listeners = new Set<(e: SystemEvent) => void>();
  private nextOrder = 1001;
  private nextMessage = 1;
  private nextPacket = 1;
  private nextLog = 1;
  private nextOutbox = 1;
  private nextLedger = 1;

  constructor(readonly cfg: SystemConfig = CONFIG) {
    for (const service of SERVICES) this.clock.every(cfg.outboxPollMs, () => this.publishOutbox(service));
    this.clock.every(cfg.timeoutCheckMs, () => this.failStuckOrders());
  }

  // ================= plumbing =================

  now(): number {
    return this.clock.time();
  }

  advanceBy(ms: number): void {
    this.clock.advanceBy(ms);
  }

  subscribe(listener: (e: SystemEvent) => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  private log(source: LogSource, text: string, result?: string, level: Level = "info", orderId?: string): void {
    const entry: LogEntry = { id: this.nextLog++, t: this.now(), source, text, result, level, orderId };
    this.logs.push(entry);
    this.version++;
    this.listeners.forEach((l) => l({ kind: "log", entry }));
  }

  private emit(event: AppEvent): void {
    this.events.push({ t: this.now(), event });
    this.version++;
    this.listeners.forEach((l) => l({ kind: "app", t: this.now(), event }));
  }

  pendingOutbox(service: ServiceName): number {
    return this.outbox[service].filter((r) => r.publishedAt === undefined).length;
  }

  // ================= chaos controls =================

  setServiceOnline(service: Exclude<ServiceName, "order">, online: boolean): void {
    if (this.online[service] === online) return;
    this.online[service] = online;
    this.log(service, online ? `${service}-service started` : `${service}-service stopped`, online ? "resuming from the last committed offset" : "consumer and outbox publisher paused", online ? "info" : "warn");
    this.emit({ type: "serviceChanged", service, online });
    if (online) {
      // Kafka hands back everything after the last committed offset, in order.
      const queued = this.waiting[service].splice(0);
      queued.forEach((p, i) => this.clock.after(i * (this.cfg.processMs + 150), () => this.startProcessing(p)));
    }
  }

  setBrokerUp(up: boolean): void {
    if (this.brokerUp === up) return;
    this.brokerUp = up;
    this.log("kafka", up ? "Kafka broker is back" : "Kafka broker unreachable", up ? "publishers can send again" : "sends will not be acknowledged", up ? "info" : "error");
    this.emit({ type: "brokerChanged", up });
  }

  // ================= order-service: REST API =================

  /** POST /orders with an Idempotency-Key header. */
  placeOrder(input: PlaceOrderInput): PlaceOrderResult {
    const product = productById(input.productId);
    const amount = product.price * input.quantity;
    this.log("client", `POST /orders  Idempotency-Key: ${input.idempotencyKey}`, `${input.quantity} x ${product.name}, ${rupees(amount)}`);

    const existingId = this.byKey.get(input.idempotencyKey);
    if (existingId) {
      const existing = this.orders.get(existingId)!;
      const same = existing.customerId === input.customerId && existing.productId === input.productId && existing.quantity === input.quantity;
      if (same) {
        this.log("order", `SELECT * FROM orders WHERE idempotency_key = '${input.idempotencyKey}'`, `found ${existing.id}`, "info", existing.id);
        this.log("client", "200 OK", `same order ${existing.id} returned, nothing created`, "info", existing.id);
        this.emit({ type: "idempotentReplay", orderId: existing.id });
        return { order: existing, created: false };
      }
      this.log("client", "409 Conflict", "this Idempotency-Key was already used for a different order", "warn", existing.id);
      this.emit({ type: "keyConflict", orderId: existing.id });
      return { order: existing, created: false, conflict: true };
    }

    const now = this.now();
    const order: Order = {
      id: `#${this.nextOrder++}`,
      idempotencyKey: input.idempotencyKey,
      customerId: input.customerId,
      productId: input.productId,
      quantity: input.quantity,
      amount,
      status: "PENDING",
      saga: "AWAITING_INVENTORY",
      createdAt: now,
      updatedAt: now,
      history: [{ at: now, saga: "AWAITING_INVENTORY", status: "PENDING", note: "Order placed" }],
    };
    this.orders.set(order.id, order);
    this.byKey.set(input.idempotencyKey, order.id);
    this.log("order", "BEGIN", undefined, "quiet", order.id);
    this.log("order", `INSERT INTO orders (id, idempotency_key, saga_status) VALUES ('${order.id}', '${input.idempotencyKey}', 'AWAITING_INVENTORY')`, "1 row", "info", order.id);
    this.writeOutbox("order", TOPICS.inventoryCommands, order.id, "ReserveInventory", { productId: order.productId, quantity: order.quantity });
    this.log("order", "COMMIT", "order and its first message saved together", "info", order.id);
    this.log("client", "202 Accepted", `Location: /orders/${order.id}`, "info", order.id);
    this.emit({ type: "orderPlaced", orderId: order.id });
    return { order, created: true };
  }

  // ================= transactional outbox (every service) =================

  private writeOutbox(service: ServiceName, topic: string, orderId: string, type: MessageType, payload: Envelope["payload"]): void {
    const envelope: Envelope = { messageId: `m-${this.nextMessage++}`, type, orderId, payload };
    if (this.corruptNext) {
      envelope.corrupt = true;
      this.corruptNext = false;
    }
    this.outbox[service].push({ seq: this.nextOutbox++, topic, key: orderId, envelope, createdAt: this.now() });
    this.log(service, `INSERT INTO outbox_events (topic, key, type) VALUES ('${topic}', '${orderId}', '${type}')`, "same transaction as the state change", "info", orderId);
    this.emit({ type: "outboxWritten", service, orderId, messageType: type });
  }

  private publishOutbox(service: ServiceName): void {
    if (!this.online[service]) return;
    const pending = this.outbox[service].filter((r) => r.publishedAt === undefined);
    if (!pending.length) {
      this.blockedCount[service] = 0;
      return;
    }
    if (!this.brokerUp) {
      // Rows stay unpublished; they go out in order when the broker acknowledges again.
      if (this.blockedCount[service] !== pending.length) {
        this.blockedCount[service] = pending.length;
        this.log(service, `outbox publisher: send to ${pending[0].topic}`, `not acknowledged, ${pending.length} message(s) wait in the outbox`, "warn");
        this.emit({ type: "publishBlocked", service, pending: pending.length });
      }
      return;
    }
    this.blockedCount[service] = 0;
    for (const row of pending) {
      this.sendToKafka(service, row);
      row.publishedAt = this.now();
      this.log(service, `UPDATE outbox_events SET published_at = now() WHERE seq = ${row.seq}`, "marked sent after the ack", "quiet", row.key);
    }
  }

  private sendToKafka(from: ServiceName, row: OutboxRow): void {
    const now = this.now();
    const packet: Packet = {
      id: this.nextPacket++,
      envelope: row.envelope,
      topic: row.topic,
      from,
      to: CONSUMER[row.topic],
      sentAt: now,
      arriveAt: now + this.cfg.hopMs,
      state: "flying",
      attempt: 1,
      stateAt: now,
    };
    if (this.redeliverNext) {
      packet.redeliver = true;
      this.redeliverNext = false;
    }
    this.packets.push(packet);
    this.log("kafka", `produce ${row.topic}  key=${row.key}  ${row.envelope.type}`, "ack (acks=all, idempotent producer)", "info", row.key);
    this.emit({ type: "published", service: from, orderId: row.key, messageType: row.envelope.type, topic: row.topic });
    this.clock.at(packet.arriveAt, () => this.arrive(packet));
  }

  // ================= Kafka delivery =================

  private arrive(packet: Packet): void {
    if (!this.online[packet.to]) {
      packet.state = "waiting";
      packet.stateAt = this.now();
      this.waiting[packet.to].push(packet);
      this.log("kafka", `${packet.topic}: ${packet.envelope.type} waits in the log`, `${packet.to}-service is offline`, "warn", packet.envelope.orderId);
      this.emit({ type: "consumerOffline", service: packet.to, messageType: packet.envelope.type });
      return;
    }
    this.startProcessing(packet);
  }

  private startProcessing(packet: Packet): void {
    packet.state = "processing";
    packet.stateAt = this.now();
    this.version++;
    this.clock.after(this.cfg.processMs, () => this.handlePacket(packet));
  }

  private handlePacket(packet: Packet): void {
    const service = packet.to;
    if (!this.online[service]) {
      packet.state = "waiting";
      packet.stateAt = this.now();
      this.waiting[service].push(packet);
      return;
    }
    const { envelope } = packet;
    this.log("kafka", `poll ${packet.topic} -> ${service}-service`, `${envelope.type} for ${envelope.orderId}${packet.attempt > 1 ? `, attempt ${packet.attempt}/${this.cfg.retry.maxAttempts}` : ""}`, "info", envelope.orderId);
    try {
      this.dispatch(service, envelope);
      packet.state = "done";
      packet.stateAt = this.now();
      this.log("kafka", `commit offset ${packet.topic}`, undefined, "quiet", envelope.orderId);
      if (packet.redeliver) this.redeliver(packet);
      this.version++;
    } catch (error) {
      const reason = error instanceof Error ? error.message : String(error);
      this.log(service, `${service}-service failed to handle ${envelope.type}`, reason, "error", envelope.orderId);
      const { maxAttempts, initialMs, multiplier } = this.cfg.retry;
      if (packet.attempt < maxAttempts) {
        const delayMs = initialMs * multiplier ** (packet.attempt - 1);
        packet.state = "retrying";
        packet.stateAt = this.now();
        this.log("kafka", `retry ${packet.attempt + 1}/${maxAttempts}`, `in ${delayMs} ms (exponential backoff)`, "warn", envelope.orderId);
        this.emit({ type: "retryScheduled", service, orderId: envelope.orderId, attempt: packet.attempt + 1, delayMs });
        this.clock.after(delayMs, () => {
          packet.attempt++;
          this.startProcessing(packet);
        });
      } else {
        packet.state = "dead";
        packet.stateAt = this.now();
        this.deadLetters.push(packet);
        this.log("kafka", `produce ${packet.topic}.DLT`, `${envelope.type} parked after ${maxAttempts} attempts; the partition moves on`, "error", envelope.orderId);
        this.emit({ type: "deadLettered", service, orderId: envelope.orderId, messageType: envelope.type });
      }
    }
  }

  /** The offset commit was lost, so Kafka hands the same message out again: at-least-once delivery. */
  private redeliver(original: Packet): void {
    const now = this.now();
    const copy: Packet = {
      ...original,
      id: this.nextPacket++,
      from: "kafka",
      sentAt: now,
      arriveAt: now + this.cfg.hopMs / 2,
      state: "flying",
      attempt: 1,
      stateAt: now,
      redeliver: false,
      isRedelivery: true,
    };
    this.packets.push(copy);
    this.log("kafka", `redeliver ${original.topic}  ${original.envelope.type}`, "the offset commit was lost: same message, same id", "warn", original.envelope.orderId);
    this.clock.at(copy.arriveAt, () => this.arrive(copy));
  }

  private dispatch(service: ServiceName, envelope: Envelope): void {
    if (envelope.corrupt) throw new DeserializationError("SerializationException: cannot read the message body");
    if (!this.firstTime(service, envelope)) return;
    if (service === "order") this.orchestrate(envelope);
    else if (service === "inventory") this.handleInventory(envelope);
    else this.handlePayment(envelope);
  }

  /** Inbox: the message id is stored in the same transaction as the effect. */
  private firstTime(service: ServiceName, envelope: Envelope): boolean {
    if (this.inbox[service].has(envelope.messageId)) {
      this.log(service, `SELECT 1 FROM processed_messages WHERE message_id = '${envelope.messageId}'`, "already processed, skipping", "warn", envelope.orderId);
      this.emit({ type: "duplicateIgnored", service, orderId: envelope.orderId, messageType: envelope.type });
      return false;
    }
    this.inbox[service].add(envelope.messageId);
    this.log(service, `INSERT INTO processed_messages VALUES ('${envelope.messageId}')`, undefined, "quiet", envelope.orderId);
    return true;
  }

  // ================= order-service: saga orchestrator =================

  private orchestrate(envelope: Envelope): void {
    const orderId = envelope.orderId;
    const order = this.orders.get(orderId);
    if (!order) return;
    switch (envelope.type) {
      case "InventoryReserved":
        if (this.transition(order, "AWAITING_INVENTORY", "AWAITING_PAYMENT", "PENDING", "Stock reserved", envelope.type)) {
          this.writeOutbox("order", TOPICS.paymentCommands, orderId, "ChargeCustomer", { customerId: order.customerId, amount: order.amount });
        }
        break;
      case "InventoryRejected":
        if (this.transition(order, "AWAITING_INVENTORY", "FAILED", "REJECTED", "Out of stock", envelope.type, String(envelope.payload.reason))) {
          this.emit({ type: "sagaFinished", orderId, outcome: "rejected" });
        }
        break;
      case "PaymentCompleted":
        if (this.transition(order, "AWAITING_PAYMENT", "COMPLETED", "CONFIRMED", "Payment received", envelope.type)) {
          this.emit({ type: "sagaFinished", orderId, outcome: "confirmed" });
        }
        break;
      case "PaymentFailed":
        if (this.transition(order, "AWAITING_PAYMENT", "COMPENSATING", "PENDING", "Payment declined, returning stock", envelope.type, String(envelope.payload.reason))) {
          this.writeOutbox("order", TOPICS.inventoryCommands, orderId, "ReleaseInventory", {});
        }
        break;
      case "InventoryReleased":
        if (this.transition(order, "COMPENSATING", "FAILED", "CANCELLED", "Stock returned", envelope.type, order.failureReason)) {
          this.emit({ type: "sagaFinished", orderId, outcome: "cancelled" });
        }
        break;
      default:
        this.log("order", `${envelope.type} noted`, "no saga step for this reply", "quiet", orderId);
    }
  }

  /** Compare-and-set on the saga state: a duplicate, late or racing message changes nothing. */
  private transition(order: Order, from: SagaStatus, to: SagaStatus, status: OrderStatus, note: string, cause: MessageType | "Timeout", reason?: string): boolean {
    const sql = `UPDATE orders SET saga_status = '${to}' WHERE id = '${order.id}' AND saga_status = '${from}'`;
    if (order.saga !== from) {
      this.log("order", sql, `0 rows: the order is already ${order.saga}`, "warn", order.id);
      if (cause !== "Timeout") this.emit({ type: "staleIgnored", orderId: order.id, messageType: cause });
      return false;
    }
    order.saga = to;
    order.status = status;
    if (reason) order.failureReason = reason;
    order.updatedAt = this.now();
    order.history.push({ at: this.now(), saga: to, status, note });
    this.log("order", sql, "1 row", "info", order.id);
    this.emit({ type: "sagaMoved", orderId: order.id, from, to });
    return true;
  }

  private failStuckOrders(): void {
    const cutoff = this.now() - this.cfg.sagaTimeoutMs;
    for (const order of this.orders.values()) {
      if (!IN_PROGRESS.includes(order.saga) || order.updatedAt > cutoff) continue;
      if (this.transition(order, order.saga, "FAILED", "CANCELLED", "Timed out, cancelling", "Timeout", "TIMEOUT")) {
        this.log("order", "SagaTimeoutJob", `${order.id} waited too long; sending both compensations`, "warn", order.id);
        this.writeOutbox("order", TOPICS.inventoryCommands, order.id, "ReleaseInventory", {});
        this.writeOutbox("order", TOPICS.paymentCommands, order.id, "RefundCustomer", {});
        this.emit({ type: "timedOut", orderId: order.id });
        this.emit({ type: "sagaFinished", orderId: order.id, outcome: "cancelled" });
      }
    }
  }

  // ================= inventory-service =================

  private handleInventory(envelope: Envelope): void {
    const orderId = envelope.orderId;
    const existing = this.reservations.get(orderId);
    if (envelope.type === "ReserveInventory") {
      if (existing) {
        this.repeatInventoryAnswer(existing, envelope.type);
        return;
      }
      const productId = String(envelope.payload.productId);
      const quantity = Number(envelope.payload.quantity);
      const available = this.stock.get(productId) ?? 0;
      const sql = `UPDATE stock SET available = available - ${quantity} WHERE product_id = '${productId}' AND available >= ${quantity}`;
      if (quantity > 0 && available >= quantity) {
        this.stock.set(productId, available - quantity);
        this.reservations.set(orderId, { orderId, productId, quantity, status: "RESERVED" });
        this.log("inventory", sql, `1 row (${available} -> ${available - quantity} left)`, "info", orderId);
        this.emit({ type: "stockReserved", orderId, productId, quantity });
        this.writeOutbox("inventory", TOPICS.inventoryEvents, orderId, "InventoryReserved", {});
      } else {
        this.reservations.set(orderId, { orderId, productId, quantity, status: "REJECTED" });
        this.log("inventory", sql, `0 rows (only ${available} left)`, "warn", orderId);
        this.emit({ type: "stockRejected", orderId, productId });
        this.writeOutbox("inventory", TOPICS.inventoryEvents, orderId, "InventoryRejected", { reason: "OUT_OF_STOCK" });
      }
      return;
    }
    if (envelope.type === "ReleaseInventory") {
      if (!existing) {
        this.reservations.set(orderId, { orderId, quantity: 0, status: "RELEASED" });
        this.log("inventory", `INSERT INTO reservations (order_id, status) VALUES ('${orderId}', 'RELEASED')`, "marker: a late reserve will be refused", "warn", orderId);
        this.emit({ type: "markerStored", service: "inventory", orderId });
      } else if (existing.status === "RESERVED" && existing.productId) {
        const before = this.stock.get(existing.productId) ?? 0;
        this.stock.set(existing.productId, before + existing.quantity);
        existing.status = "RELEASED";
        this.log("inventory", `UPDATE stock SET available = available + ${existing.quantity} WHERE product_id = '${existing.productId}'`, `${before} -> ${before + existing.quantity}`, "info", orderId);
        this.emit({ type: "stockReleased", orderId, quantity: existing.quantity });
      }
      this.writeOutbox("inventory", TOPICS.inventoryEvents, orderId, "InventoryReleased", {});
    }
  }

  private repeatInventoryAnswer(reservation: Reservation, type: MessageType): void {
    const orderId = reservation.orderId;
    this.log("inventory", `SELECT status FROM reservations WHERE order_id = '${orderId}'`, `${reservation.status}: same answer as before`, "warn", orderId);
    if (reservation.status === "RESERVED") this.writeOutbox("inventory", TOPICS.inventoryEvents, orderId, "InventoryReserved", {});
    else if (reservation.status === "REJECTED") this.writeOutbox("inventory", TOPICS.inventoryEvents, orderId, "InventoryRejected", { reason: "OUT_OF_STOCK" });
    else {
      this.emit({ type: "lateCommandRefused", service: "inventory", orderId, messageType: type });
      this.writeOutbox("inventory", TOPICS.inventoryEvents, orderId, "InventoryRejected", { reason: "CANCELLED" });
    }
  }

  // ================= payment-service =================

  private handlePayment(envelope: Envelope): void {
    const orderId = envelope.orderId;
    const existing = this.payments.get(orderId);
    if (envelope.type === "ChargeCustomer") {
      if (existing) {
        this.log("payment", `SELECT status FROM payments WHERE order_id = '${orderId}'`, `${existing.status}: same answer as before`, "warn", orderId);
        if (existing.status === "CHARGED") this.writeOutbox("payment", TOPICS.paymentEvents, orderId, "PaymentCompleted", {});
        else if (existing.status === "FAILED") this.writeOutbox("payment", TOPICS.paymentEvents, orderId, "PaymentFailed", { reason: "INSUFFICIENT_FUNDS" });
        else {
          this.emit({ type: "lateCommandRefused", service: "payment", orderId, messageType: envelope.type });
          this.writeOutbox("payment", TOPICS.paymentEvents, orderId, "PaymentFailed", { reason: "CANCELLED" });
        }
        return;
      }
      const customerId = String(envelope.payload.customerId);
      const amount = Number(envelope.payload.amount);
      const balance = this.wallets.get(customerId) ?? 0;
      const sql = `UPDATE wallets SET balance = balance - ${amount} WHERE customer_id = '${customerId}' AND balance >= ${amount}`;
      if (amount > 0 && balance >= amount) {
        this.wallets.set(customerId, balance - amount);
        this.payments.set(orderId, { orderId, customerId, amount, status: "CHARGED" });
        this.ledger.push({ id: this.nextLedger++, orderId, customerId, type: "DEBIT", amount, at: this.now() });
        this.log("payment", sql, `1 row (${rupees(balance)} -> ${rupees(balance - amount)})`, "info", orderId);
        this.log("payment", `INSERT INTO ledger_entries (order_id, type, amount) VALUES ('${orderId}', 'DEBIT', ${amount})`, "append-only", "info", orderId);
        this.emit({ type: "paymentCharged", orderId, amount });
        this.writeOutbox("payment", TOPICS.paymentEvents, orderId, "PaymentCompleted", {});
      } else {
        this.payments.set(orderId, { orderId, customerId, amount, status: "FAILED" });
        this.log("payment", sql, `0 rows (balance ${rupees(balance)})`, "warn", orderId);
        this.emit({ type: "paymentDeclined", orderId, reason: "INSUFFICIENT_FUNDS" });
        this.writeOutbox("payment", TOPICS.paymentEvents, orderId, "PaymentFailed", { reason: "INSUFFICIENT_FUNDS" });
      }
      return;
    }
    if (envelope.type === "RefundCustomer") {
      if (!existing) {
        this.payments.set(orderId, { orderId, amount: 0, status: "CANCELLED" });
        this.log("payment", `INSERT INTO payments (order_id, status) VALUES ('${orderId}', 'CANCELLED')`, "marker: a late charge will be refused", "warn", orderId);
        this.emit({ type: "markerStored", service: "payment", orderId });
      } else if (existing.status === "CHARGED" && existing.customerId) {
        const balance = this.wallets.get(existing.customerId) ?? 0;
        this.wallets.set(existing.customerId, balance + existing.amount);
        existing.status = "REFUNDED";
        this.ledger.push({ id: this.nextLedger++, orderId, customerId: existing.customerId, type: "CREDIT", amount: existing.amount, at: this.now() });
        this.log("payment", `UPDATE wallets SET balance = balance + ${existing.amount} WHERE customer_id = '${existing.customerId}'`, `${rupees(balance)} -> ${rupees(balance + existing.amount)}`, "info", orderId);
        this.log("payment", `INSERT INTO ledger_entries (order_id, type, amount) VALUES ('${orderId}', 'CREDIT', ${existing.amount})`, "append-only", "info", orderId);
        this.emit({ type: "refunded", orderId, amount: existing.amount });
      }
      this.writeOutbox("payment", TOPICS.paymentEvents, orderId, "PaymentRefunded", {});
    }
  }
}

export { SERVICES };
export type { Endpoint };
