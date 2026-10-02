-- One-tap expense presets. Templates are deleted, not archived: expenses created from a
-- template copy its values and do not reference it, so deleting a template loses no history.
create table quick_template (
    id          bigint generated always as identity primary key,
    name        varchar(64)    not null,
    category_id bigint         not null,
    amount      numeric(12, 2) not null,
    sort_order  int            not null,
    constraint fk_quick_template_category foreign key (category_id) references category (id) on delete restrict,
    constraint ck_quick_template_amount_positive check (amount > 0)
);
