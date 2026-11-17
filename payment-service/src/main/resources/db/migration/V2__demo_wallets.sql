-- Demo customers: CUST-1 can afford a lot, CUST-2 only a little (to see a payment fail and the stock released).
INSERT INTO wallets (customer_id, balance) VALUES ('CUST-1', 1000.00);
INSERT INTO wallets (customer_id, balance) VALUES ('CUST-2', 10.00);
