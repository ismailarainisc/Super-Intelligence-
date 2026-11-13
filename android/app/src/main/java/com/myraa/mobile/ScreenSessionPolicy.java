package com.myraa.mobile;

import java.util.LinkedHashSet;

/** Single-use app request ownership. Android still independently validates each projection grant. */
public final class ScreenSessionPolicy {
  private String pending="";
  private final LinkedHashSet<String> consumed=new LinkedHashSet<>();
  public synchronized boolean reserve(String id,boolean active){
    if(id==null||!id.matches("[A-Za-z0-9-]{1,80}"))throw new IllegalArgumentException("Invalid screen session identifier.");
    if(active||!pending.isEmpty()||consumed.contains(id))return false;
    pending=id;consumed.add(id);while(consumed.size()>64)consumed.remove(consumed.iterator().next());return true;
  }
  public synchronized boolean consume(String id){if(id==null||!id.equals(pending))return false;pending="";return true;}
  public synchronized void cancel(){pending="";}
  public synchronized String pending(){return pending;}
}
