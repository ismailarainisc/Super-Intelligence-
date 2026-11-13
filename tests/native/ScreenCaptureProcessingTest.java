import com.myraa.mobile.FrameProcessor;
import com.myraa.mobile.ScreenSessionPolicy;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Deterministic production pixel/sampling-policy checks. Not a MediaProjection runtime test. */
public final class ScreenCaptureProcessingTest {
  private static int checks;
  private static void check(boolean ok,String name){if(!ok)throw new AssertionError(name);checks++;System.out.println("PASS "+name);}
  private static void rejects(Runnable run,String name){try{run.run();throw new AssertionError(name);}catch(IllegalArgumentException expected){checks++;System.out.println("PASS "+name);}}
  public static void main(String[] args){
    check(Arrays.equals(FrameProcessor.boundedSize(1080,2400,1280),new int[]{576,1280}),"portrait dimensions preserve aspect ratio");
    check(Arrays.equals(FrameProcessor.boundedSize(2400,1080,1280),new int[]{1280,576}),"landscape rotation preserves aspect ratio");
    check(Arrays.equals(FrameProcessor.boundedSize(800,600,1280),new int[]{800,600}),"small app windows are not enlarged");
    check(Arrays.equals(FrameProcessor.boundedSize(4000,2000,999999),new int[]{1600,800}),"capture allocation maximum is enforced");
    rejects(()->FrameProcessor.boundedSize(0,2400,1280),"invalid display dimensions rejected");
    // Two RGB pixels per row, 4 padding bytes on row one; last row has no trailing padding.
    byte[] raw={(byte)255,0,0,(byte)255,0,(byte)255,0,(byte)255,9,9,9,9,0,0,(byte)255,(byte)255,(byte)255,(byte)255,(byte)255,(byte)255};
    int[] pixels=FrameProcessor.rgbaToArgb(ByteBuffer.wrap(raw),2,2,12,4);
    check(Arrays.equals(pixels,new int[]{0xffff0000,0xff00ff00,0xff0000ff,0xffffffff}),"padded RGBA and unpadded final row convert correctly");
    ByteBuffer offset=ByteBuffer.allocate(raw.length+5);offset.position(5);offset.put(raw);offset.position(5);
    check(Arrays.equals(FrameProcessor.rgbaToArgb(offset,2,2,12,4),pixels),"nonzero buffer origin is respected");
    rejects(()->FrameProcessor.rgbaToArgb(ByteBuffer.wrap(raw),2,2,12,3),"unexpected pixel layout rejected");
    rejects(()->FrameProcessor.rgbaToArgb(ByteBuffer.wrap(new byte[10]),2,2,12,4),"truncated screen buffers rejected");
    rejects(()->FrameProcessor.rgbaToArgb(ByteBuffer.allocate(4),100000,100000,400000,4),"oversized capture allocation rejected");
    check(FrameProcessor.blankOrProtected(new int[]{0xff000000,0xff010203}),"blank or protected black frames are rejected");
    check(!FrameProcessor.blankOrProtected(pixels),"visible screen colors remain usable");
    check(FrameProcessor.fingerprint(pixels,2,2).equals(FrameProcessor.fingerprint(pixels.clone(),2,2)),"unchanged frames deduplicate deterministically");
    int[] changed=pixels.clone();changed[0]=0xff00ff00;
    check(!FrameProcessor.fingerprint(pixels,2,2).equals(FrameProcessor.fingerprint(changed,2,2)),"a changed visible screen has a new fingerprint");
    ScreenSessionPolicy sessions=new ScreenSessionPolicy();
    check(sessions.reserve("fresh",false),"fresh consent request can reserve one service start");
    check(!sessions.reserve("other",false),"concurrent capture starts rejected");
    check(sessions.consume("fresh")&&!sessions.consume("fresh"),"authorization request is consumed once");
    check(!sessions.reserve("fresh",false),"duplicate request nonce cannot reuse consent");
    check(!sessions.reserve("active",true),"existing projection prevents competing capture");
    check(sessions.reserve("late",false),"new consent can create a new request");sessions.cancel();
    check(!sessions.consume("late"),"late service start after Stop cannot consume authorization");
    System.out.println("Screen pixel/policy checks: "+checks);
  }
}
