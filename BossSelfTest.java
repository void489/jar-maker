import dev.mobzone.BossMath;
import java.util.*;
public class BossSelfTest {
  static int f=0; static void ck(boolean c,String m){ if(!c){f++;System.out.println("FAIL: "+m);} }
  public static void main(String[] x){
    UUID a=new UUID(0,1),b=new UUID(0,2),c=new UUID(0,3);
    Map<UUID,Double> d=new LinkedHashMap<>(); d.put(a,300.0); d.put(b,100.0);
    Map<UUID,Long> r=BossMath.split(d,0,1000);
    ck(r.get(a)==750 && r.get(b)==250,"75/25 split");
    ck(new ArrayList<>(r.keySet()).get(0).equals(a),"top damager first");
    d.put(c,1.0);                      // below 5% of 400
    r=BossMath.split(d,0.05*400,1000);
    ck(!r.containsKey(c),"tiny hit gets nothing");
    ck(r.values().stream().mapToLong(Long::longValue).sum()==1000,"sums to total");
    Map<UUID,Double> e=new LinkedHashMap<>(); e.put(a,1.0); e.put(b,1.0); e.put(c,1.0);
    r=BossMath.split(e,0,100);
    ck(r.values().stream().mapToLong(Long::longValue).sum()==100,"rounding leftover assigned");
    ck(BossMath.split(new HashMap<>(),0,100).isEmpty(),"no damage -> nobody");
    ck(BossMath.split(d,10_000,100).isEmpty(),"nobody over threshold -> nobody");
    ck(BossMath.split(d,0,0).isEmpty(),"zero reward -> nothing");
    System.out.println(f==0?"ALL BOSS TESTS PASSED":"FAILURES "+f); System.exit(f==0?0:1);
  }
}
