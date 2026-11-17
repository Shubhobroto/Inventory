import { useMemo, useState } from "react";
import type { OrderSystem } from "../engine/system";
import type { LogSource } from "../engine/types";

const SOURCE_STYLE: Record<LogSource, string> = {
  client: "bg-neutral-800 text-white",
  order: "bg-[#0071e3] text-white",
  inventory: "bg-emerald-600 text-white",
  payment: "bg-violet-600 text-white",
  kafka: "bg-neutral-500 text-white",
};

const LEVEL_STYLE = { info: "", warn: "bg-orange-50", error: "bg-red-50", quiet: "opacity-60" };

export function RawLog({ system, version, selectedId }: { system: OrderSystem; version: number; selectedId: string | null }) {
  const [open, setOpen] = useState(false);
  const rows = useMemo(
    () =>
      system.logs
        .filter((l) => l.level !== "quiet" && (selectedId === null || l.orderId === selectedId || l.orderId === undefined))
        .slice(-200)
        .reverse(),
    // version changes whenever a log line is added
    [system, version, selectedId],
  );

  return (
    <div className="rounded-3xl bg-white shadow-[0_2px_20px_rgba(0,0,0,0.05)]">
      <button onClick={() => setOpen(!open)} className="flex w-full items-center justify-between px-5 py-4 text-left">
        <span>
          <span className="block text-lg font-semibold tracking-tight">Every SQL statement and Kafka call</span>
          <span className="block text-xs text-neutral-500">{selectedId ? `Only order ${selectedId}` : "All orders"} · newest first</span>
        </span>
        <span className={`text-neutral-400 transition-transform ${open ? "rotate-90" : ""}`}>{">"}</span>
      </button>
      {open && (
        <div className="max-h-80 overflow-y-auto border-t border-black/5 font-mono text-[11px]">
          {rows.length === 0 && <p className="p-4 text-neutral-400">Nothing yet.</p>}
          {rows.map((l) => (
            <div key={l.id} className={`flex gap-2 border-b border-black/[0.03] px-4 py-1.5 ${LEVEL_STYLE[l.level]}`}>
              <span className="w-12 shrink-0 text-neutral-400">{(l.t / 1000).toFixed(1)}s</span>
              <span className={`h-fit w-16 shrink-0 rounded px-1 text-center text-[9px] font-semibold ${SOURCE_STYLE[l.source]}`}>{l.source}</span>
              <span className="min-w-0 flex-1 break-words">
                {l.text}
                {l.result && <span className="text-neutral-500"> {"->"} {l.result}</span>}
              </span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
