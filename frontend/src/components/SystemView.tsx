import type { ReactNode } from "react";
import { customerById, PRODUCTS, rupees } from "../engine/catalog";
import type { OrderSystem } from "../engine/system";
import type { Endpoint, MessageType, Packet, ServiceName } from "../engine/types";
import { MESSAGE_LABEL } from "./Orders";

// Layout of the diagram in percent of its box.
const BUS_X = 50;
const SERVICE_Y: Record<ServiceName, number> = { order: 50, inventory: 24, payment: 76 };
const EDGE_X: Record<ServiceName, number> = { order: 29, inventory: 71, payment: 71 };
const DLT = { x: 50, y: 90 };
const DONE_VISIBLE_MS = 1_400;

type Point = { x: number; y: number };

function route(from: Endpoint, to: ServiceName): Point[] {
  const end = { x: EDGE_X[to], y: SERVICE_Y[to] };
  if (from === "kafka") return [{ x: BUS_X, y: SERVICE_Y[to] }, end];
  return [{ x: EDGE_X[from], y: SERVICE_Y[from] }, { x: BUS_X, y: SERVICE_Y[from] }, { x: BUS_X, y: SERVICE_Y[to] }, end];
}

function along(points: Point[], t: number): Point {
  const lengths = points.slice(1).map((p, i) => Math.hypot(p.x - points[i].x, p.y - points[i].y));
  let remaining = Math.min(Math.max(t, 0), 1) * lengths.reduce((a, b) => a + b, 0);
  for (let i = 0; i < lengths.length; i++) {
    if (remaining <= lengths[i] || i === lengths.length - 1) {
      const f = lengths[i] === 0 ? 1 : Math.min(remaining / lengths[i], 1);
      return { x: points[i].x + (points[i + 1].x - points[i].x) * f, y: points[i].y + (points[i + 1].y - points[i].y) * f };
    }
    remaining -= lengths[i];
  }
  return points[points.length - 1];
}

function ease(t: number): number {
  return t < 0.5 ? 2 * t * t : 1 - (-2 * t + 2) ** 2 / 2;
}

const COMMANDS: MessageType[] = ["ReserveInventory", "ChargeCustomer"];
const COMPENSATION: MessageType[] = ["ReleaseInventory", "RefundCustomer", "InventoryReleased", "PaymentRefunded"];
const BAD_NEWS: MessageType[] = ["InventoryRejected", "PaymentFailed"];

function packetColor(type: MessageType): string {
  if (COMMANDS.includes(type)) return "bg-[#0071e3] text-white";
  if (COMPENSATION.includes(type)) return "bg-violet-500 text-white";
  if (BAD_NEWS.includes(type)) return "bg-orange-500 text-white";
  return "bg-emerald-500 text-white";
}

function position(p: Packet, now: number): Point | null {
  const target = { x: EDGE_X[p.to], y: SERVICE_Y[p.to] };
  switch (p.state) {
    case "flying":
      return along(route(p.from, p.to), ease((now - p.sentAt) / (p.arriveAt - p.sentAt)));
    case "waiting":
      return { x: BUS_X, y: SERVICE_Y[p.to] };
    case "processing":
    case "retrying":
      return target;
    case "dead":
      return DLT;
    case "done":
      return now - p.stateAt < DONE_VISIBLE_MS ? target : null;
  }
}

interface Props {
  system: OrderSystem;
  selectedId: string | null;
}

