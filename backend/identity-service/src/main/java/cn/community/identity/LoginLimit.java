package cn.community.identity;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/** Fixed window; also repairs a legacy counter whose expiry was never set. */
final class LoginLimit {
 static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>("""
  local count = redis.call('INCR', KEYS[1])
  if redis.call('PTTL', KEYS[1]) < 0 then
    redis.call('PEXPIRE', KEYS[1], ARGV[1])
  end
  return count
  """, Long.class);
 private LoginLimit() {}
}
