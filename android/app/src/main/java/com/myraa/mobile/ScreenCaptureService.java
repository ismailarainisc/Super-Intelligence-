package com.myraa.mobile;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.hardware.display.*;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.*;
import android.os.*;
import android.util.DisplayMetrics;
import android.view.*;
import org.json.*;
import java.util.*;

/** Owns exactly one user-authorized projection. Never restores consent or starts on reboot. */
public final class ScreenCaptureService extends Service {
  public static final String ACTION_START="com.myraa.mobile.SCREEN_START";
  public static final String ACTION_REVIEW="com.myraa.mobile.SCREEN_REVIEW";
  public static final String ACTION_STOP="com.myraa.mobile.SCREEN_STOP";
  private static final int NOTIFICATION_ID=84;
  private static final String CHANNEL="screen_companion";
  public interface Listener {void onCaptureState(JSONObject state);void onFrame(FrameProcessor.Frame frame);default void onReviewRequested(FrameProcessor.Frame frame){frame.clear();}}
  private static volatile Listener listener;
  private static volatile ScreenCaptureService instance;
  private static final ScreenSessionPolicy sessions=new ScreenSessionPolicy();
  private final Handler main=new Handler(Looper.getMainLooper());
  private HandlerThread captureThread;private Handler capture;
  private MediaProjection projection;private VirtualDisplay display;private ImageReader reader;
  private MediaProjection.Callback projectionCallback;
  private DisplayManager displays;
  private DisplayManager.DisplayListener displayListener;
  private BroadcastReceiver screenReceiver;
  private final Object frameLock=new Object();
  private FrameProcessor.Frame latest;
  private volatile boolean stopping,paused,contentVisible=true;
  private volatile boolean projectionOwned;
  private volatile long generation;private long sequence,lastProcessed;
  private long intervalMs=2000;
  private int maxEdge=1280,quality=65;private volatile int sourceWidth,sourceHeight,width,height,density,rotation;
  private volatile String session="",state="STOPPED",reason="Screen sharing is stopped.";
  private boolean destroyed;
  private String lastFingerprint="";
  private volatile long acquiredFrames,encodedFrames,droppedFrames,deduplicatedFrames;

