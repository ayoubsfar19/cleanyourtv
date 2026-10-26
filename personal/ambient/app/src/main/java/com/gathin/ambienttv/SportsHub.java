package com.gathin.ambienttv;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

/** Photo-first ambient scene; Down or the discreet chevron opens sports navigation. */
final class SportsHub {
    private static final String[] CODES={"esp.1","mor.1"}, LEAGUES={"LaLiga","Botola Pro"};
    private static final long DETAIL_TTL=5*60_000L;
    private final Context c;
    private final FrameLayout root;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final android.content.SharedPreferences cache;
    private final View launch;
    private final TextView weather,notice;
    private FrameLayout page;
    private LinearLayout main,rows,detail,dateBar,mainHeader;
    private ScrollView matchScroll;
    private TextView footer,dateLabel,title,leagueButton;
    private ExecutorService net,art,detailsNet;
    private final android.util.LruCache<String,Bitmap> logos=new android.util.LruCache<String,Bitmap>(4_000_000){protected int sizeOf(String k,Bitmap b){return b.getByteCount();}};
    private final Set<String> requests=new HashSet<>();
    private final ArrayDeque<FrameLayout> inspectors=new ArrayDeque<>();
    private final ArrayDeque<View> inspectorFocus=new ArrayDeque<>();
    private ImageView backdrop;
    private android.media.ToneGenerator tone;
    private volatile boolean active;
    private volatile int generation,artGeneration;
    private volatile int filter,league,leagueGeneration;
    private int dataRequest;
    private LocalDate date=LocalDate.now(MatchFeed.zone());
    private List<MatchFeed.Match> matches=new ArrayList<>();
    private MatchFeed.GoalGate goals=new MatchFeed.GoalGate();
    private final ArrayDeque<String> pending=new ArrayDeque<>();
    private boolean showing;
    private int verticalKey;
    private long checked;
    private String failure="",selectedId="";
    private Runnable pageChanged;
    void setPageChangedListener(Runnable listener) { pageChanged = listener; }
    private void pageChanged() { if (pageChanged != null) pageChanged.run(); }
    private final Runnable poll=new Runnable(){public void run(){if(!active)return;refreshToday();ui.postDelayed(this,30_000);}};
    private final Runnable weatherPoll=new Runnable(){public void run(){if(!active)return;refreshWeather();ui.postDelayed(this,30*60_000L);}};
    private interface Loader { JSONObject load() throws Exception; }

