-- Demo accounts shared by the whole team, so everyone's scripts and demos match.
INSERT INTO accounts (id, holder_name, currency, balance, available, status, created_at, updated_at, version) VALUES
    ('ACC-1001',   'Asha Rao',     'INR', 10000.00, 10000.00, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
    ('ACC-2002',   'Vikram Shah',  'INR',  5000.00,  5000.00, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
    ('ACC-EMPTY',  'Empty Wallet', 'INR',     0.00,     0.00, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
    ('ACC-FROZEN', 'Frozen User',  'INR',  1000.00,  1000.00, 'FROZEN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
    ('ACC-USD-1',  'Dollar User',  'USD',   500.00,   500.00, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0);
