CREATE TABLE support_reply_deliveries (
    id BIGSERIAL PRIMARY KEY,
    reply_message_id BIGINT NOT NULL REFERENCES ticket_messages(id) ON DELETE CASCADE,
    ticket_id BIGINT NOT NULL REFERENCES tickets(id) ON DELETE CASCADE,
    recipient_user_id BIGINT NOT NULL REFERENCES users(id),
    acting_staff_user_id BIGINT NOT NULL REFERENCES users(id),
    acting_role TEXT NOT NULL,
    status TEXT NOT NULL,
    failure_code TEXT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ NULL,
    CONSTRAINT uq_support_reply_deliveries_reply_message UNIQUE (reply_message_id),
    CONSTRAINT support_reply_deliveries_acting_role_check
        CHECK (acting_role IN ('MANAGER', 'CLUB_ADMIN')),
    CONSTRAINT support_reply_deliveries_status_check
        CHECK (status IN ('pending', 'sending', 'delivered', 'failed', 'unconfirmed')),
    CONSTRAINT support_reply_deliveries_result_check
        CHECK (
            (status IN ('pending', 'sending', 'delivered') AND failure_code IS NULL)
            OR (status = 'failed' AND failure_code IS NOT NULL AND failure_code IN (
                'recipient_unavailable',
                'client_unavailable',
                'telegram_rejected'
            ))
            OR (status = 'unconfirmed' AND failure_code IS NOT NULL AND failure_code IN (
                'timeout',
                'transport_error',
                'canceled'
            ))
        ),
    CONSTRAINT support_reply_deliveries_completion_check
        CHECK (
            (status IN ('pending', 'sending') AND completed_at IS NULL)
            OR (status IN ('delivered', 'failed', 'unconfirmed') AND completed_at IS NOT NULL)
        )
);

CREATE INDEX idx_support_reply_deliveries_ticket_id
    ON support_reply_deliveries(ticket_id);
