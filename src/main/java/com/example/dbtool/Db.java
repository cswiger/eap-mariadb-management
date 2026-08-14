package com.example.dbtool;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Shared database plumbing: datasource lookup, identifier quoting,
 * and connections optionally switched to a chosen schema.
 */
public final class Db {

    public static final String JNDI = "java:jboss/datasources/MariaDBDS";

    private static volatile DataSource ds;

    private Db() {}

    public static DataSource ds() {
        DataSource local = ds;
        if (local == null) {
            synchronized (Db.class) {
                if (ds == null) {
                    try {
                        ds = (DataSource) new InitialContext().lookup(JNDI);
                    } catch (NamingException e) {
                        throw new IllegalStateException("Datasource " + JNDI + " not found", e);
                    }
                }
                local = ds;
            }
        }
        return local;
    }

    /** Open a connection, optionally switched to the given schema. */
    public static Connection open(String db) throws SQLException {
        Connection c = ds().getConnection();
        if (db != null && !db.isBlank()) {
            try {
                c.setCatalog(db);
            } catch (SQLException e) {
                c.close();
                throw e;
            }
        }
        return c;
    }

    /** Quote a single identifier with backticks (escaping embedded backticks). */
    public static String qi(String ident) {
        if (ident == null || ident.isBlank()) {
            throw new IllegalArgumentException("Empty identifier");
        }
        return "`" + ident.replace("`", "``") + "`";
    }

    /** Quote a schema-qualified table name. */
    public static String qt(String db, String table) {
        if (db == null || db.isBlank()) {
            return qi(table);
        }
        return qi(db) + "." + qi(table);
    }
}
