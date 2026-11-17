import { customerById, productById, rupees } from "./catalog";
import type { AppEvent, ServiceName } from "./types";

export interface Caption {
  id: number;
  pattern: string;
  title: string;
  body: string;
  tone: "neutral" | "good" | "warn";
}

const SERVICE_LABEL: Record<ServiceName, string> = { order: "Order", inventory: "Inventory", payment: "Payment" };

export const WELCOME: Omit<Caption, "id"> = {
  pattern: "Welcome",
  title: "One order, three services",
  body: "Add something to your bag and check out, or pick a story. I'll explain each step as the order moves between the services.",
  tone: "neutral",
};

/**
 * Turns engine events into short captions. Ideas that apply to every message (outbox, Kafka keys, inbox) are
 * explained the first time they happen; milestones of an order are always captioned.
 */
export class Narrator {
  private readonly seen = new Set<string>();

  constructor(private readonly ownerOf: (orderId: string) => string) {}

  private once(key: string): boolean {
    if (this.seen.has(key)) return false;
    this.seen.add(key);
    return true;
  }

  caption(e: AppEvent): Omit<Caption, "id"> | null {
    switch (e.type) {
      case "orderPlaced":
        return this.once("placed")
          ? {
              pattern: "202 Accepted",
              title: `Order ${e.orderId} accepted`,
              body: "The order and its first message were saved in one database transaction, and the API answered straight away. Everything else happens in the background, so checkout never waits for another service.",
              tone: "neutral",
            }
          : null;
      case "outboxWritten":
        return this.once("outbox")
          ? {
              pattern: "Transactional outbox",
              title: "The message waits in the outbox",
              body: "\"Reserve stock\" was written to the order database's outbox table in the same transaction as the order. Either both exist or neither does, so a crash can never leave an order without its message.",
              tone: "neutral",
            }
          : null;
      case "published":
        return this.once("published")
          ? {
              pattern: "At-least-once, ordered per order",
              title: "Sent through Kafka",
              body: "The publisher marks the row as sent only after Kafka acknowledges it. The key is the order id, so all messages of one order stay in sequence on the same partition.",
              tone: "neutral",
            }
          : null;
      case "stockReserved":
        return {
          pattern: "Guarded update",
          title: `${productById(e.productId).name} reserved`,
          body: "Inventory ran UPDATE stock ... WHERE available >= quantity. Stock can never go below zero, even when two orders race for the last unit.",
          tone: "good",
        };
      case "stockRejected":
        return {
          pattern: "Guarded update",
          title: "Someone got there first",
          body: `The update changed 0 rows: no ${productById(e.productId).name} left. Inventory remembers this answer for ${e.orderId} and replies "rejected". No payment is attempted.`,
          tone: "warn",
        };
      case "sagaMoved":
        if (e.from === "AWAITING_INVENTORY" && e.to === "AWAITING_PAYMENT") {
          return {
            pattern: "Saga orchestration",
            title: "Next step: payment",
            body: "The order service conducts the saga. The reply moved the order one step with a compare-and-set, and the charge command went into the outbox in the same transaction.",
            tone: "neutral",
          };
        }
        return null;
      case "paymentCharged":
        return {
          pattern: "Append-only ledger",
          title: `${rupees(e.amount)} charged`,
          body: "The wallet is debited only if the balance covers it, and a DEBIT row is appended to the ledger. Ledger rows are never edited, so every rupee can be traced.",
          tone: "good",
        };
      case "paymentDeclined":
        return {
          pattern: "Saga compensation",
          title: "Payment declined",
          body: "The wallet couldn't cover the order. There's no distributed rollback, so the order service compensates: it asks inventory to give the reserved stock back.",
          tone: "warn",
        };
      case "stockReleased":
        return {
          pattern: "Compensating transaction",
          title: "Stock returned",
          body: `${e.quantity} unit${e.quantity > 1 ? "s" : ""} went back on the shelf. The earlier step can't be undone, so a new step reverses its effect.`,
          tone: "good",
        };
      case "sagaFinished":
        if (e.outcome === "confirmed") {
          return {
            pattern: "Saga complete",
            title: `Order ${e.orderId} confirmed`,
            body: `${this.ownerOf(e.orderId)}'s order went through three services and three databases with no distributed transaction. Each step was a local transaction, linked by messages.`,
            tone: "good",
          };
        }
        if (e.outcome === "cancelled") {
          return {
            pattern: "Consistent again",
            title: `Order ${e.orderId} cancelled`,
            body: "Nothing is left half-done: reserved stock is returned and any charge is refunded, so all three databases agree again.",
            tone: "warn",
          };
        }
        return { pattern: "Saga complete", title: `Order ${e.orderId} rejected`, body: "The order ended cleanly before any money moved.", tone: "warn" };
      case "duplicateIgnored":
        return {
          pattern: "Inbox, idempotent consumer",
          title: "Same message, delivered twice",
          body: `${SERVICE_LABEL[e.service]} found the message id in its processed_messages table and did nothing. Stock isn't reserved twice and nobody is charged twice.`,
          tone: "good",
        };
      case "idempotentReplay":
        return {
          pattern: "Idempotency-Key",
          title: "Same request, same order",
          body: "Checkout was sent again with the same Idempotency-Key, as an app does after a timeout. The API returned the original order instead of creating a second one.",
          tone: "good",
        };
      case "keyConflict":
        return {
          pattern: "Idempotency-Key",
          title: "Key reused for a different order",
          body: "The same Idempotency-Key came with a different basket, so the API refused it with 409 Conflict instead of guessing.",
          tone: "warn",
        };
      case "publishBlocked":
        return this.once(`blocked-${e.service}`)
          ? {
              pattern: "Transactional outbox",
              title: "Kafka is down, checkout still works",
              body: `Kafka didn't acknowledge, so ${e.pending} message${e.pending > 1 ? "s stay" : " stays"} in the ${SERVICE_LABEL[e.service].toLowerCase()} outbox. Nothing is lost; ${e.pending > 1 ? "they go" : "it goes"} out in order when the broker is back.`,
              tone: "warn",
            }
          : null;
      case "brokerChanged":
        return e.up
          ? { pattern: "Transactional outbox", title: "Kafka is back", body: "The publishers send what waited in the outbox, oldest first, and the order carries on.", tone: "good" }
          : null;
      case "consumerOffline":
        return this.once(`offline-${e.service}`)
          ? {
              pattern: "Durable log",
              title: `${SERVICE_LABEL[e.service]} is offline`,
              body: "The message waits in Kafka. When the service starts again it continues from its last committed offset, so nothing is lost.",
              tone: "warn",
            }
          : null;
      case "serviceChanged":
        return e.online
          ? { pattern: "Durable log", title: `${SERVICE_LABEL[e.service]} is back`, body: "It reads the waiting messages in order, oldest first.", tone: "neutral" }
          : null;
      case "timedOut":
        return {
          pattern: "Saga timeout",
          title: `${e.orderId} waited too long`,
          body: "A watchdog cancelled the order and sent both compensations: release stock and refund. They're safe to send even if the step never happened.",
          tone: "warn",
        };
      case "markerStored":
        return {
          pattern: "Out-of-order safety",
          title: "The cancel arrived first",
          body: `${SERVICE_LABEL[e.service]} got a cancel for an order it had never seen. It stores a "cancelled" marker, so if the original command shows up late it is refused.`,
          tone: "neutral",
        };
      case "lateCommandRefused":
        return {
          pattern: "Out-of-order safety",
          title: "Late command refused",
          body: `${SERVICE_LABEL[e.service]} found the cancelled marker and refused the late command instead of taking stock or money.`,
          tone: "good",
        };
      case "staleIgnored":
        return {
          pattern: "Compare-and-set",
          title: "Late reply ignored",
          body: "This reply arrived after the order had already moved on. The guarded UPDATE changed 0 rows, so nothing happened.",
          tone: "good",
        };
      case "refunded":
        return {
          pattern: "Compensation",
          title: `${rupees(e.amount)} refunded`,
          body: "The late charge was reversed with a CREDIT row. The ledger keeps both rows and the balance is back where it started.",
          tone: "good",
        };
      case "retryScheduled":
        return {
          pattern: "Retry with backoff",
          title: `Retrying in ${e.delayMs} ms`,
          body: `${SERVICE_LABEL[e.service]} couldn't handle the message. Kafka retries with a growing delay, so a short glitch heals without hammering anything.`,
          tone: "warn",
        };
      case "deadLettered":
        return {
          pattern: "Dead-letter topic",
          title: "Parked, not lost",
          body: "The message failed 3 times, so it was moved to a dead-letter topic where it can't block the orders behind it. The saga timeout will cancel this order cleanly.",
          tone: "warn",
        };
      default:
        return null;
    }
  }
}

export const shopperName = (customerId: string) => customerById(customerId).name;
