import java.nio.file.Path;
import java.sql.*;
import org.flywaydb.core.Flyway;

/** Uses the exact Flyway/MySQL libraries packaged with the application. No credentials in arguments. */
class VerifyBusinessMigrations {
 public static void main(String[] args) throws Exception {
  String schema=args[0], service=args[1];
  if(!schema.matches("w56_[0-9]{14}_(fresh|restore|legacy)_(identity|parking|trade)"))throw new IllegalArgumentException("Unsafe test schema");
  if(!service.matches("(identity|parking|trade)-service"))throw new IllegalArgumentException("Invalid service");
  String url="jdbc:mysql://127.0.0.1:23306/"+schema+"?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
  var flyway=Flyway.configure().dataSource(url,"root",System.getenv("MYSQL_ROOT_PASSWORD"))
   .locations("filesystem:"+Path.of("backend",service,"src/main/resources/db/business").toAbsolutePath())
   .load();
  var first=flyway.migrate();flyway.validate();
  var second=flyway.migrate();flyway.validate();
  if(second.migrationsExecuted!=0)throw new IllegalStateException("Repeated startup reapplied migrations");
  try(var c=DriverManager.getConnection(url,"root",System.getenv("MYSQL_ROOT_PASSWORD"));var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM flyway_schema_history WHERE success=FALSE")){
   r.next();if(r.getInt(1)!=0)throw new IllegalStateException("Failed migration history");
  }
  System.out.println("PASS "+schema+" migrated="+first.migrationsExecuted+" repeat=0 validated");
 }
}
