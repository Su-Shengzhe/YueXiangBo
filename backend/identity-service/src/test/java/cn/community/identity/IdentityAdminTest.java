package cn.community.identity;
import cn.community.foundation.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;

class IdentityAdminTest {
 @Test void concurrentRoleUpdatesRejectLostUpdate() throws Exception {
  try(var pool=Executors.newFixedThreadPool(2)){
   Callable<String> change=()->{try{api.roles(2,Map.of("version",1,"roles",List.of("DRIVER","OWNER")),admin(1));return "OK";}catch(Problem p){return p.code;}};
   var a=pool.submit(change);var b=pool.submit(change);assertEquals(Set.of("OK","STALE_VERSION"),Set.of(a.get(),b.get()));
  }
  assertEquals(2,db.queryForObject("SELECT version FROM app_user WHERE id=2",Integer.class));
  assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='USER_ROLES'",Integer.class));
 }
 @Test void missingVersionIsRejectedAndUserSearchIsBounded(){
  assertEquals(422,assertThrows(Problem.class,()->api.status(2,Map.of("enabled",false),admin(1))).status);
  assertEquals(1L,api.users(1,20,"driver","DRIVER",true,admin(1)).get("total"));
  assertEquals(0L,api.users(1,20,"driver","PROPERTY",true,admin(1)).get("total"));
 }
 @Test void firstCommunityGrantHasAtomicVersionAndRoleRemovalInvalidatesIt() throws Exception {
  var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/",e->{e.sendResponseHeaders(200,-1);e.close();});server.start();
  try{
   api=new IdentityAdmin(db,new DataSourceTransactionManager(db.getDataSource()),"http://127.0.0.1:"+server.getAddress().getPort(),"test");
   api.roles(2,Map.of("version",1,"roles",List.of("PROPERTY")),admin(1));
   assertEquals(0,api.administrators(10,admin(1)).get("version"));
   try(var pool=Executors.newFixedThreadPool(2)){
    Callable<String> grant=()->{try{api.communities(10,Map.of("version",0,"userIds",List.of("2")),admin(1));return "OK";}catch(Problem p){return p.code;}};
    var a=pool.submit(grant);var b=pool.submit(grant);assertEquals(Set.of("OK","STALE_VERSION"),Set.of(a.get(),b.get()));
   }
   api.roles(2,Map.of("version",2,"roles",List.of("DRIVER")),admin(1));
   assertEquals(2,api.administrators(10,admin(1)).get("version"));
   assertEquals(List.of(),api.administrators(10,admin(1)).get("userIds"));
   assertEquals("STALE_VERSION",assertThrows(Problem.class,()->api.communities(10,Map.of("version",1,"userIds",List.of()),admin(1))).code);
  }finally{server.stop(0);}
 }
 JdbcTemplate db;IdentityAdmin api;
 MockHttpServletRequest admin(long id){var req=new MockHttpServletRequest();req.setAttribute("actor",new Actor(id,Set.of("PLATFORM"),Set.of()));return req;}
 @BeforeEach void setup(){var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000","sa","");db=new JdbcTemplate(ds);new ResourceDatabasePopulator(new ClassPathResource("db/business/V001__business_baseline.sql"),new ClassPathResource("db/business/V002__reliability.sql"),new ClassPathResource("db/business/V003__resource_management.sql"),new ClassPathResource("db/business/V004__authorization_versions.sql")).execute(ds);db.update("INSERT INTO app_user(id,username,password_hash,role) VALUES(1,'admin','hash','PLATFORM'),(2,'driver','hash','DRIVER')");db.update("INSERT INTO user_role VALUES(1,'PLATFORM'),(2,'DRIVER')");db.update("INSERT INTO login_session VALUES('session',2,DATEADD('DAY',1,CURRENT_TIMESTAMP),FALSE)");api=new IdentityAdmin(db,new DataSourceTransactionManager(ds),"http://127.0.0.1:1","test");}
 @Test void roleGrantIsMultipleAndRevokesExistingSessions(){api.roles(2,Map.of("version",1,"roles",List.of("DRIVER","OWNER")),admin(1));assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM user_role WHERE user_id=2",Integer.class));assertEquals(true,db.queryForObject("SELECT revoked FROM login_session WHERE jti='session'",Boolean.class));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='USER_ROLES'",Integer.class));}
 @Test void lastAdministratorCannotBeRemovedOrDisabled(){assertEquals("LAST_ADMIN",assertThrows(Problem.class,()->api.roles(1,Map.of("version",1,"roles",List.of("DRIVER")),admin(1))).code);assertEquals("LAST_ADMIN",assertThrows(Problem.class,()->api.status(1,Map.of("version",1,"enabled",false),admin(1))).code);assertEquals(true,db.queryForObject("SELECT enabled FROM app_user WHERE id=1",Boolean.class));assertEquals("PLATFORM",db.queryForObject("SELECT role_code FROM user_role WHERE user_id=1",String.class));}
 @Test void staleActorCannotWriteAfterRevocation(){assertEquals(403,assertThrows(Problem.class,()->api.roles(1,Map.of("version",1,"roles",List.of("DRIVER")),admin(2))).status);}
 @Test void concurrentAdministratorDisablesCannotRemoveBoth() throws Exception {api.roles(2,Map.of("version",1,"roles",List.of("PLATFORM")),admin(1));try(var pool=Executors.newFixedThreadPool(2)){Callable<Boolean> a=()->{try{api.status(1,Map.of("version",1,"enabled",false),admin(1));return true;}catch(Problem e){return false;}};Callable<Boolean> b=()->{try{api.status(2,Map.of("version",2,"enabled",false),admin(2));return true;}catch(Problem e){return false;}};var first=pool.submit(a);var second=pool.submit(b);assertNotEquals(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS));}assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM app_user WHERE enabled=TRUE",Integer.class));}
 @Test void unknownRoleRejectedAndPasswordNeverListed(){assertEquals(422,assertThrows(Problem.class,()->api.roles(2,Map.of("version",1,"roles",List.of("SUPERUSER")),admin(1))).status);assertFalse(Data.json(api.users(1,20,"","",null,admin(1))).contains("password"));}
}

