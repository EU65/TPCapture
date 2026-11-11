package local.appcapture.viewer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.Intent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.net.Uri;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.ArrayAdapter;
import android.widget.AbsListView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.Spinner;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Switch;
import android.widget.Toast;

import org.brotli.dec.BrotliInputStream;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

public class MainActivity extends Activity {
    private static final int BG=Color.rgb(247,247,249), INK=Color.rgb(30,32,38), MUTED=Color.rgb(120,125,138), BLUE=Color.rgb(52,130,255), GREEN=Color.rgb(20,165,105);
    private final ExecutorService workers=Executors.newCachedThreadPool();
    private final ScheduledExecutorService timer=Executors.newScheduledThreadPool(1);
    private final Handler main=new Handler(Looper.getMainLooper());
    private final AtomicBoolean switching=new AtomicBoolean(false);
    private SharedPreferences prefs;
    private LinearLayout root, content, nav;
    private TextView subtitle;
    private final LinkedHashMap<String,Target> targets=new LinkedHashMap<>();
    private final LinkedHashMap<Integer,String> activeTargets=new LinkedHashMap<>();
    private final LinkedHashMap<Integer,String> activeCertModes=new LinkedHashMap<>();
    private boolean processKnown=false,captureRunning=false,activeTargetsKnown=false,backendCompatible=false;
    private Boolean activeUdp=null;
    private TextView pendingStatus;
    private Button startButton,stopButton,reloadButton;
    private final HashMap<String,Drawable> icons=new HashMap<>();
    private String tab="";
    private TextView installStatus, processStatus, ruleStatus, versionStatus;
    private int bundledVersion=-1;
    private static final int EXPORT_CERT=923;
    private String pendingCertPem="";
    private int lastPromptedVersion=-1;
    private String installLine="正在获取…", processLine="正在获取…", ruleLine="正在获取…", versionLine="正在获取…", summaryLine="正在获取后端状态…";
    private int statusGeneration=0;
    private int requestGeneration=0;
    private boolean requestsLive=true,uiResumed=false;
    private Runnable liveTask;
    private String filterUid="",filterUrl="",filterText="",filterTransport="",filterMethod="",filterExcludedHosts="";
    private boolean filterMedia=false;
    private String selectedDatabase="current";
    private static final String AUTOSTART_ROOT="/data/adb/modules";
    private static final String AUTOSTART_ID="tpcapture_autostart";
    private TextView autostartStatus,mcpStatus;
    private boolean mcpRunning=false;
    private int mcpRunningPort=18765,mcpStatusGeneration=0;
    private int autostartGeneration=0;
    private boolean allRequests=true;
    private final ArrayList<App> apps=new ArrayList<>(), shownApps=new ArrayList<>();
    private final ArrayList<Entry> entries=new ArrayList<>();

    private static class App { String name,pkg; int uid; boolean system; App(String n,String p,int u,boolean s){name=n;pkg=p;uid=u;system=s;} }
    private static class Target {String key,label;int uid;Target(String k,String l,int u){key=k;label=l;uid=u;}}
    private static class Entry {String id,method,url,status,error,source,transport,mediaType,host,path;int uid;long time;Entry(JSONObject o){id=o.optString("id");method=o.optString("method");host=o.optString("host");path=o.optString("path");url=o.optString("scheme")+"://"+host+path;status=o.optString("response_status");error=o.optString("error");source=o.optString("package");transport=o.optString("transport","tcp");mediaType=o.optString("media_type","");uid=o.optInt("uid",-1);time=o.optLong("timestamp_ms");}}
    private interface Callback { void complete(String out,Exception error); }
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    private GradientDrawable bg(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private TextView text(String s,int size,int color){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);return v;}
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private LinearLayout.LayoutParams weight(){return new LinearLayout.LayoutParams(0,-2,1);}
    private Button button(String title){Button b=new Button(this);b.setText(title);b.setTextSize(14);b.setAllCaps(false);b.setTextColor(BLUE);b.setBackground(bg(Color.rgb(235,241,251),14));b.setMinHeight(dp(42));b.setStateListAnimator(null);return b;}
    private LinearLayout card(){LinearLayout c=column();c.setPadding(dp(18),dp(14),dp(18),dp(14));c.setBackground(bg(Color.WHITE,22));return c;}
    private void addStatusCard(LinearLayout parent){
        LinearLayout c=card();LinearLayout head=row();TextView title=text("后端状态",16,INK);title.setTypeface(null,Typeface.BOLD);head.addView(title,new LinearLayout.LayoutParams(0,-2,1));TextView refresh=text("刷新",14,BLUE);refresh.setPadding(dp(12),dp(4),0,dp(4));refresh.setOnClickListener(v->refreshState());head.addView(refresh);c.addView(head);space(c,8);
        installStatus=text(installLine,14,INK);processStatus=text(processLine,14,INK);ruleStatus=text(ruleLine,13,MUTED);versionStatus=text(versionLine,13,MUTED);c.addView(installStatus);space(c,4);c.addView(processStatus);space(c,4);c.addView(ruleStatus);space(c,4);c.addView(versionStatus);parent.addView(c);space(parent,10);
    }
    private void paintState(){if(subtitle!=null)subtitle.setText(summaryLine);if(installStatus!=null){installStatus.setText(installLine);installStatus.setTextColor(installLine.contains("需要更新")||installLine.contains("高于管理器")?Color.rgb(220,130,35):(installLine.contains("已安装")?GREEN:MUTED));}if(processStatus!=null){processStatus.setText(processLine);processStatus.setTextColor(processLine.contains("运行中")?GREEN:MUTED);}if(ruleStatus!=null)ruleStatus.setText(ruleLine);if(versionStatus!=null)versionStatus.setText(versionLine);}
    private int readVersionAsset(String name){try(InputStream in=getAssets().open(name)){byte[] b=bounded(in,64);return Integer.parseInt(new String(b,StandardCharsets.UTF_8).trim());}catch(Exception e){return -1;}}
    private int readBundledVersion(){return readVersionAsset("backend.version");}
    private long managerVersion(){try{return getPackageManager().getPackageInfo(getPackageName(),0).versionCode;}catch(Exception e){return -1;}}
    private void refreshState(){
        final int generation=++statusGeneration;processKnown=false;activeTargetsKnown=false;backendCompatible=false;paintTargetState();installLine="安装状态：获取中…";processLine="进程状态：获取中…";ruleLine="规则链：获取中…";versionLine="版本：获取中…";summaryLine="正在获取后端状态…";paintState();
        String dir=installDir(),bin=dir+"/appcapture",script=dir+"/appcapture.sh";
        String getVersion="v=$("+dataPrefix()+"sh "+q(script)+" version 2>/dev/null) || v=0; case \"$v\" in ''|*[!0-9]*) v=0;; esac; ";
        String cmd="if [ -x "+q(bin)+" ] && [ -f "+q(script)+" ]; then echo TPC_INSTALLED; "+getVersion+"echo TPC_VERSION=$v; "+dataPrefix()+"sh "+q(script)+" status; elif [ -e "+q(bin)+" ] || [ -e "+q(script)+" ]; then echo TPC_PARTIAL; else echo TPC_MISSING; fi";
        runRoot(cmd,null,(out,err)->{if(generation!=statusGeneration)return;if(err!=null){installLine="安装状态：未知";processLine="进程状态：未知";ruleLine="无法获取状态，请确认 root 授权";versionLine="管理器 "+managerVersion()+" · 内置后端 "+bundledVersion;summaryLine="状态未知 · root 不可用";}else if(out.startsWith("TPC_MISSING")){installLine="安装状态：未安装";processLine="进程状态：未启动";ruleLine="规则链：未检查";versionLine="管理器 "+managerVersion()+" · 内置后端 "+bundledVersion;summaryLine="未安装";}else if(out.startsWith("TPC_PARTIAL")){installLine="安装状态：不完整";processLine="进程状态：未知";ruleLine="请重新安装后端";versionLine="管理器 "+managerVersion()+" · 内置后端 "+bundledVersion;summaryLine="安装不完整";}else if(out.startsWith("TPC_INSTALLED")){
            String[] lines=out.split("\\r?\\n",3);int installed=0;if(lines.length>1&&lines[1].startsWith("TPC_VERSION="))try{installed=Integer.parseInt(lines[1].substring("TPC_VERSION=".length()).trim());}catch(Exception ignored){}String rest=lines.length>2?lines[2].trim():"";
            installLine="安装状态：已安装";versionLine="管理器 "+managerVersion()+" · 内置后端 "+bundledVersion+" · 已安装 "+(installed==0?"旧版/未知":String.valueOf(installed));
            if(rest.startsWith("running count=")){String first=rest.split("\\r?\\n",2)[0];processLine="进程状态：运行中（"+first.replace("running count=","")+" 个 UID）";summaryLine="已安装 · 运行中";}else if(rest.startsWith("running pid=")){processLine="进程状态：运行中（旧版后端）";summaryLine="已安装 · 运行中";}else if(rest.startsWith("proxy not running")){processLine="进程状态：未启动";summaryLine="已安装 · 未启动";}else{processLine="进程状态：未知";summaryLine="已安装 · 进程状态未知";}
            if(rest.contains("Chain TPC_SINGLE_OUT")||rest.contains("Chain TPCAPTURE_V2")||rest.contains("Chain APPCAPTURE_V1"))ruleLine="规则链：存在";else if(rest.contains("iptables not found"))ruleLine="规则链：iptables 不可用";else ruleLine="规则链：不存在或无法读取";
            if(bundledVersion<0){summaryLine="内置后端版本不可用";}else if(installed<bundledVersion){installLine="安装状态：已安装（需要更新）";summaryLine="后端需要更新";if(lastPromptedVersion!=installed&&!switching.get()){lastPromptedVersion=installed;promptUpdate(installed);}}else if(installed>bundledVersion){installLine="安装状态：后端版本高于管理器";summaryLine="请更新管理器";}
        }else{installLine="安装状态：未知";processLine="进程状态：未知";ruleLine="无法解析后端状态";versionLine="管理器 "+managerVersion()+" · 内置后端 "+bundledVersion;summaryLine="状态未知";}readActiveTargets(out,err);paintState();paintTargetState();});
    }
    private void readActiveTargets(String out,Exception err){
        processKnown=false;captureRunning=false;activeTargetsKnown=false;backendCompatible=false;activeUdp=null;activeTargets.clear();activeCertModes.clear();
        if(err!=null||out==null)return;
        if(out.startsWith("TPC_MISSING")){processKnown=true;activeTargetsKnown=true;return;}
        if(!out.startsWith("TPC_INSTALLED"))return;
        String[] lines=out.split("\\r?\\n");int installed=0;if(lines.length>1&&lines[1].startsWith("TPC_VERSION="))try{installed=Integer.parseInt(lines[1].substring("TPC_VERSION=".length()).trim());}catch(Exception ignored){}
        backendCompatible=bundledVersion>=0&&installed==bundledVersion;if(lines.length<3)return;
        if(lines[2].startsWith("proxy not running")){processKnown=true;activeTargetsKnown=true;return;}
        if(lines[2].startsWith("running pid=")){processKnown=true;captureRunning=true;return;}
        if(!lines[2].startsWith("running count="))return;
        int count;try{count=Integer.parseInt(lines[2].substring("running count=".length()).trim());}catch(Exception e){return;}
        processKnown=true;captureRunning=count>0;
        for(int i=3;i<lines.length;i++){String line=lines[i];if(line.startsWith("UDP_ENABLED=")){String value=line.substring("UDP_ENABLED=".length()).trim();if(value.equals("0")||value.equals("1"))activeUdp=value.equals("1");continue;}if(line.startsWith("UID=")){String[] parts=line.split(" ",3);if(parts.length!=3||!parts[1].startsWith("PID=")||!parts[2].startsWith("target="))continue;try{int uid=Integer.parseInt(parts[0].substring(4));activeTargets.put(uid,parts[2].substring("target=".length()));}catch(Exception ignored){}}else if(line.startsWith("CERT_UID=")){String[] parts=line.split(" ",2);if(parts.length==2&&parts[1].startsWith("MODE="))try{activeCertModes.put(Integer.parseInt(parts[0].substring("CERT_UID=".length())),parts[1].substring(5));}catch(Exception ignored){}}}
        activeTargetsKnown=activeTargets.size()==count;
    }
    private void paintTargetState(){
        if(startButton!=null)startButton.setEnabled(processKnown&&backendCompatible&&!captureRunning&&!switching.get());
        if(stopButton!=null)stopButton.setEnabled(processKnown&&captureRunning&&!switching.get());
        if(reloadButton!=null)reloadButton.setEnabled(processKnown&&backendCompatible&&captureRunning&&!switching.get());
        if(pendingStatus==null)return;
        if(!processKnown){pendingStatus.setText("目标状态：等待后端状态");pendingStatus.setTextColor(MUTED);return;}
        if(!captureRunning){pendingStatus.setText("已选目标将在启动时生效");pendingStatus.setTextColor(MUTED);return;}
        if(!activeTargetsKnown){pendingStatus.setText("目标状态未知，请更新或重载后端");pendingStatus.setTextColor(Color.rgb(205,140,30));return;}
        LinkedHashMap<Integer,String> desired=new LinkedHashMap<>();boolean unknown=false;for(Target t:targets.values()){if(t.uid<0){unknown=true;continue;}if(!desired.containsKey(t.uid))desired.put(t.uid,t.key);}
        if(unknown||!desired.equals(activeTargets)){pendingStatus.setText("● 目标已修改，未应用");pendingStatus.setTextColor(Color.rgb(205,140,30));}
        else if(activeCertModes.size()!=activeTargets.size()){pendingStatus.setText("● 证书模式未知，请更新或重载后端");pendingStatus.setTextColor(Color.rgb(205,140,30));}
        else{boolean certMatch=true;for(String mode:activeCertModes.values())if(!certMode().equals(mode))certMatch=false;if(!certMatch){pendingStatus.setText("● 证书模式已修改，未应用");pendingStatus.setTextColor(Color.rgb(205,140,30));}else if(activeUdp!=null&&activeUdp.booleanValue()!=prefs.getBoolean("tproxy_udp",false)){pendingStatus.setText("● UDP 设置已修改，未应用");pendingStatus.setTextColor(Color.rgb(205,140,30));}else{pendingStatus.setText("● 目标和配置已应用");pendingStatus.setTextColor(GREEN);}}
    }
    private void promptUpdate(int installed){new AlertDialog.Builder(this).setTitle("需要更新后端").setMessage("已安装后端版本 "+(installed==0?"旧版/未知":installed)+"，管理器内置版本 "+bundledVersion+"。更新会停止旧后端，安装新版，并尝试恢复之前正在记录的 UID，不会重启目标应用。更新前不能使用新版抓包和请求接口。").setNegativeButton("稍后",null).setPositiveButton("更新并重启",(d,w)->{if(!switching.compareAndSet(false,true))return;summaryLine="正在更新后端…";paintState();installBackend((out,err)->{switching.set(false);if(err!=null)new AlertDialog.Builder(this).setTitle("更新未完成").setMessage(err.getMessage()).setPositiveButton("关闭",null).show();else toast("后端已更新");refreshState();});}).show();}
    private void space(LinearLayout v,int h){View x=new View(this);v.addView(x,new LinearLayout.LayoutParams(1,dp(h)));}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    private String versionLabel(){try{String v=getPackageManager().getPackageInfo(getPackageName(),0).versionName;return v==null?"":v;}catch(PackageManager.NameNotFoundException e){return "";}}

