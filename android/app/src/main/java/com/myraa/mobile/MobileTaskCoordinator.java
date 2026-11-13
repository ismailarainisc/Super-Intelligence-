package com.myraa.mobile;

import org.json.*;
import java.util.*;

/** Single native authority for mobile actions. Host callbacks run on the main thread. */
public final class MobileTaskCoordinator {
  public interface Host {
    long now();
    boolean save(String value);
    void changed(JSONObject task);
    void execute(String stepId, JSONObject action);
    void cancelAction(String stepId);
    void cancelPlanning();
    void later(Runnable work, long delay);
  }
  private final Host host;
  private final LinkedHashMap<String,JSONObject> history = new LinkedHashMap<>();
  private JSONObject current;
  public static final long TIMEOUT_MS = 300000;
  public MobileTaskCoordinator(Host host, String stored) {
    this.host = host;
    try {
      JSONArray tasks = new JSONArray(stored);
      for (int i=0;i<tasks.length();i++) {
        JSONObject task=tasks.getJSONObject(i);
        if (!terminal(task.optString("status"))) {
          task.put("status","interrupted");
          task.put("message","Interrupted. Continue skips any uncertain in-flight step; start a new request to retry it.");
          // A dispatched action may have happened even without its callback.
          if (!task.optString("inFlight").isEmpty()) task.put("next",task.optInt("next")+1);
          task.remove("inFlight");
        }
        history.put(task.getString("id"),task); current=task;
      }
      persist();
    } catch (Exception ignored) { history.clear(); current=null; }
  }
  public static boolean terminal(String status) {
    return Arrays.asList("completed","failed","cancelled","expired").contains(status);
  }
  public synchronized JSONObject snapshot() { return copy(current==null?new JSONObject():current); }
  public synchronized boolean active() { return current!=null&&!terminal(current.optString("status"))&&!"interrupted".equals(current.optString("status")); }
  public synchronized boolean begin(String id,String goal) {
    if (id==null||!id.matches("[A-Za-z0-9-]{1,80}")||goal==null||goal.trim().isEmpty()||goal.length()>8000) return false;
    if(history.containsKey(id)) {host.changed(copy(history.get(id)));return false;}
    if(active()) return false;
    current=obj("id",id,"goal",goal,"status","planning","message","Planning…","steps",new JSONArray(),"next",0,"startedAt",host.now());
    history.put(id,current);
    while(history.size()>20) history.remove(history.keySet().iterator().next());
    if(!publish()) return false;
    final JSONObject task=current;
    host.later(()->{if(current==task&&active())expire();},TIMEOUT_MS);
    return true;
  }
  public synchronized void accept(String id,JSONArray steps,String explanation) {
    if(!isCurrent(id)||!"planning".equals(current.optString("status")))return;
    try {
      if(steps.length()<1||steps.length()>8)throw new IllegalArgumentException("A plan must contain one to eight steps.");
      JSONArray validated=new JSONArray();
      for(int i=0;i<steps.length();i++) validated.put(MobileActionSpec.validate(steps.getJSONObject(i)));
      current.put("steps",validated).put("message",explanation).put("status","running");
      if(publish()) next();
    }catch(Exception e){fail(id,"The plan was rejected: "+e.getMessage());}
  }
  public synchronized void reply(String id,String text) { if(isCurrent(id)){put("status","completed");put("message",text);publish();} }
  public synchronized void fail(String id,String message) { if(isCurrent(id)&&!terminal(current.optString("status"))){put("status","failed");put("message",message);publish();} }
  private boolean isCurrent(String id){return current!=null&&id.equals(current.optString("id"));}
  private void next() {
    if(!active()||expire())return;
    JSONArray steps=current.optJSONArray("steps");int index=current.optInt("next");
    if(index>=steps.length()){put("status","completed");put("message","Task finished. External drafts or setup screens still require your review; MYRAA has not verified sending or saving.");publish();return;}
    String taskId=current.optString("id");String stepId=taskId.substring(0,Math.min(60,taskId.length()))+"-"+index;
    put("status","running");put("inFlight",stepId);put("message","Step "+(index+1)+" of "+steps.length());
    // Save before dispatch: interrupted/uncertain steps are never blindly replayed.
    if(publish())host.execute(stepId,steps.optJSONObject(index));
  }
  public synchronized boolean result(String stepId,JSONObject result) {
    if(current==null||!stepId.equals(current.optString("inFlight")))return false;
    current.remove("inFlight");
    if(terminal(current.optString("status")))return true;
    String status=result.optString("status");int index=current.optInt("next");
    JSONArray outcomes=current.optJSONArray("outcomes");if(outcomes==null)outcomes=new JSONArray();
    outcomes.put(obj("step",index,"status",status,"message",result.optString("message")));put("outcomes",outcomes);
    put("message",result.optString("message"));
    if(Arrays.asList("opened","draft_opened","setup_opened").contains(status)) {
      put("next",index+1);
      boolean review=!"opened".equals(status)||index+1<current.optJSONArray("steps").length();
      put("status",review?"waiting_user":"completed");
      if(review)put("message",result.optString("message")+" Return to MYRAA and tap Continue when ready.");
    } else put("status","cancelled".equals(status)?"cancelled":"failed");
    publish();return true;
  }
  public synchronized void resume() {
    if(current==null)return;
    String status=current.optString("status");
    if(!Arrays.asList("waiting_user","interrupted","waiting_foreground").contains(status))return;
    if("interrupted".equals(status)) {
      if(current.optJSONArray("steps").length()==0){fail(current.optString("id"),"Planning was interrupted. Submit the request again.");return;}
      put("startedAt",host.now());final JSONObject task=current;
      host.later(()->{if(current==task&&active())expire();},TIMEOUT_MS);
    }
    if(expire())return;
    put("status","running");next();
  }
  public synchronized void waitForForeground() {if(active()){put("status","waiting_foreground");put("message","Return to MYRAA and tap Continue to open the next app.");current.remove("inFlight");publish();}}
  public synchronized void cancel() {
    if(current==null||terminal(current.optString("status")))return;
    String step=current.optString("inFlight");current.remove("inFlight");put("status","cancelled");put("message","Stopped. Already opened screens remain under your control.");
    publish();host.cancelPlanning();if(!step.isEmpty())host.cancelAction(step);
  }
  private boolean expire() {
    if(current!=null&&host.now()-current.optLong("startedAt")>=TIMEOUT_MS) {
      String step=current.optString("inFlight");current.remove("inFlight");put("status","expired");put("message","Task reached its five-minute limit. Start a new request to continue.");publish();host.cancelPlanning();if(!step.isEmpty())host.cancelAction(step);return true;
    }return false;
  }
  private boolean persist(){return host.save(new JSONArray(history.values()).toString());}
  private boolean publish(){
    boolean saved=persist();
    if(!saved){put("status","failed");put("message","Task storage failed. No further actions will run.");}
    host.changed(snapshot());return saved;
  }
  private void put(String key,Object value){try{current.put(key,value);}catch(JSONException e){throw new IllegalStateException(e);}}
  public static JSONObject obj(Object... values){JSONObject o=new JSONObject();try{for(int i=0;i<values.length;i+=2)o.put((String)values[i],values[i+1]);}catch(JSONException e){throw new IllegalStateException(e);}return o;}
  private static JSONObject copy(JSONObject o){try{return new JSONObject(o.toString());}catch(JSONException e){throw new IllegalStateException(e);}}
}
