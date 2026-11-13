package com.myraa.mobile;

import android.content.*;
import android.os.*;
import android.speech.*;
import org.json.*;
import java.util.*;

/** Opt-in recognition sessions, never an always-on or reboot-started microphone. */
public final class WakeListener implements RecognitionListener {
  public interface Host { void event(JSONObject event); void wake(); void service(boolean enabled); }
  private final Context context;private final Host host;private final Handler handler=new Handler(Looper.getMainLooper());
  private SpeechRecognizer recognizer;private boolean enabled,suspended;private int failures;private int generation;private int recognitionGeneration;
  public WakeListener(Context context,Host host){this.context=context;this.host=host;}
  public boolean enabled(){return enabled;}
  public static boolean matches(String text){return text!=null&&text.toLowerCase(Locale.ROOT).matches(".*\\bhey[ ,]+myraa?\\b.*");}
  public static boolean isStopPhrase(String text){return text!=null&&text.trim().toLowerCase(Locale.ROOT).matches("(?:stop listening|stop myraa?|रुको|बस)[.!। ]*");}
  public void enable(){
    disable();
    if(!SpeechRecognizer.isRecognitionAvailable(context)){report("unavailable","Install or enable an Android speech recognition service. Talk and typed tasks remain available.");return;}
    enabled=true;suspended=false;failures=0;
    try{host.service(true);}catch(RuntimeException e){disable();report("unavailable","Android could not start listening. Open MYRAA and try again.");return;}
    report("enabled","Wake listening enabled. Say Hey MYRAA. The speech provider may use internet; on-device recognition is preferred.");
    int token=generation;handler.postDelayed(()->{if(token==generation&&enabled){disable();report("stopped","Listening session ended after 30 minutes. Tap Enable listening to start another.");}},1800000);
    arm();
  }
  public void suspend(){suspended=true;destroyRecognizer();if(enabled)report("suspended","Wake listening paused during conversation or speech.");}
  public void resume(){if(!enabled)return;suspended=false;arm();}
  public void disable(){enabled=false;suspended=false;generation++;handler.removeCallbacksAndMessages(null);destroyRecognizer();host.service(false);report("stopped","Wake listening off.");}
  private void destroyRecognizer(){recognitionGeneration++;if(recognizer!=null){SpeechRecognizer old=recognizer;recognizer=null;try{old.cancel();}catch(RuntimeException ignored){}try{old.destroy();}catch(RuntimeException ignored){}}}
  private void arm(){
    if(!enabled||suspended||recognizer!=null)return;
    try{
      boolean local=Build.VERSION.SDK_INT>=31&&SpeechRecognizer.isOnDeviceRecognitionAvailable(context);
      recognizer=local?SpeechRecognizer.createOnDeviceSpeechRecognizer(context):SpeechRecognizer.createSpeechRecognizer(context);
      final int session=++recognitionGeneration;
      recognizer.setRecognitionListener(new RecognitionListener(){
        public void onResults(Bundle b){if(session==recognitionGeneration&&recognizer!=null)WakeListener.this.onResults(b);}
        public void onError(int error){if(session==recognitionGeneration&&recognizer!=null)WakeListener.this.onError(error);}
        public void onReadyForSpeech(Bundle b){}public void onBeginningOfSpeech(){}public void onRmsChanged(float f){}public void onBufferReceived(byte[] b){}public void onEndOfSpeech(){}public void onPartialResults(Bundle b){}public void onEvent(int t,Bundle b){}
      });
      Intent intent=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE,"en-IN").putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,true).putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,false);
      recognizer.startListening(intent);report("listening",local?"On-device wake listening · Hey MYRAA":"Wake listening · installed speech provider may use internet");
    }catch(RuntimeException e){disable();report("unavailable","The Android speech service cannot listen on this device. Use Talk or type a request.");}
  }
  private void rearm(long delay){int token=generation;handler.postDelayed(()->{if(token==generation)arm();},delay);}
  private void report(String status,String message){host.event(MobileTaskCoordinator.obj("type","wake-state","status",status,"enabled",enabled,"message",message));}
  @Override public void onResults(Bundle results){
    ArrayList<String> alternatives=results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);destroyRecognizer();failures=0;
    if(!enabled||suspended)return;
    if(alternatives!=null&&!alternatives.isEmpty()&&isStopPhrase(alternatives.get(0))){disable();return;}
    if(alternatives!=null&&!alternatives.isEmpty()&&matches(alternatives.get(0))){suspend();host.wake();}else rearm(1500);
  }
  @Override public void onError(int error){
    destroyRecognizer();if(!enabled||suspended)return;
    if(error==SpeechRecognizer.ERROR_NO_MATCH||error==SpeechRecognizer.ERROR_SPEECH_TIMEOUT){rearm(1500);return;}
    if(++failures>=3||error==SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS){disable();report("unavailable","Wake listening stopped after speech-service errors. Check microphone permission or use Talk.");return;}
    report("retrying","Speech service interrupted. Retrying…");rearm(2000L*failures);
  }
  @Override public void onReadyForSpeech(Bundle b){} @Override public void onBeginningOfSpeech(){} @Override public void onRmsChanged(float rms){} @Override public void onBufferReceived(byte[] b){} @Override public void onEndOfSpeech(){} @Override public void onPartialResults(Bundle b){} @Override public void onEvent(int t,Bundle b){}
}
