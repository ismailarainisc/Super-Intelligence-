package com.myraa.mobile.tests;

import android.app.*;
import android.content.*;
import android.os.*;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.view.accessibility.AccessibilityNodeInfo;
import android.webkit.WebView;
import org.json.*;
import java.io.*;
import java.lang.reflect.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Real Android consent, projection and frames. No Google calls or synthetic production results. */
public class ScreenCompanionInstrumentation extends Instrumentation {
  private Activity activity; private WebView web; private int checks;
  private final StringBuilder evidence=new StringBuilder();
  @Override public void onCreate(Bundle args){super.onCreate(args);start();}
  private void require(boolean ok,String label)throws Exception{if(!ok)throw new Exception(label);checks++;evidence.append("PASS: ").append(label).append('\n');}
  private String js(String source)throws Exception{
    CountDownLatch latch=new CountDownLatch(1);String[] value={null};
    runOnMainSync(()->web.evaluateJavascript(source,r->{value[0]=r;latch.countDown();}));
    if(!latch.await(30,TimeUnit.SECONDS))throw new Exception("WebView evaluation timed out");return value[0];
  }
  private void until(java.util.concurrent.Callable<Boolean> condition,String label,int seconds)throws Exception{
    long end=System.currentTimeMillis()+seconds*1000L;
    while(System.currentTimeMillis()<end){if(condition.call())return;Thread.sleep(250);}throw new Exception(label+" timed out");
  }
  private JSONObject state()throws Exception{return new JSONObject(new JSONTokener(js("MyraaNative.getScreenState()")).nextValue().toString());}
  private boolean projection()throws Exception{return state().optBoolean("hasProjection");}
  private AccessibilityNodeInfo find(AccessibilityNodeInfo node,String id,String text){
    if(node==null)return null;
    if((id!=null&&id.equals(node.getViewIdResourceName()))||(text!=null&&node.getText()!=null&&node.getText().toString().equalsIgnoreCase(text)))return node;
    for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo found=find(node.getChild(i),id,text);if(found!=null)return found;}return null;
  }
  private boolean clickSystem(String id,String text){AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();if(root==null||!String.valueOf(root.getPackageName()).equals("com.android.systemui"))return false;AccessibilityNodeInfo node=find(root,id,text);return node!=null&&node.performAction(AccessibilityNodeInfo.ACTION_CLICK);}
  private void consent(boolean approve)throws Exception{
    until(()->{AccessibilityNodeInfo r=getUiAutomation().getRootInActiveWindow();return r!=null&&String.valueOf(r.getPackageName()).equals("com.android.systemui");},"Android projection consent dialog",30);
    if(!approve){until(()->clickSystem("android:id/button2",null)||clickSystem(null,"Cancel"),"Cancel Android screen consent",15);return;}
    // Android 14 may offer app-window capture; choose full screen if available.
    AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();AccessibilityNodeInfo spinner=find(root,"android:id/spinner",null);
    if(spinner!=null){spinner.performAction(AccessibilityNodeInfo.ACTION_CLICK);Thread.sleep(250);AccessibilityNodeInfo entire=find(getUiAutomation().getRootInActiveWindow(),null,"Entire screen");if(entire!=null)entire.performAction(AccessibilityNodeInfo.ACTION_CLICK);}
    until(()->clickSystem("android:id/button1",null)||clickSystem(null,"Start now")||clickSystem(null,"Start"),"Approve Android screen consent",15);
  }
  private byte[] settingsDigest()throws Exception{
    File source=new File(getTargetContext().getApplicationInfo().dataDir,"shared_prefs/myraa.mobile.v1.xml");if(!source.exists())return new byte[0];
    MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(source)){byte[] bytes=new byte[4096];int n;while((n=in.read(bytes))!=-1)digest.update(bytes,0,n);}return digest.digest();
  }
  private void startFromUi()throws Exception{
    js("document.querySelector('#screen-companion-entry').click();true");
    js("document.querySelector('#screen-start').click();true");
  }
  @Override public void onStart(){Bundle result=new Bundle();try{
    activity=startActivitySync(new Intent().setClassName("com.myraa.mobile","com.myraa.mobile.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    Field view=activity.getClass().getDeclaredField("web");view.setAccessible(true);web=(WebView)view.get(activity);
    require(web!=null,"real application WebView started");
    until(()->"true".equals(js("!!document.querySelector('#screen-companion-entry')&&!!MyraaNative.getScreenState")),"Screen Companion UI",90);
    js("window.__screenTestEvents=[];window.__screenOriginal=window.MyraaEvents;window.MyraaEvents=e=>{if(e.type?.startsWith('screen-'))window.__screenTestEvents.push(e);window.__screenOriginal(e)};true");
    byte[] before=settingsDigest();require(!projection(),"no screen access before user approval");
    startFromUi();consent(false);
    until(()->"PERMISSION_DENIED".equals(state().optString("state")),"permission denied state",20);
    require(!projection(),"permission rejection creates no projection");
    js("document.querySelector('#screen-start').click();true");consent(true);
    until(()->projection(),"actual MediaProjection ownership",30);
    require(projection(),"user-approved real projection session exists");
    Class<?> capture=activity.getClassLoader().loadClass("com.myraa.mobile.ScreenCaptureService");
    Field instance=capture.getDeclaredField("instance");instance.setAccessible(true);
    Field ownedProjection=capture.getDeclaredField("projection");ownedProjection.setAccessible(true);
    Field virtualDisplay=capture.getDeclaredField("display");virtualDisplay.setAccessible(true);
    Field imageReader=capture.getDeclaredField("reader");imageReader.setAccessible(true);
    Object running=instance.get(null);
    require(running!=null&&ownedProjection.get(running)!=null&&virtualDisplay.get(running)!=null&&imageReader.get(running)!=null,"CAPTURING is backed by real MediaProjection, VirtualDisplay and ImageReader resources");
    until(()->state().optLong("frameSequence",0)>0||state().optLong("sequence",0)>0,"real ImageReader frame",45);
    require(true,"real MediaProjection frame acquired");
    js("MyraaNative.screenReview();true");
    until(()->"true".equals(js("window.__screenTestEvents.some(e=>e.type==='screen-review')")),"captured frame review",20);
    JSONObject review=new JSONObject(js("window.__screenTestEvents.filter(e=>e.type==='screen-review').at(-1)"));
    String jpeg=review.optString("jpeg",review.optString("preview"));int comma=jpeg.indexOf(',');if(comma>=0)jpeg=jpeg.substring(comma+1);
    byte[] pixels=Base64.decode(jpeg,Base64.DEFAULT);android.graphics.Bitmap image=BitmapFactory.decodeByteArray(pixels,0,pixels.length);
    require(image!=null&&image.getWidth()>0&&image.getHeight()>0,"actual captured JPEG decodes with positive dimensions");if(image!=null)image.recycle();
    require(review.has("frameId"),"review references a specific captured frame");
    require("false".equals(js("window.__screenTestEvents.some(e=>e.type==='screen-answer')")),"reviewing a frame never uploads it or invents an answer");
    require(getUiAutomation().setRotation(android.app.UiAutomation.ROTATION_FREEZE_90),"Android accepted rotation request");
    until(()->state().optInt("sourceWidth")>state().optInt("sourceHeight"),"captured content resized after rotation",20);
    require(projection(),"projection survives actual dimension-changing rotation using existing session");getUiAutomation().setRotation(android.app.UiAutomation.ROTATION_UNFREEZE);
    js("MyraaNative.screenStop();true");until(()->!projection()&&"STOPPED".equals(state().optString("state")),"projection Stop cleanup",20);
    require(!projection(),"Stop releases actual projection resources");
    require(ownedProjection.get(running)==null&&virtualDisplay.get(running)==null&&imageReader.get(running)==null,"Stop nulls released projection, display and reader resources");
    require("STOPPED".equals(state().optString("state")),"STOPPED reflects released capture resources");
    js("document.querySelector('#screen-start').click();true");consent(true);
    until(()->projection(),"second independently approved projection",30);
    runOnMainSync(()->{try{Object service=instance.get(null);((android.media.projection.MediaProjection)ownedProjection.get(service)).stop();}catch(Exception e){throw new RuntimeException(e);}});
    until(()->!projection()&&"STOPPED".equals(state().optString("state")),"actual MediaProjection.onStop cleanup",20);
    require(!projection(),"MediaProjection.stop triggers actual system callback and releases resources");
    require(Arrays.equals(before,settingsDigest()),"screen workflow preserves existing settings, credential ciphertext and history bytes");
    evidence.append("Checks: ").append(checks).append(". Real Android consent/capture only; no Gemini credential or network inference used.\n");
    result.putString("stream",evidence.toString());finish(Activity.RESULT_OK,result);
  }catch(Exception e){result.putString("stream",evidence+"FAIL: "+e.toString()+"\n");finish(Activity.RESULT_CANCELED,result);}
  finally{try{if(web!=null)js("MyraaNative.screenStop();true");}catch(Exception ignored){}if(activity!=null)runOnMainSync(()->activity.finish());}}
}
