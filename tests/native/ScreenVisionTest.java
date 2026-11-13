import com.myraa.mobile.*;
import org.json.*;
import javax.net.ssl.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.util.*;
import java.util.concurrent.*;

/** Real provider request builder/parser with deterministic injected HTTPS responses.
 * These checks do not establish authenticated Google or Android runtime compatibility. */
public final class ScreenVisionTest {
  static int checks;static final String KEY="fixture-key-for-unit-tests-only";
  static final byte[] JPEG={(byte)255,(byte)216,0,1,(byte)255,(byte)217};
  static void check(boolean yes,String label){if(!yes)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
  static String metadata(){return "{supportedGenerationMethods:['generateContent']}";}
  static String envelope(String answer){return new JSONObject().put("candidates",new JSONArray().put(new JSONObject().put("finishReason","STOP").put("content",new JSONObject().put("parts",new JSONArray().put(new JSONObject().put("text",answer)))))).toString();}
  static class Connection extends HttpsURLConnection {
    final String response;final int status;final ByteArrayOutputStream body=new ByteArrayOutputStream();boolean disconnected;final CountDownLatch entered;final CountDownLatch release;
    Connection(URL url,int status,String response){this(url,status,response,null,null);}
    Connection(URL url,int status,String response,CountDownLatch entered,CountDownLatch release){super(url);this.status=status;this.response=response;this.entered=entered;this.release=release;}
    public void connect(){}public void disconnect(){disconnected=true;if(release!=null)release.countDown();}public boolean usingProxy(){return false;}
    public OutputStream getOutputStream(){return body;}public int getResponseCode()throws IOException{if(entered!=null){entered.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}if(disconnected)throw new IOException("cancelled fixture");return status;}
    public InputStream getInputStream(){return new ByteArrayInputStream(response.getBytes(StandardCharsets.UTF_8));}public InputStream getErrorStream(){return getInputStream();}
    public String getCipherSuite(){return "fixture";}public Certificate[] getLocalCertificates(){return null;}public Certificate[] getServerCertificates(){return null;}
  }
  static class Fixture implements ScreenVision.Connections {
    final List<Connection> opened=new ArrayList<>();int postStatus=200;String postBody=envelope("{answer:'This is a settings screen.'}");String modelBody=metadata();CountDownLatch entered,release;
    public HttpsURLConnection open(URL url){Connection c=url.getPath().endsWith(":generateContent")?new Connection(url,postStatus,postBody,entered,release):new Connection(url,200,modelBody);opened.add(c);return c;}
  }
  static JSONObject analyze(ScreenVision v,boolean approved)throws Exception{return v.analyze(KEY,"fixture-model","session-1","request-1","frame-1",JPEG,"explain","What is this screen?","en-IN",new JSONArray(),approved);}
  static void failure(ScreenVision v,boolean approved,String code,String label)throws Exception{try{analyze(v,approved);throw new AssertionError(label);}catch(ScreenVision.Failure e){check(e.code.equals(code),label+" ["+e.code+"]");}}
  public static void main(String[]args)throws Exception{
    Fixture revoked=new Fixture();ScreenVision revokedProvider=new ScreenVision(revoked);
    try{revokedProvider.analyze(KEY,"fixture-model","s","r","f",JPEG,"explain","Explain","en-IN",new JSONArray(),true,()->false);throw new AssertionError("revoked frame accepted");}catch(ScreenVision.Failure e){check(e.code.equals("VISION_CANCELLED")&&revoked.opened.isEmpty(),"revoked external approval before worker start makes zero provider connections");}
    Fixture fixture=new Fixture();ScreenVision provider=new ScreenVision(fixture);
    failure(provider,false,"FRAME_NOT_APPROVED","unapproved screenshot rejected before network");check(fixture.opened.isEmpty(),"no provider call before explicit review approval");
    JSONObject response=analyze(provider,true);check(response.getString("frameId").equals("frame-1")&&response.getString("requestId").equals("request-1")&&response.getString("sessionId").equals("session-1"),"response tied to exact frame and request");
    check(response.getBoolean("visualInputAccepted")&&response.getString("source").equals("gemini_image_request"),"response evidence describes an actual image operation");
    check(fixture.opened.size()==2&&fixture.opened.get(0).getRequestMethod().equals("GET")&&fixture.opened.get(1).getRequestMethod().equals("POST"),"model method checked before visual request");
    Connection post=fixture.opened.get(1);check(!post.getURL().toString().contains(KEY)&&KEY.equals(post.getRequestProperty("x-goog-api-key")),"credential header used without API key in URL");
    JSONObject body=new JSONObject(post.body.toString("UTF-8"));JSONObject inline=body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(1).getJSONObject("inline_data");
    check(inline.getString("mime_type").equals("image/jpeg")&&Arrays.equals(Base64.getDecoder().decode(inline.getString("data")),JPEG),"exact approved image bytes placed in Gemini image part");
    check(fixture.opened.stream().allMatch(c->c.disconnected),"all metadata and image connections disconnected");
    Fixture unsupported=new Fixture();unsupported.modelBody="{supportedGenerationMethods:['bidiGenerateContent']}";failure(new ScreenVision(unsupported),true,"MODEL_VISION_UNSUPPORTED","unsupported request method rejected");check(unsupported.opened.size()==1,"unsupported method never uploads screenshot");
    Fixture modality=new Fixture();modality.postStatus=400;modality.postBody="{error:{message:'This model does not support image input'}}";failure(new ScreenVision(modality),true,"MODEL_VISION_UNSUPPORTED","image modality refusal exposed honestly");
    Fixture quota=new Fixture();quota.postStatus=429;quota.postBody="{}";failure(new ScreenVision(quota),true,"QUOTA_EXCEEDED","quota failure preserved");
    Fixture denied=new Fixture();denied.postStatus=403;denied.postBody="{error:{message:'API_KEY_INVALID secret-fixture-payload'}}";failure(new ScreenVision(denied),true,"API_KEY_INVALID","invalid credentials mapped to safe existing provider error");
    Fixture malformed=new Fixture();malformed.postBody=envelope("not JSON");failure(new ScreenVision(malformed),true,"VISION_MALFORMED","malformed visual output never becomes a screen fact");
    String raw=envelope("{answer:'Tap Save.',highlight:{x:0.1,y:0.2,width:0.3,height:0.1,confidence:0.9,label:'Save'}}");
    check(ScreenVision.parseResponse(raw,"s","r","f").has("highlight"),"valid normalized high confidence highlight accepted");
    raw=envelope("{answer:'Maybe Save.',highlight:{x:0.1,y:0.2,width:0.3,height:0.1,confidence:0.4,label:'Save'}}");check(!ScreenVision.parseResponse(raw,"s","r","f").has("highlight"),"low confidence highlight discarded");
    raw=envelope("{answer:'Save.',highlight:{x:0.9,y:0.2,width:0.3,height:0.1,confidence:0.99,label:'Save'}}");check(!ScreenVision.parseResponse(raw,"s","r","f").has("highlight"),"out of bounds highlight discarded");
    check(!ScreenPrivacy.inspect(null,"Explain this screen",false,false).getBoolean("allowed"),"privacy review mandatory even without local OCR");
    check(!ScreenPrivacy.inspect(null,"Explain this screen",true,true).getBoolean("allowed"),"user-marked sensitive frame rejected");
    check(!ScreenPrivacy.inspect("Your verification code 123456","Explain",true,false).getBoolean("allowed"),"recognized authentication content blocks analysis");
    check(!ScreenPrivacy.inspect("Bank account balance","Explain",true,false).getBoolean("allowed"),"recognized banking content blocks analysis");
    check(ScreenPrivacy.inspect(null,"Explain this screen",true,false).getBoolean("allowed")&&!ScreenPrivacy.ocrStatus().getBoolean("available"),"unknown OCR coverage is explicit instead of invented");
    check(ScreenPrivacy.sensitiveOutput("Your verification code is 123456")&&ScreenPrivacy.sensitiveOutput("Password: hunter42!"),"secret-like authentication values detected in model output");
    check(!ScreenPrivacy.sensitiveOutput("I cannot analyze passwords or authentication codes.")&&!ScreenPrivacy.sensitiveOutput("The visible error code is 404."),"refusals and ordinary error explanations remain usable");
    try{ScreenVision.parseResponse(envelope("{answer:'Your OTP is 123456'}"),"s","r","f");throw new AssertionError("secret answer accepted");}catch(ScreenVision.Failure e){check(e.code.equals("SENSITIVE_RESPONSE"),"potential secret answer discarded before UI delivery");}
    raw=envelope("{answer:'This label is sensitive.',highlight:{x:0.1,y:0.2,width:0.3,height:0.1,confidence:0.99,label:'OTP 123456'}}");check(!ScreenVision.parseResponse(raw,"s","r","f").has("highlight"),"secret-like highlight label discarded");
    check(ScreenFeatures.language("ur-PK").equals("Urdu")&&ScreenFeatures.registry().length()==7,"feature registry and Urdu response language supported");
    Fixture cancelled=new Fixture();cancelled.entered=new CountDownLatch(1);cancelled.release=new CountDownLatch(1);ScreenVision waiting=new ScreenVision(cancelled);
    ExecutorService worker=Executors.newSingleThreadExecutor();Future<String> job=worker.submit(()->{try{analyze(waiting,true);return "unexpected";}catch(ScreenVision.Failure e){return e.code;}});
    check(cancelled.entered.await(3,TimeUnit.SECONDS),"injected visual request actually pending before cancellation");
    waiting.cancel();check(job.get(3,TimeUnit.SECONDS).equals("VISION_CANCELLED"),"Stop disconnects visual request and suppresses late response");worker.shutdownNow();
    System.out.println("Screen vision/privacy fixture checks: "+checks);
  }
}
