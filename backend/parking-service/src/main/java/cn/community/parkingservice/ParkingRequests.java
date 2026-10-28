package cn.community.parkingservice;

import cn.community.foundation.*;
import org.springframework.dao.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Acquired before resource locks, retained until the caller's transaction completes. */
final class ParkingRequests {
 private ParkingRequests() {}
 static void claim(JdbcTemplate db,long actor,String operation,String key) {
  Requests.key(key);
  if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Request guard needs a transaction");
  try {
    db.update(connection->{
     // A no-op duplicate update acquires the exclusive lock directly; avoid S-to-X lock upgrades.
     var statement=connection.prepareStatement("INSERT INTO parking_request_guard(actor_id,operation,request_key) VALUES(?,?,?) ON DUPLICATE KEY UPDATE request_key=request_key");
     statement.setLong(1,actor);statement.setString(2,operation);statement.setString(3,key);statement.setQueryTimeout(3);return statement;
    });
  } catch(QueryTimeoutException | PessimisticLockingFailureException waiting) {
   throw new Problem(409,"REQUEST_IN_PROGRESS","原请求仍在处理中，请使用原请求键核对或重试");
  }
 }
}
