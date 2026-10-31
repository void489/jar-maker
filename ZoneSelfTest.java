import dev.mobzone.Zone;
public class ZoneSelfTest {
  static int fails=0;
  static void check(boolean c,String m){ if(!c){fails++;System.out.println("FAIL: "+m);} }
  public static void main(String[] a){
    Zone z=new Zone("t","w",-25,100,-70,25,110,-31); // x -25..25, z -70..-31
    double m=0.4;
    check(z.contains(0,101,-50,m),"centre inside");
    check(!z.contains(-25.5,101,-50,m),"west of wall outside");
    check(!z.contains(26.2,101,-50,m),"east of wall outside");
    check(z.contains(-24.5,101,-50,m),"just inside west wall");
    check(!z.contains(-24.7,101,-50,m),"inside the 0.4 margin counts as outside");
    check(!z.contains(-24.5,101,-50,1.0),"margin 1.0 excludes -24.5");
    check(!z.contains(0,101,-30.3,m),"south outside");
    check(!z.contains(0,99,-50,m),"below min y");
    check(z.contains(0,111,-50,m),"top edge y max+1 ok");
    check(!z.contains(0,111.5,-50,m),"above max y");
    check(z.clampX(40,m)==z.hiX(m),"clamp east");
    check(z.clampX(-40,m)==z.loX(m),"clamp west");
    check(z.clampZ(0,m)==z.hiZ(m),"clamp z hi");
    check(z.clampY(50)==100 && z.clampY(500)==111,"clamp y");
    check(z.contains(z.clampX(99,m),z.clampY(99),z.clampZ(-999,m),m),"clamped point is inside");
    // knockback guard
    double lo=z.loX(m),hi=z.hiX(m);
    check(Zone.guardScale(0,0.5,lo,hi,4)==1.0,"far from wall full kb");
    check(Zone.guardScale(hi-2,0.5,lo,hi,4)==0.5,"2 of 4 blocks to wall -> half");
    check(Zone.guardScale(hi,0.5,lo,hi,4)==0.0,"at wall -> zero outward kb");
    check(Zone.guardScale(hi-1,-0.5,lo,hi,4)==1.0,"kb away from near wall untouched");
    check(Zone.guardScale(lo+1,-0.5,lo,hi,4)==0.25,"west quarter");
    check(Zone.guardScale(hi+3,0.5,lo,hi,4)==0.0,"already outside -> zero");
    // thin zone
    Zone t=new Zone("thin","w",0,0,0,0,5,10);
    check(t.loX(0.4)<=t.hiX(0.4),"thin zone margin capped");
    check(z.nearEdge(-24.5,-50,m,1.5) && !z.nearEdge(0,-50,m,1.5),"nearEdge");
    System.out.println(fails==0?"ALL GEOMETRY TESTS PASSED":"FAILURES: "+fails);
    System.exit(fails==0?0:1);
  }
}