export function SystemView({ system, selectedId }: Props) {
  const now = system.now();
  const busy = (s: ServiceName) => system.packets.some((p) => p.to === s && (p.state === "processing" || p.state === "retrying"));
  const visible = system.packets.filter((p) => p.state !== "dead" || p === system.deadLetters[system.deadLetters.length - 1]);

  return (
    <div className="relative h-[440px] w-full overflow-hidden rounded-3xl bg-linear-to-b from-white to-neutral-50 shadow-[0_2px_20px_rgba(0,0,0,0.05)]">
      {/* Kafka */}
      <div className="absolute left-1/2 top-[6%] flex h-[80%] w-[15%] -translate-x-1/2 flex-col items-center rounded-2xl bg-neutral-900 py-3 text-white">
        <span className="text-[11px] font-semibold tracking-wide">Kafka</span>
        <span className={`mt-1 rounded-full px-2 py-0.5 text-[9px] ${system.brokerUp ? "bg-emerald-500/20 text-emerald-300" : "bg-red-500/30 text-red-200"}`}>
          {system.brokerUp ? "broker up" : "broker down"}
        </span>
        <span className="mt-auto text-center text-[8px] leading-tight text-white/50">4 topics<br />key = order id</span>
      </div>

      {/* Dead-letter topic */}
      <div className="absolute bottom-[2%] left-1/2 -translate-x-1/2 rounded-full bg-red-50 px-3 py-0.5 text-[10px] font-medium text-red-700">
        DLT · {system.deadLetters.length}
      </div>

      {/* Lanes */}
      <svg className="pointer-events-none absolute inset-0 h-full w-full" viewBox="0 0 100 100" preserveAspectRatio="none">
        {(["order", "inventory", "payment"] as ServiceName[]).map((s) => (
          <line key={s} x1={EDGE_X[s]} y1={SERVICE_Y[s]} x2={BUS_X} y2={SERVICE_Y[s]} stroke="#d4d4d8" strokeWidth="0.4" strokeDasharray="1 1" vectorEffect="non-scaling-stroke" />
        ))}
      </svg>

      <ServiceTile name="Order service" sub="REST API · saga orchestrator" x={2} y={30} online busy={busy("order")}>
        <Row label="Orders" value={String(system.orders.size)} />
        <Row label="In progress" value={String([...system.orders.values()].filter((o) => o.saga !== "COMPLETED" && o.saga !== "FAILED").length)} />
        <Row label="Outbox waiting" value={String(system.pendingOutbox("order"))} highlight={system.pendingOutbox("order") > 0} />
        <Row label="Inbox" value={`${system.inbox.order.size} seen`} />
      </ServiceTile>

      <ServiceTile name="Inventory" sub="stock · reservations" x={71} y={4} online={system.online.inventory} busy={busy("inventory")}>
        {PRODUCTS.map((p) => (
          <Row key={p.id} label={p.name.split(" ")[1] ?? p.name} value={String(system.stock.get(p.id))} />
        ))}
        <Row label="Outbox waiting" value={String(system.pendingOutbox("inventory"))} highlight={system.pendingOutbox("inventory") > 0} />
      </ServiceTile>

      <ServiceTile name="Payment" sub="wallets · ledger" x={71} y={54} online={system.online.payment} busy={busy("payment")}>
        {["CUST-1", "CUST-2", "CUST-3"].map((id) => (
          <Row key={id} label={customerById(id).name} value={rupees(system.wallets.get(id) ?? 0)} />
        ))}
        <Row label="Ledger rows" value={String(system.ledger.length)} />
        <Row label="Outbox waiting" value={String(system.pendingOutbox("payment"))} highlight={system.pendingOutbox("payment") > 0} />
      </ServiceTile>

      {/* Messages in flight */}
      {visible.map((p) => {
        const pos = position(p, now);
        if (!pos) return null;
        const dimmed = selectedId !== null && p.envelope.orderId !== selectedId;
        const fading = p.state === "done";
        return (
          <div
            key={p.id}
            className={`absolute z-20 -translate-x-1/2 -translate-y-1/2 whitespace-nowrap rounded-full px-2 py-0.5 text-[10px] font-semibold shadow-md ${packetColor(p.envelope.type)} ${
              p.state === "waiting" ? "animate-pulse" : ""
            } ${p.state === "retrying" ? "ring-2 ring-red-400" : ""}`}
            style={{ left: `${pos.x}%`, top: `${pos.y}%`, opacity: dimmed ? 0.2 : fading ? 1 - (now - p.stateAt) / DONE_VISIBLE_MS : 1 }}
            title={`Topic ${p.topic}, key ${p.envelope.orderId}`}
          >
            {MESSAGE_LABEL[p.envelope.type]}
            <span className="ml-1 font-normal opacity-80">{p.envelope.orderId}</span>
            {p.isRedelivery && <span className="ml-1 font-normal">(again)</span>}
            {p.state === "waiting" && <span className="ml-1 font-normal">waiting</span>}
            {p.state === "retrying" && <span className="ml-1 font-normal">retry {p.attempt + 1}</span>}
          </div>
        );
      })}
    </div>
  );
}

function ServiceTile(props: { name: string; sub: string; x: number; y: number; online: boolean; busy: boolean; children: ReactNode }) {
  return (
    <div
      className={`absolute z-10 w-[27%] rounded-2xl border bg-white p-3 transition ${props.online ? "border-black/5" : "border-dashed border-red-300 opacity-70"} ${
        props.busy ? "pulse-ring" : ""
      }`}
      style={{ left: `${props.x}%`, top: `${props.y}%` }}
    >
      <div className="flex items-center justify-between gap-1">
        <p className="truncate text-[12px] font-semibold">{props.name}</p>
        <span className={`h-2 w-2 shrink-0 rounded-full ${props.online ? "bg-emerald-500" : "bg-red-500"}`} title={props.online ? "online" : "offline"} />
      </div>
      <p className="mb-1.5 truncate text-[9px] text-neutral-400">{props.online ? props.sub : "offline"}</p>
      <div className="space-y-0.5">{props.children}</div>
    </div>
  );
}

function Row({ label, value, highlight }: { label: string; value: string; highlight?: boolean }) {
  return (
    <div className={`flex justify-between gap-2 text-[10px] ${highlight ? "font-semibold text-orange-600" : "text-neutral-600"}`}>
      <span className="truncate">{label}</span>
      <span className="tabular-nums">{value}</span>
    </div>
  );
}
