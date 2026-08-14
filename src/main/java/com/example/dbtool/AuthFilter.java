package com.example.dbtool;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * If DBTOOL_PASSWORD is set in the pod environment, every request must carry
 * an authenticated session. If it is unset, the filter is a no-op (rely on the
 * OpenShift Route / NetworkPolicy for access control instead).
 */
@WebFilter(urlPatterns = "/*")
public class AuthFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String password = System.getenv("DBTOOL_PASSWORD");
        if (password == null || password.isBlank()) {
            chain.doFilter(request, response);
            return;
        }

        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse resp = (HttpServletResponse) response;
        String path = req.getServletPath();

        HttpSession session = req.getSession(false);
        boolean authed = session != null && Boolean.TRUE.equals(session.getAttribute("authed"));

        if ("/logout".equals(path)) {
            if (session != null) session.invalidate();
            resp.sendRedirect(req.getContextPath() + "/");
            return;
        }

        if ("/login".equals(path) && "POST".equalsIgnoreCase(req.getMethod())) {
            String attempt = req.getParameter("password");
            if (attempt != null && MessageDigest.isEqual(
                    attempt.getBytes(StandardCharsets.UTF_8),
                    password.getBytes(StandardCharsets.UTF_8))) {
                req.getSession(true).setAttribute("authed", Boolean.TRUE);
                resp.sendRedirect(req.getContextPath() + "/");
            } else {
                loginPage(resp, true);
            }
            return;
        }

        if (authed) {
            chain.doFilter(request, response);
            return;
        }

        // API calls get a JSON 401 so the frontend can show a clean message.
        if (path != null && path.startsWith("/api")) {
            resp.setStatus(401);
            resp.setContentType("application/json");
            resp.getWriter().write("{\"error\":\"Not authenticated. Reload the page to sign in.\"}");
            return;
        }

        loginPage(resp, false);
    }

    private void loginPage(HttpServletResponse resp, boolean failed) throws IOException {
        resp.setStatus(401);
        resp.setContentType("text/html; charset=UTF-8");
        resp.getWriter().write("""
            <!doctype html><html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Sign in — MariaDB Workbench</title>
            <style>
              body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;
                   background:#04252e;font-family:system-ui,sans-serif}
              form{background:#f6f8f8;padding:2.2rem 2.4rem;border-radius:10px;min-width:280px;
                   box-shadow:0 12px 40px rgba(0,0,0,.45)}
              h1{font-size:1.05rem;margin:0 0 .35rem;color:#04252e}
              p{margin:0 0 1.1rem;font-size:.82rem;color:#5b6b6e}
              input{width:100%%;box-sizing:border-box;padding:.6rem .7rem;font-size:.95rem;
                    border:1px solid #b8c4c6;border-radius:6px;margin-bottom:.9rem}
              button{width:100%%;padding:.6rem;font-size:.95rem;border:0;border-radius:6px;
                     background:#0e7c86;color:#fff;font-weight:600;cursor:pointer}
              .err{color:#b3402e;font-size:.82rem;margin:0 0 .8rem}
            </style></head><body>
            <form method="post" action="login">
              <h1>MariaDB Workbench</h1>
              <p>Enter the admin password to continue.</p>
              %s
              <input type="password" name="password" autofocus autocomplete="current-password">
              <button type="submit">Sign in</button>
            </form></body></html>
            """.formatted(failed ? "<p class=\"err\">Wrong password, try again.</p>" : ""));
    }
}
