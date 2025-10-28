create table if not exists payments (
    id uuid default RANDOM_UUID() primary key,
    booking_id uuid null,
    provider text not null,
    currency varchar(8) not null,
    amount_minor bigint not null,
    status text not null check (status in ('INITIATED','PENDING','CAPTURED','REFUNDED','DECLINED')),
    payload text not null unique,
    external_id text null,
    idempotency_key text not null unique,
    created_at TIMESTAMP WITH TIME ZONE default now(),
    updated_at TIMESTAMP WITH TIME ZONE default now()
);
