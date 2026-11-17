export type ServiceName = "order" | "inventory" | "payment";
export type Endpoint = ServiceName | "kafka";
export type LogSource = "client" | "order" | "inventory" | "payment" | "kafka";
export type Level = "info" | "warn" | "error" | "quiet";

export type SagaStatus = "AWAITING_INVENTORY" | "AWAITING_PAYMENT" | "COMPENSATING" | "COMPLETED" | "FAILED";
export type OrderStatus = "PENDING" | "CONFIRMED" | "REJECTED" | "CANCELLED";

export type MessageType =
  | "ReserveInventory"
  | "ReleaseInventory"
  | "ChargeCustomer"
  | "RefundCustomer"
  | "InventoryReserved"
  | "InventoryRejected"
  | "InventoryReleased"
  | "PaymentCompleted"
  | "PaymentFailed"
  | "PaymentRefunded";

export interface Envelope {
  messageId: string;
  type: MessageType;
  orderId: string;
  payload: Record<string, string | number>;
  /** A message nobody can read: fails on every attempt. */
  corrupt?: boolean;
}

export interface OrderHistoryEntry {
  at: number;
  saga: SagaStatus;
  status: OrderStatus;
  note: string;
}

export interface Order {
  id: string;
  idempotencyKey: string;
  customerId: string;
  productId: string;
  quantity: number;
  amount: number;
  status: OrderStatus;
  saga: SagaStatus;
  failureReason?: string;
  createdAt: number;
  updatedAt: number;
  history: OrderHistoryEntry[];
}

export interface OutboxRow {
  seq: number;
  topic: string;
  key: string;
  envelope: Envelope;
  createdAt: number;
  publishedAt?: number;
}

export type PacketState = "flying" | "waiting" | "processing" | "retrying" | "done" | "dead";

export interface Packet {
  id: number;
  envelope: Envelope;
  topic: string;
  from: Endpoint;
  to: ServiceName;
  sentAt: number;
  arriveAt: number;
  state: PacketState;
  attempt: number;
  stateAt: number;
  /** Kafka will deliver this message once more after it is processed (a lost offset commit). */
  redeliver?: boolean;
  isRedelivery?: boolean;
}

export type ReservationStatus = "RESERVED" | "REJECTED" | "RELEASED";
export interface Reservation {
  orderId: string;
  productId?: string;
  quantity: number;
  status: ReservationStatus;
}

export type PaymentStatus = "CHARGED" | "FAILED" | "REFUNDED" | "CANCELLED";
export interface Payment {
  orderId: string;
  customerId?: string;
  amount: number;
  status: PaymentStatus;
}

export interface LedgerEntry {
  id: number;
  orderId: string;
  customerId: string;
  type: "DEBIT" | "CREDIT";
  amount: number;
  at: number;
}

export interface LogEntry {
  id: number;
  t: number;
  source: LogSource;
  text: string;
  result?: string;
  level: Level;
  orderId?: string;
}

export type SagaOutcome = "confirmed" | "rejected" | "cancelled";

export type AppEvent =
  | { type: "orderPlaced"; orderId: string }
  | { type: "idempotentReplay"; orderId: string }
  | { type: "keyConflict"; orderId: string }
  | { type: "outboxWritten"; service: ServiceName; orderId: string; messageType: MessageType }
  | { type: "published"; service: ServiceName; orderId: string; messageType: MessageType; topic: string }
  | { type: "publishBlocked"; service: ServiceName; pending: number }
  | { type: "consumerOffline"; service: ServiceName; messageType: MessageType }
  | { type: "serviceChanged"; service: ServiceName; online: boolean }
  | { type: "brokerChanged"; up: boolean }
  | { type: "duplicateIgnored"; service: ServiceName; orderId: string; messageType: MessageType }
  | { type: "stockReserved"; orderId: string; productId: string; quantity: number }
  | { type: "stockRejected"; orderId: string; productId: string }
  | { type: "stockReleased"; orderId: string; quantity: number }
  | { type: "markerStored"; service: ServiceName; orderId: string }
  | { type: "lateCommandRefused"; service: ServiceName; orderId: string; messageType: MessageType }
  | { type: "paymentCharged"; orderId: string; amount: number }
  | { type: "paymentDeclined"; orderId: string; reason: string }
  | { type: "refunded"; orderId: string; amount: number }
  | { type: "sagaMoved"; orderId: string; from: SagaStatus; to: SagaStatus }
  | { type: "staleIgnored"; orderId: string; messageType: MessageType }
  | { type: "timedOut"; orderId: string }
  | { type: "sagaFinished"; orderId: string; outcome: SagaOutcome }
  | { type: "retryScheduled"; service: ServiceName; orderId: string; attempt: number; delayMs: number }
  | { type: "deadLettered"; service: ServiceName; orderId: string; messageType: MessageType };

export type SystemEvent = { kind: "log"; entry: LogEntry } | { kind: "app"; t: number; event: AppEvent };
