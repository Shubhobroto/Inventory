import type { Product } from "../engine/catalog";

/** Simple product illustrations drawn in SVG, so the demo needs no image assets. */
export function ProductArt({ product, size = "lg" }: { product: Product; size?: "lg" | "sm" }) {
  const box = size === "lg" ? "aspect-[4/3] rounded-2xl" : "h-14 w-14 rounded-xl";
  return (
    <div className={`flex items-center justify-center bg-linear-to-br ${product.gradient} ${box}`}>
      <svg viewBox="0 0 120 90" className={size === "lg" ? "h-3/4 w-3/4" : "h-11 w-11"} aria-hidden="true">
        {product.art === "buds" && (
          <g fill="#f5f5f7" stroke="#1d1d1f" strokeWidth="2.5">
            <rect x="34" y="40" width="10" height="34" rx="5" />
            <circle cx="42" cy="34" r="14" />
            <rect x="76" y="40" width="10" height="34" rx="5" />
            <circle cx="78" cy="34" r="14" />
            <circle cx="42" cy="34" r="5" fill="#1d1d1f" />
            <circle cx="78" cy="34" r="5" fill="#1d1d1f" />
          </g>
        )}
        {product.art === "watch" && (
          <g stroke="#1d1d1f" strokeWidth="2.5">
            <rect x="48" y="4" width="24" height="18" rx="6" fill="#fb7185" />
            <rect x="48" y="68" width="24" height="18" rx="6" fill="#fb7185" />
            <rect x="38" y="18" width="44" height="54" rx="14" fill="#1d1d1f" />
            <rect x="44" y="24" width="32" height="42" rx="9" fill="#111827" stroke="#374151" />
            <path d="M50 46 h6 l3 -8 l5 16 l3 -8 h6" fill="none" stroke="#fb923c" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" />
          </g>
        )}
        {product.art === "speaker" && (
          <g stroke="#1d1d1f" strokeWidth="2.5">
            <rect x="36" y="8" width="48" height="76" rx="22" fill="#f5f5f7" />
            <circle cx="60" cy="54" r="16" fill="#e5e7eb" />
            <circle cx="60" cy="54" r="6" fill="#1d1d1f" />
            <circle cx="60" cy="26" r="6" fill="#e5e7eb" />
          </g>
        )}
        {product.art === "keyboard" && (
          <g stroke="#1d1d1f" strokeWidth="2.5">
            <rect x="10" y="26" width="100" height="40" rx="8" fill="#f5f5f7" />
            {[0, 1, 2].map((row) =>
              Array.from({ length: 8 }, (_, col) => (
                <rect key={`${row}-${col}`} x={17 + col * 11.5} y={32 + row * 9} width="8" height="6" rx="1.5" fill="#e5e7eb" strokeWidth="1.2" />
              )),
            )}
            <rect x="34" y="57" width="52" height="5" rx="2" fill="#e5e7eb" strokeWidth="1.2" />
          </g>
        )}
      </svg>
    </div>
  );
}