    SportsHub(Context context,FrameLayout container){
        c=context;root=container;cache=c.getSharedPreferences("ambient-scores",0);
        launch=new View(c){
            private final Paint ink=new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas canvas){
                super.onDraw(canvas);ink.setColor(Color.WHITE);ink.setAlpha(hasFocus()?245:190);ink.setStyle(Paint.Style.STROKE);ink.setStrokeWidth(dp(2));ink.setStrokeCap(Paint.Cap.ROUND);ink.setStrokeJoin(Paint.Join.ROUND);ink.setShadowLayer(dp(2),0,dp(1),0x99000000);
                float x=getWidth()/2f,y=getHeight()/2f;Path chevron=new Path();chevron.moveTo(x-dp(8),y-dp(3));chevron.lineTo(x,y+dp(4));chevron.lineTo(x+dp(8),y-dp(3));canvas.drawPath(chevron,ink);
            }
        };
        launch.setContentDescription("Football");launch.setDefaultFocusHighlightEnabled(false);launch.setFocusable(true);launch.setClickable(true);launch.setOnFocusChangeListener((v,f)->v.invalidate());launch.setOnClickListener(v->open());
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(dp(56),dp(36),Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);lp.bottomMargin=dp(16);root.addView(launch,lp);launch.requestFocus();
        weather=text(cache.getString("weather","Oujda  ·  Météo…"),17);weather.setShadowLayer(dp(4),0,1,Color.BLACK);
        FrameLayout.LayoutParams wp=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.LEFT);wp.leftMargin=dp(32);wp.topMargin=dp(28);root.addView(weather,wp);
        notice=text("",17);notice.setPadding(dp(20),dp(12),dp(20),dp(12));notice.setBackground(bg(0xF0182633,0xFFFFD44D,14));notice.setVisibility(View.GONE);
        FrameLayout.LayoutParams np=new FrameLayout.LayoutParams(dp(470),-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL);np.topMargin=dp(24);root.addView(notice,np);
    }
    void start(){active=true;generation++;goals=new MatchFeed.GoalGate();net=Executors.newSingleThreadExecutor();art=Executors.newSingleThreadExecutor();detailsNet=Executors.newFixedThreadPool(2);ui.post(poll);ui.post(weatherPoll);}
    void stop(){active=false;verticalKey=0;generation++;ui.removeCallbacksAndMessages(null);if(net!=null)net.shutdownNow();if(art!=null)art.shutdownNow();if(detailsNet!=null)detailsNet.shutdownNow();net=null;art=null;detailsNet=null;logos.evictAll();requests.clear();if(tone!=null){tone.release();tone=null;}pending.clear();showing=false;notice.setVisibility(View.GONE);closePage();}
    boolean isOpen(){ return page!=null; }
    /** Keep weather and notices visible while the night video owns the ambient remote actions. */
    void setAmbientActionsVisible(boolean visible) {
        launch.setVisibility(visible && page == null ? View.VISIBLE : View.GONE);
    }
    /** Capture the complete press only when changing between photos and the football page. */
    boolean handleVerticalKey(KeyEvent event){
        int key=event.getKeyCode();if(key!=KeyEvent.KEYCODE_DPAD_UP&&key!=KeyEvent.KEYCODE_DPAD_DOWN)return false;
        if(event.getAction()==KeyEvent.ACTION_UP&&verticalKey==key){verticalKey=0;return true;}
        if(event.getAction()!=KeyEvent.ACTION_DOWN)return false;
        if(event.getRepeatCount()>0)return verticalKey==key;
        if(!active)return false;
        if(key==KeyEvent.KEYCODE_DPAD_DOWN&&page==null){verticalKey=key;open();return true;}
        if(key==KeyEvent.KEYCODE_DPAD_UP&&page!=null&&atPageTop()){verticalKey=key;closePage();return true;}
        return false;
    }
    private boolean atPageTop(){
        View focused=page.findFocus();if(focused==null)return true;
        View header=inspectors.isEmpty()?mainHeader:inspectorBox(inspectors.peekLast()).getChildAt(0);
        if(inside(focused,header))return true;
        View above=focused.focusSearch(View.FOCUS_UP);return above==null||!inside(above,page);
    }
    private boolean inside(View child,View parent){
        for(View current=child;current!=null;){if(current==parent)return true;ViewParent owner=current.getParent();current=owner instanceof View?(View)owner:null;}return false;
    }
    boolean back(){
        if(page==null)return false;
        if(!inspectors.isEmpty()){
            FrameLayout top=inspectors.removeLast();page.removeView(top);artGeneration++;
            if(inspectors.isEmpty()){main.setVisibility(View.VISIBLE);if(!inspectorFocus.isEmpty()){View f=inspectorFocus.removeLast();if(f.getParent()!=null)f.requestFocus();}}
            else{inspectors.peekLast().setVisibility(View.VISIBLE);if(!inspectorFocus.isEmpty()){View f=inspectorFocus.removeLast();if(f.getParent()!=null)f.requestFocus();}}
            return true;
        }
        closePage();return true;
    }
    private void closePage(){if(page==null)return;root.removeView(page);page=null;main=null;mainHeader=null;rows=null;detail=null;backdrop=null;inspectors.clear();inspectorFocus.clear();artGeneration++;launch.setVisibility(View.VISIBLE);launch.requestFocus();pageChanged();}
    private int dp(float v){return Math.round(v*c.getResources().getDisplayMetrics().density);}
    private TextView text(String s,int size){TextView t=new TextView(c);t.setText(s);t.setTextColor(Color.WHITE);t.setTextSize(size);t.setFontFeatureSettings("tnum");return t;}
    private GradientDrawable bg(int color,int stroke,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));if(stroke!=0)d.setStroke(dp(2),stroke);return d;}
    private void focusable(View v){v.setFocusable(true);v.setClickable(true);v.setBackground(bg(0xB5101823,0x55FFFFFF,12));v.setOnFocusChangeListener((w,f)->w.setBackground(bg(f?0xF0182734:0xB5101823,f?0xFFFFD44D:0x33FFFFFF,12)));}
    private LinearLayout column(){LinearLayout l=new LinearLayout(c);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private TextView button(String s,Runnable run){TextView t=text(s,14);t.setGravity(Gravity.CENTER);t.setPadding(dp(15),dp(8),dp(15),dp(8));focusable(t);t.setOnClickListener(v->run.run());return t;}
    private void open(){
        if(page!=null)return;
        page=new FrameLayout(c);page.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xFF008E99,0xFF143B55,0xFF572C79}));root.addView(page,new FrameLayout.LayoutParams(-1,-1));launch.setVisibility(View.GONE);
        pageChanged();
        backdrop=new ImageView(c);backdrop.setScaleType(ImageView.ScaleType.FIT_CENTER);backdrop.setAlpha(0.16f);page.addView(backdrop,new FrameLayout.LayoutParams(dp(430),dp(430),Gravity.RIGHT|Gravity.CENTER_VERTICAL));
        main=column();main.setPadding(dp(30),dp(22),dp(30),dp(16));page.addView(main,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout heading=new LinearLayout(c);mainHeader=heading;heading.setGravity(Gravity.CENTER_VERTICAL);main.addView(heading,new LinearLayout.LayoutParams(-1,dp(44)));
        title=text(LEAGUES[league]+"  /  Football",24);title.setSingleLine(true);title.setEllipsize(TextUtils.TruncateAt.END);heading.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        leagueButton=button(LEAGUES[1-league]+" ↔",this::switchLeague);heading.addView(leagueButton,new LinearLayout.LayoutParams(-2,dp(38)));heading.addView(button("Photos",this::closePage),new LinearLayout.LayoutParams(-2,dp(38)));
        HorizontalScrollView tabScroll=new HorizontalScrollView(c);tabScroll.setHorizontalScrollBarEnabled(false);main.addView(tabScroll,new LinearLayout.LayoutParams(-1,dp(44)));LinearLayout tabs=new LinearLayout(c);tabScroll.addView(tabs,new HorizontalScrollView.LayoutParams(-2,-1));
        String[] names={"Matchs","● En direct","Résultats","Classement","Buteurs","Clubs"};
        for(int i=0;i<names.length;i++){final int f=i;TextView b=button(names[i],()->selectTab(f));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(36));p.rightMargin=dp(10);tabs.addView(b,p);}
        dateBar=new LinearLayout(c);dateBar.setGravity(Gravity.CENTER_VERTICAL);main.addView(dateBar,new LinearLayout.LayoutParams(-1,dp(44)));
        dateBar.addView(button("‹ Jour précédent",()->changeDay(-1)));dateLabel=text("",16);dateLabel.setGravity(Gravity.CENTER);dateBar.addView(dateLabel,new LinearLayout.LayoutParams(0,-2,1));dateBar.addView(button("Aujourd’hui",()->{date=LocalDate.now(MatchFeed.zone());loadDay();}));dateBar.addView(button("Jour suivant ›",()->changeDay(1)));
        LinearLayout body=new LinearLayout(c);LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,0,1);bp.topMargin=dp(10);bp.bottomMargin=dp(10);main.addView(body,bp);
        matchScroll=new ScrollView(c);matchScroll.setFillViewport(true);matchScroll.setClipToPadding(false);rows=column();matchScroll.addView(rows,new ScrollView.LayoutParams(-1,-2));body.addView(matchScroll,new LinearLayout.LayoutParams(0,-1,2.25f));
        detail=column();detail.setGravity(Gravity.CENTER);detail.setPadding(dp(16),dp(16),dp(16),dp(16));detail.setBackground(bg(0x8810192B,0,16));LinearLayout.LayoutParams side=new LinearLayout.LayoutParams(0,-1,1);side.leftMargin=dp(16);body.addView(detail,side);
        footer=text("",11);main.addView(footer,new LinearLayout.LayoutParams(-1,-2));selectTab(filter);tabs.getChildAt(Math.min(filter,5)).requestFocus();notice.bringToFront();
    }
    private void switchLeague(){league=1-league;leagueGeneration++;goals=new MatchFeed.GoalGate();matches=new ArrayList<>();selectedId="";checked=0;failure="";if(backdrop!=null)backdrop.setImageDrawable(null);title.setText(LEAGUES[league]+"  /  Football");leagueButton.setText(LEAGUES[1-league]+" ↔");selectTab(filter);}
    private void selectTab(int tab){filter=tab;selectedId="";dateBar.setVisibility(tab<3?View.VISIBLE:View.GONE);detail.setVisibility(tab<3?View.VISIBLE:View.GONE);if(tab<3)loadDay();else loadLeague(tab);}
    private void changeDay(int delta){date=date.plusDays(delta);loadDay();}
    private String dayKey(LocalDate day,int l){return "day-"+CODES[l]+"-"+day;}
    private void loadDay(){
        String key=dayKey(date,league),raw=cache.getString(key,"");checked=cache.getLong("at-"+key,0);failure="";
        try{matches=raw.isEmpty()?new ArrayList<>():MatchFeed.parse(raw);}catch(Exception e){matches=new ArrayList<>();}
        render();requestDate(date,league);
    }
    private void refreshToday(){requestDate(LocalDate.now(MatchFeed.zone()),league);}
    private void requestDate(LocalDate requested,int requestedLeague){
        if(!active||net==null)return;String key=dayKey(requested,requestedLeague);if(requests.contains(key))return;
        final int g=generation,lg=leagueGeneration;requests.add(key);
        net.execute(()->{
            try{
                if(!active||g!=generation||lg!=leagueGeneration){ui.post(()->requests.remove(key));return;}
                String raw=MatchFeed.fetch(requested,CODES[requestedLeague]);List<MatchFeed.Match> fresh=MatchFeed.parse(raw);long at=System.currentTimeMillis();
                if(!active||generation!=g)return;cache.edit().putString(key,raw).putLong("at-"+key,at).apply();
                ui.post(()->{requests.remove(key);if(!active||generation!=g||lg!=leagueGeneration)return;
                    if(requested.equals(LocalDate.now(MatchFeed.zone())))for(MatchFeed.Match m:fresh){String alert=goals.observe(m,at);if(alert!=null)enqueue(alert);}
                    if(date.equals(requested)&&league==requestedLeague){matches=fresh;checked=at;failure="";if(filter<3&&inspectors.isEmpty())render();}
                });
            }catch(Exception e){ui.post(()->{requests.remove(key);if(!active||generation!=g||lg!=leagueGeneration)return;if(date.equals(requested)&&league==requestedLeague){failure="Connexion indisponible · données conservées";if(filter<3&&inspectors.isEmpty())render();}});}
        });
    }
    private String stamp(long at){return at<=0?"":Instant.ofEpochMilli(at).atZone(MatchFeed.zone()).format(DateTimeFormatter.ofPattern("HH:mm"));}
    private void render(){
        if(page==null||rows==null||filter>=3||!inspectors.isEmpty())return;
        dateLabel.setText(date.format(DateTimeFormatter.ofPattern("EEEE d MMMM",Locale.FRANCE)));footer.setText((checked==0?(failure.isEmpty()?"Chargement…":"Chargement impossible"):"Actualisé à "+stamp(checked))+" · heure du téléviseur"+(failure.isEmpty()?"":"  /  "+failure));
        View focused=page.findFocus();String focusedId=focused!=null&&focused.getTag() instanceof String?(String)focused.getTag():null;int scrollY=matchScroll.getScrollY();boolean sideFocused=detail.hasFocus();
        rows.removeAllViews();List<MatchFeed.Match> visible=new ArrayList<>();for(MatchFeed.Match m:matches)if(filter==0||filter==1&&m.live()||filter==2&&m.finished())visible.add(m);
        if(visible.isEmpty()){
            String message=!failure.isEmpty()&&checked==0?"Impossible de charger les matchs":checked==0?"Chargement des matchs…":filter==1?"Aucun match en direct à cette date":filter==2?"Aucun résultat à cette date":"Aucun match à cette date";
            TextView empty=text(message,18);empty.setPadding(dp(24),dp(30),dp(24),dp(20));rows.addView(empty);if(!failure.isEmpty())rows.addView(button("Réessayer",this::loadDay));detail.removeAllViews();detail.addView(text(LEAGUES[league]+"\n\nChoisissez un autre jour",20));return;
        }
        MatchFeed.Match selected=null;
        for(MatchFeed.Match m:visible){
            LinearLayout card=column();card.setPadding(dp(16),dp(10),dp(16),dp(10));focusable(card);card.setTag("match:"+m.id);TextView state=text(m.label(),11);state.setTextColor(m.live()?0xFFFFD44D:0xFFB9D8DF);card.addView(state);
            LinearLayout line=new LinearLayout(c);line.setGravity(Gravity.CENTER_VERTICAL);card.addView(line,new LinearLayout.LayoutParams(-1,dp(38)));TextView h=text(m.home,18),score=text(m.score(),23),a=text(m.away,18);score.setTypeface(null,Typeface.BOLD);score.setGravity(Gravity.CENTER);a.setGravity(Gravity.RIGHT);line.addView(h,new LinearLayout.LayoutParams(0,-2,1));line.addView(score,new LinearLayout.LayoutParams(dp(92),-2));line.addView(a,new LinearLayout.LayoutParams(0,-2,1));
            card.setOnClickListener(v->openMatch(m));card.setOnFocusChangeListener((v,f)->{v.setBackground(bg(f?0xEE122333:0xB5101823,f?0xFFFFD44D:0x33FFFFFF,12));if(f){selectedId=m.id;showPreview(m);}});
            LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,dp(76));cp.bottomMargin=dp(8);rows.addView(card,cp);if(m.id.equals(selectedId))selected=m;if(("match:"+m.id).equals(focusedId))card.requestFocus();
        }
        if(selected==null)selected=visible.get(0);selectedId=selected.id;if(!sideFocused)showPreview(selected);matchScroll.post(()->{if(matchScroll!=null)matchScroll.scrollTo(0,scrollY);});
    }
    private void showPreview(MatchFeed.Match m){
        if(detail==null)return;detail.removeAllViews();int token=++artGeneration;TextView label=text(m.label(),12);label.setTextColor(0xFFFFD44D);detail.addView(label);
        LinearLayout pair=new LinearLayout(c);pair.setGravity(Gravity.CENTER);ImageView h=new ImageView(c),a=new ImageView(c);h.setScaleType(ImageView.ScaleType.FIT_CENTER);a.setScaleType(ImageView.ScaleType.FIT_CENTER);pair.addView(h,new LinearLayout.LayoutParams(dp(64),dp(64)));pair.addView(text("  /  ",18));pair.addView(a,new LinearLayout.LayoutParams(dp(64),dp(64)));detail.addView(pair,new LinearLayout.LayoutParams(-1,dp(90)));
        TextView teams=text(m.home+"\n"+m.score()+"\n"+m.away,20);teams.setGravity(Gravity.CENTER);detail.addView(teams);TextView open=button("Détails du match  ›",()->openMatch(m));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(16);detail.addView(open,p);loadLogo(m.homeLogo,h,token,true);loadLogo(m.awayLogo,a,token,false);
    }
    private FrameLayout inspector(String heading){
        View focused=page.findFocus();if(focused!=null)inspectorFocus.addLast(focused);else inspectorFocus.addLast(leagueButton);
        if(inspectors.isEmpty())main.setVisibility(View.GONE);else inspectors.peekLast().setVisibility(View.GONE);
        FrameLayout frame=new FrameLayout(c);frame.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xF3006374,0xF0182945,0xF347285F}));page.addView(frame,new FrameLayout.LayoutParams(-1,-1));inspectors.addLast(frame);
        LinearLayout box=column();box.setPadding(dp(30),dp(22),dp(30),dp(20));frame.addView(box,new FrameLayout.LayoutParams(-1,-1));LinearLayout head=new LinearLayout(c);head.setGravity(Gravity.CENTER_VERTICAL);box.addView(head,new LinearLayout.LayoutParams(-1,dp(52)));
        TextView title=text(heading,22);title.setMaxLines(2);title.setEllipsize(TextUtils.TruncateAt.END);head.addView(title,new LinearLayout.LayoutParams(0,-2,1));TextView back=button("‹ Retour",()->back());head.addView(back,new LinearLayout.LayoutParams(-2,dp(38)));head.addView(button("Photos",this::closePage),new LinearLayout.LayoutParams(-2,dp(38)));back.requestFocus();notice.bringToFront();return frame;
    }
    private LinearLayout inspectorBox(FrameLayout frame){return (LinearLayout)frame.getChildAt(0);}
    private LinearLayout inspectorContent(FrameLayout frame){LinearLayout box=inspectorBox(frame);ScrollView scroll=new ScrollView(c);scroll.setFillViewport(true);scroll.setClipToPadding(false);box.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));LinearLayout content=column();scroll.addView(content,new ScrollView.LayoutParams(-1,-2));return content;}
    private void openMatch(MatchFeed.Match m){
        FrameLayout frame=inspector(m.home+"   "+m.score()+"   "+m.away);LinearLayout box=inspectorBox(frame),clubs=new LinearLayout(c);box.addView(clubs,new LinearLayout.LayoutParams(-1,dp(44)));
        if(m.homeId!=null&&!m.homeId.isEmpty())clubs.addView(button("Fiche · "+m.home,()->openClub(m.homeId,m.home)),new LinearLayout.LayoutParams(0,dp(38),1));
        if(m.awayId!=null&&!m.awayId.isEmpty())clubs.addView(button("Fiche · "+m.away,()->openClub(m.awayId,m.away)),new LinearLayout.LayoutParams(0,dp(38),1));
        LinearLayout content=inspectorContent(frame);loadSections(frame,content,"match:"+CODES[league]+":"+m.id,()->SportsData.match(m),false,false);
    }
    private void openClub(String id,String name){FrameLayout frame=inspector(name);LinearLayout content=inspectorContent(frame);loadSections(frame,content,"club:"+id+":"+name,()->SportsData.club(id,name),false,false);}
    private void loadLeague(int tab){
        if(rows==null)return;int l=league;String key="league:"+CODES[l]+":"+tab;loadSections(page,rows,key,()->tab==3?LeagueData.standings(l):tab==4?LeagueData.scorers(l):LeagueData.clubs(l),tab==5,false);
    }
    private boolean validPanel(FrameLayout frame,LinearLayout target,int requestedFilter,int requestedLeague){return active&&page!=null&&target.getParent()!=null&&((frame==page&&filter==requestedFilter&&league==requestedLeague&&target==rows)||(inspectors.contains(frame)));}
    private void loadSections(FrameLayout frame,LinearLayout target,String key,Loader loader,boolean clubList,boolean force){
        final int g=generation,f=filter,l=league,request=++dataRequest;target.setTag(Integer.valueOf(request));String raw=cache.getString("data:"+key,"");JSONObject cached=null;try{if(!raw.isEmpty())cached=new JSONObject(raw);}catch(Exception ignored){}
        final boolean hadCache=cached!=null;
        if(cached!=null){showSections(target,cached,clubList);if(frame==page)footer.setText("Données conservées · actualisées à "+stamp(cached.optLong("checked")));}
        else{if(frame==page)footer.setText("Chargement…");target.removeAllViews();TextView loading=text("Chargement des détails…",18);loading.setPadding(dp(18),dp(28),dp(18),dp(28));target.addView(loading);}
        if(!force&&cached!=null&&System.currentTimeMillis()-cached.optLong("checked")>=0&&System.currentTimeMillis()-cached.optLong("checked")<DETAIL_TTL){addRefresh(target,frame,key,loader,clubList);return;}
        detailsNet.execute(()->{
            if(!active||generation!=g||(frame==page&&(filter!=f||league!=l||target!=rows)))return;
            try{JSONObject data=loader.load();if(!active||generation!=g)return;if(data.optLong("checked")==0)data.put("checked",System.currentTimeMillis());cache.edit().putString("data:"+key,data.toString()).apply();
                ui.post(()->{if(generation!=g||!Integer.valueOf(request).equals(target.getTag())||!validPanel(frame,target,f,l))return;showSections(target,data,clubList);addRefresh(target,frame,key,loader,clubList);if(frame==page)footer.setText("Actualisé à "+stamp(data.optLong("checked"))+" · heure du téléviseur");});
            }catch(Exception e){ui.post(()->{if(generation!=g||!Integer.valueOf(request).equals(target.getTag())||!validPanel(frame,target,f,l))return;TextView message=text(!hadCache?"Détails indisponibles pour le moment":"Connexion indisponible · données conservées",15);message.setPadding(dp(16),dp(16),dp(16),dp(16));if(!hadCache)target.removeAllViews();target.addView(message);addRefresh(target,frame,key,loader,clubList);if(frame==page)footer.setText(!hadCache?"Chargement impossible":"Connexion indisponible · données conservées");});}
        });
    }
    private void addRefresh(LinearLayout target,FrameLayout frame,String key,Loader loader,boolean clubs){TextView refresh=button("Actualiser",()->loadSections(frame,target,key,loader,clubs,true));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(40));p.topMargin=dp(14);p.bottomMargin=dp(12);target.addView(refresh,p);}
    private void showSections(LinearLayout target,JSONObject data,boolean clubList){
        target.removeAllViews();boolean any=false;JSONArray clubs=data.optJSONArray("clubs");
        if(clubList&&clubs!=null)for(int i=0;i<clubs.length();i++){JSONObject club=clubs.optJSONObject(i);if(club==null)continue;String id=club.optString("id"),name=club.optString("name","Club");TextView row=button(name+"  ›",()->openClub(id,name));row.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(48));p.bottomMargin=dp(8);target.addView(row,p);any=true;}
        JSONArray sections=data.optJSONArray("sections");if(sections!=null)for(int i=0;i<sections.length();i++){JSONObject section=sections.optJSONObject(i);if(section==null)continue;JSONArray lines=section.optJSONArray("rows");if(lines==null||lines.length()==0)continue;TextView h=text(section.optString("title","Détails"),20);h.setTypeface(null,Typeface.BOLD);h.setTextColor(0xFFFFD44D);h.setPadding(dp(12),dp(18),dp(12),dp(10));target.addView(h);
            for(int j=0;j<lines.length();j++){String value=lines.optString(j);if(value.isEmpty())continue;TextView row=text(value,16);row.setPadding(dp(16),dp(11),dp(16),dp(11));row.setFocusable(true);row.setBackground(bg(0x6610192B,0,10));row.setOnFocusChangeListener((v,focus)->v.setBackground(bg(focus?0xDD152B3B:0x6610192B,focus?0xFFFFD44D:0,10)));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(5);target.addView(row,p);any=true;}
        }
        if(!any){TextView no=text("Données pas encore disponibles",18);no.setPadding(dp(18),dp(30),dp(18),dp(30));target.addView(no);}
    }
    private void loadLogo(String url,ImageView target,int token,boolean background){
        if(url==null||!(url.startsWith("https://a.espncdn.com/")||url.startsWith("https://cdn.sportfeeds.io/"))||art==null)return;int g=generation;Bitmap cached=logos.get(url);if(cached!=null){target.setImageBitmap(cached);if(background&&backdrop!=null)backdrop.setImageBitmap(cached);return;}
        art.execute(()->{if(!active||g!=generation||token!=artGeneration)return;Bitmap b=logos.get(url);HttpURLConnection conn=null;try{if(b==null){conn=(HttpURLConnection)new URL(url).openConnection();conn.setConnectTimeout(5000);conn.setReadTimeout(5000);try(InputStream in=conn.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buf=new byte[4096];int n;while((n=in.read(buf))!=-1){if(out.size()+n>1_000_000)throw new IOException();out.write(buf,0,n);}byte[] bytes=out.toByteArray();BitmapFactory.Options opt=new BitmapFactory.Options();opt.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,opt);if(opt.outWidth>1024||opt.outHeight>1024)throw new IOException();opt.inJustDecodeBounds=false;opt.inSampleSize=2;b=BitmapFactory.decodeByteArray(bytes,0,bytes.length,opt);}}}catch(Exception ignored){}finally{if(conn!=null)conn.disconnect();}Bitmap result=b;if(result!=null)logos.put(url,result);ui.post(()->{if(active&&g==generation&&token==artGeneration){target.setImageBitmap(result);if(background&&backdrop!=null)backdrop.setImageBitmap(result);}});});
    }
    private void refreshWeather(){int g=generation;detailsNet.execute(()->{try{String w="Oujda  ·  "+Weather.now(34.68,-1.91);cache.edit().putString("weather",w).apply();ui.post(()->{if(active&&g==generation)weather.setText(w);});}catch(Exception e){ui.post(()->{if(active&&g==generation)weather.setText(cache.getString("weather","Oujda · Météo indisponible")+" · hors ligne");});}});}
    private void enqueue(String s){if(pending.size()<8)pending.add(s);if(!showing)nextNotice();}
    private void nextNotice(){if(!active)return;String s=pending.poll();if(s==null){showing=false;notice.setVisibility(View.GONE);return;}showing=true;String scorer=s.split("\\n")[0];if(scorer.contains("Barcelona")){try{if(tone==null)tone=new android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION,60);tone.startTone(android.media.ToneGenerator.TONE_PROP_ACK,500);}catch(RuntimeException ignored){}}notice.setText(s);notice.setVisibility(View.VISIBLE);notice.bringToFront();ui.postDelayed(this::nextNotice,10_000);}
}
