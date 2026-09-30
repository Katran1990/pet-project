create table expense (
    id          bigint generated always as identity primary key,
    category_id bigint         not null,
    amount      numeric(12, 2) not null,
    currency    char(3)        not null default 'PLN',
    spent_on    date           not null,
    note        varchar(255),
    created_at  timestamptz    not null default now(),
    constraint fk_expense_category foreign key (category_id) references category (id) on delete restrict,
    constraint ck_expense_amount_positive check (amount > 0)
);

create index ix_expense_spent_on_category_id on expense (spent_on, category_id);
