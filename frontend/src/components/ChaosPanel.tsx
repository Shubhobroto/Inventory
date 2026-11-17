import { useState } from "react";
import type { OrderSystem } from "../engine/system";

function Toggle({ label, hint, on, onChange }: { label: string; hint: string; on: boolean; onChange: (v: boolean) => void }) {
  return (
    <label className="flex items-center justify-between gap-3 py-2">
      <span>
        <span className="block text-sm font-medium">{label}</span>
        <span className="block text-xs text-neutral-500">{hint}</span>
      </span>
      <button
        role="switch"
        aria-checked={on}
        onClick={() => onChange(!on)}
        className={`relative h-[26px] w-[44px] shrink-0 rounded-full transition-colors ${on ? "bg-[#34c759]" : "bg-neutral-300"}`}
      >
        <span className={`absolute top-[2px] h-[22px] w-[22px] rounded-full bg-white shadow transition-all ${on ? "left-[20px]" : "left-[2px]"}`} />
      </button>
    </label>
  );
}

export function ChaosPanel({ system }: { system: OrderSystem }) {
  // One-shot flags don't change the system's version, so re-render on click.
  const [, setClicks] = useState(0);
  const arm = (fn: () => void) => () => {
    fn();
    setClicks((c) => c + 1);
  };

  return (
    <div className="rounded-3xl bg-white p-5 shadow-[0_2px_20px_rgba(0,0,0,0.05)]">
      <h3 className="text-lg font-semibold tracking-tight">Chaos controls</h3>
      <p className="text-xs text-neutral-500">Break part of the system, then place an order and watch it recover.</p>
      <div className="mt-2 divide-y divide-black/5">
        <Toggle label="Kafka broker" hint="Off: messages wait in the outbox" on={system.brokerUp} onChange={(v) => system.setBrokerUp(v)} />
        <Toggle label="Inventory service" hint="Off: commands wait in Kafka" on={system.online.inventory} onChange={(v) => system.setServiceOnline("inventory", v)} />
        <Toggle label="Payment service" hint="Off: the saga times out and compensates" on={system.online.payment} onChange={(v) => system.setServiceOnline("payment", v)} />
      </div>
      <div className="mt-3 grid gap-2 sm:grid-cols-2">
        <button
          onClick={arm(() => (system.redeliverNext = true))}
          className={`rounded-2xl px-3 py-2.5 text-left text-xs transition ${system.redeliverNext ? "bg-[#0071e3] text-white" : "bg-neutral-100 hover:bg-neutral-200"}`}
        >
          <span className="block font-semibold">Deliver the next message twice</span>
          <span className="block opacity-80">{system.redeliverNext ? "Armed: place an order" : "Tests the inbox"}</span>
        </button>
        <button
          onClick={arm(() => (system.corruptNext = true))}
          className={`rounded-2xl px-3 py-2.5 text-left text-xs transition ${system.corruptNext ? "bg-[#0071e3] text-white" : "bg-neutral-100 hover:bg-neutral-200"}`}
        >
          <span className="block font-semibold">Corrupt the next message</span>
          <span className="block opacity-80">{system.corruptNext ? "Armed: place an order" : "Tests retries and the dead-letter topic"}</span>
        </button>
      </div>
    </div>
  );
}