  public static void setListener(Listener value){listener=value;if(value!=null){ScreenCaptureService current=instance;if(current!=null)current.main.post(()->current.publish());}}
  /** Call only from the Activity's fresh RESULT_OK consent callback. */
  public static synchronized boolean startAuthorized(Context context,int resultCode,Intent data,String sessionId,int maxEdge,int quality,long intervalMs){
    if(resultCode!=Activity.RESULT_OK||data==null||sessionId==null||!sessionId.matches("[A-Za-z0-9-]{1,80}"))throw new IllegalArgumentException("Fresh Android screen-sharing approval is required.");
    if(!sessions.reserve(sessionId,instance!=null))return false;
    Intent command=new Intent(context,ScreenCaptureService.class).setAction(ACTION_START).putExtra("code",resultCode).putExtra("approval",data).putExtra("session",sessionId).putExtra("edge",maxEdge).putExtra("quality",quality).putExtra("interval",intervalMs);
    try{context.startForegroundService(command);return true;}catch(RuntimeException e){sessions.cancel();throw e;}
  }
  public static void stopCapture(Context context,String message){ScreenCaptureService current=instance;if(current!=null)current.main.post(()->current.stopInternal(message==null?"Screen sharing stopped.":message,"STOPPED"));else sessions.cancel();}
  public static boolean hasProjection(){ScreenCaptureService current=instance;return current!=null&&current.projectionOwned&&!current.stopping;}
  public static JSONObject status(){ScreenCaptureService current=instance;return current==null?object("state",sessions.pending().isEmpty()?"STOPPED":"STARTING","capturing",false,"sessionId",sessions.pending(),"message",sessions.pending().isEmpty()?"Screen sharing is stopped.":"Starting the approved capture service."):current.stateSnapshot();}
  public static FrameProcessor.Frame snapshotFrame(){ScreenCaptureService current=instance;if(current==null||!current.projectionOwned||current.paused||!current.contentVisible)return null;synchronized(current.frameLock){if(current.latest==null||!current.latest.fresh(System.currentTimeMillis()))return null;return current.latest.copy();}}
  /** Invoke from MainActivity.onNewIntent ACTION_REVIEW before rendering its own screen. */
  public static void requestReview(){
    ScreenCaptureService current=instance;if(current==null)return;
    Runnable review=()->{
      if(!current.projectionOwned||current.stopping)return;
      Listener target=listener;if(target==null){current.stopInternal("MYRAA's review UI is unavailable. Sharing stopped; request fresh approval to recover.","STOPPED");return;}
      FrameProcessor.Frame frame=snapshotFrame();
      if(frame==null){current.update(current.paused?"PAUSED":"CAPTURING","No fresh visible frame is available to review. Resume capture and try again.");return;}
      current.paused=true;current.dropFrame();current.lastFingerprint="";
      current.update("PAUSED","Current screen frozen locally for privacy review. Nothing has been uploaded.");
      target.onReviewRequested(frame);
    };
    if(Looper.myLooper()==Looper.getMainLooper())review.run();else current.main.post(review);
  }
  public static void clearFrame(){ScreenCaptureService current=instance;if(current!=null)current.dropFrame();}
  public static void setPaused(boolean value){ScreenCaptureService current=instance;if(current==null)return;current.main.post(()->{if(!current.projectionOwned||current.stopping)return;current.paused=value;current.dropFrame();current.lastFingerprint="";current.update(value?"PAUSED":"CAPTURING",value?"Analysis paused. Projection remains authorized; no frames are processed or uploaded.":"Capturing locally. Review a frame before sending it to Gemini.");});}
  @Override public IBinder onBind(Intent intent){return null;}
  @Override public void onCreate(){super.onCreate();instance=this;captureThread=new HandlerThread("myraa-screen-capture");captureThread.start();capture=new Handler(captureThread.getLooper());}
  @Override public int onStartCommand(Intent command,int flags,int startId){
    if(command==null){stopInternal("Capture cannot restart without fresh Android approval.","STOPPED");return START_NOT_STICKY;}
    if(ACTION_STOP.equals(command.getAction())){stopInternal("Screen sharing stopped from its notification.","STOPPED");return START_NOT_STICKY;}
    if(!ACTION_START.equals(command.getAction())||projectionOwned||!session.isEmpty())return START_NOT_STICKY;
    String requested=command.getStringExtra("session");
    if(!sessions.consume(requested)){stopInternal("A fresh screen-sharing request is required.","ERROR");return START_NOT_STICKY;}
    if(listener==null){stopInternal("MYRAA review controls are unavailable. Request sharing again from the app.","ERROR");return START_NOT_STICKY;}
    session=requested;maxEdge=Math.max(320,Math.min(1600,command.getIntExtra("edge",1280)));quality=Math.max(35,Math.min(85,command.getIntExtra("quality",65)));intervalMs=Math.max(1000,Math.min(10000,command.getLongExtra("interval",2000)));
    update("STARTING","Starting the user-approved screen capture.");
    try{
      // Android 14+: consent was already approved; foreground service precedes getMediaProjection.
      startProjectionForeground();
      Intent approval=Build.VERSION.SDK_INT>=33?command.getParcelableExtra("approval",Intent.class):(Intent)command.getParcelableExtra("approval");
      if(approval==null||command.getIntExtra("code",Activity.RESULT_CANCELED)!=Activity.RESULT_OK)throw new SecurityException("Fresh capture approval is missing.");
      MediaProjectionManager manager=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
      projection=manager.getMediaProjection(Activity.RESULT_OK,approval);
      if(projection==null)throw new SecurityException("Android did not grant a projection session.");
      final long mine=++generation;
      projectionCallback=new MediaProjection.Callback(){
        @Override public void onStop(){if(mine==generation)stopInternal("Android ended screen sharing. Start again to request fresh consent.","STOPPED");}
        @Override public void onCapturedContentResize(int w,int h){main.post(()->{if(mine==generation&&projectionOwned&&!stopping)resize(w,h);});}
        @Override public void onCapturedContentVisibilityChanged(boolean visible){main.post(()->{if(mine!=generation||stopping)return;contentVisible=visible;if(!visible)dropFrame();update(paused?"PAUSED":"CAPTURING",visible?"Shared content is visible. Review a frame before sending.":"Shared app content is not visible. Visual analysis is unavailable until it returns.");});}
      };
      // Must be registered before createVirtualDisplay on API 34+.
      projection.registerCallback(projectionCallback,main);
      measureDisplay();int[] size=FrameProcessor.boundedSize(sourceWidth,sourceHeight,maxEdge);width=size[0];height=size[1];
      reader=createReader(width,height,mine);
      display=projection.createVirtualDisplay("MYRAA user-approved screen",width,height,density,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader.getSurface(),null,main);
      if(display==null)throw new IllegalStateException("Android could not create the capture display.");
      projectionOwned=true;registerLifecycle();
      update("CAPTURING","Capturing locally. No frame is sent until you review and approve it.");
    }catch(SecurityException e){stopInternal("Android rejected screen capture. Start again and approve a fresh sharing request.","ERROR");}
    catch(OutOfMemoryError e){stopInternal("Not enough memory to capture this screen safely. Close other apps and request sharing again.","ERROR");}
    catch(RuntimeException e){stopInternal("Screen capture could not start. Check Android permissions and request sharing again.","ERROR");}
    finally{command.removeExtra("approval");}
    return START_NOT_STICKY;
  }
  private void startProjectionForeground(){
    NotificationManager manager=getSystemService(NotificationManager.class);manager.createNotificationChannel(new NotificationChannel(CHANNEL,"Screen Companion sharing",NotificationManager.IMPORTANCE_LOW));
    PendingIntent open=PendingIntent.getActivity(this,84,new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    PendingIntent review=PendingIntent.getActivity(this,86,new Intent(this,MainActivity.class).setAction(ACTION_REVIEW).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    PendingIntent stop=PendingIntent.getService(this,85,new Intent(this,ScreenCaptureService.class).setAction(ACTION_STOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    Notification notification=new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("MYRAA · screen sharing on").setContentText("Frames stay local until reviewed. Stop sharing at any time.").setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"Review current screen",review).build()).addAction(new Notification.Action.Builder(null,"Stop sharing",stop).build()).build();
    if(Build.VERSION.SDK_INT>=29)startForeground(NOTIFICATION_ID,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);else startForeground(NOTIFICATION_ID,notification);
  }
  private void measureDisplay(){
    Display device=((WindowManager)getSystemService(WINDOW_SERVICE)).getDefaultDisplay();DisplayMetrics metrics=new DisplayMetrics();device.getRealMetrics(metrics);
    sourceWidth=metrics.widthPixels;sourceHeight=metrics.heightPixels;density=Math.max(1,metrics.densityDpi);rotation=device.getRotation();
  }
  private ImageReader createReader(int w,int h,long mine){
    ImageReader next=ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,2);
    next.setOnImageAvailableListener(source->consume(source,mine),capture);return next;
  }
  private void consume(ImageReader source,long mine){
    Image image=null;
    try{
      image=source.acquireLatestImage();if(image==null)return;acquiredFrames++;
      if(mine!=generation||!projectionOwned||stopping||paused||!contentVisible){droppedFrames++;return;}
      long now=System.currentTimeMillis();if(now-lastProcessed<intervalMs){droppedFrames++;return;}lastProcessed=now;
      final String currentSession=session;final long frameSequence=++sequence;
      main.post(()->{if(mine==generation&&projectionOwned&&!paused&&!stopping)update("ANALYZING","Processing an actual captured frame locally. No upload has occurred.");});
      FrameProcessor.Frame frame=FrameProcessor.convert(image,currentSession,frameSequence,now,sourceWidth,sourceHeight,rotation,quality);
      if(mine!=generation||stopping||paused||source!=reader){frame.clear();return;}
      if(frame.blankOrProtected){frame.clear();dropFrame();main.post(()->{if(mine==generation&&!stopping)update("CAPTURING","The captured image is blank or protected. No image can be approved for analysis. MYRAA cannot bypass protected screens.");});return;}
      if(frame.fingerprint.equals(lastFingerprint)){
        deduplicatedFrames++;
        // Refresh the age of unchanged legitimate content using the newly acquired frame.
      }
      lastFingerprint=frame.fingerprint;encodedFrames++;
      synchronized(frameLock){if(mine!=generation||stopping||paused||source!=reader){frame.clear();return;}if(latest!=null)latest.clear();latest=frame;}
      main.postDelayed(()->{synchronized(frameLock){if(latest!=null&&!latest.fresh(System.currentTimeMillis())){latest.clear();latest=null;}}},FrameProcessor.MAX_FRAME_AGE_MS+1);
      main.post(()->{if(mine!=generation||!projectionOwned||stopping||paused)return;update("CAPTURING","A real captured frame is available locally. Review and approve before Gemini analysis.");Listener target=listener;if(target!=null)target.onFrame(frame);});
    }catch(OutOfMemoryError e){main.post(()->{if(mine==generation)stopInternal("Screen capture stopped due to memory pressure. No further images will be sent.","ERROR");});}
    catch(RuntimeException e){main.post(()->{if(mine==generation&&!stopping)stopInternal("Screen frame processing failed. Start sharing again to recover.","ERROR");});}
    finally{if(image!=null)image.close();}
  }
  private void resize(int newSourceWidth,int newSourceHeight){
    if(!projectionOwned||stopping)return;
    ImageReader replacement=null;
    try{
      int[] size=FrameProcessor.boundedSize(newSourceWidth,newSourceHeight,maxEdge);
      sourceWidth=newSourceWidth;sourceHeight=newSourceHeight;rotation=((WindowManager)getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRotation();
      if(size[0]==width&&size[1]==height)return;
      dropFrame();lastFingerprint="";
      // Replace Surface and resize this SAME VirtualDisplay; never create a second one for a token.
      ImageReader previous=reader;replacement=createReader(size[0],size[1],generation);
      display.setSurface(null);display.resize(size[0],size[1],density);display.setSurface(replacement.getSurface());reader=replacement;width=size[0];height=size[1];
      if(previous!=null){previous.setOnImageAvailableListener(null,null);capture.post(previous::close);}
      update(paused?"PAUSED":"CAPTURING","Shared content size changed. Previous frame approvals must be reviewed again.");
    }catch(OutOfMemoryError e){if(replacement!=null&&replacement!=reader)replacement.close();stopInternal("Capture stopped because resizing exceeded available memory. Request fresh approval after freeing memory.","ERROR");}
    catch(RuntimeException e){if(replacement!=null&&replacement!=reader)replacement.close();stopInternal("Capture could not resize safely. Start sharing again and approve Android's request.","ERROR");}
  }
  private void registerLifecycle(){
    displays=(DisplayManager)getSystemService(DISPLAY_SERVICE);
    displayListener=new DisplayManager.DisplayListener(){public void onDisplayAdded(int id){}public void onDisplayRemoved(int id){}public void onDisplayChanged(int id){if(Build.VERSION.SDK_INT<34&&id==Display.DEFAULT_DISPLAY){int oldWidth=sourceWidth,oldHeight=sourceHeight;measureDisplay();if(oldWidth!=sourceWidth||oldHeight!=sourceHeight)resize(sourceWidth,sourceHeight);}}};
    displays.registerDisplayListener(displayListener,main);
    screenReceiver=new BroadcastReceiver(){@Override public void onReceive(Context context,Intent intent){if(Intent.ACTION_SCREEN_OFF.equals(intent.getAction()))stopInternal("Screen sharing stopped when the device screen turned off. Fresh approval is required to share again.","STOPPED");}};
    IntentFilter filter=new IntentFilter(Intent.ACTION_SCREEN_OFF);if(Build.VERSION.SDK_INT>=33)registerReceiver(screenReceiver,filter,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(screenReceiver,filter);
  }
  private void dropFrame(){synchronized(frameLock){if(latest!=null){latest.clear();latest=null;}}}
  private JSONObject stateSnapshot(){int frameBytes=0;long frameTime=0;long frameSequence=0;synchronized(frameLock){if(latest!=null){frameBytes=latest.jpeg.length;frameTime=latest.capturedAt;frameSequence=latest.sequence;}}return object("state",state,"sessionId",session,"message",reason,"capturing",projectionOwned&&!stopping,"paused",paused,"contentVisible",contentVisible,"width",width,"height",height,"sourceWidth",sourceWidth,"sourceHeight",sourceHeight,"rotation",rotation,"intervalMs",intervalMs,"jpegQuality",quality,"sequence",sequence,"frameSequence",frameSequence,"latestFrameBytes",frameBytes,"jpegBytes",frameBytes,"framesAcquired",acquiredFrames,"framesProcessed",encodedFrames,"framesDropped",droppedFrames,"captureMode",Build.VERSION.SDK_INT>=34?"android_selected_screen_or_app":"android_selected_screen","latestFrameCapturedAt",frameTime,"encodedFrames",encodedFrames,"droppedFrames",droppedFrames,"deduplicatedFrames",deduplicatedFrames,"sharingScope",Build.VERSION.SDK_INT>=34?"Android-selected screen or app window":"Android-selected screen");}
  private void update(String next,String message){state=next;reason=message;publish();}
  private void publish(){Listener target=listener;if(target!=null)target.onCaptureState(stateSnapshot());}
  private void stopInternal(String message,String terminalState){
    if(stopping)return;stopping=true;generation++;update("STOPPING",message);dropFrame();
    projectionOwned=false;paused=false;contentVisible=false;
    if(displays!=null&&displayListener!=null){try{displays.unregisterDisplayListener(displayListener);}catch(RuntimeException ignored){}displayListener=null;}
    if(screenReceiver!=null){try{unregisterReceiver(screenReceiver);}catch(RuntimeException ignored){}screenReceiver=null;}
    if(display!=null){try{display.setSurface(null);}catch(RuntimeException ignored){}try{display.release();}catch(RuntimeException ignored){}display=null;}
    final ImageReader old=reader;reader=null;if(old!=null)old.setOnImageAvailableListener(null,null);
    if(projection!=null){if(projectionCallback!=null)try{projection.unregisterCallback(projectionCallback);}catch(RuntimeException ignored){}try{projection.stop();}catch(RuntimeException ignored){}projection=null;projectionCallback=null;}
    sessions.cancel();
    // STOPPED is published only AFTER the reader and any acquired frame have closed.
    capture.post(()->{if(old!=null)try{old.close();}catch(RuntimeException ignored){}main.post(()->{stopForeground(true);update(terminalState,message);if(destroyed){if(instance==this)instance=null;main.removeCallbacksAndMessages(null);}else stopSelf();});});
  }
  @Override public void onTaskRemoved(Intent rootIntent){stopInternal("Screen sharing stopped when MYRAA was dismissed.","STOPPED");}
  @Override public void onTrimMemory(int level){super.onTrimMemory(level);if(level==TRIM_MEMORY_RUNNING_CRITICAL||level>=TRIM_MEMORY_BACKGROUND)stopInternal("Screen sharing stopped under memory pressure. Start again when memory is available.","STOPPED");}
  @Override public void onDestroy(){destroyed=true;if(!stopping)stopInternal("The screen sharing service ended. Fresh approval is required to share again.","STOPPED");if(captureThread!=null){captureThread.quitSafely();captureThread=null;}if("STOPPED".equals(state)||"ERROR".equals(state)){main.removeCallbacksAndMessages(null);if(instance==this)instance=null;}super.onDestroy();}
  private static JSONObject object(Object... pairs){JSONObject result=new JSONObject();try{for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);}catch(JSONException e){throw new IllegalStateException(e);}return result;}
}
