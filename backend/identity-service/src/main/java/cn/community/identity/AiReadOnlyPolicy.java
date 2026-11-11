package cn.community.identity;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Explicit writes and credential disclosures need no supplier inference. */
final class AiReadOnlyPolicy {
 // Only short, unqualified search phrases. Concrete places, prices and absolute times use normal slot extraction.
 private static final Pattern GENERAL_SEARCH = Pattern.compile("(?:[今明后]天|今晚|上午|下午|晚上|早上|附近|在小区|我想|我要|需要|请|帮我|查找|查询|搜索|查|搜|找|一个|个|便宜|一点|的|停车|车位|[一二三四五六七八九十两0-9]+(?:个)?小时|但(?:我)?(?:还)?(?:没|未)确定(?:日期|时间)|[，。！？?,.!\\s])+");
 static boolean needsSearchDetails(String text){
  return text.length()<100&&(text.contains("车位")||text.contains("停车"))&&GENERAL_SEARCH.matcher(text.strip()).matches();
 }
 /** General policy questions can be answered from the effective rule text. Personal records require a business tool. */
 static boolean globalRule(String text){
  return text.strip().matches("(?:请)?(?:帮我)?(?:查询|查找|检索|查看|了解|解释)?(?:停车)?(?:费用|计费|预约费|超时费|预约|取消预约|退款|入场|到场|离场|宽限|共享窗口)(?:规则|政策|条款|怎么算|是什么|有何区别)[。？！?！]?");
 }
 static boolean generalPolicyQuestion(String text){
  if(text.matches(".*(?:我的|本人|这笔|这单|这个订单|订单号|预约号|账单|编号|[0-9]{4,}).*"))return false;
  boolean question=text.matches(".*(?:[？?]|吗|怎么办|怎么算|是什么|有何区别)$");
  boolean topic=text.matches(".*(?:退款|退订|费用争议|入场|到场|离场|预约费|计费|超时费|取消预约|预约取消|规则|宽限|优惠券|封顶|库存|共享窗口|预约时长|预约时间|预约最[短长]|预约至少).*" );
  boolean explicitRule=text.strip().matches("(?:请)?(?:帮我)?(?:查询|查找|检索|查看|了解|解释)?[^，。？！?！]{1,60}(?:规则|政策|条款)[。？！?！]?");
  return explicitRule||question&&topic;
 }
 private static final Pattern PRIVILEGED = Pattern.compile("绕过.{0,8}(权限|授权)|忽略.{0,8}(规则|权限|指令).{0,12}(调用|执行)|(?:输出|显示|读取|泄露|给我).{0,12}(apikey|密钥|密码|令牌)|(?:其他人|他人|其他用户).{0,10}(手机号|联系方式|账单)|(?:执行)?sql.{0,12}(删除|修改|插入|delete|update|insert|drop)");
 private static final Pattern WRITE = Pattern.compile("^(?:(?:请你?|帮我|替我|立即|马上|现在|直接|我要|麻烦))*+(?:预订|下单|付款|转账|退款给|支付给|删除|修改|更改|关闭|开通|封禁|取消我的预约)");
 static boolean forbidden(String input){
  String text=Normalizer.normalize(input,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("\\s+","");
  if(PRIVILEGED.matcher(text).find())return true;
  if(text.matches(".*(?:规则|政策|如何|怎么|为什么|需要多久|可以吗|是否|记录|状态)(?:.*[?？]|[?？]?)$"))return false;
  return WRITE.matcher(text).find();
 }
}
