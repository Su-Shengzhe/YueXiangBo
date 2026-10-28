package cn.community.foundation;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jwt.*;
import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
import java.security.interfaces.*;
import java.util.*;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
@Component @Profile("business")
public class Tokens {
 private final RSAPublicKey publicKey;private final String privateFile;
 public Tokens(@Value("${business.jwt-public-file}")String publicFile,@Value("${business.jwt-private-file:}")String privateFile)throws Exception{
  this.publicKey=(RSAPublicKey)KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(bytes(publicFile)));this.privateFile=privateFile;
 }
 private static byte[] bytes(String file)throws Exception{return Base64.getDecoder().decode(Files.readString(Path.of(file)).replaceAll("-----[^-]+-----","").replaceAll("\\s",""));}
 public String issue(long user,String jti){
  try {var claims=new JWTClaimsSet.Builder().subject(Long.toString(user)).jwtID(jti).issuer("parking-demo").audience("parking-api").issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(900))).build();
   var token=new SignedJWT(new JWSHeader(JWSAlgorithm.RS256),claims);token.sign(new RSASSASigner((RSAPrivateKey)KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(bytes(privateFile)))));return token.serialize();
  } catch(Exception e){throw new IllegalStateException("Token signing unavailable",e);}
 }
 public JWTClaimsSet verify(String bearer){
  try{Problem.require(bearer!=null&&bearer.startsWith("Bearer "),401,"UNAUTHENTICATED","请先登录");var t=SignedJWT.parse(bearer.substring(7));
   Problem.require(JWSAlgorithm.RS256.equals(t.getHeader().getAlgorithm())&&t.verify(new RSASSAVerifier(publicKey)),401,"INVALID_TOKEN","令牌无效");
   var c=t.getJWTClaimsSet();Problem.require("parking-demo".equals(c.getIssuer())&&c.getAudience().contains("parking-api")&&c.getJWTID()!=null,401,"INVALID_TOKEN","令牌无效");
   Problem.require(c.getExpirationTime()!=null&&c.getExpirationTime().after(new Date()),401,"TOKEN_EXPIRED","登录已过期");return c;
  }catch(Problem e){throw e;}catch(Exception e){throw new Problem(401,"INVALID_TOKEN","令牌无效");}
 }
}
