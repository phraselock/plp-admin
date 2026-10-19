package plp.ui;

import plp.lib.SmartCardUserStore;

import java.util.List;

import static j2html.TagCreator.*;

public class SmartCardPage
{
  private static final String CARD =
    "max-width:1024px;margin:60px auto;padding:30px;" +
    "background:white;border:0.5px solid #c0c0c0;" +
    "border-radius:12px;box-shadow:0 2px 6px rgba(0,0,0,0.1);";

  private static final String FIELD =
    "padding:10px;margin-top:4px;margin-bottom:15px;" +
    "background:#fafafa;border:1px solid #ddd;border-radius:8px;";

  public static String render(List<SmartCardUserStore.User> users, SmartCardUserStore.User selected)
  {
    String fId       = selected != null ? String.valueOf(selected.id()) : "";
    String fName     = selected != null ? selected.name()               : "";
    String fUsername = selected != null ? selected.username()           : "";
    String fEmail    = selected != null ? selected.email()              : "";

    return document(html(
      head(title("SmartCard Users")),
      body()
        .withStyle("font-family:Arial,Helvetica,sans-serif;background-color:#f5f5f5;")
        .with(
          div().withStyle(CARD).with(

            Nav.bar("/admin/smartcard-user"),

            h1("SmartCard Users").withStyle("text-align:center;margin-bottom:28px;"),

            // ── Search + New User bar ─────────────────────────────────────
            div().withStyle("display:flex;gap:10px;align-items:center;margin-bottom:12px;").with(
              input().withType("text").withId("user-search")
                .withPlaceholder("Search name, username or email…")
                .withStyle(
                  "flex:1;padding:7px 12px;font-size:13px;" +
                  "border:1px solid #ddd;border-radius:6px;background:#fafafa;")
                .attr("oninput", "filterUsers()"),
              a("+ New User").withHref("/admin/smartcard-user")
                .withStyle("font-size:13px;text-decoration:none;color:#333;white-space:nowrap;" +
                           "padding:6px 14px;border:1px solid #bbb;border-radius:6px;")
            ),

            // ── User table ────────────────────────────────────────────────
            users.isEmpty()
              ? p("No users yet.").withStyle("color:#888;font-size:13px;")
              : div().withStyle(
                    "max-height:220px;overflow-y:auto;border:1px solid #eee;" +
                    "border-radius:8px;margin-bottom:16px;")
                  .with(table()
                  .withStyle("width:100%;border-collapse:collapse;font-size:13px;")
                  .with(
                    thead(tr()
                      .withStyle(
                        "background:#f0f0f0;text-align:left;" +
                        "position:sticky;top:0;z-index:1;")
                      .with(
                        th("Name").withStyle("padding:8px 10px;"),
                        th("Username").withStyle("padding:8px 10px;"),
                        th("Email").withStyle("padding:8px 10px;"),
                        th("Updated").withStyle("padding:8px 10px;"),
                        th("").withStyle("padding:8px 10px;")
                      )
                    ),
                    tbody().withId("user-tbody").with(
                      each(users, u -> tr()
                        .withStyle(
                          "border-top:1px solid #eee;" +
                          (selected != null && selected.id() == u.id()
                            ? "background:#f5f9ff;" : ""))
                        .attr("data-name",     u.name())
                        .attr("data-username", u.username())
                        .attr("data-email",    u.email())
                        .with(
                          td(u.name()).withStyle("padding:8px 10px;"),
                          td(u.username()).withStyle("padding:8px 10px;color:#555;"),
                          td(u.email()).withStyle("padding:8px 10px;color:#555;"),
                          td(u.updatedAt()).withStyle("padding:8px 10px;color:#aaa;font-size:12px;"),
                          td().withStyle("padding:8px 10px;white-space:nowrap;").with(
                            a("Select").withHref("/admin/smartcard-user?select=" + u.id())
                              .withStyle("margin-right:10px;font-size:12px;text-decoration:none;color:#333;"),
                            form().withMethod("post").withAction("/admin/smartcard-user/delete")
                              .withStyle("display:inline;")
                              .with(
                                input().withType("hidden").withName("id").withValue(String.valueOf(u.id())),
                                button("Delete").withType("submit")
                                  .withStyle("font-size:12px;color:#cc0000;background:none;" +
                                             "border:none;cursor:pointer;padding:0;")
                                  .attr("onclick", "return confirm('Delete " + esc(u.name()) + "?')")
                              )
                          )
                        )
                      )
                    )
                  )),

            hr().withStyle("margin:0 0 28px 0;"),

            // ── Form ──────────────────────────────────────────────────────
            h3(selected != null ? "Edit User" : "New User")
              .withStyle("margin-top:0;margin-bottom:20px;"),

            form().withMethod("post").withAction("/admin/smartcard-user/save").with(

              input().withType("hidden").withName("id").withValue(fId),

              label("Name"),
              input().withType("text").withName("name")
                .withValue(fName)
                .withPlaceholder("e.g. Max Mustermann")
                .withStyle(FIELD + "width:calc(100% - 22px);display:block;font-size:14px;"),

              label("Username (SAM account name)"),
              input().withType("text").withName("username")
                .withValue(fUsername)
                .withPlaceholder("e.g. mmustermann")
                .withStyle(FIELD + "width:calc(100% - 22px);display:block;font-size:14px;"),

              label("Email"),
              input().withType("text").withName("email")
                .withValue(fEmail)
                .withPlaceholder("e.g. max.mustermann@company.com")
                .withStyle(FIELD + "width:calc(100% - 22px);display:block;font-size:14px;"),

              div().withStyle("margin-top:4px;").with(
                button("Save").withType("submit")
                  .withStyle("padding:10px 24px;font-size:14px;cursor:pointer;")
              )
            )
          ),
          script(rawHtml(buildJs())),
          Nav.footer()
        )
    ));
  }

  private static String esc(String s)
  {
    return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("'", "\\'");
  }

  private static String buildJs()
  {
    return
      "function filterUsers(){\n" +
      "  var q=document.getElementById('user-search').value.toLowerCase();\n" +
      "  var rows=document.querySelectorAll('#user-tbody tr');\n" +
      "  rows.forEach(function(tr){\n" +
      "    var text=(tr.dataset.name+' '+tr.dataset.username+' '+tr.dataset.email).toLowerCase();\n" +
      "    tr.style.display=text.includes(q)?'':'none';\n" +
      "  });\n" +
      "}\n";
  }
}
