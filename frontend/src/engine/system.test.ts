import { describe, expect, it } from "vitest";
import { loadStory, storyById } from "./stories";
import type { AppEvent } from "./types";
import type { OrderSystem } from "./system";

function run(id: string, ms: number): OrderSystem {
  const system = loadStory(storyById(id));
  system.advanceBy(ms);
  return system;
}

const of = <T extends AppEvent["type"]>(s: OrderSystem, type: T) =>
  s.events.map((e) => e.event).filter((e): e is Extract<AppEvent, { type: T }> => e.type === type);

describe("order platform stories", () => {
  it("a perfect order reserves stock, charges the wallet and confirms", () => {
    const s = run("happy", 40_000);
    const order = s.orders.get("#1001")!;
    expect(order).toMatchObject({ status: "CONFIRMED", saga: "COMPLETED" });
    expect(s.stock.get("SKU-1")).toBe(99);
    expect(s.wallets.get("CUST-1")).toBe(50_000 - 2_499);
    expect(s.ledger.map((l) => l.type)).toEqual(["DEBIT"]);
  });

  it("each step takes several seconds so it can be watched", () => {
    const s = run("happy", 10_000);
    expect(s.orders.get("#1001")?.saga).toBe("AWAITING_INVENTORY");
  });

  it("two shoppers race for the last unit: exactly one gets it and stock never goes negative", () => {
    const s = run("last-unit", 50_000);
    expect(s.orders.get("#1001")?.status).toBe("CONFIRMED");
    expect(s.orders.get("#1002")).toMatchObject({ status: "REJECTED", failureReason: "OUT_OF_STOCK" });
    expect(s.stock.get("SKU-3")).toBe(0);
    expect(s.wallets.get("CUST-1")).toBe(50_000);
  });

  it("a declined payment is compensated: the reserved stock goes back", () => {
    const s = run("declined", 50_000);
    expect(s.orders.get("#1001")).toMatchObject({ status: "CANCELLED", saga: "FAILED", failureReason: "INSUFFICIENT_FUNDS" });
    expect(s.stock.get("SKU-2")).toBe(5);
    expect(s.wallets.get("CUST-2")).toBe(1_500);
    expect(of(s, "stockReleased")).toHaveLength(1);
  });

  it("the same checkout sent twice creates one order", () => {
    const s = run("double-tap", 40_000);
    expect(s.orders.size).toBe(1);
    expect(of(s, "idempotentReplay")).toHaveLength(1);
    expect(s.stock.get("SKU-1")).toBe(99);
    expect(s.ledger).toHaveLength(1);
  });

  it("the same Idempotency-Key with a different basket is refused", () => {
    const s = run("shop", 0);
    s.placeOrder({ idempotencyKey: "k", customerId: "CUST-1", productId: "SKU-1", quantity: 1 });
    const second = s.placeOrder({ idempotencyKey: "k", customerId: "CUST-1", productId: "SKU-1", quantity: 2 });
    expect(second.conflict).toBe(true);
    expect(s.orders.size).toBe(1);
  });

  it("a redelivered message is ignored by the inbox", () => {
    const s = run("redelivery", 40_000);
    expect(of(s, "duplicateIgnored")).toEqual([{ type: "duplicateIgnored", service: "inventory", orderId: "#1001", messageType: "ReserveInventory" }]);
    expect(s.stock.get("SKU-1")).toBe(99);
    expect(s.orders.get("#1001")?.status).toBe("CONFIRMED");
  });

  it("with Kafka down, checkout still works and messages go out when it returns", () => {
    const s = run("kafka-down", 14_000);
    expect(s.orders.get("#1001")?.saga).toBe("AWAITING_INVENTORY");
    expect(s.pendingOutbox("order")).toBe(1);
    expect(s.packets).toHaveLength(0);
    s.advanceBy(40_000);
    expect(s.orders.get("#1001")?.status).toBe("CONFIRMED");
    expect(s.pendingOutbox("order")).toBe(0);
  });

  it("payment down: the order times out, stock returns, and the late charge is refunded", () => {
    const s = run("payment-down", 90_000);
    expect(s.orders.get("#1001")).toMatchObject({ status: "CANCELLED", failureReason: "TIMEOUT" });
    expect(of(s, "staleIgnored").map((e) => e.messageType)).toContain("PaymentCompleted");
    expect(s.stock.get("SKU-1")).toBe(100);
    expect(s.wallets.get("CUST-1")).toBe(50_000);
    expect(s.ledger.map((l) => l.type)).toEqual(["DEBIT", "CREDIT"]);
  });

  it("an unreadable message is dead-lettered and the timeout cancels the order cleanly", () => {
    const s = run("poison", 60_000);
    expect(of(s, "retryScheduled").map((e) => e.delayMs)).toEqual([500, 1_000]);
    expect(s.deadLetters).toHaveLength(1);
    expect(s.orders.get("#1001")).toMatchObject({ status: "CANCELLED", failureReason: "TIMEOUT" });
    expect(of(s, "markerStored").map((e) => e.service).sort()).toEqual(["inventory", "payment"]);
    expect(s.stock.get("SKU-1")).toBe(100);
    expect(s.wallets.get("CUST-1")).toBe(50_000);
  });

  it("a late command after a cancel marker is refused", () => {
    const s = run("shop", 0);
    s.setServiceOnline("inventory", false);
    s.placeOrder({ idempotencyKey: "late", customerId: "CUST-1", productId: "SKU-1", quantity: 1 });
    s.advanceBy(40_000); // times out while inventory is down; Reserve and Release both wait in Kafka
    s.setServiceOnline("inventory", true);
    s.advanceBy(10_000);
    expect(s.stock.get("SKU-1")).toBe(100);
    expect(s.orders.get("#1001")?.status).toBe("CANCELLED");
  });
});
