package com.myraa.mobile;

import android.app.Activity;
import android.content.*;
import android.os.*;
import android.util.Base64;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Authoritative composition of real projection ownership, exact-frame approval and provider work.
 * All mutable UI/capture coordination is main-thread-only; worker results cross a generation gate. */
public final class ScreenCompanionController implements ScreenCaptureService.Listener {
  public interface Host {
    void event(JSONObject event);
    String key() throws Exception;
    String model();
    boolean foreground();
    void requestConsent();
    void cancelScreenVoice();
  }
  private final Activity activity;private final Host host;
  private final Handler main=new Handler(Looper.getMainLooper());
  private final ExecutorService worker=Executors.newSingleThreadExecutor();
  private final AtomicBoolean workerBusy=new AtomicBoolean();
  private final ScreenVision vision=new ScreenVision();
  private final ScreenRequestGate gate=new ScreenRequestGate();
  private final JSONArray context=new JSONArray();
  private volatile String sessionId="",phase="IDLE",message="Screen sharing is off. Start sharing and approve Android's permission request.",mode="once";
  private String lastAnswerFrame="",lastAnswerRequest="";
  private volatile FrameProcessor.Frame review;
  private static final long REVIEW_TTL_MS=60000;
  private long reviewTime;private boolean requestPending,closed,reviewNextFrame;
  private int maxEdge=1280,quality=65;private long intervalMs=2000;
  private volatile JSONObject capture=new JSONObject();
  public ScreenCompanionController(Activity activity,Host host){this.activity=activity;this.host=host;ScreenCaptureService.setListener(this);}
  private static JSONObject obj(Object... values){return MobileTaskCoordinator.obj(values);}
  private static void put(JSONObject o,String k,Object v){try{o.put(k,v);}catch(JSONException e){throw new IllegalStateException(e);}}
  public synchronized JSONObject state(){
    boolean owns=ScreenCaptureService.hasProjection();String visible=phase;
    if(!owns&&Arrays.asList("CAPTURING","ANALYZING","WAITING_FOR_AI","SPEAKING","PAUSED").contains(visible))visible="STOPPING".equals(ScreenCaptureService.status().optString("state"))?"STOPPING":"STOPPED";
    JSONObject result=obj("type","screen-state","state",visible,"message",message,"sessionId",sessionId,"hasProjection",owns,"reviewPending",review!=null,"mode",mode,
      "frameSequence",review!=null?review.sequence:Math.max(capture.optLong("frameSequence",0),capture.optLong("sequence",0)),"ocrAvailable",false,"ocr",ScreenPrivacy.ocrStatus(),"features",ScreenFeatures.registry(),
      "maxEdge",maxEdge,"jpegQuality",quality,"intervalMs",intervalMs,"providerPending",vision.pending(),"transport","gemini_image_request","liveVideoSupported","not_verified",
      "privacy","No frames upload automatically. Every snapshot requires review and approval. No screenshots are stored by MYRAA.");
    for(String key:Arrays.asList("width","height","sourceWidth","sourceHeight","windowLabel","contentVisible","framesAcquired","framesProcessed","framesDropped","jpegBytes","captureMode"))if(capture.has(key))put(result,key,capture.opt(key));
    return result;
  }
  private void publish(){if(!closed)host.event(state());}
  private void error(String code,String text){message=text;host.event(obj("type","screen-error","code",code,"message",text,"sessionId",sessionId));publish();}
  public void start(String requestedMode){
    if(closed)return;
    if(!host.foreground()){error("OPEN_APP","Open MYRAA and tap Start Screen Sharing to request Android permission.");return;}
    if(ScreenCaptureService.hasProjection()||Arrays.asList("REQUESTING_PERMISSION","STARTING","STOPPING").contains(phase)){error("SESSION_ACTIVE","A sharing request or session already exists. Stop it before starting another.");return;}
    if(!Arrays.asList("once","guidance").contains(requestedMode)){error("MODE_INVALID","Choose Ask Once or Live Guidance.");return;}
    cancelAnalysis();clearContext();mode=requestedMode;sessionId=UUID.randomUUID().toString();gate.start(sessionId);phase="REQUESTING_PERMISSION";
    message="Approve Android's screen-sharing request. No screen is visible to MYRAA yet.";requestPending=true;publish();
    try{host.requestConsent();}catch(RuntimeException e){requestPending=false;gate.stop();phase="ERROR";error("CONSENT_UNAVAILABLE","Android could not open screen-sharing consent. Sharing is off. Open MYRAA and try again.");}
  }
  public void permissionResult(int code,Intent data){
    if(closed||!requestPending||!"REQUESTING_PERMISSION".equals(phase))return;
    requestPending=false;
    if(code!=Activity.RESULT_OK||data==null){gate.stop();phase="PERMISSION_DENIED";message="Screen sharing was not approved. MYRAA cannot see your screen. Tap Start to request permission again.";publish();return;}
    if(!host.foreground()){gate.stop();phase="STOPPED";message="MYRAA was not visible when consent returned. Start again from the app for a fresh permission request.";publish();return;}
    phase="STARTING";message="Starting Android's screen-capture service…";publish();
    try{if(!ScreenCaptureService.startAuthorized(activity,code,data,sessionId,maxEdge,quality,intervalMs))throw new IllegalStateException("A screen session is already starting.");}
    catch(RuntimeException e){gate.stop();phase="ERROR";error("CAPTURE_START_FAILED","Android could not start screen capture. No sharing session is active; request fresh consent to retry.");}
  }
  @Override public void onCaptureState(JSONObject status){
    if(closed)return;
    String incoming=status.optString("sessionId","");
    if(!incoming.isEmpty()&&!incoming.equals(sessionId))return;
    boolean geometryChanged=capture.has("width")&&status.has("width")&&(capture.optInt("width")!=status.optInt("width")||capture.optInt("height")!=status.optInt("height")||capture.optInt("rotation")!=status.optInt("rotation"));
    capture=status;
    String actual=status.optString("state","");
    if("STOPPING".equals(actual)){cancelAnalysis();gate.stop();clearContext();host.cancelScreenVoice();phase="STOPPING";message=status.optString("message","Releasing capture resources…");publish();return;}
    if(geometryChanged||status.has("contentVisible")&&!status.optBoolean("contentVisible")){cancelAnalysis();clearContext();host.cancelScreenVoice();phase=actual;if(geometryChanged)message="Shared content changed size. Review a new frame before analysis.";}
    if(Arrays.asList("STOPPED","ERROR","DISABLED").contains(actual)){
      cancelAnalysis();gate.stop();clearContext();requestPending=false;lastAnswerFrame="";host.cancelScreenVoice();phase=actual;
      message=status.optString("message","Screen sharing stopped. Capture resources were released.");publish();return;
    }
    if("STOPPING".equals(phase))return;
    if(!Arrays.asList("WAITING_FOR_AI","SPEAKING").contains(phase)&&review==null){phase=actual;message=status.optString("message",actual);}
    publish();
  }
  @Override public void onFrame(FrameProcessor.Frame frame){
    if(closed||!sessionId.equals(frame.sessionId))return;
    if(reviewNextFrame){reviewNextFrame=false;reviewFrame(ScreenCaptureService.snapshotFrame());}
  }
  @Override public void onReviewRequested(FrameProcessor.Frame frame){
    if(closed){if(frame!=null)Arrays.fill(frame.jpeg,(byte)0);return;}
    reviewFrame(frame);
  }
  private void clearReview(){if(review!=null){Arrays.fill(review.jpeg,(byte)0);review=null;}reviewTime=0;}
  private void cancelAnalysis(){vision.cancel();gate.invalidate();reviewNextFrame=false;clearReview();lastAnswerFrame="";lastAnswerRequest="";}
  private void clearContext(){while(context.length()>0)context.remove(0);}
  public void review(){
    if(!ScreenCaptureService.hasProjection()||!gate.active()){error("NO_PROJECTION","Start screen sharing and approve Android's request before capturing a frame.");return;}
    if(workerBusy.get()){error("ANALYSIS_PENDING","An analysis is pending. Pause Analysis to cancel it before capturing another frame.");return;}
    cancelAnalysis();host.cancelScreenVoice();
    // A notification review freezes the frame before returning to MYRAA. In-app review
    // takes a current frame; from paused mode, request one new observation first.
    FrameProcessor.Frame frame=ScreenCaptureService.snapshotFrame();
    if(frame!=null){reviewFrame(frame);return;}
    reviewNextFrame=true;phase="CAPTURING";message="Waiting for a fresh screen frame. Switch to the screen you want to share, or use Review current screen in the notification.";
    ScreenCaptureService.setPaused(false);publish();
    final String expected=sessionId;
    main.postDelayed(()->{if(!closed&&reviewNextFrame&&expected.equals(sessionId)){reviewNextFrame=false;ScreenCaptureService.setPaused(true);phase="PAUSED";error("NO_FRAME","No usable frame arrived. The screen may be protected, hidden or unavailable. No screenshot was sent.");}},10000);
  }
  private void reviewFrame(FrameProcessor.Frame frame){
    if(frame==null){error("NO_FRAME","No recent screen frame is available. Resume capture and use Review current screen in the notification.");return;}
    if(!ScreenCaptureService.hasProjection()||!frame.sessionId.equals(sessionId)){Arrays.fill(frame.jpeg,(byte)0);return;}
    cancelAnalysis();host.cancelScreenVoice();review=frame;reviewTime=SystemClock.elapsedRealtime();
    String frameId=sessionId+":"+frame.sequence;
    gate.review(sessionId,frameId);ScreenCaptureService.setPaused(true);
    phase="PAUSED";message="Captured snapshot ready for review. Inspect it for sensitive information before explicitly sending it to Gemini.";publish();
    host.event(obj("type","screen-review","sessionId",sessionId,"frameId",frameId,"sequence",frame.sequence,"jpeg",Base64.encodeToString(frame.jpeg,Base64.NO_WRAP),
      "width",frame.width,"height",frame.height,"capturedAt",frame.capturedAt,"privacyMessage",ScreenPrivacy.inspect(null,"",false,false).optString("warning"),"ocrAvailable",false));
    main.postDelayed(()->{if(!closed&&review==frame&&SystemClock.elapsedRealtime()-reviewTime>=REVIEW_TTL_MS&&!workerBusy.get()){clearReview();gate.invalidate();phase="PAUSED";message="The review expired. Capture and review a fresh frame.";publish();}},REVIEW_TTL_MS);
  }
  public void discardReview(){cancelAnalysis();host.cancelScreenVoice();if(ScreenCaptureService.hasProjection()){ScreenCaptureService.setPaused(true);phase="PAUSED";message="Snapshot discarded. No image was sent. Resume capture for a new observation.";}publish();}
  public void pause(boolean paused){
    if(!ScreenCaptureService.hasProjection()){error("NO_PROJECTION","Screen sharing is off.");return;}
    cancelAnalysis();host.cancelScreenVoice();ScreenCaptureService.setPaused(paused);phase=paused?"PAUSED":"CAPTURING";
    message=paused?"Analysis paused. Pending requests and cached images were cleared; Android sharing remains active until Stop Sharing.":"Capturing locally. Use Review current screen in the notification; nothing uploads automatically.";publish();
  }
  public void analyze(String frameId,String feature,String question,String language){
    if(!ScreenCaptureService.hasProjection()||review==null||!gate.active()||!frameId.equals(sessionId+":"+review.sequence)){error("REVIEW_REQUIRED","Review this exact frame in the current sharing session before approving analysis.");return;}
    if(SystemClock.elapsedRealtime()-reviewTime>REVIEW_TTL_MS){discardReview();error("REVIEW_EXPIRED","That snapshot review expired. Capture a fresh frame.");return;}
    if(!ScreenFeatures.supported(feature)){error("FEATURE_INVALID","Choose a supported Screen Companion feature.");return;}
    JSONObject privacy=ScreenPrivacy.inspect(null,question,true,false);
    if(!privacy.optBoolean("allowed")){discardReview();error("SENSITIVE_CONTENT",privacy.optString("reason"));return;}
    if(!workerBusy.compareAndSet(false,true)){error("ANALYSIS_PENDING","A visual request is already pending. Wait or pause to cancel it.");return;}
    final String expectedSession=sessionId,requestId=UUID.randomUUID().toString();final FrameProcessor.Frame frame=review;
    final long token;
    try{token=gate.begin(frameId,requestId);}catch(IllegalStateException e){workerBusy.set(false);error("REVIEW_REQUIRED",e.getMessage());return;}
    final JSONArray approvedContext=new JSONArray();
    for(int i=0;i<context.length();i++)approvedContext.put(context.opt(i));
    phase="WAITING_FOR_AI";message="Sending the approved snapshot to your configured Gemini image-understanding model. Waiting for its actual response…";publish();
    worker.execute(()->{
      try{
        String key=host.key(),model=host.model();
        if(!gate.valid(token,expectedSession,frameId,requestId))return;
        JSONObject result=vision.analyze(key,model,expectedSession,requestId,frameId,frame.jpeg,feature,question,language,approvedContext,true,()->gate.valid(token,expectedSession,frameId,requestId));
        main.post(()->{
          if(closed||!ScreenCaptureService.hasProjection()||!gate.valid(token,expectedSession,frameId,requestId))return;
          put(result,"type","screen-answer");host.event(result);
          while(context.length()>=3)context.remove(0);
          context.put(obj("frameId",frameId,"answer",result.optString("answer")));
          lastAnswerFrame=frameId;lastAnswerRequest=requestId;
          gate.finish();clearReview();phase="PAUSED";message="Gemini analyzed the approved snapshot. Review a fresh frame for the next observation; screen content may have changed.";publish();
        });
      }catch(Exception e){
        final String code=e instanceof ScreenVision.Failure?((ScreenVision.Failure)e).code:"VISION_UNAVAILABLE";
        final String text=e instanceof ScreenVision.Failure?e.getMessage():"Screen analysis could not start. Check your saved Gemini key and model. No model or preferences were changed.";
        main.post(()->{if(!closed&&gate.valid(token,expectedSession,frameId,requestId)){gate.finish();clearReview();phase="PAUSED";error(code,text);}});
      }finally{workerBusy.set(false);}
    });
  }
  public void voiceState(String frameId,boolean speaking){
    if(closed||!ScreenCaptureService.hasProjection()||!lastAnswerFrame.equals(frameId))return;
    phase=speaking?"SPEAKING":"PAUSED";message=speaking?"The existing Gemini Live voice session is speaking the approved screen explanation.":"Screen explanation finished. Review a fresh frame for further guidance.";publish();
  }
  public void options(String raw){
    if(ScreenCaptureService.hasProjection()||requestPending){error("OPTIONS_LOCKED","Stop sharing before changing capture quality or sampling limits.");return;}
    try{JSONObject options=new JSONObject(raw);int edge=options.optInt("maxEdge",maxEdge),q=options.optInt("jpegQuality",quality);long interval=options.optLong("intervalMs",intervalMs);
      if(edge<480||edge>1600||q<40||q>85||interval<1000||interval>10000)throw new IllegalArgumentException();
      maxEdge=edge;quality=q;intervalMs=interval;message="Capture options updated for the next explicitly authorized session.";publish();
    }catch(Exception e){error("OPTIONS_INVALID","Use 480–1600 pixels, JPEG quality 40–85, and a sampling interval of 1–10 seconds.");}
  }
  public void stop(String reason){
    requestPending=false;cancelAnalysis();gate.stop();clearContext();host.cancelScreenVoice();
    if(ScreenCaptureService.hasProjection()||Arrays.asList("STARTING","STOPPING").contains(phase)||"STOPPING".equals(ScreenCaptureService.status().optString("state"))){phase="STOPPING";message="Stopping Android screen capture…";publish();ScreenCaptureService.stopCapture(activity,reason);}
    else{phase="STOPPED";message="Screen sharing stopped. MYRAA cannot see your screen.";publish();}
  }
  public void close(){if(closed)return;stop("activity_destroyed");closed=true;ScreenCaptureService.setListener(null);main.removeCallbacksAndMessages(null);worker.shutdownNow();}
}
