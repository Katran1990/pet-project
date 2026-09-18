create table greetings (
    id      bigint generated always as identity primary key,
    message varchar(255) not null
);

insert into greetings (message) values ('Hello from Postgres');
