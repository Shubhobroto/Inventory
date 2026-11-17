-- Demo products: plenty of SKU-1, a little SKU-2 (to try running out), none of SKU-3 (always rejected).
INSERT INTO stock (product_id, available) VALUES ('SKU-1', 100);
INSERT INTO stock (product_id, available) VALUES ('SKU-2', 5);
INSERT INTO stock (product_id, available) VALUES ('SKU-3', 0);
