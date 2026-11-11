import java.sql.*;
import java.nio.file.*;
class EvalSql {
 public static void main(String[] args)throws Exception{
  try(var c=DriverManager.getConnection("jdbc:mysql://127.0.0.1:23306/?connectionTimeZone=UTC","root",System.getenv("MYSQL_ROOT_PASSWORD"));var s=c.createStatement()){
   c.setAutoCommit(false);try{for(String sql:Files.readString(Path.of(args[0])).split(";"))if(!sql.isBlank())s.execute(sql);c.commit();}catch(Exception e){c.rollback();throw e;}
  }
 }
}
