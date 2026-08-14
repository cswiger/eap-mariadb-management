package com.example.dbtool;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.example.dbtool.Db.qi;
import static com.example.dbtool.Db.qt;

/**
 * JSON API backing the single-page UI.
 *
 * GET  /api/meta                                  server + connection info
 * GET  /api/databases                             list schemas
 * GET  /api/tables?db=                            list tables + views in a schema
 * GET  /api/structure?db=&table=                  columns, indexes, PK, SHOW CREATE
 * GET  /api/browse?db=&table=&page=&size=&sort=&dir=
 *
 * POST /api/sql              {db, sql}
 * POST /api/create_database  {name}
 * POST /api/drop_database    {name}
 * POST /api/create_table     {db, name, engine?, charset?, columns:[{name,type,nullable,autoInc,defaultValue?,comment?,pk}]}
 * POST /api/drop_table       {db, table, kind: "TABLE"|"VIEW"}
 * POST /api/truncate_table   {db, table}
 * POST /api/rename_table     {db, table, newName}
 * POST /api/add_column       {db, table, column:{...}}
 * POST /api/modify_column    {db, table, oldName, column:{...}}
 * POST /api/drop_column      {db, table, name}
 * POST /api/insert_row       {db, table, values:{col: string|null}}
 * POST /api/update_row       {db, table, values:{...}, key:{col: string|null}}
 * POST /api/delete_row       {db, table, key:{...}}
 */
@WebServlet(urlPatterns = "/api/*")
public class ApiServlet extends HttpServlet {

    private static final int MAX_CONSOLE_ROWS = 500;

