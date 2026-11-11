package local.appcapture.viewer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TargetPickerActivity extends Activity {
    private static final int BG=Color.rgb(243,244,248),INK=Color.rgb(30,32,38),MUTED=Color.rgb(120,125,138),BLUE=Color.rgb(0,112,235),AMBER=Color.rgb(190,112,16);
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private final LinkedHashMap<String,Target> targets=new LinkedHashMap<>();
    private final ArrayList<App> apps=new ArrayList<>();
    private final ArrayList<Row> visible=new ArrayList<>();
    private final HashSet<String> marked=new HashSet<>();
    private final HashMap<String,Drawable> icons=new HashMap<>();
    private String tab="apps";
    private boolean appsLoaded=false;
    private TextView summary,empty,tabApps,tabSelected;
    private EditText search;
    private LinearLayout bottom;
    private Button delete;
    private ListView list;
    private final RowsAdapter adapter=new RowsAdapter();

    private static class Target {String key,label;int uid;Target(String k,String l,int u){key=k;label=l;uid=u;}boolean manual(){return key.startsWith("uid:");}}
    private static class App {String pkg,label;int uid;boolean system;App(String p,String l,int u,boolean s){pkg=p;label=l;uid=u;system=s;}}
    private static class Row {String key,label,description;int uid;boolean manual;Row(String k,String l,String d,int u,boolean m){key=k;label=l;description=d;uid=u;manual=m;}}
    private static class Holder {FrameLayout iconSlot;ImageView icon;TextView badge,title,description;CheckBox check;}
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    private GradientDrawable bg(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private TextView text(String s,int size,int color){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private void gap(LinearLayout parent,int height){parent.addView(new View(this),new LinearLayout.LayoutParams(1,dp(height)));}
    private Button button(String label){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(13);b.setTextColor(BLUE);b.setMinHeight(dp(44));b.setBackground(bg(Color.rgb(235,241,251),14));b.setStateListAnimator(null);return b;}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
    @Override public void onCreate(Bundle state){super.onCreate(state);prefs=getSharedPreferences("settings",MODE_PRIVATE);readTargets();
        LinearLayout root=column();root.setBackgroundColor(BG);root.setPadding(dp(16),0,dp(16),0);root.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(dp(16),insets.getSystemWindowInsetTop()+dp(6),dp(16),insets.getSystemWindowInsetBottom()+dp(8));return insets;});
        LinearLayout bar=row();TextView back=text("‹",34,BLUE);back.setPadding(0,0,dp(16),0);back.setOnClickListener(v->finish());bar.addView(back);TextView title=text("选择目标",24,INK);title.setTypeface(null,Typeface.BOLD);bar.addView(title,new LinearLayout.LayoutParams(0,-2,1));TextView done=text("完成",15,BLUE);done.setPadding(dp(12),dp(10),dp(4),dp(10));done.setOnClickListener(v->finish());bar.addView(done);root.addView(bar);
        summary=text("",13,MUTED);root.addView(summary);gap(root,10);
        LinearLayout nav=row();nav.setBackground(bg(Color.rgb(230,232,238),16));nav.setPadding(dp(3),dp(3),dp(3),dp(3));tabApps=text("应用",14,INK);tabSelected=text("已添加",14,MUTED);for(TextView t:new TextView[]{tabApps,tabSelected}){t.setGravity(Gravity.CENTER);nav.addView(t,new LinearLayout.LayoutParams(0,dp(40),1));}tabApps.setOnClickListener(v->setTab("apps"));tabSelected.setOnClickListener(v->setTab("selected"));root.addView(nav);gap(root,10);
        LinearLayout tools=row();search=new EditText(this);search.setSingleLine(true);search.setTextSize(14);search.setHint("搜索应用、包名或 UID");search.setBackground(bg(Color.WHITE,14));search.setPadding(dp(14),0,dp(12),0);tools.addView(search,new LinearLayout.LayoutParams(0,dp(46),1));Button add=button("添加 UID");LinearLayout.LayoutParams addParams=new LinearLayout.LayoutParams(dp(96),dp(46));addParams.leftMargin=dp(8);tools.addView(add,addParams);add.setOnClickListener(v->addUid());root.addView(tools);gap(root,10);
        FrameLayout body=new FrameLayout(this);body.setBackground(bg(Color.WHITE,20));body.setClipToOutline(true);list=new ListView(this);list.setDivider(new ColorDrawable(Color.rgb(238,239,243)));list.setDividerHeight(dp(1));list.setPadding(0,dp(2),0,dp(2));list.setClipToPadding(false);body.addView(list,new FrameLayout.LayoutParams(-1,-1));empty=text("",14,MUTED);empty.setGravity(Gravity.CENTER);body.addView(empty,new FrameLayout.LayoutParams(-1,-1));list.setEmptyView(empty);root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        bottom=row();bottom.setPadding(0,dp(10),0,0);Button all=button("全选当前"),clear=button("清空全部");delete=button("删除所选");delete.setTextColor(Color.rgb(195,52,58));clear.setTextColor(Color.rgb(195,52,58));bottom.addView(all,new LinearLayout.LayoutParams(0,dp(46),1));LinearLayout.LayoutParams mid=new LinearLayout.LayoutParams(0,dp(46),1);mid.leftMargin=dp(6);bottom.addView(delete,mid);LinearLayout.LayoutParams end=new LinearLayout.LayoutParams(0,dp(46),1);end.leftMargin=dp(6);bottom.addView(clear,end);root.addView(bottom);
        all.setOnClickListener(v->{if(visible.isEmpty())return;boolean allMarked=true;for(Row r:visible)if(!marked.contains(r.key)){allMarked=false;break;}for(Row r:visible)if(allMarked)marked.remove(r.key);else marked.add(r.key);updateSummary();adapter.notifyDataSetChanged();});delete.setOnClickListener(v->deleteMarked());clear.setOnClickListener(v->clearAll());list.setAdapter(adapter);
        list.setOnItemClickListener((p,v,pos,id)->{Row r=visible.get(pos);if(tab.equals("apps")){if(targets.containsKey(r.key))targets.remove(r.key);else targets.put(r.key,new Target(r.key,r.label,r.uid));saveTargets();rebuild();}else{if(!marked.add(r.key))marked.remove(r.key);updateSummary();adapter.notifyDataSetChanged();}});
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){rebuild();}public void afterTextChanged(Editable e){}});
        setContentView(root);setTab("apps");loadApps();
    }
    @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}
    private void readTargets(){targets.clear();try{JSONArray a=new JSONArray(prefs.getString("targets","[]"));for(int i=0;i<a.length();i++){JSONObject j=a.getJSONObject(i);String k=j.getString("key");targets.put(k,new Target(k,j.optString("label",k),j.optInt("uid",-1)));}}catch(Exception ignored){}}
    private void saveTargets(){JSONArray a=new JSONArray();for(Target t:targets.values()){JSONObject j=new JSONObject();try{j.put("key",t.key);j.put("label",t.label);j.put("uid",t.uid);a.put(j);}catch(Exception ignored){}}prefs.edit().putString("targets",a.toString()).apply();}
    private void loadApps(){worker.execute(()->{ArrayList<App> found=new ArrayList<>();PackageManager pm=getPackageManager();for(ApplicationInfo a:pm.getInstalledApplications(0)){try{found.add(new App(a.packageName,String.valueOf(pm.getApplicationLabel(a)),a.uid,(a.flags&ApplicationInfo.FLAG_SYSTEM)!=0));}catch(Exception ignored){}}Collator collator=Collator.getInstance(Locale.getDefault());Collections.sort(found,(a,b)->collator.compare(a.label,b.label));main.post(()->{if(isFinishing()||isDestroyed())return;apps.clear();apps.addAll(found);appsLoaded=true;boolean changed=false;for(App a:found){Target t=targets.get(a.pkg);if(t!=null&&(t.uid!=a.uid||!t.label.equals(a.label))){t.uid=a.uid;t.label=a.label;changed=true;}}if(changed)saveTargets();rebuild();});});}
    private void setTab(String value){tab=value;marked.clear();tabApps.setBackground(value.equals("apps")?bg(Color.WHITE,13):null);tabSelected.setBackground(value.equals("selected")?bg(Color.WHITE,13):null);tabApps.setTextColor(value.equals("apps")?INK:MUTED);tabSelected.setTextColor(value.equals("selected")?INK:MUTED);tabApps.setTypeface(null,value.equals("apps")?Typeface.BOLD:Typeface.NORMAL);tabSelected.setTypeface(null,value.equals("selected")?Typeface.BOLD:Typeface.NORMAL);bottom.setVisibility(value.equals("selected")?View.VISIBLE:View.GONE);rebuild();}
    private boolean matches(String q,String label,String key,int uid){return q.isEmpty()||label.toLowerCase(Locale.ROOT).contains(q)||key.toLowerCase(Locale.ROOT).contains(q)||String.valueOf(uid).contains(q);}
    private void rebuild(){if(search==null||list==null)return;String q=search.getText().toString().trim().toLowerCase(Locale.ROOT);visible.clear();ArrayList<Target> manual=new ArrayList<>(),selectedApps=new ArrayList<>();for(Target t:targets.values())if(t.manual())manual.add(t);else selectedApps.add(t);Collections.sort(manual,(a,b)->Integer.compare(a.uid,b.uid));for(Target t:manual)if(matches(q,t.label,t.key,t.uid))visible.add(new Row(t.key,"UID "+t.uid,"手动添加 · 按 UID 捕获",t.uid,true));
        if(tab.equals("apps")){for(App a:apps)if(matches(q,a.label,a.pkg,a.uid))visible.add(new Row(a.pkg,a.label,a.pkg+"  ·  UID "+a.uid+(a.system?"  ·  系统":""),a.uid,false));}
        else{Collator col=Collator.getInstance(Locale.getDefault());Collections.sort(selectedApps,(a,b)->col.compare(a.label,b.label));for(Target t:selectedApps)if(matches(q,t.label,t.key,t.uid))visible.add(new Row(t.key,t.label,t.key+"  ·  UID "+t.uid,t.uid,false));}
        empty.setText(tab.equals("apps")&&!appsLoaded?"正在读取应用…":tab.equals("apps")?"没有匹配的应用或 UID":"没有匹配的已添加目标");updateSummary();adapter.notifyDataSetChanged();}
    private void updateSummary(){int manual=0;for(Target t:targets.values())if(t.manual())manual++;summary.setText("已添加 "+targets.size()+" 个目标"+(manual>0?" · 手动 UID "+manual:""));tabSelected.setText("已添加  "+targets.size());if(delete!=null)delete.setText(marked.isEmpty()?"删除所选":"删除所选 ("+marked.size()+")");}
    private void addUid(){EditText input=new EditText(this);input.setSingleLine(true);input.setInputType(InputType.TYPE_CLASS_NUMBER);input.setHint("输入非负 UID，例如 0 或 10234");LinearLayout wrap=column();wrap.setPadding(dp(22),0,dp(22),0);wrap.addView(input);AlertDialog dialog=new AlertDialog.Builder(this).setTitle("添加 UID").setView(wrap).setNegativeButton("取消",null).setPositiveButton("添加",null).create();dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{long uid=Long.parseLong(input.getText().toString().trim());if(uid<0||uid>Integer.MAX_VALUE)throw new NumberFormatException();String key="uid:"+uid;if(targets.containsKey(key)){input.setError("该 UID 已添加");return;}targets.put(key,new Target(key,"UID "+uid,(int)uid));saveTargets();dialog.dismiss();rebuild();}catch(NumberFormatException e){input.setError("请输入有效的非负 UID");}}));dialog.show();}
    private void deleteMarked(){if(marked.isEmpty()){toast("请先选择要删除的目标");return;}int count=marked.size();new AlertDialog.Builder(this).setTitle("删除所选目标？").setMessage("将从列表移除 "+count+" 个目标。若后端正在运行，需要重载后才会应用。 ").setNegativeButton("取消",null).setPositiveButton("删除",(d,w)->{for(String key:marked)targets.remove(key);marked.clear();saveTargets();rebuild();}).show();}
    private void clearAll(){if(targets.isEmpty()){toast("目标列表已经为空");return;}new AlertDialog.Builder(this).setTitle("清空全部目标？").setMessage("将移除全部 "+targets.size()+" 个目标。若后端正在运行，需要停止或重载后才会应用。").setNegativeButton("取消",null).setPositiveButton("清空",(d,w)->{targets.clear();marked.clear();saveTargets();rebuild();}).show();}
    private Drawable appIcon(String pkg){if(icons.containsKey(pkg))return icons.get(pkg);Drawable d;try{d=getPackageManager().getApplicationIcon(pkg);}catch(Exception e){d=getDrawable(android.R.drawable.sym_def_app_icon);}icons.put(pkg,d);return d;}
    private class RowsAdapter extends BaseAdapter {
        public int getCount(){return visible.size();}public Object getItem(int pos){return visible.get(pos);}public long getItemId(int pos){return pos;}
        public View getView(int pos,View convert,ViewGroup parent){Holder h;LinearLayout row;
            if(convert instanceof LinearLayout&&convert.getTag() instanceof Holder){row=(LinearLayout)convert;h=(Holder)row.getTag();}
            else{row=TargetPickerActivity.this.row();row.setPadding(dp(14),dp(8),dp(12),dp(8));row.setMinimumHeight(dp(66));h=new Holder();h.iconSlot=new FrameLayout(TargetPickerActivity.this);row.addView(h.iconSlot,new LinearLayout.LayoutParams(dp(40),dp(40)));h.icon=new ImageView(TargetPickerActivity.this);h.icon.setScaleType(ImageView.ScaleType.FIT_CENTER);h.iconSlot.addView(h.icon,new FrameLayout.LayoutParams(-1,-1));h.badge=text("UID",11,AMBER);h.badge.setTypeface(null,Typeface.BOLD);h.badge.setGravity(Gravity.CENTER);h.badge.setBackground(bg(Color.rgb(255,241,217),13));h.iconSlot.addView(h.badge,new FrameLayout.LayoutParams(-1,-1));LinearLayout labels=column();LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.leftMargin=dp(12);row.addView(labels,lp);h.title=text("",15,INK);h.title.setTypeface(null,Typeface.BOLD);h.title.setSingleLine(true);h.title.setEllipsize(TextUtils.TruncateAt.END);h.description=text("",12,MUTED);h.description.setSingleLine(true);h.description.setEllipsize(TextUtils.TruncateAt.END);labels.addView(h.title);gap(labels,3);labels.addView(h.description);h.check=new CheckBox(TargetPickerActivity.this);h.check.setClickable(false);h.check.setFocusable(false);h.check.setButtonTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{BLUE,Color.rgb(180,184,193)}));row.addView(h.check,new LinearLayout.LayoutParams(dp(42),dp(48)));row.setTag(h);}
            Row item=visible.get(pos);h.badge.setVisibility(item.manual?View.VISIBLE:View.GONE);h.icon.setVisibility(item.manual?View.GONE:View.VISIBLE);if(!item.manual)h.icon.setImageDrawable(appIcon(item.key));h.title.setText(item.label);h.title.setTextColor(item.manual?AMBER:INK);h.description.setText(item.description);h.description.setTextColor(item.manual?AMBER:MUTED);h.check.setChecked(tab.equals("apps")?targets.containsKey(item.key):marked.contains(item.key));return row;
        }
    }
}
