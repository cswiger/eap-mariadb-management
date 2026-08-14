# eap-mariadb-dbtool — MariaDB Workbench for OpenShift

A general-purpose MariaDB management web app for JBoss EAP 8.1 on OpenShift.
Same build pattern as the hello-world project: **you never run Maven yourself** —
the JBoss EAP 8.1 Helm chart builds this repo inside the EAP builder image
(`mvn package -Popenshift`) and deploys the result.

## Features

- Database browser: list schemas, create and drop databases
- Table management: create tables with a column builder (any type — free-form
  MariaDB type syntax), drop, rename, truncate; views are listed and droppable
- Structure editing: add, modify, and drop columns; index listing; SHOW CREATE
- Data browsing: pagination, click-to-sort, row counts, NULL and binary display
- Row editing: inline edit and delete (keyed by primary key, or a full-row
  match with LIMIT 1 for tables without one), insert with per-column NULL toggles
- SQL console: run any SQL, multiple statements per run (quote/comment-aware
  splitting), result sets and affected-row counts with timings
- CSV export (full table download) and CSV import (header row maps to columns,
  `\N` = NULL, batched inserts inside a transaction)
- Optional password gate via the `DBTOOL_PASSWORD` env var

## What's in here

- `pom.xml` — provisions an EAP 8.1 `cloud-server`, adds the MariaDB driver
  module, runs the datasource CLI script.
- `src/main/server-content/.../module.xml` — JBoss module for the MariaDB driver.
- `src/main/scripts/mariadb-datasource.cli` — registers the driver + a datasource
  at `java:jboss/datasources/MariaDBDS`. Connection details resolve at runtime
  from env vars (`MARIADB_HOST`, `MARIADB_PORT`, `MARIADB_DATABASE`,
  `MARIADB_USER`, `MARIADB_PASSWORD`).
- `src/main/webapp/index.html` — the whole UI (single page, no build step).
- `src/main/java/com/example/dbtool/`
  - `ApiServlet.java` — JSON API: schemas, tables, structure, browse, DDL, DML, SQL console
  - `ExportServlet.java` / `ImportServlet.java` — CSV out / in
  - `AuthFilter.java` — optional password gate
  - `Db.java` — datasource lookup + identifier quoting

## Deploy

1. Push this repo to Git (GitHub or anywhere your cluster can reach).
2. Create the DB secret in the namespace where the app will run:
   ```
   oc create secret generic mariadb-app-creds \
     --from-literal=user=<dbuser> \
     --from-literal=password='<dbpassword>' \
     -n <namespace>
   ```
3. Developer console → **+Add** → **Helm Chart** → **JBoss EAP 8.1**:
   - `build.uri` → this repo's URL
   - `deploy.env`:

   | Name | Value |
   |---|---|
   | `MARIADB_HOST` | your MariaDB service name (e.g. `mariadb`) |
   | `MARIADB_PORT` | `3306` |
   | `MARIADB_DATABASE` | default database (e.g. `sampledb`) |
   | `MARIADB_USER` | from secret `mariadb-app-creds`, key `user` |
   | `MARIADB_PASSWORD` | from secret `mariadb-app-creds`, key `password` |
   | `DBTOOL_PASSWORD` | *(optional but recommended)* UI sign-in password |

4. Wait for the build, click the Route.

## Notes on privileges and safety

- The tool can only do what `MARIADB_USER` is allowed to do. Creating/dropping
  *databases* needs a user with those grants; a single-schema user still gets
  full table/data management within its schema.
- This is a raw SQL surface by design. If the Route is reachable beyond your
  team, set `DBTOOL_PASSWORD` (served over the Route's TLS) or restrict the
  Route / add a NetworkPolicy. There is no undo for DROP.
- Row edits from the browser bind all values as strings and let MariaDB coerce
  types; binary columns display as hex previews and aren't editable inline —
  use the SQL console for those.
