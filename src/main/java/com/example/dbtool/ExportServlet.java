package com.example.dbtool;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;

/**
 * GET /export?db=&table=  →  streams the whole table as CSV.
 * NULL cells are written as \N (mysqldump convention) so the importer
 * can round-trip them.
 */
@WebServlet(urlPatterns = "/export")
public class ExportServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String db = req.getParameter("db");
        String table = req.getParameter("table");
        if (db == null || db.isBlank() || table == null || table.isBlank()) {
            resp.sendError(400, "db and table parameters are required");
            return;
        }

        try (Connection c = Db.open(null);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM " + Db.qt(db, table))) {

            resp.setContentType("text/csv; charset=UTF-8");
            resp.setHeader("Content-Disposition",
                    "attachment; filename=\"" + table.replaceAll("[^A-Za-z0-9_.-]", "_") + ".csv\"");
            PrintWriter out = resp.getWriter();

            ResultSetMetaData md = rs.getMetaData();
            int n = md.getColumnCount();
            StringBuilder header = new StringBuilder();
            for (int i = 1; i <= n; i++) {
                if (i > 1) header.append(',');
                header.append(csv(md.getColumnLabel(i)));
            }
            out.println(header);

            while (rs.next()) {
                StringBuilder row = new StringBuilder();
                for (int i = 1; i <= n; i++) {
                    if (i > 1) row.append(',');
                    String v = rs.getString(i);
                    row.append(v == null ? "\\N" : csv(v));
                }
                out.println(row);
            }
        } catch (Exception e) {
            if (!resp.isCommitted()) {
                resp.sendError(400, e.getMessage() == null ? "Export failed" : e.getMessage());
            }
        }
    }

    private String csv(String v) {
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}
