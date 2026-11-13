import com.myraa.mobile.*;
import org.json.*;
import java.util.*;

/** Deterministic tests of the production coordinator, not a second task implementation. */
public final class MobileTaskCoordinatorTest {
  static int checks;
  static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
  static class Fixture implements MobileTaskCoordinator.Host {
    long time=1000;String stored="[]";boolean writable=true;int planningCancels;String cancelled;String step;JSONObject action;List<Runnable> timers=new ArrayList<>();
    public long now(){return time;}public boolean save(String value){if(writable)stored=value;return writable;}
    public void changed(JSONObject task){}public void execute(String id,JSONObject args){step=id;action=args;}
    public void cancelAction(String id){cancelled=id;}public void cancelPlanning(){planningCancels++;}public void later(Runnable work,long delay){timers.add(work);}
    MobileTaskCoordinator coordinator(){return new MobileTaskCoordinator(this,stored);}
  }
  static JSONArray steps(String json){return new JSONArray(json);}
  static JSONObject result(String status){return new JSONObject().put("status",status).put("message",status);}
  static void rejects(JSONObject action,String label){try{MobileActionSpec.validate(action);throw new AssertionError(label);}catch(IllegalArgumentException expected){checks++;System.out.println("PASS "+label);}}
  public static void main(String[] args)throws Exception{
    Fixture f=new Fixture();MobileTaskCoordinator c=f.coordinator();
    check(c.begin("one","Open browser then prepare message"),"starts a task");
    check(!c.begin("other","Other task"),"one active task across entry points");
    c.accept("one",steps("[{action:'open_app',app:'Chrome'},{action:'whatsapp_message',recipient:'Sam',message:'Meet at 5?'}]"),"Working");
    check(f.action.getString("app").equals("Chrome"),"dispatches first validated action");
    String first=f.step;c.result(first,result("opened"));
    check(c.snapshot().getString("status").equals("waiting_user")&&c.snapshot().getInt("next")==1,"external handoff pauses remaining steps");
    check(!c.result(first,result("opened")),"duplicate result never advances task");
    c.resume();check(f.action.getString("message").equals("Meet at 5?"),"continuation preserves exact message");
    c.result(f.step,result("draft_opened"));check(c.snapshot().getString("status").equals("waiting_user"),"last draft still requires review");
    c.resume();check(c.snapshot().getString("status").equals("completed")&&c.snapshot().getString("message").contains("not verified"),"completion does not assert sending or saving");
    check(!c.begin("one","duplicate"),"completed request IDs are not replayed");
    Fixture invalid=new Fixture();MobileTaskCoordinator bad=invalid.coordinator();bad.begin("invalid","Request");
    bad.accept("invalid",steps("[{action:'open_app',app:'Chrome'},{action:'set_timer',seconds:-1}]"),"");
    check(invalid.action==null&&bad.snapshot().getString("status").equals("failed"),"entire plan validated before first side effect");
    Fixture stop=new Fixture();MobileTaskCoordinator stopped=stop.coordinator();stopped.begin("stop","Task");stopped.accept("stop",steps("[{action:'open_app',app:'Chrome'}]"),"");
    String pending=stop.step;stopped.cancel();stopped.result(pending,result("opened"));
    check(stopped.snapshot().getString("status").equals("cancelled")&&pending.equals(stop.cancelled)&&stop.planningCancels==1,"Stop cancels planning and dispatch; late callback ignored");
    Fixture timeout=new Fixture();MobileTaskCoordinator timed=timeout.coordinator();timed.begin("time","Task");timeout.time+=MobileTaskCoordinator.TIMEOUT_MS;timeout.timers.get(0).run();
    check(timed.snapshot().getString("status").equals("expired")&&timeout.planningCancels==1,"five minute deadline cancels planning");
    Fixture restart=new Fixture();MobileTaskCoordinator original=restart.coordinator();original.begin("restart","Task");original.accept("restart",steps("[{action:'open_app',app:'Chrome'},{action:'search_web',query:'cats'}]"),"");
    restart.action=null;MobileTaskCoordinator restored=restart.coordinator();
    check(restored.snapshot().getString("status").equals("interrupted")&&restart.action==null,"restart never executes automatically");
    restored.resume();check(restart.action.getString("action").equals("search_web"),"explicit continuation skips uncertain in-flight action");
    Fixture denied=new Fixture();MobileTaskCoordinator noStorage=denied.coordinator();denied.writable=false;check(!noStorage.begin("disk","Task")&&denied.action==null,"storage failure blocks dispatch");
    Fixture stale=new Fixture();MobileTaskCoordinator fresh=stale.coordinator();fresh.begin("old","Old");fresh.cancel();fresh.begin("new","New");fresh.accept("old",steps("[{action:'open_app',app:'Chrome'}]"),"");check(stale.action==null,"stale model response cannot affect a new task");
    rejects(new JSONObject("{action:'run_shell',command:'anything'}"),"unknown tool rejected");
    rejects(new JSONObject("{action:'set_alarm',hour:24,minute:0}"),"invalid alarm rejected");
    rejects(new JSONObject("{action:'set_timer',seconds:1.5}"),"fractional timer rejected");
    rejects(new JSONObject("{action:'set_timer',seconds:'60'}"),"numeric strings rejected");
    rejects(new JSONObject("{action:'calendar_event',title:'Meeting',startAt:200,endAt:100}"),"inverted event rejected");
    rejects(new JSONObject("{action:'whatsapp_message',recipient:'Sam',message:''}"),"empty message rejected");
    check(MobileActionSpec.validate(new JSONObject("{action:'set_timer',seconds:60,label:'Tea'}")).getInt("seconds")==60,"valid timer retained");
    check(WakeListener.matches("Hey MYRAA")&&WakeListener.matches("hey, myra"),"wake aliases accepted");
    check(!WakeListener.matches("play my radio")&&!WakeListener.matches("hey myraaaaa")&&!WakeListener.matches(null),"unrelated speech does not trigger wake");
    check(WakeListener.isStopPhrase("Stop listening!")&&!WakeListener.isStopPhrase("how do I stop listening"),"explicit listening-stop phrase recognized without matching explanations");
    System.out.println("Native coordinator checks: "+checks);
  }
}
