package plp.lib;

import com.ipoxo.plcore.lib.Log;

import java.nio.file.Path;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class SmartCardUserStore
{
  private static final String DB_FILE = "smartcard-users.db";
  private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  private final String dbUrl;

  private static SmartCardUserStore instance;

  public static synchronized SmartCardUserStore getInstance()
  {
    if (instance == null) instance = new SmartCardUserStore();
    return instance;
  }

  public record User(int id, String name, String username, String email, String domain, String updatedAt) {}

  private SmartCardUserStore()
  {
    Path dbPath = ConfigPathResolver.resolve(DB_FILE, SmartCardUserStore.class);
    dbUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();
    initTable();
    Log.i("[SmartCardUserStore] SQLite: " + dbPath.toAbsolutePath());
  }

  private void initTable()
  {
    try (Connection conn = DriverManager.getConnection(dbUrl);
         Statement stmt = conn.createStatement())
    {
      stmt.execute("""
        CREATE TABLE IF NOT EXISTS smartcard_users (
          id         INTEGER PRIMARY KEY AUTOINCREMENT,
          name       TEXT NOT NULL,
          username   TEXT NOT NULL UNIQUE,
          email      TEXT NOT NULL,
          domain     TEXT NOT NULL DEFAULT '',
          created_at TEXT NOT NULL,
          updated_at TEXT NOT NULL
        )
      """);
      // migrate: add domain column if missing (existing databases)
      try { stmt.execute("ALTER TABLE smartcard_users ADD COLUMN domain TEXT NOT NULL DEFAULT ''"); }
      catch (SQLException ignored) {}
    }
    catch (Exception e) { Log.e("[SmartCardUserStore] initTable: " + e.getMessage()); }
  }

  /** Inserts or updates a user by username. Returns the row id, or -1 on error. */
  public int upsert(String name, String username, String email, String domain)
  {
    String now = LocalDateTime.now().format(FMT);
    try (Connection conn = DriverManager.getConnection(dbUrl))
    {
      try (PreparedStatement ps = conn.prepareStatement("""
        INSERT INTO smartcard_users (name, username, email, domain, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?)
        ON CONFLICT(username) DO UPDATE SET
          name       = excluded.name,
          email      = excluded.email,
          domain     = excluded.domain,
          updated_at = excluded.updated_at
      """))
      {
        ps.setString(1, name);
        ps.setString(2, username);
        ps.setString(3, email);
        ps.setString(4, domain);
        ps.setString(5, now);
        ps.setString(6, now);
        ps.executeUpdate();
      }
      try (PreparedStatement q = conn.prepareStatement(
          "SELECT id FROM smartcard_users WHERE username = ?"))
      {
        q.setString(1, username);
        try (ResultSet rs = q.executeQuery())
        {
          if (rs.next()) return rs.getInt("id");
        }
      }
    }
    catch (Exception e) { Log.e("[SmartCardUserStore] upsert: " + e.getMessage()); }
    return -1;
  }

  public List<User> findAll()
  {
    List<User> list = new ArrayList<>();
    try (Connection conn = DriverManager.getConnection(dbUrl);
         PreparedStatement ps = conn.prepareStatement(
           "SELECT id, name, username, email, domain, updated_at FROM smartcard_users ORDER BY name"))
    {
      try (ResultSet rs = ps.executeQuery())
      {
        while (rs.next())
          list.add(new User(rs.getInt("id"), rs.getString("name"),
                            rs.getString("username"), rs.getString("email"),
                            rs.getString("domain"), rs.getString("updated_at")));
      }
    }
    catch (Exception e) { Log.e("[SmartCardUserStore] findAll: " + e.getMessage()); }
    return list;
  }

  public User findById(int id)
  {
    try (Connection conn = DriverManager.getConnection(dbUrl);
         PreparedStatement ps = conn.prepareStatement(
           "SELECT id, name, username, email, domain, updated_at FROM smartcard_users WHERE id = ?"))
    {
      ps.setInt(1, id);
      try (ResultSet rs = ps.executeQuery())
      {
        if (rs.next())
          return new User(rs.getInt("id"), rs.getString("name"),
                          rs.getString("username"), rs.getString("email"),
                          rs.getString("domain"), rs.getString("updated_at"));
      }
    }
    catch (Exception e) { Log.e("[SmartCardUserStore] findById: " + e.getMessage()); }
    return null;
  }

  public User findByUsername(String username)
  {
    try (Connection conn = DriverManager.getConnection(dbUrl);
         PreparedStatement ps = conn.prepareStatement(
           "SELECT id, name, username, email, domain, updated_at FROM smartcard_users WHERE username = ?"))
    {
      ps.setString(1, username);
      try (ResultSet rs = ps.executeQuery())
      {
        if (rs.next())
          return new User(rs.getInt("id"), rs.getString("name"),
                          rs.getString("username"), rs.getString("email"),
                          rs.getString("domain"), rs.getString("updated_at"));
      }
    }
    catch (Exception e) { Log.e("[SmartCardUserStore] findByUsername: " + e.getMessage()); }
    return null;
  }

  public void deleteById(int id)
  {
    try (Connection conn = DriverManager.getConnection(dbUrl);
         PreparedStatement ps = conn.prepareStatement("DELETE FROM smartcard_users WHERE id = ?"))
    {
      ps.setInt(1, id);
      ps.executeUpdate();
    }
    catch (Exception e) { Log.e("[SmartCardUserStore] deleteById: " + e.getMessage()); }
  }
}
