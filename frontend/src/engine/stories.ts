import { OrderSystem, type PlaceOrderInput } from "./system";

export interface StoryAction {
  at: number;
  run: (system: OrderSystem) => void;
}

export interface Story {
  id: string;
  title: string;
  summary: string;
  setup?: (system: OrderSystem) => void;
  actions: StoryAction[];
}

const order = (at: number, input: PlaceOrderInput): StoryAction => ({ at, run: (s) => s.placeOrder(input) });
const aarav = (key: string, productId = "SKU-1", quantity = 1): PlaceOrderInput => ({ idempotencyKey: key, customerId: "CUST-1", productId, quantity });

export const STORIES: Story[] = [
  {
    id: "shop",
    title: "Shop freely",
    summary: "Add products to your bag and check out. Switch the shopper to Diya to see a wallet that's too small.",
    actions: [],
  },
  {
    id: "happy",
    title: "A perfect order",
    summary: "Aarav buys AirFlow Buds. Stock is reserved, the wallet is charged, the order is confirmed.",
    actions: [order(600, aarav("story-happy"))],
  },
  {
    id: "last-unit",
    title: "Last one left",
    summary: "Only one Studio Speaker is left. Rohan and Aarav check out at the same moment; exactly one of them gets it.",
    actions: [
      order(600, { idempotencyKey: "story-rohan", customerId: "CUST-3", productId: "SKU-3", quantity: 1 }),
      order(800, aarav("story-last-unit", "SKU-3")),
    ],
  },
  {
    id: "declined",
    title: "Payment declined",
    summary: "Diya orders an Apple Watch, but her wallet can't cover it. The saga gives the reserved stock back.",
    actions: [order(600, { idempotencyKey: "story-diya", customerId: "CUST-2", productId: "SKU-2", quantity: 1 })],
  },
  {
    id: "double-tap",
    title: "Double-tapped checkout",
    summary: "The app times out and sends the same checkout again with the same Idempotency-Key. Still one order.",
    actions: [order(600, aarav("story-double-tap")), order(2_200, aarav("story-double-tap"))],
  },
  {
    id: "redelivery",
    title: "A message arrives twice",
    summary: "Kafka delivers the reserve-stock command a second time. The inventory inbox recognises it.",
    setup: (s) => (s.redeliverNext = true),
    actions: [order(600, aarav("story-redelivery"))],
  },
  {
    id: "kafka-down",
    title: "Kafka goes down",
    summary: "The broker is unreachable for 15 seconds. Checkout still works; messages wait in the outbox and go out in order.",
    setup: (s) => s.setBrokerUp(false),
    actions: [order(600, aarav("story-kafka-down")), { at: 15_000, run: (s) => s.setBrokerUp(true) }],
  },
  {
    id: "payment-down",
    title: "Payment service is down",
    summary: "Payment is offline, so the order times out and is compensated. When payment comes back, the late charge is refunded.",
    setup: (s) => s.setServiceOnline("payment", false),
    actions: [order(600, aarav("story-payment-down")), { at: 55_000, run: (s) => s.setServiceOnline("payment", true) }],
  },
  {
    id: "poison",
    title: "An unreadable message",
    summary: "The reserve command can't be read. After 3 attempts it's parked in a dead-letter topic, and the timeout cleans up.",
    setup: (s) => (s.corruptNext = true),
    actions: [order(600, aarav("story-poison"))],
  },
];

export function loadStory(story: Story): OrderSystem {
  const system = new OrderSystem();
  story.setup?.(system);
  story.actions.forEach((a) => system.clock.at(a.at, () => a.run(system)));
  return system;
}

export const storyById = (id: string) => STORIES.find((s) => s.id === id) ?? STORIES[0];
