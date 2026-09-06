CREATE TABLE products (
    id            BIGINT       PRIMARY KEY,
    name          VARCHAR(200) NOT NULL,
    initial_stock BIGINT       NOT NULL,
    created_at    TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- 확정된 주문(컨슈머가 적재). idempotency_key UNIQUE 로 중복 소비 무해화.
CREATE TABLE orders (
    id               CHAR(36)     PRIMARY KEY,
    product_id       BIGINT       NOT NULL,
    user_id          VARCHAR(64)  NOT NULL,
    status           VARCHAR(16)  NOT NULL, -- CONFIRMED | CANCELLED
    idempotency_key  VARCHAR(128) NOT NULL,
    reason           VARCHAR(200),
    created_at       TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    confirmed_at     TIMESTAMP(3) NULL,
    UNIQUE KEY ux_orders_idem (idempotency_key),
    KEY ix_orders_product (product_id, status)
);

-- 트랜잭셔널 아웃박스. 핫패스는 이 테이블 INSERT 1건만 DB에 쓴다.
CREATE TABLE outbox (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    aggregate_id  CHAR(36)     NOT NULL,
    type          VARCHAR(64)  NOT NULL,
    payload       JSON         NOT NULL,
    created_at    TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    published_at  TIMESTAMP(3) NULL,
    KEY ix_outbox_unpublished (published_at, id)
);
