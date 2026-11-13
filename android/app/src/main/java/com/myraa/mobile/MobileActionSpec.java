package com.myraa.mobile;

import org.json.*;
import java.util.*;

/** Shared native validation used for the entire plan before its first action. */
public final class MobileActionSpec {
  public static final String CAPABILITIES = "open_app(app), search_web(query), search_youtube(query), whatsapp_message(recipient,message), navigate(destination), set_alarm(hour:0..23,minute:0..59,label), set_timer(seconds:1..86400,label), calendar_event(title,startAt:epoch milliseconds,endAt:epoch milliseconds,location).";
  public static JSONObject validate(JSONObject raw) throws JSONException {
    JSONObject a=new JSONObject();String name=raw.optString("action");a.put("action",name);
    switch(name){
      case "open_app": text(raw,a,"app",100);break;
      case "search_web":case "search_youtube":text(raw,a,"query",1000);break;
      case "whatsapp_message":text(raw,a,"recipient",100);text(raw,a,"message",4000);break;
      case "navigate":text(raw,a,"destination",1000);break;
      case "set_alarm":number(raw,a,"hour",0,23);number(raw,a,"minute",0,59);optional(raw,a,"label",200);break;
      case "set_timer":number(raw,a,"seconds",1,86400);optional(raw,a,"label",200);break;
      case "calendar_event":
        text(raw,a,"title",200);number(raw,a,"startAt",1,4102444800000L);number(raw,a,"endAt",1,4102444800000L);
        if(a.getLong("endAt")<=a.getLong("startAt"))throw new IllegalArgumentException("The event end must follow its start.");
        optional(raw,a,"location",500);break;
      default:throw new IllegalArgumentException("Unsupported mobile action.");
    }
    return a;
  }
  private static void text(JSONObject src,JSONObject dst,String key,int max)throws JSONException{
    Object value=src.opt(key);if(!(value instanceof String)||((String)value).trim().isEmpty()||((String)value).length()>max)throw new IllegalArgumentException("Provide a valid "+key+".");dst.put(key,value);
  }
  private static void optional(JSONObject src,JSONObject dst,String key,int max)throws JSONException{if(src.has(key))text(src,dst,key,max);}
  private static void number(JSONObject src,JSONObject dst,String key,long min,long max)throws JSONException{
    Object value=src.opt(key);if(!(value instanceof Number))throw new IllegalArgumentException("Provide a numeric "+key+".");
    double n=((Number)value).doubleValue();if(!Double.isFinite(n)||n!=Math.rint(n)||n<min||n>max)throw new IllegalArgumentException("Invalid "+key+".");dst.put(key,(long)n);
  }
}