    @Override public void onCreate(Bundle b){super.onCreate(b);if(b!=null)pendingCertPem=b.getString("pending_cert_pem","");prefs=getSharedPreferences("settings",MODE_PRIVATE);bundledVersion=readBundledVersion();requestsLive=prefs.getBoolean("requests_live",true);filterUid=prefs.getString("filter_uid","");filterUrl=prefs.getString("filter_url","");filterText=prefs.getString("filter_text","");filterTransport=prefs.getString("filter_transport","");filterMethod=prefs.getString("filter_method","");filterExcludedHosts=prefs.getString("filter_exclude_hosts","");filterMedia=prefs.getBoolean("filter_media",false);selectedDatabase=prefs.getString("view_database","current");loadTargets();
        root=column();root.setBackgroundColor(BG);root.setPadding(dp(18),0,dp(18),0);
        root.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(dp(18),insets.getSystemWindowInsetTop()+dp(8),dp(18),insets.getSystemWindowInsetBottom()+dp(8));return insets;});
        TextView title=text("TPCapture",28,INK);title.setTypeface(null,Typeface.BOLD);root.addView(title);
        subtitle=text("本机抓包 · 请求记录",13,MUTED);root.addView(subtitle);space(root,10);
        nav=row();root.addView(nav);space(root,8);content=column();root.addView(content,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
        showTab(prefs.contains("install_dir")?"apps":"settings");
    }
    @Override protected void onResume(){super.onResume();uiResumed=true;if(root!=null){loadTargets();if(tab.equals("apps"))renderApps();refreshState();scheduleLive(0);}}
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putString("pending_cert_pem",pendingCertPem);}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request!=EXPORT_CERT)return;if(result!=RESULT_OK||data==null||data.getData()==null){pendingCertPem="";return;}String pem=pendingCertPem;pendingCertPem="";if(pem.isEmpty()){toast("证书内容不可用，请重新导出");return;}Uri uri=data.getData();workers.execute(()->{String error=null;try(OutputStream out=getContentResolver().openOutputStream(uri)){if(out==null)throw new IOException("无法打开目标文件");out.write(pem.getBytes(StandardCharsets.US_ASCII));out.flush();}catch(Exception e){error=e.getMessage()==null?"写入失败":e.getMessage();}String message=error;main.post(()->{if(!isFinishing()&&!isDestroyed())toast(message==null?"证书已导出":"导出失败："+message);});});}
    @Override protected void onPause(){uiResumed=false;if(liveTask!=null)main.removeCallbacks(liveTask);super.onPause();}
    @Override protected void onDestroy(){if(liveTask!=null)main.removeCallbacks(liveTask);workers.shutdownNow();timer.shutdownNow();super.onDestroy();}
    private void stopLive(){if(liveTask!=null){main.removeCallbacks(liveTask);liveTask=null;}}
    private void scheduleLive(long delay){if(uiResumed&&requestsLive&&tab.equals("requests")&&selectedDatabase.equals("current")&&liveTask!=null){main.removeCallbacks(liveTask);main.postDelayed(liveTask,delay);}}
    private void showTab(String which){stopLive();if(!which.equals("requests"))requestGeneration++;tab=which;installStatus=null;processStatus=null;ruleStatus=null;versionStatus=null;pendingStatus=null;startButton=null;stopButton=null;reloadButton=null;autostartStatus=null;mcpStatus=null;nav.removeAllViews();nav.setBackground(bg(Color.rgb(230,232,238),16));nav.setPadding(dp(3),dp(3),dp(3),dp(3));String[] ids={"apps","requests","settings"};String[] names={"应用","请求","设置"};for(int i=0;i<ids.length;i++){final String id=ids[i];TextView b=text(names[i],14,id.equals(which)?INK:MUTED);b.setGravity(Gravity.CENTER);b.setTypeface(null,id.equals(which)?Typeface.BOLD:Typeface.NORMAL);if(id.equals(which))b.setBackground(bg(Color.WHITE,13));b.setOnClickListener(v->showTab(id));nav.addView(b,new LinearLayout.LayoutParams(0,dp(40),1));}content.removeAllViews();switch(which){case "apps":renderApps();break;case "requests":renderRequests();break;default:renderSettings();}paintState();}
    private String q(String s){return "'"+s.replace("'","'\\''")+"'";}
    private String installDir(){String d=prefs.getString("install_dir","/data/adb/tpcapture").trim();while(d.endsWith("/")&&d.length()>1)d=d.substring(0,d.length()-1);return d;}
    private String certMode(){return "self".equals(prefs.getString("cert_mode","emudhra"))?"self":"emudhra";}
    private String dataPrefix(){String data=prefs.getString("data","").trim();return (data.isEmpty()?"":"APPCAPTURE_DATA_DIR="+q(data)+" ")+"TPCAPTURE_CA_MODE="+q(certMode())+" TPCAPTURE_UDP="+(prefs.getBoolean("tproxy_udp",false)?"1":"0")+" ";}
    private void rootCall(String[] args,Callback cb){
        if(bundledVersion<0){cb.complete("",new Exception("无法读取 APK 内置后端版本"));return;}
        String script=installDir()+"/appcapture.sh";StringBuilder cmd=new StringBuilder();boolean guarded=args.length>0&&!args[0].equals("stop")&&!args[0].equals("cleanup")&&!args[0].equals("status")&&!args[0].equals("version")&&!args[0].equals("mcp-stop")&&!args[0].equals("mcp-status");
        if(guarded){cmd.append("v=$(").append(dataPrefix()).append("sh ").append(q(script)).append(" version 2>/dev/null) || v=0; case \"$v\" in ''|*[!0-9]*) v=0;; esac; ");cmd.append("if [ \"$v\" -lt ").append(bundledVersion).append(" ]; then echo '后端需要更新，请在设置页更新'; exit 1; fi; ");cmd.append("if [ \"$v\" -gt ").append(bundledVersion).append(" ]; then echo '后端版本高于管理器，请更新管理器'; exit 1; fi; ");}
        String env=dataPrefix();if(args.length>0&&!selectedDatabase.equals("current")&&(args[0].equals("list-json")||args[0].equals("list")||args[0].equals("show")||args[0].equals("events")))env+="TPCAPTURE_DB="+q(selectedDatabase)+" ";cmd.append(env).append("sh ").append(q(script));for(String a:args)cmd.append(' ').append(q(a));int timeout=(args.length>0&&(args[0].equals("start")||args[0].equals("stop")||args[0].equals("new-session-start")||args[0].equals("reload")))?600:30;for(String a:args)if(a.equals("-text"))timeout=600;runRoot(cmd.toString(),null,cb,timeout);
    }
    private void runRoot(String cmd,String inputAsset,Callback cb){runRoot(cmd,inputAsset,cb,inputAsset==null?30:600);}
    private void runRoot(String cmd,String inputAsset,Callback cb,int timeout){
        workers.execute(()->{String result="";Exception failure=null;Process p=null;java.util.concurrent.ScheduledFuture<?> kill=null;try{
            p=new ProcessBuilder("su","-c",cmd).redirectErrorStream(true).start();Process proc=p;kill=timer.schedule(()->{try{proc.destroy();}catch(Exception ignored){}},timeout,TimeUnit.SECONDS);
            try(OutputStream target=p.getOutputStream()){if(inputAsset!=null)try(InputStream asset=getAssets().open(inputAsset)){byte[] chunk=new byte[32768];int count;while((count=asset.read(chunk))!=-1)target.write(chunk,0,count);}}
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;try(InputStream in=p.getInputStream()){while((n=in.read(buf))!=-1){if(out.size()+n>5*1024*1024){p.destroy();throw new IOException("命令输出过大");}out.write(buf,0,n);}}
            int rc=p.waitFor();result=new String(out.toByteArray(),StandardCharsets.UTF_8);if(rc!=0)throw new IOException(result.trim().isEmpty()?"su 命令失败，退出码 "+rc:result.trim());
        }catch(Exception e){failure=e;}finally{if(kill!=null)kill.cancel(false);if(p!=null)p.destroy();}final String r=result;final Exception f=failure;main.post(()->{if(!isFinishing()&&!isDestroyed())cb.complete(r,f);});});
    }
    private interface Progress { void line(String value); }
    private void rootCallProgress(String[] args,Progress progress,Callback cb){
        if(bundledVersion<0){cb.complete("",new Exception("无法读取 APK 内置后端版本"));return;}String script=installDir()+"/appcapture.sh";StringBuilder cmd=new StringBuilder();boolean guarded=args.length>0&&!args[0].equals("stop")&&!args[0].equals("cleanup");if(guarded){cmd.append("v=$(").append(dataPrefix()).append("sh ").append(q(script)).append(" version 2>/dev/null) || v=0; case \"$v\" in ''|*[!0-9]*) v=0;; esac; [ \"$v\" = ").append(q(String.valueOf(bundledVersion))).append(" ] || { echo '后端版本不匹配，请更新后端'; exit 1; }; ");}cmd.append(dataPrefix()).append("sh ").append(q(script));for(String a:args)cmd.append(' ').append(q(a));
        workers.execute(()->{StringBuilder output=new StringBuilder();Exception failure=null;Process p=null;java.util.concurrent.ScheduledFuture<?> kill=null;try{p=new ProcessBuilder("su","-c",cmd.toString()).redirectErrorStream(true).start();Process proc=p;kill=timer.schedule(()->{try{proc.destroy();}catch(Exception ignored){}},600,TimeUnit.SECONDS);p.getOutputStream().close();try(BufferedReader in=new BufferedReader(new InputStreamReader(p.getInputStream(),StandardCharsets.UTF_8))){String line;while((line=in.readLine())!=null){if(output.length()+line.length()>1024*1024)throw new IOException("操作输出过大");output.append(line).append('\n');final String shown=line;main.post(()->{if(!isFinishing()&&!isDestroyed())progress.line(shown);});}}int rc=p.waitFor();if(rc!=0)throw new IOException(output.length()==0?"su 命令失败，退出码 "+rc:output.toString().trim());}catch(Exception e){failure=e;}finally{if(kill!=null)kill.cancel(false);if(p!=null)p.destroy();}String out=output.toString();Exception err=failure;main.post(()->{if(!isFinishing()&&!isDestroyed())cb.complete(out,err);});});
    }
    private String shortError(Exception e){String text=e.getMessage();if(text==null||text.isEmpty())return "未知错误";return text.length()>300?text.substring(text.length()-300):text;}
    private void repairCaptureLock(Callback cb){if(bundledVersion<1){cb.complete("",new Exception("无法读取内置后端版本"));return;}String dir=installDir(),data=prefs.getString("data","").trim(),script=dir+"/appcapture.sh",stage=dir+"/.repair-stage-"+UUID.randomUUID().toString();if(data.isEmpty())data=dir+"/data";String cmd="set -e; umask 077; mkdir -p -m 700 "+q(dir)+"; mkdir -m 700 "+q(stage)+"; tar -xf - -C "+q(stage)+"; cat >/dev/null; chmod 700 "+q(stage+"/appcapture")+"; v=$("+q(stage+"/appcapture")+" version); [ \"$v\" = "+q(String.valueOf(bundledVersion))+" ] || { echo '内置后端版本不一致'; exit 1; }; "+q(stage+"/appcapture")+" repair-lock -dir "+q(data)+" -script "+q(script)+"; rm -f "+q(stage+"/appcapture")+" "+q(stage+"/appcapture.sh")+"; rmdir "+q(stage);runRoot(cmd,"backend.tar",cb);}
    private void installBackend(Callback cb){
        if(bundledVersion<0){cb.complete("",new Exception("无法读取 APK 内置后端版本"));return;}
        String dir=installDir(), script=dir+"/appcapture.sh", bin=dir+"/appcapture", stage=dir+"/.stage-"+UUID.randomUUID().toString(),status=stage+"/status",targets=stage+"/targets",pids=stage+"/pids";String prefix=dataPrefix();
        StringBuilder cmd=new StringBuilder("set -e; umask 077; ");
        cmd.append("mkdir -p -m 700 ").append(q(dir)).append("; mkdir -m 700 ").append(q(stage)).append("; ");
        cmd.append("tar -xf - -C ").append(q(stage)).append("; cat >/dev/null; ");
        cmd.append("chmod 700 ").append(q(stage+"/appcapture")).append(' ').append(q(stage+"/appcapture.sh")).append("; ");
        cmd.append("v=$(").append(q(stage+"/appcapture")).append(" version); [ \"$v\" = ").append(q(String.valueOf(bundledVersion))).append(" ] || { echo 'APK 内置后端版本不一致'; exit 1; }; ");
        cmd.append("mcpport=''; : > ").append(q(targets)).append("; ");
        cmd.append("if [ -f ").append(q(script)).append(" ]; then ");
        cmd.append("old=$(").append(prefix).append("sh ").append(q(script)).append(" version 2>/dev/null) || old=0; case \"$old\" in ''|*[!0-9]*) old=0;; esac; ");
        cmd.append("if [ \"$old\" -gt ").append(bundledVersion).append(" ]; then echo '已安装后端版本更高，拒绝降级'; exit 1; fi; ");
        String actualData=prefs.getString("data","").trim();if(actualData.isEmpty())actualData=dir+"/data";
        cmd.append("if [ \"$old\" -lt 11 ]; then APPCAPTURE_DATA_DIR=").append(q(actualData)).append(" TPCAPTURE_CA_MODE=").append(q(certMode())).append(" sh ").append(q(stage+"/appcapture.sh")).append(" preflight; fi; ");
        cmd.append(prefix).append("sh ").append(q(script)).append(" status > ").append(q(status)).append(" 2>/dev/null || :; ");
        cmd.append("awk '/^UID=[0-9]+ PID=[0-9]+ target=/{split($1,u,\"=\");sub(/^.* target=/,\"\");if($0 ~ /^uid:[0-9]+$/)print;else print \"uidpkg:\" u[2] \":\" $0}' ").append(q(status)).append(" > ").append(q(targets)).append("; ");
        cmd.append("awk '/^UID=[0-9]+ PID=[0-9]+ target=/{split($2,a,\"=\");print a[2]} /^running pid=[0-9]+/{split($2,a,\"=\");print a[2]}' ").append(q(status)).append(" > ").append(q(pids)).append("; ");
        cmd.append("pid=$(sed -n 's/^running pid=\\([0-9][0-9]*\\).*/\\1/p' ").append(q(status)).append(" | head -n 1); ");
        cmd.append("if [ -n \"$pid\" ] && [ -r \"/proc/$pid/cmdline\" ]; then tr '\\000' ' ' < \"/proc/$pid/cmdline\" | awk '{for(i=1;i<NF;i++){if($i==\"-package\")t=$(i+1);if($i==\"-uid\")u=$(i+1)} if(t ~ /^uid:[0-9]+$/)print t;else if(u!=\"\" && t!=\"\")print \"uidpkg:\" u \":\" t;else if(t!=\"\")print t}' >> ").append(q(targets)).append("; fi; ");
        cmd.append("mcpstate=$(").append(prefix).append("sh ").append(q(script)).append(" mcp-status 2>/dev/null) || mcpstate=''; case \"$mcpstate\" in 'MCP_RUNNING port='*) mcpport=${mcpstate#MCP_RUNNING port=}; mcpport=${mcpport%% *}; case \"$mcpport\" in ''|*[!0-9]*) echo 'MCP 端口状态无效'; exit 1;; esac; ").append(prefix).append("sh ").append(q(script)).append(" mcp-stop;; esac; ");
        cmd.append(prefix).append("sh ").append(q(script)).append(" stop; ");
        cmd.append("while IFS= read -r oldpid; do case \"$oldpid\" in ''|*[!0-9]*) continue;; esac; n=0; while kill -0 \"$oldpid\" 2>/dev/null; do state=$(awk '{print $3}' \"/proc/$oldpid/stat\" 2>/dev/null || :); [ \"$state\" = Z ] && break; [ \"$n\" -lt 50 ] || { echo '旧后端进程未退出，停止更新'; exit 1; }; sleep 0.1; n=$((n+1)); done; done < ").append(q(pids)).append("; fi; ");
        cmd.append("mv -f ").append(q(stage+"/appcapture")).append(' ').append(q(bin)).append("; ");
        cmd.append("mv -f ").append(q(stage+"/appcapture.sh")).append(' ').append(q(script)).append("; ");
        cmd.append("set --; while IFS= read -r target; do [ -n \"$target\" ] || continue; set -- \"$@\" \"$target\"; done < ").append(q(targets)).append("; failed=0; if [ \"$#\" -gt 0 ]; then ").append(prefix).append("sh ").append(q(script)).append(" start \"$@\" || failed=1; fi; ");
        cmd.append("if [ -n \"$mcpport\" ]; then ").append(prefix).append("sh ").append(q(script)).append(" mcp-start \"$mcpport\" || { echo 'MCP 服务未能恢复'; failed=1; }; fi; ");
        cmd.append("rm -f ").append(q(status)).append(' ').append(q(targets)).append(' ').append(q(pids)).append("; rmdir ").append(q(stage)).append("; [ \"$failed\" = 0 ] || { echo '后端已更新，但部分 UID 抓包未能恢复'; exit 1; }; echo '后端已更新，原有 UID 抓包已恢复'");
        runRoot(cmd.toString(),"backend.tar",cb);
    }
    private void removeBackend(Callback cb){String dir=installDir(),script=dir+"/appcapture.sh";String cmd="set -e; if [ -f "+q(script)+" ]; then mcpstate=$("+dataPrefix()+"sh "+q(script)+" mcp-status 2>/dev/null) || mcpstate=''; case \"$mcpstate\" in 'MCP_RUNNING '*) "+dataPrefix()+"sh "+q(script)+" mcp-stop;; esac; "+dataPrefix()+"sh "+q(script)+" stop; fi; rm -f "+q(dir+"/appcapture")+" "+q(script)+"; echo '后端已移除，数据保留'";runRoot(cmd,null,cb);}
    private void certificateAction(int action){
        String dir=installDir(),tcp=dir+"/appcapture.sh",tcpBin=dir+"/appcapture";String prefix=dataPrefix();
        String cmd="if [ -x "+q(tcpBin)+" ] && [ -f "+q(tcp)+" ]; then v=$("+prefix+"sh "+q(tcp)+" version 2>/dev/null) || v=0; if [ \"$v\" = "+q(String.valueOf(bundledVersion))+" ]; then "+prefix+"sh "+q(tcp)+" ca-info; exit $?; fi; fi; echo '请先安装或更新后端'; exit 1";
        runRoot(cmd,null,(out,err)->{if(err!=null){new AlertDialog.Builder(this).setTitle("生成证书失败").setMessage(err.getMessage()).setPositiveButton("关闭",null).show();return;}try{JSONObject o=new JSONObject(out);String pem=o.getString("pem"),name=o.getString("name");if(action==0){String message="PEM："+o.optString("pem_path")+"\n系统文件："+o.optString("hash_path")+"\nSHA-256："+o.optString("sha256")+"\n\n仅生成文件，不会安装到系统。";new AlertDialog.Builder(this).setTitle("自签 CA 已生成").setMessage(message).setPositiveButton("关闭",null).show();return;}pendingCertPem=pem;Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType(action==1?"application/x-pem-file":"application/octet-stream");i.putExtra(Intent.EXTRA_TITLE,action==1?"tpcapture-ca.pem":name);try{startActivityForResult(i,EXPORT_CERT);}catch(Exception e){pendingCertPem="";toast("无法打开系统文件选择器");}}catch(Exception e){new AlertDialog.Builder(this).setTitle("证书信息无效").setMessage(e.getMessage()).setPositiveButton("关闭",null).show();}});
    }
    private void addCertificateCard(LinearLayout parent,Runnable savePaths){LinearLayout c=card();TextView title=text("证书模式",18,INK);title.setTypeface(null,Typeface.BOLD);c.addView(title);space(c,6);TextView description=text("TCP 与 HTTP/3 共用此设置。自签 CA 仅生成和导出，不会自动安装到系统。",13,MUTED);c.addView(description);space(c,8);LinearLayout modes=row();Button leaked=button("泄漏证书"),self=button("自签 CA");modes.addView(leaked,weight());LinearLayout.LayoutParams spacing=weight();spacing.leftMargin=dp(8);modes.addView(self,spacing);c.addView(modes);Runnable paint=()->{boolean selected=certMode().equals("self");leaked.setText(selected?"泄漏证书":"✓ 泄漏证书");self.setText(selected?"✓ 自签 CA":"自签 CA");};leaked.setOnClickListener(v->{savePaths.run();prefs.edit().putString("cert_mode","emudhra").apply();paint.run();paintTargetState();refreshAutostartStatus();});self.setOnClickListener(v->{savePaths.run();prefs.edit().putString("cert_mode","self").apply();paint.run();paintTargetState();refreshAutostartStatus();});paint.run();space(c,7);TextView hint=text("修改模式后需重载正在运行的后端。安装证书请使用你自己的模块；证书固定等校验仍可能阻止解密。",12,MUTED);c.addView(hint);space(c,8);Button generate=button("生成 / 查看自签 CA");c.addView(generate);generate.setOnClickListener(v->{savePaths.run();certificateAction(0);});space(c,6);LinearLayout exports=row();Button pem=button("导出 PEM"),hashed=button("导出 .0 文件");exports.addView(pem,weight());LinearLayout.LayoutParams p=weight();p.leftMargin=dp(8);exports.addView(hashed,p);c.addView(exports);pem.setOnClickListener(v->{savePaths.run();certificateAction(1);});hashed.setOnClickListener(v->{savePaths.run();certificateAction(2);});parent.addView(c);space(parent,10);}
    private void addRewriteCard(LinearLayout box,Runnable savePaths){
        LinearLayout card=card();TextView title=text("重写规则",18,INK);title.setTypeface(null,Typeface.BOLD);card.addView(title);space(card,6);
        card.addView(text("按 UID、主机、方法、URL 命中后改写请求或响应，也可以直接拦截。仅重写模式下未命中的 TLS 连接直接透传，不解密也不记录。",12,MUTED));space(card,8);
        TextView info=text("正在读取规则…",12,MUTED);card.addView(info);space(card,8);
        Button open=button("打开重写编辑器");card.addView(open);
        open.setOnClickListener(v->{savePaths.run();Intent i=new Intent(this,local.appcapture.viewer.ui.RewriteActivity.class);i.putExtra("install_dir",installDir());i.putExtra("data_dir",prefs.getString("data",""));startActivity(i);});
        rootCall(new String[]{"rewrite-get"},(out,err)->{
            if(err!=null){info.setText("规则状态未知："+shortError(err));return;}
            try{JSONObject o=new JSONObject(out);String mode=o.optString("mode","record");JSONArray rules=o.optJSONArray("rules");info.setText("模式："+(mode.equals("matched")?"仅记录命中":mode.equals("rewrite-only")?"仅重写不记录":"全部记录")+" · 规则 "+rules.length()+" 条");}catch(Exception e){info.setText("规则状态未知");}
        });
        box.addView(card);space(box,10);
    }
    private String bootDataDir(){String data=prefs.getString("data","").trim();return data.isEmpty()?installDir()+"/data":data;}
    private String bootConfig(){return "BACKEND_SCRIPT="+q(installDir()+"/appcapture.sh")+"\nAPPCAPTURE_DATA_DIR="+q(bootDataDir())+"\nTPCAPTURE_CA_MODE="+q(certMode())+"\nTPCAPTURE_UDP="+q(prefs.getBoolean("tproxy_udp",false)?"1":"0")+"\nCREATE_SESSION="+q(prefs.getBoolean("new_session",false)?"1":"0")+"\nEXPECTED_VERSION="+q(String.valueOf(bundledVersion))+"\n";}
    private String bootTargets(Iterable<Target> values){StringBuilder b=new StringBuilder();for(Target t:values){if(t.key.startsWith("uid:"))b.append(t.key);else b.append("uidpkg:").append(t.uid).append(':').append(t.key);b.append('\n');}return b.toString();}
    private String bootSignature(String config,String values){try{byte[] digest=MessageDigest.getInstance("SHA-256").digest((config+"\u0000"+values).getBytes(StandardCharsets.UTF_8));StringBuilder b=new StringBuilder();for(byte v:digest)b.append(String.format(Locale.ROOT,"%02x",v&255));return b.toString();}catch(Exception e){return "";}}
    private void refreshAutostartStatus(){final int generation=++autostartGeneration;if(autostartStatus!=null)autostartStatus.setText("正在读取模块状态…");String module=AUTOSTART_ROOT+"/"+AUTOSTART_ID;String expected=bootSignature(bootConfig(),bootTargets(targets.values()));String cmd="if [ ! -f "+q(module+"/module.prop")+" ]; then echo MISSING; elif [ -f "+q(module+"/remove")+" ]; then echo REMOVING; elif [ -f "+q(module+"/disable")+" ]; then echo DISABLED; else echo INSTALLED; cat "+q(module+"/config.sha256")+" 2>/dev/null || :; fi";runRoot(cmd,null,(out,err)->{if(generation!=autostartGeneration||autostartStatus==null)return;if(err!=null){autostartStatus.setText("模块状态未知：请确认 root 授权");return;}String[] lines=out.trim().split("\\r?\\n");String state=lines.length==0?"":lines[0];if(state.equals("MISSING"))autostartStatus.setText("未安装");else if(state.equals("REMOVING"))autostartStatus.setText("已禁用，重启后移除");else if(state.equals("DISABLED"))autostartStatus.setText("已禁用");else if(state.equals("INSTALLED")){String actual=lines.length>1?lines[1].trim():"";autostartStatus.setText(actual.equals(expected)&&!actual.isEmpty()?"已安装 · 配置一致":"已安装 · 当前配置已改变，请更新模块");}else autostartStatus.setText("模块状态未知");});}
    private void installAutostart(Callback callback){
        if(bundledVersion<1){callback.complete("",new Exception("无法读取内置后端版本"));return;}String dir=installDir(),data=bootDataDir();if(!dir.startsWith("/")||!data.startsWith("/")){callback.complete("",new Exception("后端和数据目录需要使用绝对路径"));return;}ArrayList<Target> snapshot=new ArrayList<>();for(Target t:targets.values())snapshot.add(new Target(t.key,t.label,t.uid));if(snapshot.isEmpty()){callback.complete("",new Exception("请先选择目标"));return;}
        final String config=bootConfig();workers.execute(()->{ArrayList<Target> resolved=new ArrayList<>();ArrayList<String> missing=new ArrayList<>();PackageManager pm=getPackageManager();for(Target t:snapshot){if(t.key.startsWith("uid:")){resolved.add(t);continue;}try{ApplicationInfo info=pm.getApplicationInfo(t.key,0);resolved.add(new Target(t.key,t.label,info.uid));}catch(PackageManager.NameNotFoundException e){missing.add(t.label+"（"+t.key+"）");}}main.post(()->{if(isFinishing()||isDestroyed())return;if(!missing.isEmpty()){callback.complete("",new IOException("以下目标不可用，请先移除："+TextUtils.join("、",missing)));return;}boolean changed=false;for(Target t:resolved){Target existing=targets.get(t.key);if(existing!=null&&existing.uid!=t.uid){existing.uid=t.uid;changed=true;}}if(changed)saveTargets();String targetText=bootTargets(resolved),sig=bootSignature(config,targetText);if(sig.isEmpty()){callback.complete("",new IOException("无法生成配置摘要"));return;}String script=dir+"/appcapture.sh",module=AUTOSTART_ROOT+"/"+AUTOSTART_ID,stage=AUTOSTART_ROOT+"/.tpcapture-stage-"+UUID.randomUUID(),backup=AUTOSTART_ROOT+"/.tpcapture-old-"+UUID.randomUUID();String env="APPCAPTURE_DATA_DIR="+q(data)+" TPCAPTURE_CA_MODE="+q(certMode())+" TPCAPTURE_UDP="+(prefs.getBoolean("tproxy_udp",false)?"1":"0")+" ";StringBuilder cmd=new StringBuilder("set -e; umask 077; ");
            cmd.append("[ -x ").append(q(dir+"/appcapture")).append(" ] && [ -f ").append(q(script)).append(" ] || { echo '请先安装后端'; exit 1; }; v=$(").append(env).append("sh ").append(q(script)).append(" version 2>/dev/null) || v=0; [ \"$v\" = ").append(q(String.valueOf(bundledVersion))).append(" ] || { echo '请先更新后端'; exit 1; }; ");
            cmd.append("mkdir -p -m 700 ").append(q(AUTOSTART_ROOT)).append("; [ ! -L ").append(q(module)).append(" ] || { echo '模块目录是符号链接，拒绝覆盖'; exit 1; }; if [ -e ").append(q(module)).append(" ]; then [ -f ").append(q(module+"/module.prop")).append(" ] && grep -qx ").append(q("id="+AUTOSTART_ID)).append(' ').append(q(module+"/module.prop")).append(" || { echo '同名目录不属于 TPCapture'; exit 1; }; fi; ");
            cmd.append("mkdir -m 700 ").append(q(stage)).append("; tar -xf - -C ").append(q(stage)).append("; cat >/dev/null; printf '%s' ").append(q(config)).append(" > ").append(q(stage+"/config.sh")).append("; printf '%s' ").append(q(targetText)).append(" > ").append(q(stage+"/targets.txt")).append("; printf '%s\\n' ").append(q(sig)).append(" > ").append(q(stage+"/config.sha256")).append("; printf '%s\\n' \"$PATH\" > ").append(q(stage+"/root-path")).append("; ");
            cmd.append("chmod 755 ").append(q(stage+"/boot.sh")).append(' ').append(q(stage+"/post-fs-data.sh")).append(' ').append(q(stage+"/service.sh")).append("; chmod 644 ").append(q(stage+"/module.prop")).append("; chmod 600 ").append(q(stage+"/config.sh")).append(' ').append(q(stage+"/targets.txt")).append(' ').append(q(stage+"/config.sha256")).append(' ').append(q(stage+"/root-path")).append("; ");
            cmd.append("if [ -f ").append(q(data+"/run-single/config")).append(" ] && [ ! -s ").append(q(data+"/run-single/boot-id")).append(" ]; then cat /proc/sys/kernel/random/boot_id > ").append(q(data+"/run-single/boot-id")).append("; chmod 600 ").append(q(data+"/run-single/boot-id")).append("; fi; ");
            cmd.append("old=0; if [ -d ").append(q(module)).append(" ]; then mv ").append(q(module)).append(' ').append(q(backup)).append("; old=1; fi; if ! mv ").append(q(stage)).append(' ').append(q(module)).append("; then [ \"$old\" = 0 ] || mv ").append(q(backup)).append(' ').append(q(module)).append("; echo '模块安装失败'; exit 1; fi; ");
            cmd.append("if [ \"$old\" = 1 ]; then rm -f ").append(q(backup+"/module.prop")).append(' ').append(q(backup+"/post-fs-data.sh")).append(' ').append(q(backup+"/service.sh")).append(' ').append(q(backup+"/boot.sh")).append(' ').append(q(backup+"/config.sh")).append(' ').append(q(backup+"/targets.txt")).append(' ').append(q(backup+"/config.sha256")).append(' ').append(q(backup+"/root-path")).append(' ').append(q(backup+"/disable")).append(' ').append(q(backup+"/remove")).append(' ').append(q(backup+"/update")).append("; rmdir ").append(q(backup)).append(" 2>/dev/null || :; fi; echo '自启动模块已安装，重启后生效'");
            runRoot(cmd.toString(),"autostart.tar",callback);
        });});
    }
    private void removeAutostart(Callback cb){String module=AUTOSTART_ROOT+"/"+AUTOSTART_ID;String cmd="if [ ! -f "+q(module+"/module.prop")+" ]; then echo '自启动模块未安装'; exit 0; fi; grep -qx "+q("id="+AUTOSTART_ID)+" "+q(module+"/module.prop")+" || { echo '模块标识不匹配'; exit 1; }; : > "+q(module+"/disable")+"; : > "+q(module+"/remove")+"; echo '自启动已禁用，模块将在重启后移除，当前抓包不受影响'";runRoot(cmd,null,cb);}
    private void addAutostartCard(LinearLayout box,Runnable savePaths){LinearLayout card=card();TextView title=text("开机自启动",18,INK);title.setTypeface(null,Typeface.BOLD);card.addView(title);space(card,6);autostartStatus=text("正在读取模块状态…",13,MUTED);card.addView(autostartStatus);space(card,6);card.addView(text("将当前目标、证书模式、UDP 和会话设置保存为 Magisk 模块。配置改变或应用卸载重装后需要更新模块。模块会尽早尝试启动，但不保证覆盖所有开机请求。",12,MUTED));space(card,8);Button install=button("安装 / 更新自启动模块");card.addView(install);install.setOnClickListener(v->{String dir=installDir();if(dir.isEmpty()||!dir.startsWith("/")){toast("请先保存后端目录");return;}savePaths.run();if(targets.isEmpty()){toast("请先选择目标");return;}String warning="将安装一个开机执行的 Magisk 模块，并保存当前目标和配置。模块不会安装证书。"+(systemTargets()?"\n\n当前包含系统 UID，开机抓包可能影响系统网络；如出现异常，可在 Magisk 中禁用模块。":"");new AlertDialog.Builder(this).setTitle("安装自启动模块？").setMessage(warning).setNegativeButton("取消",null).setPositiveButton("安装",(d,w)->{if(!switching.compareAndSet(false,true))return;autostartStatus.setText("正在安装…");installAutostart((out,err)->{switching.set(false);if(err!=null)new AlertDialog.Builder(this).setTitle("安装未完成").setMessage(err.getMessage()).setPositiveButton("关闭",null).show();else toast(out.trim());refreshAutostartStatus();});}).show();});space(card,6);Button remove=button("移除自启动模块");remove.setTextColor(Color.rgb(204,61,65));card.addView(remove);remove.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("移除自启动模块？").setMessage("只关闭开机自启并安排模块在下次重启时移除；不会停止当前抓包或删除数据库。").setNegativeButton("取消",null).setPositiveButton("移除",(d,w)->{if(!switching.compareAndSet(false,true))return;autostartStatus.setText("正在移除…");removeAutostart((out,err)->{switching.set(false);if(err!=null)new AlertDialog.Builder(this).setTitle("移除未完成").setMessage(err.getMessage()).setPositiveButton("关闭",null).show();else toast(out.trim());refreshAutostartStatus();});}).show());space(card,6);TextView logs=text("查看开机日志",13,BLUE);logs.setPadding(0,dp(4),0,dp(4));logs.setOnClickListener(v->{String path=bootDataDir()+"/autostart.log";runRoot("if [ -f "+q(path)+" ]; then tail -n 80 "+q(path)+"; else echo '暂无开机日志'; fi",null,(out,err)->new AlertDialog.Builder(this).setTitle("开机日志").setMessage(err==null?out:err.getMessage()).setPositiveButton("关闭",null).show());});card.addView(logs);box.addView(card);space(box,10);refreshAutostartStatus();}
    private void refreshMcpStatus(){final int gen=++mcpStatusGeneration;if(mcpStatus!=null)mcpStatus.setText("正在读取 MCP 状态…");rootCall(new String[]{"mcp-status"},(out,err)->{if(gen!=mcpStatusGeneration)return;mcpRunning=false;String label;if(err!=null){label="状态未知，请先安装或更新后端";}else if(out.startsWith("MCP_RUNNING ")){mcpRunning=true;java.util.regex.Matcher m=java.util.regex.Pattern.compile("port=([0-9]+)").matcher(out);if(m.find())try{mcpRunningPort=Integer.parseInt(m.group(1));}catch(Exception ignored){}label="运行中 · http://127.0.0.1:"+mcpRunningPort+"/mcp";}else label="未启动";if(mcpStatus!=null){mcpStatus.setText(label);mcpStatus.setTextColor(mcpRunning?GREEN:MUTED);}});}
    private void showMcpConnection(){rootCall(new String[]{"mcp-token"},(out,err)->{if(err!=null){new AlertDialog.Builder(this).setTitle("获取令牌失败").setMessage(err.getMessage()).setPositiveButton("关闭",null).show();return;}String token=out.trim();int port=mcpRunning?mcpRunningPort:prefs.getInt("mcp_port",18765);String endpoint="http://127.0.0.1:"+port+"/mcp";try{JSONObject config=new JSONObject();config.put("type","streamable_http");config.put("url",endpoint);JSONObject headers=new JSONObject();headers.put("Authorization","Bearer "+token);config.put("headers",headers);String text="地址："+endpoint+"\nAuthorization：Bearer "+token+"\n\n此令牌可访问抓包数据，请只提供给可信 Agent。";TextView value=text(text,13,INK);value.setTextIsSelectable(true);value.setPadding(dp(22),dp(8),dp(22),dp(8));new AlertDialog.Builder(this).setTitle("MCP 连接信息").setView(value).setNegativeButton("关闭",null).setPositiveButton("复制配置",(d,w)->{ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);clipboard.setPrimaryClip(ClipData.newPlainText("TPCapture MCP",config.toString()));toast("已复制，配置中包含访问令牌");}).show();}catch(Exception e){toast("无法生成连接信息");}});}
    private void addMcpCard(LinearLayout box,Runnable savePaths){LinearLayout card=card();TextView title=text("MCP 服务",18,INK);title.setTypeface(null,Typeface.BOLD);card.addView(title);space(card,6);mcpStatus=text("正在读取状态…",13,MUTED);card.addView(mcpStatus);space(card,6);card.addView(text("仅监听本机，需要访问令牌。Agent 可以查询当前和历史数据库，也可以增删重写规则；请求地址及授权查看的头和正文可能包含敏感信息。",12,MUTED));space(card,8);EditText port=new EditText(this);port.setSingleLine(true);port.setInputType(InputType.TYPE_CLASS_NUMBER);port.setTextSize(14);port.setHint("本地端口");port.setTextColor(INK);port.setHintTextColor(MUTED);port.setBackground(bg(Color.rgb(242,243,245),14));port.setPadding(dp(14),dp(11),dp(14),dp(11));port.setText(String.valueOf(prefs.getInt("mcp_port",18765)));card.addView(port);space(card,7);LinearLayout actions=row();Button start=button("启动 MCP"),stop=button("停止 MCP");actions.addView(start,weight());LinearLayout.LayoutParams spacing=weight();spacing.leftMargin=dp(8);actions.addView(stop,spacing);card.addView(actions);start.setOnClickListener(v->{int value;try{value=Integer.parseInt(port.getText().toString().trim());if(value<1024||value>65535)throw new NumberFormatException();}catch(NumberFormatException e){port.setError("请输入 1024–65535 的端口");return;}savePaths.run();prefs.edit().putInt("mcp_port",value).apply();if(!switching.compareAndSet(false,true))return;mcpStatus.setText("正在启动…");rootCall(new String[]{"mcp-start",String.valueOf(value)},(out,err)->{switching.set(false);if(err!=null)new AlertDialog.Builder(this).setTitle("MCP 启动失败").setMessage(err.getMessage()).setPositiveButton("关闭",null).show();else toast("MCP 已启动");refreshMcpStatus();});});stop.setOnClickListener(v->{if(!switching.compareAndSet(false,true))return;mcpStatus.setText("正在停止…");rootCall(new String[]{"mcp-stop"},(out,err)->{switching.set(false);if(err!=null)new AlertDialog.Builder(this).setTitle("MCP 停止失败").setMessage(err.getMessage()).setPositiveButton("关闭",null).show();else toast("MCP 已停止");refreshMcpStatus();});});space(card,6);TextView info=text("查看 / 复制连接信息",13,BLUE);info.setPadding(0,dp(5),0,dp(5));info.setOnClickListener(v->{int value;try{value=Integer.parseInt(port.getText().toString().trim());if(value<1024||value>65535)throw new NumberFormatException();}catch(NumberFormatException e){port.setError("请输入 1024–65535 的端口");return;}prefs.edit().putInt("mcp_port",value).apply();savePaths.run();showMcpConnection();});card.addView(info);box.addView(card);space(box,10);refreshMcpStatus();}
    private void loadTargets(){targets.clear();try{JSONArray a=new JSONArray(prefs.getString("targets","[]"));for(int i=0;i<a.length();i++){JSONObject j=a.getJSONObject(i);String k=j.getString("key");targets.put(k,new Target(k,j.optString("label",k),j.optInt("uid",-1)));}}catch(Exception ignored){}}
    private void saveTargets(){JSONArray a=new JSONArray();for(Target t:targets.values()){JSONObject j=new JSONObject();try{j.put("key",t.key);j.put("label",t.label);j.put("uid",t.uid);a.put(j);}catch(Exception ignored){}}prefs.edit().putString("targets",a.toString()).apply();}
    private void callSelectedTargets(String action,Progress progress,Callback callback){
        ArrayList<Target> snapshot=new ArrayList<>();for(Target t:targets.values())snapshot.add(new Target(t.key,t.label,t.uid));
        workers.execute(()->{ArrayList<String> args=new ArrayList<>();args.add(action);ArrayList<String> missing=new ArrayList<>();LinkedHashMap<String,Integer> current=new LinkedHashMap<>();PackageManager pm=getPackageManager();
            for(Target t:snapshot){if(t.key.startsWith("uid:")){args.add(t.key);continue;}try{ApplicationInfo info=pm.getApplicationInfo(t.key,0);current.put(t.key,info.uid);args.add("uidpkg:"+info.uid+":"+t.key);}catch(PackageManager.NameNotFoundException e){missing.add(t.label+"（"+t.key+"）");}}
            main.post(()->{if(isFinishing()||isDestroyed())return;boolean changed=false;for(Map.Entry<String,Integer> e:current.entrySet()){Target t=targets.get(e.getKey());if(t!=null&&t.uid!=e.getValue()){t.uid=e.getValue();changed=true;}}if(changed){saveTargets();paintTargetState();}if(args.size()==1){callback.complete("",new IOException("没有可用目标："+TextUtils.join("、",missing)));return;}rootCallProgress(args.toArray(new String[0]),progress,(out,err)->{if(missing.isEmpty()){callback.complete(out,err);return;}String text="以下目标不可用，未启动："+TextUtils.join("、",missing);if(err!=null)text+="\n"+err.getMessage();callback.complete(out,new IOException(text));});});
        });
    }
    private boolean systemTargets(){for(Target t:targets.values())if(t.uid>=0&&t.uid<10000)return true;return false;}
    private String targetSummary(){return targets.isEmpty()?"尚未选择目标":"已选 "+targets.size()+" 个目标";}
    private void chooseManualUid(Runnable changed){EditText input=new EditText(this);input.setInputType(InputType.TYPE_CLASS_NUMBER);input.setHint("例如 0 或 10234");new AlertDialog.Builder(this).setTitle("手动添加 UID").setView(input).setNegativeButton("取消",null).setPositiveButton("添加",(d,w)->{try{long uid=Long.parseLong(input.getText().toString());if(uid<0||uid>Integer.MAX_VALUE)throw new NumberFormatException();String key="uid:"+uid;targets.put(key,new Target(key,"UID "+uid,(int)uid));saveTargets();changed.run();}catch(Exception e){toast("UID 必须是有效的非负整数");}}).show();}
    private void manageTargets(Runnable changed){if(targets.isEmpty()){toast("尚未选择目标");return;}ArrayList<Target> list=new ArrayList<>(targets.values());String[] labels=new String[list.size()];for(int i=0;i<list.size();i++)labels[i]=list.get(i).label+"  ·  UID "+list.get(i).uid;new AlertDialog.Builder(this).setTitle("已选目标 · 点击移除").setItems(labels,(d,w)->{targets.remove(list.get(w).key);saveTargets();changed.run();}).setNegativeButton("关闭",null).show();}
    private String sourceName(Entry e){if(e.uid<0)return "来源未知";if(e.source.startsWith("uid:")){String[] p=getPackageManager().getPackagesForUid(e.uid);if(p!=null&&p.length==1){try{return String.valueOf(getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(p[0],0)));}catch(Exception ignored){return p[0];}}return p!=null&&p.length>1?"共享 UID":"未知应用";}try{return String.valueOf(getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(e.source,0)));}catch(Exception ignored){return e.source.isEmpty()?"未知应用":e.source;}}
    private String formatTime(long timestamp,String pattern){if(timestamp<=0)return "未知";return new SimpleDateFormat(pattern,Locale.getDefault()).format(new Date(timestamp));}
    private String appLabel(String pkg){try{return String.valueOf(getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg,0)));}catch(Exception e){return "未知（可能已卸载）";}}
    private String sourceMetadata(Entry e){if(e.uid<0)return "来源 UID：未知";StringBuilder b=new StringBuilder();String[] packages=getPackageManager().getPackagesForUid(e.uid);boolean shared=packages!=null&&packages.length>1;
        if(e.source.startsWith("uid:")){b.append("记录目标：").append(e.source).append('\n');if(packages==null||packages.length==0){b.append("应用名：无对应应用或未知\n包名：无对应应用或未知");}else if(packages.length==1){b.append("应用名：").append(appLabel(packages[0])).append("（UID 关联）\n包名：").append(packages[0]).append("（UID 关联）");}else{b.append("应用名：共享 UID，无法确定具体应用\n关联包名：").append(TextUtils.join(", ",packages));}}
        else{b.append("应用名：").append(appLabel(e.source)).append("\n包名：").append(e.source);if(shared)b.append("\n注意：该 UID 被多个包共享，无法确认实际来源进程");}
        return b.append("\nUID：").append(e.uid).toString();
    }
    private Drawable sourceIcon(Entry e){String pkg=e.source;if(e.uid<0)pkg="";else if(pkg.startsWith("uid:")){String[] p=getPackageManager().getPackagesForUid(e.uid);pkg=p!=null&&p.length==1?p[0]:"";}if(icons.containsKey(pkg))return icons.get(pkg);Drawable d;try{d=getPackageManager().getApplicationIcon(pkg);}catch(Exception ex){d=getDrawable(android.R.drawable.sym_def_app_icon);}icons.put(pkg,d);return d;}
    private void loadApps(){workers.execute(()->{ArrayList<App> found=new ArrayList<>();PackageManager pm=getPackageManager();for(ApplicationInfo ai:pm.getInstalledApplications(0)){try{String label=String.valueOf(pm.getApplicationLabel(ai));found.add(new App(label,ai.packageName,ai.uid,(ai.flags&ApplicationInfo.FLAG_SYSTEM)!=0));}catch(Exception ignored){}}Collections.sort(found,Comparator.comparing(a->a.name.toLowerCase(Locale.ROOT)));main.post(()->{apps.clear();apps.addAll(found);boolean changed=false;for(App a:found){Target t=targets.get(a.pkg);if(t!=null&&t.uid!=a.uid){t.uid=a.uid;changed=true;}}if(changed)saveTargets();if(tab.equals("apps"))renderApps();paintTargetState();});});}
    private void renderApps(){content.removeAllViews();ScrollView appScroll=new ScrollView(this);appScroll.setFillViewport(false);content.addView(appScroll,new LinearLayout.LayoutParams(-1,-1));LinearLayout page=column();appScroll.addView(page);addStatusCard(page);
        LinearLayout config=card();LinearLayout header=row();TextView heading=text("抓包目标",17,INK);heading.setTypeface(null,Typeface.BOLD);header.addView(heading,new LinearLayout.LayoutParams(0,-2,1));TextView select=text("选择与管理  ›",14,BLUE);select.setPadding(dp(10),dp(4),0,dp(4));select.setOnClickListener(v->startActivity(new Intent(this,TargetPickerActivity.class)));header.addView(select);config.addView(header);space(config,6);
        TextView selected=text(targetSummary(),14,INK);config.addView(selected);space(config,4);TextView certSummary=text("证书模式："+(certMode().equals("self")?"自签 CA":"泄漏证书"),13,MUTED);config.addView(certSummary);space(config,4);pendingStatus=text("",13,MUTED);config.addView(pendingStatus);space(config,7);
        if(!targets.isEmpty()){int shown=0;for(Target t:targets.values()){if(shown>=4)break;TextView item=text(t.label+(t.key.startsWith("uid:")?"":"  ·  UID "+t.uid),13,t.key.startsWith("uid:")?Color.rgb(205,140,30):MUTED);item.setSingleLine(true);item.setEllipsize(TextUtils.TruncateAt.END);config.addView(item);space(config,3);shown++;}if(targets.size()>shown){TextView more=text("以及其他 "+(targets.size()-shown)+" 个目标",12,MUTED);config.addView(more);}}
        space(config,4);Switch newSession=new Switch(this);newSession.setText("创建新会话");newSession.setTextSize(13);newSession.setTextColor(INK);newSession.setChecked(prefs.getBoolean("new_session",false));newSession.setOnCheckedChangeListener((v,on)->prefs.edit().putBoolean("new_session",on).apply());config.addView(newSession);space(config,5);Switch udp=new Switch(this);udp.setText("拦截 UDP/443（QUIC，实验）");udp.setTextSize(13);udp.setTextColor(INK);udp.setChecked(prefs.getBoolean("tproxy_udp",false));udp.setOnCheckedChangeListener((v,on)->{if(!on){prefs.edit().putBoolean("tproxy_udp",false).apply();paintTargetState();return;}new AlertDialog.Builder(this).setTitle("启用实验性 UDP/QUIC？").setMessage("将截获目标 UID 的 IPv4 和 IPv6 UDP/443。非 QUIC 流量和不兼容的 QUIC 可能失败，策略路由也可能与 Clash 或其他模块冲突。确认启用？").setNegativeButton("取消",(d,w)->udp.setChecked(false)).setPositiveButton("启用",(d,w)->{prefs.edit().putBoolean("tproxy_udp",true).apply();paintTargetState();}).setOnCancelListener(d->udp.setChecked(false)).show();});config.addView(udp);space(config,7);
        TextView status=text("启动前选择目标；运行时修改后请重载。",12,MUTED);status.setMaxLines(3);status.setEllipsize(TextUtils.TruncateAt.END);config.addView(status);final String[] operationLog={""};status.setOnClickListener(v->{if(!operationLog[0].isEmpty())new AlertDialog.Builder(this).setTitle("操作详情").setMessage(operationLog[0]).setPositiveButton("关闭",null).show();});space(config,7);
        LinearLayout actions=row();Button start=button("启动"),stop=button("停止"),reload=button("重载");startButton=start;stopButton=stop;reloadButton=reload;actions.addView(start,weight());LinearLayout.LayoutParams stopSpacing=weight();stopSpacing.leftMargin=dp(8);actions.addView(stop,stopSpacing);LinearLayout.LayoutParams reloadSpacing=weight();reloadSpacing.leftMargin=dp(8);actions.addView(reload,reloadSpacing);config.addView(actions);page.addView(config);
        Runnable startCapture=()->{if(!switching.compareAndSet(false,true))return;paintTargetState();boolean fresh=newSession.isChecked();operationLog[0]="";status.setText(fresh?"正在归档并启动…":"正在启动…");callSelectedTargets(fresh?"new-session-start":"start",line->status.setText("正在启动："+line),(o,e)->{switching.set(false);if(fresh&&e==null){selectedDatabase="current";prefs.edit().putString("view_database","current").apply();}if(e==null)status.setText("启动完成");else{operationLog[0]=e.getMessage()==null?"未知错误":e.getMessage();status.setText("启动未完成，点击查看详情");}refreshState();});};
        start.setOnClickListener(v->{if(targets.isEmpty()){toast("请先选择目标");return;}if(captureRunning){toast("后端正在运行，请使用重载");return;}boolean fresh=newSession.isChecked();if(systemTargets()||fresh){String message=(fresh?"将归档当前数据库，再启动所选目标。历史数据库会保留。\n\n":"")+(systemTargets()?"所选系统 UID 下所有进程都会被截获，可能影响系统网络。UID 0 的出口标记也可能影响路由。":"");new AlertDialog.Builder(this).setTitle(fresh?"开始新会话？":"包含系统 UID").setMessage(message).setNegativeButton("取消",null).setPositiveButton("启动",(d,w)->startCapture.run()).show();}else startCapture.run();});
        stop.setOnClickListener(v->{if(!switching.compareAndSet(false,true))return;paintTargetState();operationLog[0]="";status.setText("正在停止…");rootCallProgress(new String[]{"stop"},line->status.setText("正在停止："+line),(o,e)->{switching.set(false);if(e==null)status.setText("已停止");else{operationLog[0]=e.getMessage()==null?"未知错误":e.getMessage();status.setText("停止未完成，点击查看详情");}refreshState();});});
        Runnable reloadCapture=()->{if(!switching.compareAndSet(false,true))return;paintTargetState();operationLog[0]="";status.setText("正在重载目标…");callSelectedTargets("reload",line->status.setText("正在重载："+line),(o,e)->{switching.set(false);if(e==null)status.setText("重载完成");else{operationLog[0]=e.getMessage()==null?"未知错误":e.getMessage();status.setText("重载未完成，点击查看详情");}refreshState();});};
        reload.setOnClickListener(v->{if(targets.isEmpty()){toast("请先选择至少一个目标；要停止请点击停止");return;}if(systemTargets())new AlertDialog.Builder(this).setTitle("包含系统 UID").setMessage("该 UID 下所有进程都会被截获，可能影响系统网络。确认重载？").setNegativeButton("取消",null).setPositiveButton("重载",(d,w)->reloadCapture.run()).show();else reloadCapture.run();});
        paintTargetState();
    }
    private void chooseDatabase(){rootCall(new String[]{"databases"},(out,err)->{if(err!=null){new AlertDialog.Builder(this).setTitle("读取数据库列表失败").setMessage(err.getMessage()).setPositiveButton("关闭",null).show();return;}ArrayList<String> ids=new ArrayList<>(),labels=new ArrayList<>();for(String line:out.split("\\r?\\n")){if(line.trim().isEmpty())continue;try{JSONObject j=new JSONObject(line);String id=j.getString("id");ids.add(id);labels.add(id.equals("current")?"当前会话":"历史 · "+j.optString("label",id));}catch(Exception ignored){}}if(ids.isEmpty()){toast("没有可用数据库");return;}int checked=ids.indexOf(selectedDatabase);new AlertDialog.Builder(this).setTitle("选择数据库").setSingleChoiceItems(labels.toArray(new String[0]),checked,(dialog,which)->{selectedDatabase=ids.get(which);prefs.edit().putString("view_database",selectedDatabase).apply();dialog.dismiss();renderRequests();}).setNegativeButton("取消",null).show();});}
    private String databaseLabel(){return selectedDatabase.equals("current")?"当前会话":"历史 · "+selectedDatabase;}
    private void filterDialog(){
        ScrollView scroll=new ScrollView(this);LinearLayout box=column();box.setPadding(dp(22),dp(6),dp(22),0);scroll.addView(box);
        EditText uid=new EditText(this);uid.setSingleLine(true);uid.setInputType(InputType.TYPE_CLASS_NUMBER);uid.setHint("UID（精确匹配）");uid.setText(filterUid);box.addView(uid);
        EditText url=new EditText(this);url.setSingleLine(true);url.setHint("URL 包含");url.setText(filterUrl);box.addView(url);
        EditText keyword=new EditText(this);keyword.setSingleLine(true);keyword.setHint("全文包含（请求 / 响应）");keyword.setText(filterText);box.addView(keyword);
        box.addView(text("排除 Host，每行一个",13,MUTED));EditText excluded=new EditText(this);excluded.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);excluded.setSingleLine(false);excluded.setMinLines(2);excluded.setMaxLines(5);excluded.setGravity(Gravity.TOP);excluded.setHint("google.cn\ngoogle.com");excluded.setText(filterExcludedHosts);box.addView(excluded);
        box.addView(text("传输协议",13,MUTED));Spinner transport=new Spinner(this);String[] transportNames={"全部协议","TCP","UDP（QUIC）"};ArrayAdapter<String> transportAdapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,transportNames);transportAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);transport.setAdapter(transportAdapter);transport.setSelection(filterTransport.equals("tcp")?1:filterTransport.equals("udp")?2:0);box.addView(transport);
        box.addView(text("请求方法",13,MUTED));Spinner method=new Spinner(this);String[] methods={"全部方法","GET","POST","WS↑","WS↓"};ArrayAdapter<String> methodAdapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,methods);methodAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);method.setAdapter(methodAdapter);method.setSelection(filterMethod.equals("GET")?1:filterMethod.equals("POST")?2:filterMethod.equals("WS↑")?3:filterMethod.equals("WS↓")?4:0);box.addView(method);
        CheckBox media=new CheckBox(this);media.setText("只看媒体（视频、音频、图片、播放列表）");media.setTextSize(14);media.setChecked(filterMedia);box.addView(media);
        TextView tip=text("多个条件同时生效。排除 Host 也包含其子域名，仅影响列表显示。媒体类型为特征识别，可能漏判或误判。",12,MUTED);box.addView(tip);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("筛选请求").setView(scroll).setNegativeButton("取消",null).setNeutralButton("清除",(d,w)->{filterUid="";filterUrl="";filterText="";filterTransport="";filterMethod="";filterExcludedHosts="";filterMedia=false;prefs.edit().remove("filter_exclude_hosts").remove("filter_uid").remove("filter_url").remove("filter_text").remove("filter_transport").remove("filter_method").remove("filter_media").apply();renderRequests();}).setPositiveButton("应用",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String u=uid.getText().toString().trim();if(!u.isEmpty()){try{long n=Long.parseLong(u);if(n<0||n>Integer.MAX_VALUE)throw new NumberFormatException();}catch(NumberFormatException e){uid.setError("请输入有效 UID");return;}}filterUid=u;filterUrl=url.getText().toString().trim();filterText=keyword.getText().toString().trim();filterExcludedHosts=normalizeExcludedHosts(excluded.getText().toString());filterTransport=transport.getSelectedItemPosition()==1?"tcp":transport.getSelectedItemPosition()==2?"udp":"";filterMethod=method.getSelectedItemPosition()==1?"GET":method.getSelectedItemPosition()==2?"POST":method.getSelectedItemPosition()==3?"WS↑":method.getSelectedItemPosition()==4?"WS↓":"";filterMedia=media.isChecked();prefs.edit().putString("filter_uid",filterUid).putString("filter_url",filterUrl).putString("filter_text",filterText).putString("filter_exclude_hosts",filterExcludedHosts).putString("filter_transport",filterTransport).putString("filter_method",filterMethod).putBoolean("filter_media",filterMedia).apply();dialog.dismiss();renderRequests();}));dialog.show();
    }
    private String normalizeExcludedHosts(String raw){LinkedHashSet<String> hosts=new LinkedHashSet<>();for(String item:raw.split("[\\s,;，；]+")){String host=item.trim().toLowerCase(Locale.ROOT);if(host.startsWith("*."))host=host.substring(2);while(host.startsWith("."))host=host.substring(1);while(host.endsWith("."))host=host.substring(0,host.length()-1);if(!host.isEmpty())hosts.add(host);}return TextUtils.join("\n",hosts);}
    private String filterSummary(){StringBuilder b=new StringBuilder();if(!filterUid.isEmpty())b.append(" · UID ").append(filterUid);if(!filterUrl.isEmpty())b.append(" · URL ").append(filterUrl);if(!filterText.isEmpty())b.append(" · 全文 ").append(filterText);if(!filterTransport.isEmpty())b.append(" · ").append(filterTransport.toUpperCase(Locale.ROOT));if(!filterMethod.isEmpty())b.append(" · ").append(filterMethod);if(filterMedia)b.append(" · 媒体");if(!filterExcludedHosts.isEmpty())b.append(" · 排除 ").append(filterExcludedHosts.split("\\n").length).append(" 个 Host");return b.toString();}
    private String requestIcon(Entry e){switch(e.mediaType){case "video":return "▶";case "audio":return "♫";case "image":return "▣";case "playlist":return "≋";case "media":return "◆";default:return e.method.equalsIgnoreCase("GET")?"↓":e.method.equalsIgnoreCase("POST")?"↑":"↔";}}
    private ArrayList<String> requestArgs(int pageSize,long before,Long after){
        ArrayList<String> args=new ArrayList<>();args.add("list-json");args.add("-limit");args.add(String.valueOf(pageSize));if(before>0){args.add("-before");args.add(String.valueOf(before));}if(after!=null){args.add("-after");args.add(String.valueOf(after));args.add("-watermark");}
        if(!allRequests){LinkedHashSet<String> filters=new LinkedHashSet<>();for(Target t:targets.values()){filters.add(t.key);if(t.uid>=0)filters.add("uid:"+t.uid);}for(String target:filters){args.add("-target");args.add(target);}}
        if(!filterExcludedHosts.isEmpty())for(String host:filterExcludedHosts.split("\\n")){if(!host.isEmpty()){args.add("-exclude-host");args.add(host);}}if(!filterUid.isEmpty()){args.add("-uid");args.add(filterUid);}if(!filterUrl.isEmpty()){args.add("-url");args.add(filterUrl);}if(!filterText.isEmpty()){args.add("-text");args.add(filterText);}if(!filterTransport.isEmpty()){args.add("-transport");args.add(filterTransport);}if(!filterMethod.isEmpty()){args.add("-method");args.add(filterMethod);}if(filterMedia)args.add("-media");return args;
    }
    private void renderRequests(){
        stopLive();content.removeAllViews();final int generation=++requestGeneration;entries.clear();
        LinearLayout dbRow=row();TextView dbName=text(databaseLabel(),13,INK);dbName.setSingleLine(true);dbName.setEllipsize(TextUtils.TruncateAt.END);dbRow.addView(dbName,new LinearLayout.LayoutParams(0,-2,1));TextView liveButton=text("",13,BLUE);liveButton.setPadding(dp(8),dp(6),dp(8),dp(6));dbRow.addView(liveButton);TextView choose=text("选择数据库",13,BLUE);choose.setPadding(dp(8),dp(4),0,dp(4));choose.setOnClickListener(v->chooseDatabase());dbRow.addView(choose);content.addView(dbRow);space(content,6);
        TextView info=text("请求记录",14,MUTED);content.addView(info);space(content,6);
        LinearLayout actions=row();Button refresh=button("刷新"),filter=button(allRequests?"筛选所选":"查看全部"),criteria=button("条件"),events=button("错误事件");actions.addView(refresh,weight());actions.addView(filter,weight());actions.addView(criteria,weight());actions.addView(events,weight());content.addView(actions);space(content,6);
        ListView list=new ListView(this);list.setBackground(bg(Color.WHITE,20));list.setClipToOutline(true);list.setDividerHeight(dp(1));content.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        TextView footer=text("加载中…",13,MUTED);footer.setGravity(Gravity.CENTER);footer.setPadding(dp(12),dp(14),dp(12),dp(14));list.addFooterView(footer,null,false);
        ArrayAdapter<Entry> adapter=new ArrayAdapter<Entry>(this,android.R.layout.simple_list_item_1,entries){@Override public View getView(int pos,View cv,android.view.ViewGroup parent){
            LinearLayout row;ImageView icon;TextView title,url,time,kind,uidCorner;
            if(cv instanceof LinearLayout&&cv.getTag() instanceof TextView[]&&((TextView[])cv.getTag()).length==5){row=(LinearLayout)cv;TextView[] views=(TextView[])row.getTag();title=views[0];url=views[1];time=views[2];kind=views[3];uidCorner=views[4];icon=(ImageView)row.getChildAt(0);}
            else{row=row();row.setPadding(dp(14),dp(10),dp(14),dp(10));icon=new ImageView(MainActivity.this);row.addView(icon,new LinearLayout.LayoutParams(dp(38),dp(38)));LinearLayout texts=column();LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.leftMargin=dp(12);row.addView(texts,lp);
                LinearLayout top=row();kind=text("",16,BLUE);kind.setGravity(Gravity.CENTER);top.addView(kind,new LinearLayout.LayoutParams(dp(22),-2));title=text("",14,INK);title.setTypeface(null,Typeface.BOLD);title.setSingleLine(true);title.setEllipsize(TextUtils.TruncateAt.END);top.addView(title,new LinearLayout.LayoutParams(0,-2,1));time=text("",11,MUTED);time.setSingleLine(true);time.setPadding(dp(7),0,0,0);top.addView(time);texts.addView(top);space(texts,3);
                LinearLayout bottom=row();url=text("",12,MUTED);url.setSingleLine(true);url.setEllipsize(TextUtils.TruncateAt.END);bottom.addView(url,new LinearLayout.LayoutParams(0,-2,1));uidCorner=text("",11,MUTED);uidCorner.setSingleLine(true);uidCorner.setGravity(Gravity.END);uidCorner.setPadding(dp(7),0,0,0);bottom.addView(uidCorner);texts.addView(bottom);row.setTag(new TextView[]{title,url,time,kind,uidCorner});}
            Entry e=getItem(pos);icon.setImageDrawable(sourceIcon(e));kind.setText(requestIcon(e));kind.setTextColor(e.mediaType.isEmpty()?MUTED:BLUE);title.setText("#"+e.id+"  ·  "+sourceName(e)+"  ·  "+e.transport.toUpperCase(Locale.ROOT)+"  ·  "+(e.status.equals("0")?"错误":e.status));time.setText(formatTime(e.time,"MM-dd HH:mm:ss"));url.setText(e.method+"  "+e.url);uidCorner.setText(e.uid>=0?"UID "+e.uid:"UID 未知");return row;
        }};list.setAdapter(adapter);
        final int pageSize=100;final long[] before={0},latest={0};final boolean[] loading={false},done={false},initialized={false},retry={false},polling={false};final Runnable[] loadMore=new Runnable[1],poll={new Runnable(){public void run(){}}};
        Runnable paintLive=()->{if(!selectedDatabase.equals("current")){liveButton.setText("历史");liveButton.setTextColor(MUTED);liveButton.setEnabled(false);liveButton.setContentDescription("历史数据库不实时同步");}else{liveButton.setEnabled(true);liveButton.setText(requestsLive?"▶ 实时":"⏸ 手动");liveButton.setTextColor(requestsLive?GREEN:MUTED);liveButton.setContentDescription(requestsLive?"当前实时同步，点击暂停":"当前手动刷新，点击恢复实时同步");}};paintLive.run();
        liveButton.setOnClickListener(v->{requestsLive=!requestsLive;prefs.edit().putBoolean("requests_live",requestsLive).apply();paintLive.run();if(requestsLive){list.setSelection(0);scheduleLive(0);}else if(liveTask!=null)main.removeCallbacks(liveTask);});
        loadMore[0]=()->{
            if(loading[0]||done[0]||generation!=requestGeneration)return;
            if(!allRequests&&targets.isEmpty()){info.setText("请先选择目标");footer.setText("没有可筛选的目标");done[0]=true;return;}
            ArrayList<String> args=requestArgs(pageSize,before[0],null);
            loading[0]=true;footer.setText(filterText.isEmpty()?"加载中…":"正在更新全文索引并搜索…");
            rootCall(args.toArray(new String[0]),(out,err)->{
                if(!tab.equals("requests")||generation!=requestGeneration)return;loading[0]=false;initialized[0]=true;
                if(err!=null){retry[0]=true;footer.setText("加载失败，点击重试");info.setText("读取失败："+err.getMessage());return;}
                int count=0;long last=before[0],first=0;for(String line:out.split("\\r?\\n")){if(line.trim().isEmpty())continue;try{Entry e=new Entry(new JSONObject(line));long id=Long.parseLong(e.id);if(id<=0)continue;if(first==0)first=id;entries.add(e);last=id;count++;}catch(Exception ignored){}}
                if(latest[0]==0&&first>0)latest[0]=first;before[0]=last;done[0]=count<pageSize;retry[0]=false;adapter.notifyDataSetChanged();info.setText((allRequests?"全部应用和 UID":"已选目标")+"  ·  已加载 "+entries.size()+" 条"+filterSummary());footer.setText(done[0]?"没有更多记录":"上拉或点击加载更多");
            });
        };
        footer.setOnClickListener(v->{retry[0]=false;loadMore[0].run();});
        list.setOnScrollListener(new AbsListView.OnScrollListener(){public void onScrollStateChanged(AbsListView view,int state){}public void onScroll(AbsListView view,int first,int visible,int total){if(initialized[0]&&!retry[0]&&!loading[0]&&!done[0]&&visible>0&&first+visible>=total-2)loadMore[0].run();}});
        poll[0]=new Runnable(){public void run(){
            if(generation!=requestGeneration||!uiResumed||!requestsLive||!tab.equals("requests")||!selectedDatabase.equals("current"))return;
            if(!initialized[0]||loading[0]||polling[0]||retry[0]){scheduleLive(2000);return;}
            if(!allRequests&&targets.isEmpty()){scheduleLive(2000);return;}
            polling[0]=true;ArrayList<String> args=requestArgs(pageSize,0,Long.valueOf(latest[0]));
            rootCall(args.toArray(new String[0]),(out,err)->{
                polling[0]=false;if(generation!=requestGeneration||!uiResumed||!requestsLive||!tab.equals("requests"))return;
                if(err!=null){requestsLive=false;prefs.edit().putBoolean("requests_live",false).apply();paintLive.run();info.setText("实时同步已暂停："+err.getMessage());return;}
                ArrayList<Entry> fresh=new ArrayList<>();long newest=latest[0],watermark=0;for(String line:out.split("\\r?\\n")){if(line.trim().isEmpty())continue;try{JSONObject obj=new JSONObject(line);if(obj.has("_watermark")){watermark=obj.optLong("_watermark",0);continue;}Entry entry=new Entry(obj);long id=Long.parseLong(entry.id);if(id<=newest)continue;fresh.add(entry);newest=id;}catch(Exception ignored){}}
                if(fresh.size()<pageSize&&watermark>newest)newest=watermark;latest[0]=newest;
                if(!fresh.isEmpty()){for(Entry entry:fresh)entries.add(0,entry);adapter.notifyDataSetChanged();info.setText((allRequests?"全部应用和 UID":"已选目标")+"  ·  已加载 "+entries.size()+" 条"+filterSummary());list.setSelection(0);}
                scheduleLive(fresh.size()==pageSize?0:2000);
            });
        }};liveTask=poll[0];scheduleLive(0);
        refresh.setOnClickListener(v->renderRequests());criteria.setOnClickListener(v->filterDialog());filter.setOnClickListener(v->{allRequests=!allRequests;renderRequests();});events.setOnClickListener(v->rootCall(new String[]{"events"},(o,e)->new AlertDialog.Builder(this).setTitle("最近错误事件（全部目标）").setMessage(e==null?(o.trim().isEmpty()?"暂无事件":o):e.getMessage()).setPositiveButton("关闭",null).show()));list.setOnItemClickListener((p,v,pos,id)->{if(pos<entries.size())showExchange(entries.get(pos));});list.setOnItemLongClickListener((p,v,pos,id)->{if(pos<entries.size())quickRewrite(entries.get(pos));return true;});loadMore[0].run();
    }
    private void renderSettings(){content.removeAllViews();ScrollView sc=new ScrollView(this);content.addView(sc,new LinearLayout.LayoutParams(-1,-1));LinearLayout box=column();sc.addView(box);addStatusCard(box);
        LinearLayout config=card();TextView lead=text("后端管理",18,INK);lead.setTypeface(null,Typeface.BOLD);config.addView(lead);space(config,7);TextView desc=text("后端内置于应用。版本较旧时必须更新；更新会保留数据并尝试恢复之前运行的目标。",13,MUTED);config.addView(desc);space(config,10);
        EditText directory=new EditText(this);directory.setSingleLine(true);directory.setTextSize(14);directory.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);directory.setHint("后端安装目录");directory.setTextColor(INK);directory.setHintTextColor(MUTED);directory.setBackground(bg(Color.rgb(242,243,245),14));directory.setPadding(dp(14),dp(11),dp(14),dp(11));directory.setText(prefs.getString("install_dir","/data/adb/tpcapture"));config.addView(directory);space(config,5);
        EditText data=new EditText(this);data.setSingleLine(true);data.setTextSize(14);data.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);data.setHint("数据目录（留空使用默认目录）");data.setTextColor(INK);data.setHintTextColor(MUTED);data.setBackground(bg(Color.rgb(242,243,245),14));data.setPadding(dp(14),dp(11),dp(14),dp(11));data.setText(prefs.getString("data",""));config.addView(data);space(config,10);
        TextView status=text("",13,MUTED);config.addView(status);space(config,4);
        Runnable saveValues=()->{String dir=directory.getText().toString().trim();while(dir.endsWith("/")&&dir.length()>1)dir=dir.substring(0,dir.length()-1);prefs.edit().putString("install_dir",dir).putString("data",data.getText().toString().trim()).apply();};
        Button install=button("安装 / 更新后端");config.addView(install);install.setOnClickListener(v->{String dir=directory.getText().toString().trim();if(!dir.startsWith("/")||dir.equals("/")){toast("请填写有效的后端目录");return;}saveValues.run();if(!switching.compareAndSet(false,true))return;status.setText("正在安装，请授予 root 权限…");summaryLine="正在安装后端…";paintState();installBackend((out,err)->{switching.set(false);status.setText(err==null?out.trim():"安装失败："+err.getMessage());refreshState();});});
        space(config,6);Button remove=button("移除后端");remove.setTextColor(Color.rgb(204,61,65));config.addView(remove);remove.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("移除后端？").setMessage("将停止当前抓包并移除后端程序和脚本，数据库与日志会保留。确认继续？").setNegativeButton("取消",null).setPositiveButton("移除",(d,w)->{String dir=directory.getText().toString().trim();if(!dir.startsWith("/")||dir.equals("/")){toast("请填写有效的后端目录");return;}saveValues.run();if(!switching.compareAndSet(false,true))return;status.setText("正在移除…");removeBackend((out,err)->{switching.set(false);status.setText(err==null?out.trim():"移除失败："+err.getMessage());refreshState();});}).show());
        space(config,6);Button cleanTCP=button("清理 TPROXY 残留规则");config.addView(cleanTCP);cleanTCP.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("清理 TPROXY 规则？").setMessage("将停止统一后端并移除它自己的规则和路由，不会清空其他模块的规则。").setNegativeButton("取消",null).setPositiveButton("清理",(d,w)->{saveValues.run();if(!switching.compareAndSet(false,true))return;status.setText("正在清理…");rootCallProgress(new String[]{"cleanup"},line->status.setText(line),(out,err)->{switching.set(false);status.setText(err==null?"清理完成":"清理未完成："+shortError(err));refreshState();});}).show());
        space(config,6);Button repair=button("修复残留操作锁");config.addView(repair);repair.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("修复残留操作锁？").setMessage("请先确认没有其他启停、重载或会话切换正在执行。此操作只处理操作锁，不会删除数据库或网络规则。若检测到正在运行的启停任务会拒绝清理。").setNegativeButton("取消",null).setPositiveButton("修复",(d,w)->{saveValues.run();if(!switching.compareAndSet(false,true))return;status.setText("正在检查操作锁…");repairCaptureLock((out,err)->{switching.set(false);status.setText(err==null?out.trim():"修复未完成："+shortError(err));if(err==null)lastPromptedVersion=-1;refreshState();});}).show());
        space(config,6);Button save=button("保存路径");config.addView(save);save.setOnClickListener(v->{saveValues.run();toast("已保存");refreshState();refreshAutostartStatus();refreshMcpStatus();});box.addView(config);space(box,10);addMcpCard(box,saveValues);addAutostartCard(box,saveValues);addCertificateCard(box,saveValues);
        addRewriteCard(box,saveValues);
        LinearLayout about=card();TextView aboutTitle=text("关于",18,INK);aboutTitle.setTypeface(null,Typeface.BOLD);about.addView(aboutTitle);space(about,6);about.addView(text("TPCapture "+versionLabel(),13,INK));space(about,4);about.addView(text("已 root 设备上的应用流量抓包与改写工具。通过 TPROXY 透明代理记录并改写选定应用的 HTTP/1.1、HTTP/2 与 HTTP/3 请求。",13,MUTED));space(about,10);TextView repo=text("github.com/EU65/TPCapture",13,BLUE);repo.setPadding(0,dp(2),0,dp(2));repo.setOnClickListener(v->{try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/EU65/TPCapture")));}catch(Exception e){toast("无法打开浏览器");}});about.addView(repo);space(about,8);about.addView(text("作者：GPT 6.1 Terra + DeepSeek V4.1 Flash",12,MUTED));box.addView(about);
    }
    private static byte[] bounded(InputStream in,int max)throws IOException{try(InputStream stream=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=stream.read(b))!=-1){if(out.size()+n>max)throw new IOException("输出超出限制");out.write(b,0,n);}return out.toByteArray();}}
    private void quickRewrite(Entry entry){
        String[] items={"打开详情","以此为模板新建规则","拦截此 URL","拦截此主机"};
        new AlertDialog.Builder(this).setTitle("#"+entry.id+"  "+entry.method).setItems(items,(d,which)->{
            if(which==0){showExchange(entry);return;}
            Intent i=new Intent(this,local.appcapture.viewer.ui.RewriteActivity.class);
            i.putExtra("install_dir",installDir());
            i.putExtra("data_dir",prefs.getString("data",""));
            i.putExtra("seed_host",entry.host);
            i.putExtra("seed_path",entry.path);
            i.putExtra("seed_uid",entry.uid);
            i.putExtra("seed_method",entry.method);
            i.putExtra("seed",which==1?"edit":which==2?"block-url":"block-host");
            startActivity(i);
        }).show();
    }
    private void showExchange(Entry entry){Intent i=new Intent(this,RequestDetailActivity.class);i.putExtra("id",entry.id);i.putExtra("source_metadata",sourceMetadata(entry));i.putExtra("timestamp_ms",entry.time);i.putExtra("database",selectedDatabase);i.putExtra("install_dir",installDir());i.putExtra("data_dir",prefs.getString("data",""));i.putExtra("backend_version",bundledVersion);startActivity(i);}
}
