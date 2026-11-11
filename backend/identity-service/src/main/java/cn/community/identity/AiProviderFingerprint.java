package cn.community.identity;
import cn.community.foundation.*;
import java.nio.file.*;
final class AiProviderFingerprint {
 static String fingerprint(String directory,String reference){
  Problem.require(reference.matches("[a-zA-Z0-9_-]{1,60}\\.properties"),422,"INVALID_CREDENTIAL_REF","凭据引用无效");
  try{Path root=Path.of(directory).toRealPath(),file=root.resolve(reference).toRealPath();Problem.require(file.getParent().equals(root),422,"INVALID_CREDENTIAL_REF","凭据路径无效");var props=new java.util.Properties();try(var r=Files.newBufferedReader(file)){props.load(r);}String key=props.getProperty("api-key","").trim();Problem.require(!key.isBlank(),422,"AI_CREDENTIAL_UNAVAILABLE","凭据未配置");return Data.hash(key);}catch(Problem e){throw e;}catch(Exception e){throw new Problem(422,"AI_CREDENTIAL_UNAVAILABLE","凭据不可读取");}
 }
}
