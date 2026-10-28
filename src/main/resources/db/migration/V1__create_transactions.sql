CREATE TABLE transactions (
    id                      UUID           PRIMARY KEY,
    account_id              VARCHAR(100)   NOT NULL,
    type                    VARCHAR(10)    NOT NULL,
    amount                  NUMERIC(19, 2) NOT NULL,
    currency                VARCHAR(3)     NOT NULL,
    description             VARCHAR(255),
    status                  VARCHAR(10)    NOT NULL,
    provider_transaction_id VARCHAR(100),
    balance_after           NUMERIC(19, 2),
    -- code y message del proveedor cuando rechaza (RN06).
    code                    VARCHAR(100),
    message                 VARCHAR(500),
    idempotency_key         VARCHAR(100),
    created_at              TIMESTAMPTZ    NOT NULL,
    CONSTRAINT ck_transactions_type CHECK (type IN ('CREDIT', 'DEBIT')),
    CONSTRAINT ck_transactions_status CHECK (status IN ('PENDING', 'EXECUTED', 'REJECTED', 'FAILED', 'UNKNOWN')),
    CONSTRAINT uk_transactions_idempotency_key UNIQUE (idempotency_key)
);

-- Filtros de RN07 con el orden estable de la paginación (created_at, id) como sufijo.
CREATE INDEX ix_transactions_created_at_id ON transactions (created_at, id);
CREATE INDEX ix_transactions_account_id_created_at_id ON transactions (account_id, created_at, id);
CREATE INDEX ix_transactions_status_created_at_id ON transactions (status, created_at, id);
CREATE INDEX ix_transactions_type_created_at_id ON transactions (type, created_at, id);
