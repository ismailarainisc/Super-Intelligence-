package com.myraa.mobile;

/** In-memory approval/request identity. Never restores permission or approvals from storage. */
public final class ScreenRequestGate {
  private String session="",frame="",request="";private long generation;private boolean active;
  public synchronized void start(String id){generation++;session=id;frame="";request="";active=true;}
  public synchronized void review(String sessionId,String frameId){if(!active||!session.equals(sessionId))throw new IllegalStateException("Screen session ended.");generation++;frame=frameId;request="";}
  public synchronized long begin(String frameId,String requestId){if(!active||!frame.equals(frameId)||!request.isEmpty())throw new IllegalStateException("Review and approve a fresh frame first.");request=requestId;return ++generation;}
  public synchronized boolean valid(long token,String sessionId,String frameId,String requestId){return active&&token==generation&&session.equals(sessionId)&&frame.equals(frameId)&&request.equals(requestId);}
  public synchronized void finish(){generation++;frame="";request="";}
  public synchronized void invalidate(){finish();}
  public synchronized void stop(){finish();active=false;session="";}
  public synchronized boolean active(){return active;}
}
