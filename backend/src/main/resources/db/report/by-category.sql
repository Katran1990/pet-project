-- Monthly report by category.
-- Used by GET /api/reports/by-category (CategoryReportRepository) and as the source of the
-- first Grafana panel, which substitutes its own month expression for the month parameter.
-- Parameter month: the first day of the month (a date).
-- One row per category that has expenses and/or a limit in that month, sorted by amount desc,
-- then category id. Money keeps scale 2; share is the percent of the month total with scale 1,
-- 0.0 when the total is zero.
with params as (
    select cast(:month as date) as first_day
),
spent as (
    select e.category_id, sum(e.amount) as amount
    from expense e
    cross join params p
    where e.spent_on >= p.first_day
      and e.spent_on < cast(p.first_day + interval '1 month' as date)
    group by e.category_id
),
month_limit as (
    select l.category_id, l.amount
    from budget_limit l
    cross join params p
    where l.month = p.first_day
),
combined as (
    select coalesce(s.category_id, ml.category_id) as category_id,
           coalesce(s.amount, 0.00)                as amount,
           ml.amount                               as limit_amount
    from spent s
    full outer join month_limit ml on ml.category_id = s.category_id
)
select c.id                      as category_id,
       c.name                    as category_name,
       c.icon                    as category_icon,
       x.amount,
       coalesce(round(100 * x.amount / nullif(sum(x.amount) over w, 0), 1), 0.0) as share,
       x.limit_amount,
       x.limit_amount - x.amount as remaining,
       sum(x.amount) over w      as total_amount
from combined x
join category c on c.id = x.category_id
window w as ()
order by x.amount desc, c.id
