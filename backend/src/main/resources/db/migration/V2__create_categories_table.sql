create table category (
    id         bigint generated always as identity primary key,
    name       varchar(64) not null,
    icon       varchar(32),
    archived   boolean not null default false,
    created_at timestamptz not null default now()
);

-- Names are unique regardless of case ("Food" and "food" collide), archived rows included.
-- This index replaces a plain UNIQUE on name, which would only be case-sensitive.
create unique index uq_category_name_lower on category (lower(name));
