package com.example.dbtool;

import jakarta.json.Json;
import jakarta.servlet.annotation.MultipartConfig;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * POST /import  (multipart: db, table, file)
 * The first CSV record is treated as a header row whose fields must be
 * column names in the target table. \N (unquoted) is imported as SQL NULL.
 */
@WebServlet(urlPatterns = "/import")
@MultipartConfig(maxFileSize = 50L * 1024 * 1024, maxRequestSize = 55L * 1024 * 1024)
public class ImportServlet extends HttpServlet {

    private static final int BATCH = 200;

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            String db = req.getParameter("db");
            String table = req.getParameter("table");
            Part file = req.getPart("file");
            if (db == null || db.isBlank() || table == null || table.isBlank() || file == null) {
                throw new IllegalArgumentException("db, table, and file are required");
            }

            List<List<String>> records = parseCsv(
                    new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8)));
            if (records.size() < 2) {
                throw new IllegalArgumentException("CSV needs a header row plus at least one data row");
            }

            List<String> header = records.get(0);
            StringBuilder cols = new StringBuilder();
            StringBuilder marks = new StringBuilder();
            for (int i = 0; i < header.size(); i++) {
                if (i > 0) { cols.append(", "); marks.append(", "); }
                cols.append(Db.qi(header.get(i).trim()));
                marks.append("?");
            }
            String sql = "INSERT INTO " + Db.qt(db, table) + " (" + cols + ") VALUES (" + marks + ")";

            int inserted = 0;
            try (Connection c = Db.open(null)) {
                boolean oldAuto = c.getAutoCommit();
                c.setAutoCommit(false);
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    int pending = 0;
                    for (int r = 1; r < records.size(); r++) {
                        List<String> rec = records.get(r);
                        for (int i = 0; i < header.size(); i++) {
                            String v = i < rec.size() ? rec.get(i) : null;
                            if (v == null || v.equals("\\N")) ps.setNull(i + 1, Types.VARCHAR);
                            else ps.setString(i + 1, v);
                        }
                        ps.addBatch();
                        pending++;
                        if (pending >= BATCH) {
                            ps.executeBatch();
                            inserted += pending;
                            pending = 0;
                        }
                    }
                    if (pending > 0) {
                        ps.executeBatch();
                        inserted += pending;
                    }
                    c.commit();
                } catch (Exception e) {
                    c.rollback();
                    throw e;
                } finally {
                    c.setAutoCommit(oldAuto);
                }
            }

            resp.setContentType("application/json");
            resp.setCharacterEncoding("UTF-8");
            resp.getWriter().write(Json.createObjectBuilder().add("inserted", inserted).build().toString());
        } catch (Exception e) {
            resp.setStatus(400);
            resp.setContentType("application/json");
            resp.setCharacterEncoding("UTF-8");
            String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            resp.getWriter().write(Json.createObjectBuilder().add("error", m).build().toString());
        }
    }

    /** Minimal RFC-4180 CSV parser: quoted fields, escaped quotes, embedded newlines. */
    static List<List<String>> parseCsv(BufferedReader in) throws IOException {
        List<List<String>> records = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        boolean fieldWasQuoted = false;
        int ci;
        while ((ci = in.read()) != -1) {
            char ch = (char) ci;
            if (inQuotes) {
                if (ch == '"') {
                    in.mark(1);
                    int next = in.read();
                    if (next == '"') {
                        field.append('"');
                    } else {
                        inQuotes = false;
                        if (next != -1) in.reset();
                    }
                } else {
                    field.append(ch);
                }
            } else {
                if (ch == '"' && field.length() == 0) {
                    inQuotes = true;
                    fieldWasQuoted = true;
                } else if (ch == ',') {
                    cur.add(finish(field, fieldWasQuoted));
                    fieldWasQuoted = false;
                } else if (ch == '\n') {
                    cur.add(finish(field, fieldWasQuoted));
                    fieldWasQuoted = false;
                    if (!(cur.size() == 1 && cur.get(0).isEmpty())) records.add(cur);
                    cur = new ArrayList<>();
                } else if (ch != '\r') {
                    field.append(ch);
                }
            }
        }
        if (field.length() > 0 || !cur.isEmpty()) {
            cur.add(finish(field, fieldWasQuoted));
            if (!(cur.size() == 1 && cur.get(0).isEmpty())) records.add(cur);
        }
        return records;
    }

    private static String finish(StringBuilder field, boolean wasQuoted) {
        // Note: \N is treated as NULL whether quoted or not (kept simple on purpose).
        String s = field.toString();
        field.setLength(0);
        return s;
    }
}
