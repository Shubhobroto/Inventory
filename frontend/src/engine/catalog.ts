export interface Product {
  id: string;
  name: string;
  tagline: string;
  price: number;
  stock: number;
  art: "buds" | "watch" | "speaker" | "keyboard";
  gradient: string;
}

export interface Customer {
  id: string;
  name: string;
  balance: number;
  note: string;
}

// Demo catalog. Stock and balances are chosen so every story can happen (a last unit, a wallet that's too small).
export const PRODUCTS: Product[] = [
  { id: "SKU-1", name: "AirFlow Buds", tagline: "Silence, on demand.", price: 2_499, stock: 100, art: "buds", gradient: "from-sky-100 to-indigo-100" },
  { id: "SKU-2", name: "Apple Watch", tagline: "Every beat, counted.", price: 4_999, stock: 5, art: "watch", gradient: "from-rose-100 to-orange-100" },
  { id: "SKU-3", name: "Studio Speaker", tagline: "Room-filling sound.", price: 7_999, stock: 1, art: "speaker", gradient: "from-emerald-100 to-teal-100" },
  { id: "SKU-4", name: "Glide Keyboard", tagline: "Type at the speed of thought.", price: 3_499, stock: 20, art: "keyboard", gradient: "from-violet-100 to-fuchsia-100" },
];

export const CUSTOMERS: Customer[] = [
  { id: "CUST-1", name: "Aarav", balance: 50_000, note: "Plenty in the wallet" },
  { id: "CUST-2", name: "Diya", balance: 1_500, note: "Low wallet balance" },
  { id: "CUST-3", name: "Rohan", balance: 20_000, note: "Another shopper" },
];

export const productById = (id: string) => PRODUCTS.find((p) => p.id === id)!;
export const customerById = (id: string) => CUSTOMERS.find((c) => c.id === id)!;

export const rupees = (n: number) => `\u20B9${n.toLocaleString("en-IN")}`;
