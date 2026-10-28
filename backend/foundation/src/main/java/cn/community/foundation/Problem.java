package cn.community.foundation;
public class Problem extends RuntimeException {
 public final int status; public final String code;
 public Problem(int status,String code,String message){super(message);this.status=status;this.code=code;}
 public static void require(boolean ok,int status,String code,String message){if(!ok)throw new Problem(status,code,message);}
}
