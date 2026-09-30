-- One limit per category and month. month is always the first day of the month; the API speaks YYYY-MM.
create table budget_limit (
    id          bigint generated always as identity primary key,
    category_id bigint         not null,
    month       date           not null,
    amount      numeric(12, 2) not null,
    constraint fk_budget_limit_category foreign key (category_id) references category (id) on delete restrict,
    constraint ck_budget_limit_month_first_day check (extract(day from month) = 1),
    constraint ck_budget_limit_amount_positive check (amount > 0),
    constraint uq_budget_limit_category_month unique (category_id, month)
);
