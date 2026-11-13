package com.myraa.mobile;

import org.json.*;
import java.util.*;
import java.util.regex.Pattern;

/** Best-effort local text policy, not a detector capable of proving an image safe. */
public final class ScreenPrivacy {
  private static final Pattern SENSITIVE=Pattern.compile(
    "(?iu)\\b(?:password|passcode|pin\\s*(?:code|number)?|one[ -]?time[ -]?(?:password|code)|otp|verification\\s*code|security\\s*code|authentication\\s*code|recovery\\s*(?:code|phrase)|seed\\s*phrase|cvv|cvc|credit\\s*card|debit\\s*card|bank\\s*(?:account|balance|statement)|account\\s*number|routing\\s*number|iban|sign[ -]?in|log[ -]?in|banking)\\b|(?:पासवर्ड|ओटीपी|पिन कोड|बैंक खाता|پاس ورڈ|تصدیقی کوڈ|بینک اکاؤنٹ)");
  private static final Pattern CARD_NUMBER=Pattern.compile("(?<![0-9])(?:[0-9][ -]?){13,19}(?![0-9])");
  // Only secret-like values in output are blocked; a refusal that mentions passwords
  // or authentication without a value must remain usable. These patterns are not a guarantee.
  private static final Pattern AUTH_VALUE=Pattern.compile("(?iu)(?:\\b(?:otp|one[ -]?time[ -]?(?:password|code)|verification[ -]?code|security[ -]?code|authentication[ -]?code|passcode|pin(?:[ -]?code)?)\\b|ओटीपी|تصدیقی کوڈ)\\s*(?:is|:|=|है|ہے)?\\s*[`\"']?\\d{4,8}\\b");
  private static final Pattern PASSWORD_VALUE=Pattern.compile("(?iu)(?:\\bpassword\\b|पासवर्ड|پاس ورڈ)\\s*[:=]\\s*[`\"']?[\\p{L}\\p{N}!@#$%^&*()_+.,/?=-]{4,}");
  public static boolean sensitiveOutput(String text){return text!=null&&(AUTH_VALUE.matcher(text).find()||PASSWORD_VALUE.matcher(text).find()||CARD_NUMBER.matcher(text).find());}
  public static boolean sensitive(String text){return text!=null&&(SENSITIVE.matcher(text).find()||CARD_NUMBER.matcher(text).find());}
  public static JSONObject inspect(String recognizedText,String question,boolean reviewed,boolean sensitiveDeclared){
    boolean hasOcr=recognizedText!=null;
    String reason="";
    if(!reviewed)reason="Review this exact frame and explicitly approve it before sending anything to Gemini.";
    else if(sensitiveDeclared)reason="This frame was marked sensitive. Analysis is blocked. Open a non-sensitive screen and capture a new frame.";
    else if(sensitive(recognizedText)||sensitive(question))reason="Potential banking, sign-in, password or authentication content was detected. Analysis is blocked; capture a non-sensitive screen.";
    try{return new JSONObject().put("allowed",reason.isEmpty()).put("reason",reason).put("ocrAvailable",hasOcr).put("protection","best_effort").put("reviewRequired",true).put("warning",hasOcr?"Local text checks cannot guarantee that all sensitive content was found.":"On-device OCR is not bundled. MYRAA cannot automatically inspect text in this image; inspect it carefully and do not approve banking, sign-in, password or authentication screens.");}
    catch(JSONException e){throw new IllegalStateException(e);}
  }
  public static JSONObject ocrStatus(){
    try{return new JSONObject().put("available",false).put("status","NOT_IMPLEMENTED").put("reason","This source build does not bundle an on-device OCR library. Frame review is mandatory; no local OCR or script coverage is claimed.").put("blocks",new JSONArray());}
    catch(JSONException e){throw new IllegalStateException(e);}
  }
}
