package plp.ui;

import plp.lib.SmartCardCertStore;
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

  public static String render(List<SmartCardUserStore.User> users, SmartCardUserStore.User selected, String defaultDomain,
                              List<SmartCardCertStore.Cert> certs, int defaultTtl)
  {
    String fId       = selected != null ? String.valueOf(selected.id()) : "";
    String fName     = selected != null ? selected.name()               : "";
    String fUsername = selected != null ? selected.username()           : "";
    String fEmail    = selected != null ? selected.email()              : "";
    String fDomain   = selected != null ? selected.domain()             : defaultDomain;

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
                        th("UPN").withStyle("padding:8px 10px;"),
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
                        .attr("data-username", u.username() + "@" + u.domain())
                        .attr("data-email",    u.email())
                        .with(
                          td(u.name()).withStyle("padding:8px 10px;"),
                          td(u.username() + "@" + u.domain()).withStyle("padding:8px 10px;color:#555;font-family:monospace;font-size:12px;"),
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

              label("Domain (for UPN: username@domain)"),
              input().withType("text").withName("domain")
                .withValue(fDomain)
                .withPlaceholder("e.g. example.com")
                .withStyle(FIELD + "width:calc(100% - 22px);display:block;font-size:14px;"),

              div().withStyle("margin-top:4px;").with(
                button("Save").withType("submit")
                  .withStyle("padding:10px 24px;font-size:14px;cursor:pointer;")
              )
            ),

            // ── Issued certificates of the selected user ──────────────────
            selected == null ? text("") : certSection(selected, certs, defaultTtl)
          ),
          script(rawHtml(buildJs())),
          Nav.footer()
        )
    ));
  }

  private static j2html.tags.DomContent certSection(SmartCardUserStore.User user,
                                                    List<SmartCardCertStore.Cert> certs, int defaultTtl)
  {
    String upn = user.username() + "@" + user.domain();
    return div().withStyle("margin-top:32px;").with(
      hr().withStyle("margin:0 0 20px 0;"),
      h3("Certificates of " + upn).withStyle("margin-top:0;"),
      form().withMethod("post").withAction("/admin/smartcard-user/cert/issue").withStyle("margin-bottom:16px;").with(
        input().withType("hidden").withName("user").withValue(String.valueOf(user.id())),
        button("Issue new certificate…").withType("submit")
          .withStyle("padding:10px 24px;font-size:14px;cursor:pointer;")
          .attr("onclick", "return confirm('Issue a new certificate for " + esc(upn) + " now?')")
      ),
      div().withStyle("display:flex;gap:10px;align-items:center;margin-bottom:10px;font-size:13px;").with(
        label("QR code valid for (minutes)"),
        input().withType("number").withId("qr-ttl").withValue(String.valueOf(defaultTtl))
          .attr("min", "1").withStyle("width:90px;padding:6px;border:1px solid #ddd;border-radius:6px;")
      ),
      certs.isEmpty()
        ? p("None issued yet.").withStyle("color:#888;font-size:13px;")
        : div().withStyle("max-height:220px;overflow-y:auto;border:1px solid #eee;border-radius:8px;").with(
          table().withStyle("width:100%;border-collapse:collapse;font-size:13px;").with(
            thead(tr().withStyle("background:#f0f0f0;text-align:left;position:sticky;top:0;z-index:1;").with(
              th("Serial").withStyle("padding:8px 10px;"),
              th("Issued").withStyle("padding:8px 10px;"),
              th("Valid until").withStyle("padding:8px 10px;"),
              th("Status").withStyle("padding:8px 10px;"),
              th("").withStyle("padding:8px 10px;"),
              th("").withStyle("padding:8px 10px;"))),
            tbody().with(each(certs, c ->
            {
              boolean revoked = SmartCardCertStore.REVOKED.equals(c.status());
              return tr().withStyle("border-top:1px solid #eee;").with(
                td(c.serialHex().substring(0, Math.min(12, c.serialHex().length())) + "…")
                  .withTitle(c.serialHex())
                  .withStyle("padding:8px 10px;font-family:monospace;font-size:12px;"),
                td(c.issuedAt()).withStyle("padding:8px 10px;color:#555;"),
                td(c.notAfter()).withStyle("padding:8px 10px;color:#555;"),
                td(revoked ? "revoked " + c.revokedAt() : "active")
                  .withStyle("padding:8px 10px;color:" + (revoked ? "#cc0000" : "#2a7a2a") + ";"),
                td().withStyle("padding:8px 10px;").with(
                  c.deliverable() && !revoked
                    ? span().with(
                        button("QR code").withType("button")
                          .attr("onclick", "genEnrollQR('" + c.serialHex() + "')")
                          .withStyle("font-size:12px;cursor:pointer;margin-right:6px;"),
                        button("P12").withType("button")
                          .attr("onclick", "downloadP12('" + c.serialHex() + "','" + esc(c.upn()) + "')")
                          .withStyle("font-size:12px;cursor:pointer;"))
                    : span(revoked ? "" : "–").withStyle("font-size:12px;color:#888;")),
                td().withStyle("padding:8px 10px;").with(
                  form().withMethod("post")
                    .withAction("/admin/smartcard-user/cert/" + (revoked ? "reinstate" : "revoke"))
                    .withStyle("display:inline;").with(
                      input().withType("hidden").withName("serial").withValue(c.serialHex()),
                      input().withType("hidden").withName("user").withValue(String.valueOf(user.id())),
                      button(revoked ? "Reinstate" : "Revoke").withType("submit")
                        .withStyle("font-size:12px;cursor:pointer;")))
              );
            }))
          )),
      label("JSON Preview").withStyle("display:block;margin-top:20px;"),
      pre().withId("enroll-json")
        .withStyle(FIELD + "font-size:12px;overflow-x:auto;white-space:pre-wrap;min-height:60px;")
        .withText("Select a certificate and press \"QR code\"."),
      div().withStyle("text-align:center;margin-top:16px;").with(
        img().withId("enroll-qr").withSrc("")
          .withStyle("display:none;border:1px solid #ddd;border-radius:8px;padding:10px;max-width:320px;"),
        p("").withId("enroll-exp").withStyle("font-size:13px;color:#555;")
      )
    );
  }

  private static String esc(String s)
  {
    return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("'", "\\'");
  }

  private static String buildJs()
  {
    return
      "function downloadP12(serial,upn){\n" +
      "  var pw=prompt('Password for the PKCS#12 of '+upn+':');\n" +
      "  if(!pw) return;\n" +
      "  var tok=new URLSearchParams(location.search).get('token');\n" +
      "  fetch('/admin/smartcard-user/cert/p12'+(tok?'?token='+encodeURIComponent(tok):''),\n" +
      "    {method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},\n" +
      "     body:new URLSearchParams({serial:serial,password:pw})})\n" +
      "   .then(function(r){return r.ok?r.blob():r.text().then(function(t){throw new Error(t);});})\n" +
      "   .then(function(b){var a=document.createElement('a');a.href=URL.createObjectURL(b);\n" +
      "     a.download=upn+'.p12';a.click();URL.revokeObjectURL(a.href);})\n" +
      "   .catch(function(e){alert('Error: '+e.message);});\n" +
      "}\n" +
      "function genEnrollQR(serial){\n" +
      "  var ttl=parseInt(document.getElementById('qr-ttl').value,10)||1;\n" +
      "  var tok=new URLSearchParams(location.search).get('token');\n" +
      "  var url='/admin/smartcard-user/qr.json?serial='+encodeURIComponent(serial)+'&ttl='+ttl+(tok?'&token='+encodeURIComponent(tok):'');\n" +
      "  fetch(url).then(function(r){return r.ok?r.json():r.text().then(function(t){throw new Error(t);});})\n" +
      "   .then(function(d){\n" +
      "     var img=document.getElementById('enroll-qr');\n" +
      "     img.src='data:image/png;base64,'+d.png;\n" +
      "     img.style.display='inline-block';\n" +
      "     document.getElementById('enroll-json').textContent=JSON.stringify(d.payload,null,2);\n" +
      "     var e=new Date(Date.now()+ttl*60000);\n" +
      "     document.getElementById('enroll-exp').textContent='Valid until '+e.toLocaleString();\n" +
      "   }).catch(function(e){document.getElementById('enroll-exp').textContent='Error: '+e.message;});\n" +
      "}\n" +
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