    // ------------------------------------------------------------------ GET

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String path = req.getPathInfo() == null ? "" : req.getPathInfo();
        try {
            switch (path) {
                case "/meta"      -> meta(resp);
                case "/databases" -> databases(resp);
                case "/tables"    -> tables(req, resp);
                case "/structure" -> structure(req, resp);
                case "/browse"    -> browse(req, resp);
                default -> error(resp, 404, "Unknown endpoint: " + path);
            }
        } catch (Exception e) {
            error(resp, 400, message(e));
        }
    }

    // ----------------------------------------------------------------- POST

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String path = req.getPathInfo() == null ? "" : req.getPathInfo();
        JsonObject body;
        try {
            body = Json.createReader(req.getReader()).readObject();
        } catch (Exception e) {
            error(resp, 400, "Invalid JSON body");
            return;
        }
        try {
            switch (path) {
                case "/sql"             -> runSql(body, resp);
                case "/create_database" -> ddl(resp, null, "CREATE DATABASE " + qi(reqStr(body, "name"))
                        + " DEFAULT CHARACTER SET utf8mb4");
                case "/drop_database"   -> ddl(resp, null, "DROP DATABASE " + qi(reqStr(body, "name")));
                case "/create_table"    -> createTable(body, resp);
                case "/drop_table"      -> dropTable(body, resp);
                case "/truncate_table"  -> ddl(resp, str(body, "db"),
                        "TRUNCATE TABLE " + qt(str(body, "db"), reqStr(body, "table")));
                case "/rename_table"    -> ddl(resp, str(body, "db"),
                        "RENAME TABLE " + qt(str(body, "db"), reqStr(body, "table"))
                        + " TO " + qt(str(body, "db"), reqStr(body, "newName")));
                case "/add_column"      -> ddl(resp, str(body, "db"),
                        "ALTER TABLE " + qt(str(body, "db"), reqStr(body, "table"))
                        + " ADD COLUMN " + columnDef(body.getJsonObject("column")));
                case "/modify_column"   -> ddl(resp, str(body, "db"),
                        "ALTER TABLE " + qt(str(body, "db"), reqStr(body, "table"))
                        + " CHANGE COLUMN " + qi(reqStr(body, "oldName")) + " "
                        + columnDef(body.getJsonObject("column")));
                case "/drop_column"     -> ddl(resp, str(body, "db"),
                        "ALTER TABLE " + qt(str(body, "db"), reqStr(body, "table"))
                        + " DROP COLUMN " + qi(reqStr(body, "name")));
                case "/insert_row"      -> insertRow(body, resp);
                case "/update_row"      -> updateRow(body, resp);
                case "/delete_row"      -> deleteRow(body, resp);
                default -> error(resp, 404, "Unknown endpoint: " + path);
            }
        } catch (Exception e) {
            error(resp, 400, message(e));
        }
    }

    // ------------------------------------------------------------- handlers

    private void meta(HttpServletResponse resp) throws Exception {
        try (Connection c = Db.open(null);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT VERSION(), CURRENT_USER(), DATABASE()")) {
            rs.next();
            JsonObjectBuilder o = Json.createObjectBuilder()
                    .add("version", nz(rs.getString(1)))
                    .add("user", nz(rs.getString(2)))
                    .add("database", nz(rs.getString(3)))
                    .add("host", nz(System.getenv("MARIADB_HOST")))
                    .add("port", nz(orDefault(System.getenv("MARIADB_PORT"), "3306")))
                    .add("authEnabled", isAuthEnabled());
            ok(resp, o.build());
        }
    }

    private void databases(HttpServletResponse resp) throws Exception {
        try (Connection c = Db.open(null);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT schema_name FROM information_schema.schemata ORDER BY schema_name")) {
            JsonArrayBuilder arr = Json.createArrayBuilder();
            while (rs.next()) {
                String name = rs.getString(1);
                arr.add(Json.createObjectBuilder()
                        .add("name", name)
                        .add("system", isSystemSchema(name)));
            }
            ok(resp, Json.createObjectBuilder().add("databases", arr).build());
        }
    }

    private void tables(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        String db = required(req, "db");
        try (Connection c = Db.open(null);
             PreparedStatement ps = c.prepareStatement(
                     "SELECT table_name, table_type, engine, table_rows, "
                     + "ROUND((COALESCE(data_length,0)+COALESCE(index_length,0))/1024,1), table_comment "
                     + "FROM information_schema.tables WHERE table_schema = ? ORDER BY table_name")) {
            ps.setString(1, db);
            try (ResultSet rs = ps.executeQuery()) {
                JsonArrayBuilder arr = Json.createArrayBuilder();
                while (rs.next()) {
                    JsonObjectBuilder o = Json.createObjectBuilder()
                            .add("name", rs.getString(1))
                            .add("view", "VIEW".equalsIgnoreCase(rs.getString(2)))
                            .add("engine", nz(rs.getString(3)))
                            .add("sizeKb", rs.getDouble(5))
                            .add("comment", nz(rs.getString(6)));
                    long rows = rs.getLong(4);
                    if (rs.wasNull()) o.addNull("rows"); else o.add("rows", rows);
                    arr.add(o);
                }
                ok(resp, Json.createObjectBuilder().add("tables", arr).build());
            }
        }
    }

    private void structure(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        String db = required(req, "db");
        String table = required(req, "table");
        JsonObjectBuilder out = Json.createObjectBuilder();

        try (Connection c = Db.open(null)) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT column_name, column_type, is_nullable, column_key, column_default, extra, column_comment "
                    + "FROM information_schema.columns WHERE table_schema = ? AND table_name = ? "
                    + "ORDER BY ordinal_position")) {
                ps.setString(1, db);
                ps.setString(2, table);
                try (ResultSet rs = ps.executeQuery()) {
                    JsonArrayBuilder cols = Json.createArrayBuilder();
                    while (rs.next()) {
                        JsonObjectBuilder o = Json.createObjectBuilder()
                                .add("name", rs.getString(1))
                                .add("type", rs.getString(2))
                                .add("nullable", "YES".equalsIgnoreCase(rs.getString(3)))
                                .add("key", nz(rs.getString(4)))
                                .add("extra", nz(rs.getString(6)))
                                .add("comment", nz(rs.getString(7)));
                        String def = rs.getString(5);
                        if (def == null) o.addNull("default"); else o.add("default", def);
                        cols.add(o);
                    }
                    out.add("columns", cols);
                }
            }

            out.add("pk", toJson(primaryKey(c, db, table)));

            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT index_name, non_unique, GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ', ') "
                    + "FROM information_schema.statistics WHERE table_schema = ? AND table_name = ? "
                    + "GROUP BY index_name, non_unique ORDER BY index_name")) {
                ps.setString(1, db);
                ps.setString(2, table);
                try (ResultSet rs = ps.executeQuery()) {
                    JsonArrayBuilder idx = Json.createArrayBuilder();
                    while (rs.next()) {
                        idx.add(Json.createObjectBuilder()
                                .add("name", rs.getString(1))
                                .add("unique", rs.getInt(2) == 0)
                                .add("columns", nz(rs.getString(3))));
                    }
                    out.add("indexes", idx);
                }
            }

            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SHOW CREATE TABLE " + qt(db, table))) {
                if (rs.next()) {
                    out.add("createSql", rs.getString(2));
                }
            } catch (Exception e) {
                out.add("createSql", "-- unavailable: " + message(e));
            }
        }
        ok(resp, out.build());
    }

    private void browse(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        String db = required(req, "db");
        String table = required(req, "table");
        int page = Math.max(0, intParam(req, "page", 0));
        int size = Math.min(500, Math.max(1, intParam(req, "size", 50)));
        String sort = req.getParameter("sort");
        boolean desc = "desc".equalsIgnoreCase(req.getParameter("dir"));

        try (Connection c = Db.open(null)) {
            long total;
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + qt(db, table))) {
                rs.next();
                total = rs.getLong(1);
            }

            List<String> pk = primaryKey(c, db, table);

            StringBuilder sql = new StringBuilder("SELECT * FROM ").append(qt(db, table));
            if (sort != null && !sort.isBlank()) {
                sql.append(" ORDER BY ").append(qi(sort)).append(desc ? " DESC" : " ASC");
            }
            sql.append(" LIMIT ").append(size).append(" OFFSET ").append((long) page * size);

            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(sql.toString())) {
                JsonObjectBuilder out = resultSetJson(rs, size);
                out.add("total", total).add("page", page).add("size", size).add("pk", toJson(pk));
                ok(resp, out.build());
            }
        }
    }

    private void runSql(JsonObject body, HttpServletResponse resp) throws Exception {
        String db = str(body, "db");
        String raw = reqStr(body, "sql");
        List<String> statements = splitSql(raw);
        if (statements.isEmpty()) throw new IllegalArgumentException("No SQL to execute");

        JsonArrayBuilder results = Json.createArrayBuilder();
        try (Connection c = Db.open(db)) {
            for (String sql : statements) {
                long t0 = System.nanoTime();
                JsonObjectBuilder r = Json.createObjectBuilder().add("statement", abbreviate(sql));
                try (Statement st = c.createStatement()) {
                    boolean hasRs = st.execute(sql);
                    long ms = (System.nanoTime() - t0) / 1_000_000;
                    if (hasRs) {
                        try (ResultSet rs = st.getResultSet()) {
                            JsonObjectBuilder data = resultSetJson(rs, MAX_CONSOLE_ROWS);
                            r.add("resultSet", data).add("ms", ms);
                        }
                    } else {
                        r.add("affected", st.getUpdateCount()).add("ms", ms);
                    }
                } catch (Exception e) {
                    r.add("error", message(e));
                    results.add(r);
                    break; // stop on first failing statement
                }
                results.add(r);
            }
        }
        ok(resp, Json.createObjectBuilder().add("results", results).build());
    }

    private void createTable(JsonObject body, HttpServletResponse resp) throws Exception {
        String db = str(body, "db");
        String name = reqStr(body, "name");
        JsonArray cols = body.getJsonArray("columns");
        if (cols == null || cols.isEmpty()) throw new IllegalArgumentException("At least one column is required");

        List<String> defs = new ArrayList<>();
        List<String> pk = new ArrayList<>();
        for (JsonValue v : cols) {
            JsonObject col = v.asJsonObject();
            defs.add(columnDef(col));
            if (col.getBoolean("pk", false)) pk.add(qi(reqStr(col, "name")));
        }
        if (!pk.isEmpty()) defs.add("PRIMARY KEY (" + String.join(", ", pk) + ")");

        String engine = orDefault(str(body, "engine"), "InnoDB");
        String sql = "CREATE TABLE " + qt(db, name) + " (\n  " + String.join(",\n  ", defs)
                + "\n) ENGINE=" + engine + " DEFAULT CHARSET=utf8mb4";
        ddl(resp, db, sql);
    }

    private void dropTable(JsonObject body, HttpServletResponse resp) throws Exception {
        String kind = "VIEW".equalsIgnoreCase(str(body, "kind")) ? "VIEW" : "TABLE";
        ddl(resp, str(body, "db"), "DROP " + kind + " " + qt(str(body, "db"), reqStr(body, "table")));
    }

    private void insertRow(JsonObject body, HttpServletResponse resp) throws Exception {
        String db = str(body, "db");
        String table = reqStr(body, "table");
        Map<String, String> values = valueMap(body.getJsonObject("values"));
        if (values.isEmpty()) throw new IllegalArgumentException("No values supplied");

        StringBuilder sql = new StringBuilder("INSERT INTO ").append(qt(db, table)).append(" (");
        StringBuilder marks = new StringBuilder();
        boolean first = true;
        for (String col : values.keySet()) {
            if (!first) { sql.append(", "); marks.append(", "); }
            sql.append(qi(col));
            marks.append("?");
            first = false;
        }
        sql.append(") VALUES (").append(marks).append(")");

        try (Connection c = Db.open(null);
             PreparedStatement ps = c.prepareStatement(sql.toString())) {
            bind(ps, 1, values);
            int n = ps.executeUpdate();
            ok(resp, Json.createObjectBuilder().add("affected", n).build());
        }
    }

    private void updateRow(JsonObject body, HttpServletResponse resp) throws Exception {
        String db = str(body, "db");
        String table = reqStr(body, "table");
        Map<String, String> values = valueMap(body.getJsonObject("values"));
        Map<String, String> key = valueMap(body.getJsonObject("key"));
        if (values.isEmpty()) throw new IllegalArgumentException("No values supplied");
        if (key.isEmpty()) throw new IllegalArgumentException("No key supplied");

        StringBuilder sql = new StringBuilder("UPDATE ").append(qt(db, table)).append(" SET ");
        boolean first = true;
        for (String col : values.keySet()) {
            if (!first) sql.append(", ");
            sql.append(qi(col)).append(" = ?");
            first = false;
        }
        sql.append(" WHERE ").append(whereClause(key)).append(" LIMIT 1");

        try (Connection c = Db.open(null);
             PreparedStatement ps = c.prepareStatement(sql.toString())) {
            int i = bind(ps, 1, values);
            bindWhere(ps, i, key);
            int n = ps.executeUpdate();
            ok(resp, Json.createObjectBuilder().add("affected", n).build());
        }
    }

    private void deleteRow(JsonObject body, HttpServletResponse resp) throws Exception {
        String db = str(body, "db");
        String table = reqStr(body, "table");
        Map<String, String> key = valueMap(body.getJsonObject("key"));
        if (key.isEmpty()) throw new IllegalArgumentException("No key supplied");

        String sql = "DELETE FROM " + qt(db, table) + " WHERE " + whereClause(key) + " LIMIT 1";
        try (Connection c = Db.open(null);
             PreparedStatement ps = c.prepareStatement(sql)) {
            bindWhere(ps, 1, key);
            int n = ps.executeUpdate();
            ok(resp, Json.createObjectBuilder().add("affected", n).build());
        }
    }

    // -------------------------------------------------------------- helpers

    private void ddl(HttpServletResponse resp, String db, String sql) throws Exception {
        try (Connection c = Db.open(db);
             Statement st = c.createStatement()) {
            st.executeUpdate(sql);
        }
        ok(resp, Json.createObjectBuilder().add("ok", true).add("sql", sql).build());
    }

    /** Build a column definition fragment from a JSON column spec. */
    private String columnDef(JsonObject col) {
        if (col == null) throw new IllegalArgumentException("Missing column spec");
        String name = reqStr(col, "name");
        String type = reqStr(col, "type").trim();
        if (type.contains("`") || type.contains(";")) {
            throw new IllegalArgumentException("Invalid characters in column type");
        }
        StringBuilder sb = new StringBuilder(qi(name)).append(" ").append(type);
        if (!col.getBoolean("nullable", true)) sb.append(" NOT NULL");
        if (col.getBoolean("autoInc", false)) sb.append(" AUTO_INCREMENT");
        if (col.containsKey("defaultValue") && !col.isNull("defaultValue")) {
            sb.append(" DEFAULT ").append(defaultLiteral(col.getString("defaultValue")));
        }
        String comment = str(col, "comment");
        if (comment != null && !comment.isBlank()) {
            sb.append(" COMMENT '").append(comment.replace("'", "''")).append("'");
        }
        return sb.toString();
    }

    /** NULL / TRUE / FALSE / numbers / CURRENT_TIMESTAMP pass through raw; everything else is quoted. */
    private String defaultLiteral(String v) {
        String u = v.trim().toUpperCase();
        if (u.equals("NULL") || u.equals("TRUE") || u.equals("FALSE")
                || u.startsWith("CURRENT_TIMESTAMP") || u.matches("-?\\d+(\\.\\d+)?")) {
            return v.trim();
        }
        return "'" + v.replace("'", "''") + "'";
    }

    private List<String> primaryKey(Connection c, String db, String table) throws Exception {
        DatabaseMetaData md = c.getMetaData();
        Map<Integer, String> ordered = new java.util.TreeMap<>();
        try (ResultSet rs = md.getPrimaryKeys(db, null, table)) {
            while (rs.next()) {
                ordered.put(rs.getInt("KEY_SEQ"), rs.getString("COLUMN_NAME"));
            }
        }
        return new ArrayList<>(ordered.values());
    }

    /** Serialize a ResultSet to {columns, types, rows} with cell-level null and binary handling. */
    private JsonObjectBuilder resultSetJson(ResultSet rs, int maxRows) throws Exception {
        ResultSetMetaData md = rs.getMetaData();
        int n = md.getColumnCount();

        JsonArrayBuilder cols = Json.createArrayBuilder();
        JsonArrayBuilder types = Json.createArrayBuilder();
        boolean[] binary = new boolean[n + 1];
        for (int i = 1; i <= n; i++) {
            cols.add(md.getColumnLabel(i));
            types.add(md.getColumnTypeName(i));
            int t = md.getColumnType(i);
            binary[i] = t == Types.BINARY || t == Types.VARBINARY || t == Types.LONGVARBINARY || t == Types.BLOB;
        }

        JsonArrayBuilder rows = Json.createArrayBuilder();
        int count = 0;
        boolean truncated = false;
        while (rs.next()) {
            if (count >= maxRows) { truncated = true; break; }
            JsonArrayBuilder row = Json.createArrayBuilder();
            for (int i = 1; i <= n; i++) {
                if (binary[i]) {
                    byte[] b = rs.getBytes(i);
                    if (b == null) row.addNull();
                    else row.add(hexPreview(b));
                } else {
                    String s = rs.getString(i);
                    if (s == null) row.addNull();
                    else row.add(s);
                }
            }
            rows.add(row);
            count++;
        }
        return Json.createObjectBuilder()
                .add("columns", cols).add("types", types).add("rows", rows)
                .add("rowCount", count).add("truncated", truncated);
    }

    private String hexPreview(byte[] b) {
        int limit = Math.min(b.length, 64);
        StringBuilder sb = new StringBuilder("0x");
        for (int i = 0; i < limit; i++) sb.append(String.format("%02X", b[i]));
        if (b.length > limit) sb.append("… (").append(b.length).append(" bytes)");
        return sb.toString();
    }

    /** Split a script into individual statements, respecting quotes and comments. */
    static List<String> splitSql(String script) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inSingle = false, inDouble = false, inBacktick = false, lineComment = false, blockComment = false;
        for (int i = 0; i < script.length(); i++) {
            char ch = script.charAt(i);
            char next = i + 1 < script.length() ? script.charAt(i + 1) : '\0';

            if (lineComment) {
                cur.append(ch);
                if (ch == '\n') lineComment = false;
                continue;
            }
            if (blockComment) {
                cur.append(ch);
                if (ch == '*' && next == '/') { cur.append(next); i++; blockComment = false; }
                continue;
            }
            if (!inSingle && !inDouble && !inBacktick) {
                if (ch == '-' && next == '-') { lineComment = true; cur.append(ch); continue; }
                if (ch == '#') { lineComment = true; cur.append(ch); continue; }
                if (ch == '/' && next == '*') { blockComment = true; cur.append(ch); continue; }
                if (ch == '\'') inSingle = true;
                else if (ch == '"') inDouble = true;
                else if (ch == '`') inBacktick = true;
                else if (ch == ';') {
                    String s = cur.toString().trim();
                    if (!s.isEmpty()) out.add(s);
                    cur.setLength(0);
                    continue;
                }
            } else if (inSingle && ch == '\'') {
                if (next == '\'') { cur.append(ch); cur.append(next); i++; continue; }
                inSingle = false;
            } else if (inSingle && ch == '\\') {
                cur.append(ch);
                if (next != '\0') { cur.append(next); i++; }
                continue;
            } else if (inDouble && ch == '"') {
                inDouble = false;
            } else if (inBacktick && ch == '`') {
                inBacktick = false;
            }
            cur.append(ch);
        }
        String s = cur.toString().trim();
        if (!s.isEmpty()) out.add(s);
        return out;
    }

    private String whereClause(Map<String, String> key) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : key.entrySet()) {
            if (!first) sb.append(" AND ");
            sb.append(qi(e.getKey())).append(e.getValue() == null ? " IS NULL" : " = ?");
            first = false;
        }
        return sb.toString();
    }

    private int bind(PreparedStatement ps, int start, Map<String, String> values) throws Exception {
        int i = start;
        for (String v : values.values()) {
            if (v == null) ps.setNull(i, Types.VARCHAR); else ps.setString(i, v);
            i++;
        }
        return i;
    }

    private void bindWhere(PreparedStatement ps, int start, Map<String, String> key) throws Exception {
        int i = start;
        for (String v : key.values()) {
            if (v != null) { ps.setString(i, v); i++; }
        }
    }

    private Map<String, String> valueMap(JsonObject obj) {
        Map<String, String> map = new LinkedHashMap<>();
        if (obj == null) return map;
        for (Map.Entry<String, JsonValue> e : obj.entrySet()) {
            JsonValue v = e.getValue();
            if (v == null || v.getValueType() == JsonValue.ValueType.NULL) {
                map.put(e.getKey(), null);
            } else if (v.getValueType() == JsonValue.ValueType.STRING) {
                map.put(e.getKey(), ((JsonString) v).getString());
            } else {
                map.put(e.getKey(), v.toString());
            }
        }
        return map;
    }

    private JsonArrayBuilder toJson(List<String> list) {
        JsonArrayBuilder b = Json.createArrayBuilder();
        for (String s : list) b.add(s);
        return b;
    }

    private boolean isSystemSchema(String name) {
        return name.equalsIgnoreCase("information_schema") || name.equalsIgnoreCase("performance_schema")
                || name.equalsIgnoreCase("mysql") || name.equalsIgnoreCase("sys");
    }

    private boolean isAuthEnabled() {
        String p = System.getenv("DBTOOL_PASSWORD");
        return p != null && !p.isBlank();
    }

    private String abbreviate(String sql) {
        String s = sql.replaceAll("\\s+", " ").trim();
        return s.length() > 120 ? s.substring(0, 117) + "…" : s;
    }

    private static String message(Exception e) {
        String m = e.getMessage();
        return (m == null || m.isBlank()) ? e.getClass().getSimpleName() : m;
    }

    private static String nz(String s) { return s == null ? "" : s; }
    private static String orDefault(String s, String d) { return (s == null || s.isBlank()) ? d : s; }

    private static String str(JsonObject o, String key) {
        return (o != null && o.containsKey(key) && !o.isNull(key)) ? o.getString(key) : null;
    }

    private static String reqStr(JsonObject o, String key) {
        String v = str(o, key);
        if (v == null || v.isBlank()) throw new IllegalArgumentException("Missing field: " + key);
        return v;
    }

    private static String required(HttpServletRequest req, String name) {
        String v = req.getParameter(name);
        if (v == null || v.isBlank()) throw new IllegalArgumentException("Missing parameter: " + name);
        return v;
    }

    private static int intParam(HttpServletRequest req, String name, int def) {
        String v = req.getParameter(name);
        if (v == null || v.isBlank()) return def;
        return Integer.parseInt(v);
    }

    private void ok(HttpServletResponse resp, JsonObject obj) throws IOException {
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        resp.getWriter().write(obj.toString());
    }

    private void error(HttpServletResponse resp, int status, String msg) throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        resp.getWriter().write(Json.createObjectBuilder().add("error", msg).build().toString());
    }
}
