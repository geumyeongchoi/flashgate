-- 대안 비교용(strategy=dblock): DB 비관락 재고 테이블. 채택안(Lua)에서는 사용하지 않는다.
CREATE TABLE product_stock (
    product_id BIGINT PRIMARY KEY,
    remaining  BIGINT NOT NULL
);
CREATE TABLE reservations_db (
    order_id   CHAR(36)    PRIMARY KEY,
    product_id BIGINT      NOT NULL,
    user_id    VARCHAR(64) NOT NULL,
    status     VARCHAR(16) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY ux_res_user (product_id, user_id)
);
