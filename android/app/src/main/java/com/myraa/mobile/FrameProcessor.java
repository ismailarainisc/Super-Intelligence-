package com.myraa.mobile;

import android.graphics.Bitmap;
import android.media.Image;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Arrays;

/** Bounded native RGBA -> JPEG conversion. No screenshot files or external storage. */
public final class FrameProcessor {
  public static final int DEFAULT_MAX_EDGE=1280;
  public static final int MAX_JPEG_BYTES=700000;
  public static final long MAX_FRAME_AGE_MS=15000;
  private FrameProcessor() {}

  public static final class Frame {
    public final String sessionId,fingerprint;
    public final long sequence,capturedAt;
    public final int width,height,sourceWidth,sourceHeight,rotation;
    public final boolean blankOrProtected;
    public final byte[] jpeg;
    Frame(String sessionId,long sequence,long capturedAt,int width,int height,int sourceWidth,int sourceHeight,int rotation,byte[] jpeg,String fingerprint,boolean blank){
      this.sessionId=sessionId;this.sequence=sequence;this.capturedAt=capturedAt;this.width=width;this.height=height;this.sourceWidth=sourceWidth;this.sourceHeight=sourceHeight;this.rotation=rotation;this.jpeg=jpeg;this.fingerprint=fingerprint;this.blankOrProtected=blank;
    }
    public Frame copy(){return new Frame(sessionId,sequence,capturedAt,width,height,sourceWidth,sourceHeight,rotation,jpeg.clone(),fingerprint,blankOrProtected);}
    public void clear(){Arrays.fill(jpeg,(byte)0);}
    public boolean fresh(long now){return now>=capturedAt&&now-capturedAt<=MAX_FRAME_AGE_MS;}
  }

  public static int[] boundedSize(int width,int height,int maxEdge){
    if(width<1||height<1||width>32768||height>32768)throw new IllegalArgumentException("Invalid capture size.");
    int bounded=Math.max(320,Math.min(1600,maxEdge));
    double scale=Math.min(1d,bounded/(double)Math.max(width,height));
    return new int[]{Math.max(1,(int)Math.round(width*scale)),Math.max(1,(int)Math.round(height*scale))};
  }

  /** Handles both row padding and a last row that does not contain trailing padding. */
  public static int[] rgbaToArgb(ByteBuffer bytes,int width,int height,int rowStride,int pixelStride){
    if(width<1||height<1||width>1600||height>1600||pixelStride!=4||rowStride<width*pixelStride)throw new IllegalArgumentException("Unsupported capture buffer.");
    long required=(long)(height-1)*rowStride+(long)width*pixelStride;
    if(required>bytes.remaining())throw new IllegalArgumentException("Incomplete capture buffer.");
    ByteBuffer input=bytes.duplicate();int origin=input.position();
    int[] argb=new int[width*height];
    for(int y=0;y<height;y++){
      int row=origin+y*rowStride;
      for(int x=0;x<width;x++){
        int offset=row+x*pixelStride;
        int r=input.get(offset)&255,g=input.get(offset+1)&255,b=input.get(offset+2)&255;
        argb[y*width+x]=0xff000000|(r<<16)|(g<<8)|b;
      }
    }
    return argb;
  }

  public static boolean blankOrProtected(int[] pixels){
    if(pixels.length==0)return true;
    int count=0,max=0;long sum=0;
    int stride=Math.max(1,pixels.length/256);
    for(int i=0;i<pixels.length;i+=stride){int p=pixels[i];int value=Math.max((p>>>16)&255,Math.max((p>>>8)&255,p&255));max=Math.max(max,value);sum+=value;count++;}
    return max<12&&sum/(double)count<5;
  }

  /** A compact luminance fingerprint supports useful deduplication without storing raw pixels. */
  public static String fingerprint(int[] pixels,int width,int height){
    byte[] sample=new byte[16*16];
    for(int y=0;y<16;y++)for(int x=0;x<16;x++){
      int p=pixels[Math.min(height-1,y*height/16)*width+Math.min(width-1,x*width/16)];
      int luminance=(54*((p>>>16)&255)+183*((p>>>8)&255)+19*(p&255))>>>8;
      sample[y*16+x]=(byte)(luminance/8);
    }
    try{
      byte[] digest=MessageDigest.getInstance("SHA-256").digest(sample);
      StringBuilder out=new StringBuilder();for(byte b:digest)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();
    }catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
  }

  public static Frame convert(Image image,String session,long sequence,long now,int sourceWidth,int sourceHeight,int rotation,int quality){
    Image.Plane[] planes=image.getPlanes();if(planes.length<1)throw new IllegalArgumentException("Screen image has no pixel plane.");
    int width=image.getWidth(),height=image.getHeight();
    int[] pixels=rgbaToArgb(planes[0].getBuffer(),width,height,planes[0].getRowStride(),planes[0].getPixelStride());
    String hash=fingerprint(pixels,width,height);
    if(blankOrProtected(pixels)){Arrays.fill(pixels,0);return new Frame(session,sequence,now,width,height,sourceWidth,sourceHeight,rotation,new byte[0],hash,true);}
    Bitmap bitmap=null;
    try{
      bitmap=Bitmap.createBitmap(pixels,width,height,Bitmap.Config.ARGB_8888);
      ByteArrayOutputStream output=new ByteArrayOutputStream();
      if(!bitmap.compress(Bitmap.CompressFormat.JPEG,Math.max(35,Math.min(85,quality)),output))throw new IllegalStateException("Screen image encoding failed.");
      if(output.size()>MAX_JPEG_BYTES)throw new IllegalArgumentException("Screen image exceeds the upload-size limit. Reduce capture quality.");
      return new Frame(session,sequence,now,width,height,sourceWidth,sourceHeight,rotation,output.toByteArray(),hash,false);
    }finally{Arrays.fill(pixels,0);if(bitmap!=null)bitmap.recycle();}
  }
}
