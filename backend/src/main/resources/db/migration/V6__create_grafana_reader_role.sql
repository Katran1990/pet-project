-- Read-only login role for the Grafana "Wallet" dashboard (datasource "Wallet (prod Postgres)",
-- infra/monitoring/values.yaml). It may only read the tables used by db/report/by-category.sql.
-- No credential is set here: the role cannot log in until an operator sets it once per
-- database with ALTER USER (README "Grafana read-only user (grafana_reader)").
-- Roles are cluster-wide, not per database: create it only if it does not exist yet, so this
-- migration also succeeds where the role already exists (created by hand, or by another
-- database in the same Postgres instance).
do $$
begin
    if not exists (select 1 from pg_catalog.pg_roles where rolname = 'grafana_reader') then
        create role grafana_reader login;
    end if;
end
$$;

-- Defence in depth on top of the grants: every session of this role starts read-only.
alter role grafana_reader set default_transaction_read_only = on;

-- PUBLIC has USAGE on schema public by default; granted explicitly so the grants below do not
-- depend on that default.
grant usage on schema public to grafana_reader;
grant select on table category, expense, budget_limit to grafana_reader;
