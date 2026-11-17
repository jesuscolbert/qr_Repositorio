package bo.colbert.licensing;
import org.json.*;

/** Server time anchored to monotonic time; paid grants can survive a normal reboot.
 * A local clock is not tamper-proof hardware. Large rollback requires online validation.
 */
public final class LicenseClock {
 private LicenseClock(){}
 public static long now(JSONObject claims,JSONObject cache,JSONObject checkpoint,long wall,long elapsed,int boot){
  long issued=claims.optLong("issuedAt"),base=cache.optLong("elapsed",-1),candidate;
  if(boot>=0&&cache.optInt("boot",-2)==boot&&base>=0&&elapsed>=base)candidate=issued+elapsed-base;
  else {
   long savedWall=cache.optLong("wall",0),lastWall=checkpoint.optLong("wall",savedWall);
   if(!LicenseProtocol.paidPermit(claims)||savedWall<=0||wall<savedWall-120000||wall<lastWall-120000)return -1;
   candidate=issued+Math.max(0,wall-savedWall);
  }
  return Math.max(candidate,checkpoint.optLong("serverTime",issued));
 }
 public static String renewalNotice(JSONObject claims,long now){
  long remaining=claims.optLong("paidUntil")-now;
  if(!claims.optBoolean("allowed")||now<=0||remaining<=0||remaining>2*LicenseProtocol.OFFLINE_MS)return "";
  long days=(remaining+LicenseProtocol.OFFLINE_MS-1)/LicenseProtocol.OFFLINE_MS;
  return "Tu licencia vence en "+days+(days==1?" día":" días")+" · Puedes renovar sin perder tu vigencia actual.";
 }
}
