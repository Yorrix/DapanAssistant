package cn.dapan.assistant.timetable;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.content.FileProvider;
import org.json.JSONObject;
import org.mozilla.geckoview.*;
import java.io.File;
import java.io.FileOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/** Same-signed optional browser. The extension reads only the visible course table. */
public final class ImportActivity extends Activity {
 private static GeckoRuntime engine;
 private static final String HOST="cn.dapan.assistant";
 private static final String ENTRY="http://jxgl.dlut.edu.cn/student/ucas-sso/login";
 private static final String COURSES="http://jxgl.dlut.edu.cn/student/for-std/course-table";
 private static final int MAX_RESULT=600000;
 private GeckoSession session;
 private TextView status;
 private Button read;
 private WebExtension.Port port;
 private final Handler handler=new Handler(Looper.getMainLooper());
 private final Runnable pollRead=this::requestSnapshot;
 private JSONObject grid;
 private boolean pending,back,destroyed;
 private String currentUrl=ENTRY;
 private int serial,attempts,ink;
 private static boolean allowed(String url){
  try {
   URI u=new URI(url);String host=u.getHost()==null?"":u.getHost().toLowerCase(Locale.ROOT),scheme=u.getScheme();
   if(u.getRawUserInfo()!=null||(u.getPort()!=-1&&u.getPort()!=("https".equals(scheme)?443:80)))return false;
   return ("https".equals(scheme)&&(host.equals("sso.dlut.edu.cn")||host.equals("jxgl.dlut.edu.cn")))||("http".equals(scheme)&&host.equals("jxgl.dlut.edu.cn"));
  }catch(Exception ignored){return false;}
 }
 private static boolean course(String url){
  try {URI u=new URI(url);return allowed(url)&&"jxgl.dlut.edu.cn".equals(u.getHost())&&u.getPath().startsWith("/student/for-std/course-table");}
  catch(Exception ignored){return false;}
 }
 private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density);}
 private Button button(String label,Runnable action){
  Button b=new Button(this);b.setText(label);b.setTextSize(13);b.setTextColor(ink);b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(dp(8),0,dp(8),0);b.setOnClickListener(v->action.run());return b;
 }
 private TextView label(String text,int size){TextView t=new TextView(this);t.setText(text);t.setTextSize(size);t.setTextColor(ink);return t;}
 @Override public void onCreate(Bundle saved){
  super.onCreate(saved);
  if(!HOST.equals(getCallingPackage())||getPackageManager().checkSignatures(HOST,getPackageName())!=PackageManager.SIGNATURE_MATCH){finish();return;}
  boolean dark=(getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
  ink=dark?Color.WHITE:Color.rgb(24,29,39);
  LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(dark?Color.rgb(19,23,32):Color.WHITE);
  root.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
  LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);
  bar.addView(button("返回",this::finish),new LinearLayout.LayoutParams(dp(60),dp(46)));
  TextView title=label("课表兼容模式",17);title.setGravity(Gravity.CENTER_VERTICAL);bar.addView(title,new LinearLayout.LayoutParams(0,dp(46),1));
  bar.addView(button("课表",()->{grid=null;stopRead();if(session!=null)session.loadUri(COURSES);}),new LinearLayout.LayoutParams(dp(60),dp(46)));
  read=button("读取",this::startRead);bar.addView(read,new LinearLayout.LayoutParams(dp(60),dp(46)));root.addView(bar);
  status=label("登录学校账号，进入我的课表，选择学期后读取。",12);status.setPadding(dp(14),dp(4),dp(14),dp(6));root.addView(status);
  GeckoView view=new GeckoView(this);root.addView(view,new LinearLayout.LayoutParams(-1,0,1));
  TextView footer=label("独立浏览器 · 登录仅交给学校 · 课表回到大盘助手确认",11);footer.setPadding(dp(14),dp(6),dp(14),dp(6));root.addView(footer);setContentView(root);
  try {
   if(engine==null)engine=GeckoRuntime.create(getApplicationContext(),new GeckoRuntimeSettings.Builder().remoteDebuggingEnabled(false).build());
   session=new GeckoSession(new GeckoSessionSettings.Builder().userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP).build());
   session.setContentDelegate(new GeckoSession.ContentDelegate(){@Override public void onCrash(GeckoSession s){stopRead();status.setText("浏览器进程已停止，请返回后重新打开兼容模式。");}});
   session.setNavigationDelegate(new GeckoSession.NavigationDelegate(){
    @Override public void onCanGoBack(GeckoSession s,boolean value){back=value;}
    @Override public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession s,LoadRequest request){
     if(!allowed(request.uri)){status.setText("已拦截非学校教务或认证网站的跳转。");return GeckoResult.fromValue(AllowOrDeny.DENY);}
     if(request.target==TARGET_WINDOW_NEW){handler.post(()->{if(!destroyed)session.loadUri(request.uri);});return GeckoResult.fromValue(AllowOrDeny.DENY);}
     return GeckoResult.fromValue(AllowOrDeny.ALLOW);
    }
    @Override public GeckoResult<String> onLoadError(GeckoSession s,String uri,WebRequestError error){stopRead();status.setText("学校页面连接失败（"+error.code+"）。检查网络后点“课表”重试。");return null;}
   });
   session.setProgressDelegate(new GeckoSession.ProgressDelegate(){
    @Override public void onPageStart(GeckoSession s,String url){
     currentUrl=url;port=null;
     if(!allowed(url)){session.stop();stopRead();status.setText("已停止非学校网页。");}
     else if(!course(url)){grid=null;stopRead();status.setText("请完成学校登录，再进入我的课表。");}
     else status.setText(pending?"正在读取本学期课程明细…":"选择学期，等待课表显示后点右上角“读取”。");
    }
   });
   session.open(engine);view.setSession(session);
   engine.getWebExtensionController().ensureBuiltIn("resource://android/assets/timetable/","timetable@dapan.local").accept(extension->{
    if(destroyed)return;
    session.getWebExtensionController().setMessageDelegate(extension,new WebExtension.MessageDelegate(){
     @Override public void onConnect(WebExtension.Port next){
      if(next.sender.session!=session||!next.sender.isTopLevel()||!course(next.sender.url)){next.disconnect();return;}
      port=next;
      next.setDelegate(new WebExtension.PortDelegate(){
       @Override public void onPortMessage(Object value,WebExtension.Port p){
        if(destroyed||port!=p||!pending||!course(currentUrl))return;
        if(!(value instanceof JSONObject)||value.toString().length()>MAX_RESULT){stopRead();status.setText("课表内容异常，请重新读取。");return;}
        JSONObject message=(JSONObject)value;if(message.optInt("request")!=serial)return;acceptSnapshot(message);
       }
       @Override public void onDisconnect(WebExtension.Port p){if(port==p)port=null;}
      });
      if(pending)requestSnapshot();
     }
    },"dapan_timetable");
    session.loadUri(ENTRY);
   },error->{if(!destroyed){status.setText("课表读取组件未就绪，请返回后重新打开。");read.setEnabled(false);}});
  }catch(Exception|LinkageError error){status.setText("备用浏览器未能启动，请返回重试。");read.setEnabled(false);}
 }
 private void stopRead(){pending=false;serial++;handler.removeCallbacksAndMessages(null);if(read!=null)read.setEnabled(true);}
 private void startRead(){
  if(!course(currentUrl)){status.setText("请先登录，并点“课表”进入自己的课表。");return;}
  if(!currentUrl.contains("/info/all-courses"))grid=null;
  stopRead();pending=true;attempts=0;read.setEnabled(false);status.setText("正在读取学校课表…");requestSnapshot();
 }
 private void requestSnapshot(){
  handler.removeCallbacks(pollRead);if(!pending||destroyed)return;
  if(++attempts>35){stopRead();status.setText("课表尚未加载完成，等页面显示后再点“读取”。");return;}
  try {if(port!=null)port.postMessage(new JSONObject().put("action","read").put("request",serial));}
  catch(Exception ignored){port=null;}
  handler.postDelayed(pollRead,500);
 }
 private void acceptSnapshot(JSONObject message){
  JSONObject value=message.optJSONObject("snapshot");if(value==null)return;
  if(value.has("error")){String s=value.optString("error");status.setText(s.substring(0,Math.min(s.length(),120)));return;}
  try {
   if(value.optString("kind").equals("grid")){
    if(grid==null){grid=value;status.setText("已读取学期，正在打开全部课程…");if(port!=null)port.postMessage(new JSONObject().put("action","courses"));}
   }else if(value.optString("kind").equals("courses")){
    if(grid==null){stopRead();status.setText("请点“课表”，从周课表开始读取。");return;}
    if(!grid.optString("semester").equals(value.optString("semester"))){grid=null;stopRead();status.setText("学期已改变，请返回课表重新读取。");return;}
    byte[] payload=new JSONObject().put("schema",1).put("grid",grid).put("courses",value).toString().getBytes(StandardCharsets.UTF_8);
    if(payload.length>MAX_RESULT){stopRead();status.setText("课表内容过大，请检查当前学期。");return;}
    File dir=new File(getCacheDir(),"timetable");if(!dir.isDirectory()&&!dir.mkdirs())throw new IllegalStateException();
    File[] old=dir.listFiles();if(old!=null)for(File f:old)if(f.isFile()&&f.getName().endsWith(".json"))f.delete();
    File file=new File(dir,UUID.randomUUID()+".json");try(FileOutputStream output=new FileOutputStream(file)){output.write(payload);}
    android.net.Uri uri=FileProvider.getUriForFile(this,getPackageName()+".results",file);
    Intent result=new Intent().setDataAndType(uri,"application/json").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);result.setClipData(ClipData.newRawUri("课表",uri));
    stopRead();setResult(RESULT_OK,result);finish();
   }
  }catch(Exception error){stopRead();status.setText("无法返回课表，请检查存储空间后重试。");}
 }
 @Override @SuppressWarnings("deprecation") public void onBackPressed(){if(session!=null&&back)session.goBack();else finish();}
 @Override public void onDestroy(){destroyed=true;stopRead();if(port!=null)port.disconnect();port=null;if(session!=null)session.close();super.onDestroy();}
}
