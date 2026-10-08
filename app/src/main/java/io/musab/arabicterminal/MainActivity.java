package io.musab.arabicterminal;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** واجهة الطرفية العربية: جلسات PTY متعددة، شاشة ألوان، وأوامر تعمل محليًا. */
public final class MainActivity extends Activity {
    private static final int BG=0xFF0B1422, PANEL=0xFF1C2A3C,
            ACCENT=0xFF54DCAD, FG=0xFFECF3FC, MUTED=0xFFB1C3D5;
    private static final int FILE_PICKER=2026, MAX_SESSIONS=6;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final List<Session> sessions=new ArrayList<>();
    private Session active;
    private TextView console, status;
    private ScrollView viewport;
    private EditText entry;
    private Button modeButton;
    private int rows=24, columns=75, nextId=1;
    private boolean rawMode;

    private static final class Session {
        final Object mutex=new Object(), pendingLock=new Object();
        final StringBuilder pending=new StringBuilder();
        final TerminalScreen display=new TerminalScreen();
        final long handle;
        final String name;
        final boolean root;
        volatile boolean finished;
        boolean flushScheduled;
        Session(long handle,String name,boolean root){
            this.handle=handle;this.name=name;this.root=root;
        }
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        buildUi();
        createSession(false);
    }
    private int dp(float n){return (int)(getResources().getDisplayMetrics().density*n+0.5f);}
    private TextView label(String s,int size,int color){
        TextView v=new TextView(this);
        v.setText(s);v.setTextColor(color);v.setTextSize(size);
        v.setGravity(Gravity.CENTER_VERTICAL|Gravity.RIGHT);
        return v;
    }
    private Button button(String s,View.OnClickListener action){
        Button b=new Button(this);
        b.setAllCaps(false);b.setText(s);b.setTextSize(11);b.setTextColor(FG);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(PANEL));
        b.setMinWidth(0);b.setMinimumWidth(0);b.setOnClickListener(action);
        return b;
    }
    private LinearLayout row(){
        LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);return l;
    }
    private void key(LinearLayout container,String title,String control){
        container.addView(button(title,v->write(active,control)));
    }
    private void buildUi(){
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setPadding(dp(8),dp(6),dp(8),dp(6));
        root.setBackgroundColor(BG);
        setContentView(root);
        TextView heading=label("◀  الطرفية العربية",22,ACCENT);
        heading.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        root.addView(heading);
        status=label("جلسة محلية — جارٍ الاتصال",12,MUTED);
        root.addView(status);
        HorizontalScrollView menuScroll=new HorizontalScrollView(this);
        menuScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout menu=row();
        menu.addView(button("جلسة +",v->createSession(false)));
        menu.addView(button("التطبيقات",v->notice(PhoneController.run(this,ArabicCommandRouter.parse("اعرض التطبيقات")))));
        menu.addView(button("تحكم الهاتف",v->phoneControlSetup()));
        menu.addView(button("السابقة",v->previousSession()));
        menu.addView(button("إغلاق",v->closeActive()));
        menu.addView(button("الجذر",v->confirmRoot()));
        menu.addView(button("استيراد ملف",v->selectDocument()));
        menu.addView(button("نسخ",v->copyText()));
        menu.addView(button("مساعدة",v->help()));
        menuScroll.addView(menu);root.addView(menuScroll);
        viewport=new ScrollView(this);
        viewport.setFillViewport(true);
        viewport.setBackgroundColor(0xFF050D18);
        console=label("",14,FG);
        console.setTypeface(Typeface.MONOSPACE);
        console.setGravity(Gravity.LEFT|Gravity.TOP);
        console.setTextDirection(View.TEXT_DIRECTION_LTR);
        console.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        console.setPadding(dp(8),dp(8),dp(8),dp(8));
        console.setTextIsSelectable(true);
        viewport.addView(console);
        LinearLayout.LayoutParams area=new LinearLayout.LayoutParams(-1,0,1);
        area.topMargin=dp(7);root.addView(viewport,area);
        viewport.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,oright,ob)->{
            float charWidth=Math.max(7f,console.getPaint().measureText("M"));
            int newColumns=Math.max(20,Math.min(200,(int)((r-l-dp(20))/charWidth)));
            int newRows=Math.max(8,Math.min(90,(b-t-dp(20))/console.getLineHeight()));
            if(newColumns==columns && newRows==rows)return;
            columns=newColumns;rows=newRows;
            for(Session s:sessions){
                s.display.resize(rows,columns);
                synchronized(s.mutex){
                    if(!s.finished)NativePty.resize(s.handle,rows,columns);
                }
            }
            render();
        });
        HorizontalScrollView keysScroll=new HorizontalScrollView(this);
        keysScroll.setHorizontalScrollBarEnabled(false);
        keysScroll.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        LinearLayout keys=row();keys.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        key(keys,"هروب","\u001b");key(keys,"جدولة","\t");
        key(keys,"تحكم+C","\u0003");key(keys,"تحكم+D","\u0004");
        key(keys,"تحكم+Z","\u001a");key(keys,"↑","\u001b[A");
        key(keys,"↓","\u001b[B");key(keys,"←","\u001b[D");
        key(keys,"→","\u001b[C");key(keys,"دخول","\r");
        key(keys,"حذف","\u007f");
        keysScroll.addView(keys);root.addView(keysScroll);
        LinearLayout command=row();command.setGravity(Gravity.CENTER_VERTICAL);
        entry=new EditText(this);
        entry.setSingleLine(true);entry.setTextSize(15);entry.setTextColor(FG);
        entry.setHintTextColor(MUTED);entry.setBackgroundColor(PANEL);
        entry.setHint("اكتب أمرًا بالعربية…");
        entry.setImeOptions(EditorInfo.IME_ACTION_GO);
        entry.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        entry.setOnEditorActionListener((v,id,event)->{
            if(id==EditorInfo.IME_ACTION_GO||(event!=null &&
                event.getKeyCode()==KeyEvent.KEYCODE_ENTER && event.getAction()==KeyEvent.ACTION_DOWN)){
                submit();return true;
            }
            return false;
        });
        command.addView(entry,new LinearLayout.LayoutParams(0,dp(48),1));
        command.addView(button("إرسال ↵",v->submit()));root.addView(command);
        modeButton=button("الوضع: أوامر عربية",v->{
            rawMode=!rawMode;
            modeButton.setText(rawMode?"الوضع: كتابة تفاعلية":"الوضع: أوامر عربية");
            entry.setHint(rawMode?"إرسال نص خام؛ زر دخول لسطر جديد":"اكتب أمرًا بالعربية…");
        });
        root.addView(modeButton);
        root.addView(label("بدون إنترنت أو ADB • الصلاحيات حسب نظام أندرويد",10,MUTED));
    }
    private void createSession(boolean root){
        if(sessions.size()>=MAX_SESSIONS){notice("الحد الأقصى ست جلسات. أغلق جلسة أولًا.");return;}
        try{
            long h=NativePty.start(root,getFilesDir().getAbsolutePath(),
                    getCacheDir().getAbsolutePath(),rows,columns);
            if(h==0)throw new IOException("لم يتم إنشاء PTY");
            Session s=new Session(h,"جلسة "+nextId++,root);
            s.display.resize(rows,columns);
            sessions.add(s);active=s;
            render();
            new Thread(()->readLoop(s),"arabicpty-reader-"+s.name).start();
        }catch(Exception|UnsatisfiedLinkError ex){
            notice("تعذر فتح جلسة طرفية: "+ex.getMessage());
        }
    }
    private void previousSession(){
        if(sessions.isEmpty())return;
        int position=sessions.indexOf(active);
        active=sessions.get((position+sessions.size()-1)%sessions.size());
        render();
    }
    private void closeActive(){
        if(active==null)return;
        Session s=active;
        sessions.remove(s);
        synchronized(s.mutex){
            if(!s.finished)NativePty.terminate(s.handle);
        }
        active=sessions.isEmpty()?null:sessions.get(sessions.size()-1);
        if(active==null)createSession(false);
        else render();
    }
    private void readLoop(Session s){
        try(Reader reader=new InputStreamReader(new InputStream(){
            @Override public int read()throws IOException{
                byte[] one=new byte[1];int n=read(one,0,1);return n<0?-1:one[0]&255;
            }
            @Override public int read(byte[] into,int offset,int count)throws IOException{
                if(count==0)return 0;
                byte[] bytes=new byte[Math.min(count,4096)];
                int n=NativePty.read(s.handle,bytes);
                if(n<0)return -1;
                System.arraycopy(bytes,0,into,offset,n);return n;
            }
        },StandardCharsets.UTF_8)){
            char[] chars=new char[4096];
            int n;
            while((n=reader.read(chars))>=0)if(n>0)enqueue(s,new String(chars,0,n));
        }catch(IOException e){enqueue(s,"\r\nتعذرت قراءة جلسة الطرفية: "+e.getMessage()+"\r\n");}
        synchronized(s.mutex){
            s.finished=true;
            NativePty.destroy(s.handle);
        }
        ui.post(()->{if(active==s)status.setText(s.name+" — انتهت العملية");});
    }
    private void enqueue(Session s,String text){
        synchronized(s.pendingLock){
            if(s.pending.length()>120000)s.pending.delete(0,s.pending.length()-80000);
            s.pending.append(text);
            if(s.flushScheduled)return;
            s.flushScheduled=true;
        }
        ui.postDelayed(()->flush(s),40);
    }
    private void flush(Session s){
        String payload;
        synchronized(s.pendingLock){
            payload=s.pending.toString();s.pending.setLength(0);s.flushScheduled=false;
        }
        if(payload.isEmpty())return;
        s.display.append(payload);
        String reply=s.display.drainResponse();
        if(!reply.isEmpty())write(s,reply);
        if(active==s)render();
    }
    private void write(Session s,String bytes){
        if(s==null||s.finished)return;
        synchronized(s.mutex){
            if(!s.finished){
                byte[] value=bytes.getBytes(StandardCharsets.UTF_8);
                NativePty.write(s.handle,value,value.length);
            }
        }
    }
    private void submit(){
        if(active==null)return;
        String text=entry.getText().toString();
        entry.setText("");
        if(rawMode){write(active,text);return;}
        ArabicCommandRouter.Parsed parsed=ArabicCommandRouter.parse(text);
        switch(parsed.kind){
            case SHELL:write(active,parsed.command+"\n");break;
            case CONFIRM_DELETE:
                new AlertDialog.Builder(this).setTitle("تأكيد الحذف")
                    .setMessage("سيُحذف العنصر: "+parsed.description)
                    .setNegativeButton("إلغاء",null)
                    .setPositiveButton("حذف",(d,w)->write(active,parsed.command+"\n")).show();
                break;
            case HELP:help();break;
            case PHONE:notice("الشركة: "+Build.MANUFACTURER+"\nالطراز: "+Build.MODEL+
                    "\nأندرويد: "+Build.VERSION.RELEASE+"\nواجهة API: "+Build.VERSION.SDK_INT);break;
            case BATTERY:battery();break;
            case MEMORY:memory();break;
            case STORAGE:storage();break;
            case IDENTITY:write(active,"id\n");break;
            case SETTINGS:startActivity(new Intent(Settings.ACTION_SETTINGS));break;
            case CHOOSE_FILE:selectDocument();break;
            case CLEAR:active.display.clear();render();break;
            case ROOT:confirmRoot();break;
            case LIST_APPS:case OPEN_APP:case OPEN_SYSTEM_APP:case OPEN_SETTINGS_PAGE:
            case OPEN_URL:case DIAL:case SHARE:case ACCESSIBILITY_SETTINGS:
            case HOME:case BACK:case RECENTS:case NOTIFICATIONS:case QUICK_SETTINGS:
            case SWIPE_UP:case SWIPE_DOWN:case TAP_TEXT:case TAP_POINT:
                notice(PhoneController.run(this,parsed));break;
            default:notice("الأمر غير معروف. اكتب «مساعدة» للاطلاع على الدليل.");
        }
    }
    private void render(){
        if(console==null)return;
        if(active==null){console.setText("");status.setText("لا توجد جلسة");return;}
        status.setText(active.name+(active.root?" • صلاحيات جذر مطلوبة":" • تطبيق عادي")
                +(active.finished?" • متوقفة":" • متصلة"));
        TerminalScreen.Frame frame=active.display.frame();
        SpannableStringBuilder shown=new SpannableStringBuilder(frame.text);
        int length=frame.text.length();
        for(int i=0;i<length;){
            int fg=frame.foreground[i],bg=frame.background[i],next=i+1;
            while(next<length && frame.foreground[next]==fg && frame.background[next]==bg)next++;
            if(fg!=TerminalScreen.DEFAULT_FG)
                shown.setSpan(new ForegroundColorSpan(0xFF000000|fg),i,next,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if(bg!=TerminalScreen.DEFAULT_BG)
                shown.setSpan(new BackgroundColorSpan(0xFF000000|bg),i,next,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            i=next;
        }
        console.setText(shown);
        viewport.post(()->viewport.fullScroll(View.FOCUS_DOWN));
    }
    private void notice(String content){
        if(active!=null)enqueue(active,"\r\n« "+content+" »\r\n");
    }
    private void battery(){
        Intent i=registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if(i==null){notice("البطارية غير متاحة");return;}
        int level=i.getIntExtra(BatteryManager.EXTRA_LEVEL,-1);
        int scale=i.getIntExtra(BatteryManager.EXTRA_SCALE,100);
        boolean charging=i.getIntExtra(BatteryManager.EXTRA_STATUS,0)==BatteryManager.BATTERY_STATUS_CHARGING;
        notice("البطارية: "+(scale>0?level*100/scale:level)+"% — الشحن: "+(charging?"نعم":"لا"));
    }
    private void memory(){
        ActivityManager manager=(ActivityManager)getSystemService(Context.ACTIVITY_SERVICE);
        if(manager==null){notice("الذاكرة غير متاحة");return;}
        ActivityManager.MemoryInfo info=new ActivityManager.MemoryInfo();
        manager.getMemoryInfo(info);
        notice("الذاكرة الكلية: "+gb(info.totalMem)+" غيغابايت\nالمتاحة: "+gb(info.availMem)+" غيغابايت");
    }
    private String gb(long n){return String.format(Locale.US,"%.2f",n/1073741824.0);}
    private void storage(){
        StatFs disk=new StatFs(getFilesDir().getAbsolutePath());
        notice("القسم: "+gb(disk.getTotalBytes())+" غيغابايت\nالمتاح: "+
                gb(disk.getAvailableBytes())+" غيغابايت\nمسار العمل: "+getFilesDir());
    }
    private void confirmRoot(){
        new AlertDialog.Builder(this).setTitle("طلب صلاحيات الجذر")
            .setMessage("الجذر غير مضمون؛ يجب أن يكون su متاحًا وأن يوافق مدير الجذر. "+
                "قد تتمكن برامج الجذر من تغيير ملفات النظام. هل تريد بدء جلسة جديدة؟")
            .setNegativeButton("إلغاء",null)
            .setPositiveButton("طلب الجذر",(d,w)->createSession(true)).show();
    }
    private void phoneControlSetup(){
        new AlertDialog.Builder(this)
            .setTitle("التحكم بالهاتف")
            .setMessage("يمكنك فتح التطبيقات من الطرفية مباشرة بدون أذونات خاصة. "+
                "أما الرجوع والرئيسية والنقر والتمرير فوق التطبيقات الأخرى فتحتاج تفعيل "+
                "خدمة «تحكم الطرفية العربية» من إعدادات إمكانية الوصول. "+
                "ستظهر لوحة عائمة صغيرة تستطيع إخفاءها. الخدمة لا تحفظ محتوى الشاشة ولا ترسل بيانات للإنترنت. "+
                "فعّلها فقط إذا رغبت ويمكن إيقافها في أي وقت.")
            .setNegativeButton("إلغاء",null)
            .setPositiveButton("إعدادات إمكانية الوصول",(dialog,which)->
                notice(PhoneController.run(this,ArabicCommandRouter.parse("تفعيل التحكم"))))
            .show();
    }
    private void help(){
        new AlertDialog.Builder(this).setTitle("دليل الطرفية العربية")
            .setMessage("الملفات — قائمة الملفات\nأين أنا — مجلد العمل\nالهاتف — معلومات الهاتف"+
                "\nالبطارية — نسبة الشحن\nالرام — الذاكرة\nالتخزين — المساحة"+
                "\nأنشئ مجلد اسم\nأنشئ ملف اسم\nاذهب إلى مسار\nاقرأ ملف"+
                "\nاحذف ملف اسم — مع تأكيد\nالهوية — صلاحيات العملية"+
                "\nالعمليات، النواة، الوقت، مسح، روت، استيراد ملف"+
                "\n\nالتحكم بالجوال: افتح واتساب، افتح يوتيوب، شغل تلغرام، اعرض التطبيقات."+
                "\nالكاميرا، الصور، المتصفح، افتح الواي فاي، افتح البلوتوث."+
                "\nافتح رابط https://example.org، اتصل بالرقم 12345، شارك النص مرحبا."+
                "\nأوامر الواجهة (بعد تفعيل خدمة التحكم): الرئيسية، رجوع، التطبيقات الأخيرة، الإشعارات، مرر للأعلى، مرر للأسفل، اضغط على إرسال، اضغط عند 100 250."+
                "\nاكتب «تفعيل التحكم» لإظهار إعدادات الخدمة. ستظهر لوحة عائمة فوق التطبيقات الأخرى."+

                "\n\nالأوامر الإنجليزية مثل ls وpython تُرسل مباشرة للصدفة إن توفرت."+
                "\n! يرسل الأمر كما هو.\nالوضع التفاعلي يرسل النص بلا Enter."+
                "\nأزرار التحكم ترسل أحرفًا خامًا مباشرة إلى PTY."+
                "\nلا تملك الطرفية صلاحية تجاوز عزل أندرويد.")
            .setPositiveButton("مفهوم",null).show();
    }
    private void selectDocument(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");
        startActivityForResult(i,FILE_PICKER);
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==FILE_PICKER && result==RESULT_OK && data!=null && data.getData()!=null){
            Uri uri=data.getData();
            new AlertDialog.Builder(this).setTitle("استيراد الملف")
                .setMessage("نسخ الملف المختار إلى مجلد التطبيق بدون تغيير الأصل؟")
                .setNegativeButton("إلغاء",null)
                .setPositiveButton("نسخ",(d,w)->importFile(uri)).show();
        }
    }
    private void importFile(Uri uri){
        String name="مستورد";
        try(Cursor cursor=getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){
            if(cursor!=null && cursor.moveToFirst()&&!cursor.isNull(0))name=cursor.getString(0);
        }catch(Exception ignored){}
        name=name.replace('/','_').replace('\\','_').replace("..","_")
                .replaceAll("\\p{Cntrl}","_");
        if(name.trim().isEmpty()||name.equals("."))name="مستورد";
        final String safe=name;
        new Thread(()->{
            try{
                File dir=new File(getFilesDir(),"المستوردات");
                if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("تعذر إنشاء المجلد");
                File dst=new File(dir,safe);int k=1;
                while(dst.exists())dst=new File(dir,k+++"-"+safe);
                try(InputStream in=getContentResolver().openInputStream(uri);
                    FileOutputStream out=new FileOutputStream(dst)){
                    if(in==null)throw new IOException("تعذر فتح الملف");
                    byte[] bytes=new byte[8192];int n;
                    while((n=in.read(bytes))!=-1)out.write(bytes,0,n);
                }
                Session s=active;if(s!=null)enqueue(s,"\r\nتم الاستيراد: "+dst.getAbsolutePath()+"\r\n");
            }catch(Exception error){
                Session s=active;if(s!=null)enqueue(s,"\r\nفشل الاستيراد: "+error.getMessage()+"\r\n");
            }
        },"file-import").start();
    }
    private void copyText(){
        ClipboardManager clipboard=(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
        if(clipboard!=null&&active!=null){
            clipboard.setPrimaryClip(ClipData.newPlainText("الطرفية العربية",active.display.render()));
            notice("تم نسخ نص الشاشة.");
        }
    }
    @Override public void onConfigurationChanged(Configuration change){super.onConfigurationChanged(change);}
    @Override protected void onDestroy(){
        for(Session s:sessions){
            synchronized(s.mutex){if(!s.finished)NativePty.terminate(s.handle);}
        }
        super.onDestroy();
    }
}
