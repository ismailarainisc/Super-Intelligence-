package com.myraa.mobile;

import org.json.*;
import java.util.*;

/** Capability registry used by both the native prompt and the Screen Companion workspace. */
public final class ScreenFeatures {
  private static final LinkedHashMap<String,String> FEATURES=new LinkedHashMap<>();
  static {
    FEATURES.put("explain","Explain the visible app context, important controls and information. State uncertainty when the app cannot be identified.");
    FEATURES.put("error","Quote only the actual visible non-sensitive error text, explain likely causes, and propose safe troubleshooting. Never invent error codes.");
    FEATURES.put("guide","Describe one likely next control to tap for the user's goal. Only include a highlight if you can locate it confidently in this exact image. Never tap anything.");
    FEATURES.put("translate","Translate the visible non-sensitive text into the requested response language, preserving names, values and warnings. Do not claim local OCR was used.");
    FEATURES.put("form","Explain the visible fields and required information without filling or submitting anything. Do not repeat passwords, authentication codes or financial secrets.");
    FEATURES.put("tutorial","Give only one tutorial step based on this image and wait for the user's confirmation and a newly approved observation before advancing.");
    FEATURES.put("followup","Answer the user's follow-up using this newly approved image and the bounded prior approved observations. Explain when the referenced option is absent or uncertain.");
  }
  public static boolean supported(String mode){return FEATURES.containsKey(mode);}
  public static String instruction(String mode){if(!supported(mode))throw new IllegalArgumentException("Choose a supported screen feature.");return FEATURES.get(mode);}
  public static JSONArray registry(){
    JSONArray result=new JSONArray();
    try{for(Map.Entry<String,String> feature:FEATURES.entrySet())result.put(new JSONObject().put("id",feature.getKey()).put("description",feature.getValue()).put("requiresReviewedFrame",true));}
    catch(JSONException e){throw new IllegalStateException(e);}return result;
  }
  public static String language(String requested){
    if(requested==null)return "English";
    String value=requested.trim().toLowerCase(Locale.ROOT);
    if(value.equals("urdu")||value.startsWith("ur"))return "Urdu";
    if(value.equals("hindi")||value.startsWith("hi"))return "Hindi";
    if(value.equals("english")||value.startsWith("en"))return "English";
    throw new IllegalArgumentException("Choose English, Hindi or Urdu for screen responses.");
  }
}
