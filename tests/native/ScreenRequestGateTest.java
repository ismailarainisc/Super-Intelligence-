import com.myraa.mobile.ScreenRequestGate;
public final class ScreenRequestGateTest {
  static int count;
  static void check(boolean b,String s){if(!b)throw new AssertionError(s);count++;System.out.println("PASS "+s);}
  public static void main(String[] args){
    ScreenRequestGate gate=new ScreenRequestGate();gate.start("session-a");
    try{gate.begin("unreviewed","request");throw new AssertionError("unreviewed image");}catch(IllegalStateException expected){check(true,"unreviewed image cannot start analysis");}
    gate.review("session-a","frame-1");long first=gate.begin("frame-1","request-1");
    check(gate.valid(first,"session-a","frame-1","request-1"),"approved exact session/frame/request is valid");
    check(!gate.valid(first,"session-b","frame-1","request-1"),"wrong session rejected");
    check(!gate.valid(first,"session-a","frame-2","request-1"),"wrong screenshot rejected");
    check(!gate.valid(first,"session-a","frame-1","request-2"),"wrong request rejected");
    try{gate.begin("frame-1","duplicate");throw new AssertionError("duplicate approval");}catch(IllegalStateException expected){check(true,"approval is single-use while provider pending");}
    gate.stop();check(!gate.valid(first,"session-a","frame-1","request-1"),"Stop suppresses late provider response");
    gate.start("session-b");gate.review("session-b","frame-2");long second=gate.begin("frame-2","request-2");
    check(!gate.valid(first,"session-a","frame-1","request-1"),"new sharing session cannot restore old authorization");
    gate.invalidate();check(!gate.valid(second,"session-b","frame-2","request-2"),"pause/review discard revokes an in-flight approval");
    try{gate.review("session-a","stale");throw new AssertionError("stale review");}catch(IllegalStateException expected){check(true,"stale capture session cannot create a review");}
    gate.review("session-b","frame-3");long third=gate.begin("frame-3","request-3");gate.finish();
    check(!gate.valid(third,"session-b","frame-3","request-3"),"completed answer approval cannot be replayed");
    System.out.println("Request gate checks: "+count);
  }
}
