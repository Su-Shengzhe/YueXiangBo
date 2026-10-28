import cn.community.identity.IdentityAdmin;
import cn.community.foundation.*;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;

/** Executes the packaged controller and transaction code against an isolated real MySQL schema. */
class VerifyAdminConcurrency {
 static HttpServletRequest actor(long id){return (HttpServletRequest)Proxy.newProxyInstance(HttpServletRequest.class.getClassLoader(),new Class[]{HttpServletRequest.class},(p,m,a)->m.getName().equals("getAttribute")&&a[0].equals("actor")?new Actor(id,Set.of("PLATFORM"),Set.of()):null);}
 static void require(boolean value,String reason){if(!value)throw new IllegalStateException(reason);}
 public static void main(String[] args)throws Exception{
  String schema=args[0];require(schema.matches("w56_admin_[0-9]{14}"),"Unsafe schema");
  String server="jdbc:mysql://127.0.0.1:23306/",password=System.getenv("MYSQL_ROOT_PASSWORD");
  boolean created=false;
  try(var c=DriverManager.getConnection(server,"root",password);var sql=c.createStatement()){
   try{
    sql.executeUpdate("CREATE DATABASE "+schema);created=true;
    String url=server+schema+"?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
    Flyway.configure().dataSource(url,"root",password).locations("filesystem:backend/identity-service/src/main/resources/db/business").load().migrate();
    var ds=new DriverManagerDataSource(url,"root",password);var db=new JdbcTemplate(ds);
    var api=new IdentityAdmin(db,new DataSourceTransactionManager(ds),"http://127.0.0.1:1","unused");
    for(String mode:List.of("disable-disable","revoke-revoke","disable-revoke")){
     for(int round=0;round<10;round++){
      for(String table:List.of("audit_log","login_session","community_admin","user_role","app_user"))db.update("DELETE FROM "+table);
      db.update("INSERT INTO app_user(id,username,password_hash,role) VALUES(1,'test_admin_a','unused','PLATFORM'),(2,'test_admin_b','unused','PLATFORM')");
      db.update("INSERT INTO user_role VALUES(1,'PLATFORM'),(2,'PLATFORM')");
      db.update("INSERT INTO login_session VALUES('a',1,DATE_ADD(CURRENT_TIMESTAMP,INTERVAL 1 DAY),FALSE),('b',2,DATE_ADD(CURRENT_TIMESTAMP,INTERVAL 1 DAY),FALSE)");
      var barrier=new CyclicBarrier(2);var actions=mode.split("-");
      try(var pool=Executors.newFixedThreadPool(2)){
       var tasks=new ArrayList<Future<String>>();
       for(int i=0;i<2;i++){long id=i+1;String action=actions[i];tasks.add(pool.submit(()->{barrier.await(5,TimeUnit.SECONDS);try{if(action.equals("disable"))api.status(id,Map.of("version",1,"enabled",false),actor(id));else api.roles(id,Map.of("version",1,"roles",List.of("DRIVER")),actor(id));return "OK";}catch(Problem e){return e.code;}}));}
       var results=List.of(tasks.get(0).get(15,TimeUnit.SECONDS),tasks.get(1).get(15,TimeUnit.SECONDS));
       require(Collections.frequency(results,"OK")==1&&Collections.frequency(results,"LAST_ADMIN")==1,"Unexpected race result: "+results);
      }
      require(db.queryForObject("SELECT COUNT(*) FROM app_user u JOIN user_role r ON u.id=r.user_id WHERE u.enabled=TRUE AND r.role_code='PLATFORM'",Integer.class)==1,"No active admin");
      require(db.queryForObject("SELECT SUM(version) FROM app_user",Integer.class)==3,"Failed write changed version");
      require(db.queryForObject("SELECT COUNT(*) FROM audit_log",Integer.class)==1,"Audit not atomic");
      require(db.queryForObject("SELECT COUNT(*) FROM login_session WHERE revoked=TRUE",Integer.class)==1,"Session revocation not atomic");
     }
     System.out.println("PASS "+mode+" rounds=10 active-admin=1 audit=1 revoked-session=1 version-sum=3");
    }
   }finally{if(created)sql.executeUpdate("DROP DATABASE "+schema);}
  }
 }
}
