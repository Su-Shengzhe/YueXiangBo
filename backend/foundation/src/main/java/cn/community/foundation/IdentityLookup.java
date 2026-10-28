package cn.community.foundation;
import java.util.Map;
public interface IdentityLookup { Map<String,Object> state(String bearer); }
