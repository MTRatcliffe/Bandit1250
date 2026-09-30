package com.bandit1250.fuelmonitor;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.text.InputType;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private static final int REQ_BT=1001;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final List<BluetoothDevice> devices=new ArrayList<>();

    private BluetoothAdapter adapter;
    private Elm327Client elm;
    private SuzukiSds sds;
    private volatile boolean polling=false;

    private Spinner spinner;
    private TextView status, ecu, live, raw, log;
    private EditText flow, latency, cal;
    private Button connect, init, poll;

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        buildUi();
        BluetoothManager bm=(BluetoothManager)getSystemService(BLUETOOTH_SERVICE);
        adapter=bm==null?null:bm.getAdapter();
        if(adapter==null){ status.setText("Bluetooth unavailable"); connect.setEnabled(false); return; }
        ensurePermission();
    }

    private void buildUi(){
        ScrollView sv=new ScrollView(this);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24,24,24,40);
        sv.addView(root);

        TextView title=new TextView(this);
        title.setText("Bandit Fuel Monitor — v0.2.0-test (build 2)");
        title.setTextSize(24);
        root.addView(title);

        TextView note=new TextView(this);
        note.setText("2008 GSF1250SA • ELM327 Bluetooth • Suzuki SDS 2108\nFuel estimate is provisional; raw frames remain visible for decoder verification.");
        root.addView(note);

        spinner=new Spinner(this); root.addView(spinner);

        LinearLayout r1=row();
        Button refresh=new Button(this); refresh.setText("Refresh paired"); refresh.setOnClickListener(v->ensurePermission());
        connect=new Button(this); connect.setText("Connect ELM"); connect.setOnClickListener(v->connect());
        r1.addView(refresh,weight()); r1.addView(connect,weight()); root.addView(r1);

        LinearLayout r2=row();
        init=new Button(this); init.setText("Initialise SDS"); init.setEnabled(false); init.setOnClickListener(v->initialise());
        poll=new Button(this); poll.setText("Start 2108"); poll.setEnabled(false); poll.setOnClickListener(v->togglePoll());
        r2.addView(init,weight()); r2.addView(poll,weight()); root.addView(r2);

        status=text("Bluetooth: disconnected"); ecu=text("Suzuki ECU: not initialised");
        root.addView(status); root.addView(ecu);

        TextView modelHead=text("Fuel model (editable provisional assumptions)");
        modelHead.setTextSize(17);
        root.addView(modelHead);

        TextView flowLabel=text("Injector static flow (cc/min @ ~3 bar)");
        root.addView(flowLabel);
        flow=number("220.0");
        root.addView(flow);

        TextView latencyLabel=text("Net injector latency (ms: opening delay minus closing-flow tail)");
        root.addView(latencyLabel);
        latency=number("0.600");
        root.addView(latency);

        TextView calLabel=text("Tank calibration factor");
        root.addView(calLabel);
        cal=number("1.000");
        root.addView(cal);

        live=text("RPM: —\nInj1: — ms\nInj2: — ms\nInj3: — ms\nInj4: — ms\nAverage commanded PW: — ms\nEstimated flowing PW: — ms\nPW-only fuel: — L/h\nEstimated fuel: — L/h");
        live.setTextSize(19); root.addView(live);

        TextView rh=text("Raw 2108 response"); rh.setTextSize(18); root.addView(rh);
        raw=text("—"); raw.setTextIsSelectable(true); root.addView(raw);

        TextView lh=text("ELM / SDS log"); lh.setTextSize(18); root.addView(lh);
        log=text(""); log.setTextIsSelectable(true); root.addView(log);

        setContentView(sv);
    }

    private LinearLayout row(){ LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); return l; }
    private LinearLayout.LayoutParams weight(){ return new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f); }
    private TextView text(String s){ TextView t=new TextView(this); t.setText(s); t.setPadding(0,8,0,8); return t; }
    private EditText number(String s){ EditText e=new EditText(this); e.setText(s); e.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL); return e; }

    private void ensurePermission(){
        if(Build.VERSION.SDK_INT>=31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT},REQ_BT);
        } else refreshPaired();
    }

    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){
        super.onRequestPermissionsResult(r,p,g);
        if(r==REQ_BT && g.length>0 && g[0]==PackageManager.PERMISSION_GRANTED) refreshPaired();
        else status.setText("Bluetooth permission denied");
    }

    private void refreshPaired(){
        try{
            devices.clear();
            Set<BluetoothDevice> bonded=adapter.getBondedDevices();
            List<String> names=new ArrayList<>();
            for(BluetoothDevice d:bonded){ devices.add(d); names.add((d.getName()==null?"Unknown":d.getName())+"\n"+d.getAddress()); }
            spinner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,names));
            status.setText("Paired Bluetooth devices: "+devices.size());
        }catch(SecurityException e){ status.setText("Bluetooth permission required"); }
    }

    private void connect(){
        if(devices.isEmpty()){ toast("Pair the ELM327 in Android Bluetooth settings first."); return; }
        int pos=spinner.getSelectedItemPosition(); if(pos<0)pos=0;
        BluetoothDevice d=devices.get(pos);
        connect.setEnabled(false);
        io.execute(()->{
            try{
                if(elm!=null)elm.close();
                elm=new Elm327Client(d); elm.connect();
                sds=new SuzukiSds(elm,this::appendLog);
                ui.post(()->{ status.setText("Bluetooth: connected to "+safeName(d)); init.setEnabled(true); connect.setEnabled(true); });
            }catch(Exception e){ ui.post(()->{ status.setText("Connect failed: "+e.getMessage()); connect.setEnabled(true); }); }
        });
    }

    private void initialise(){
        if(sds==null)return;
        init.setEnabled(false);
        io.execute(()->{
            try{
                sds.initialise();
                String first=sds.read2108();
                ui.post(()->{
                    ecu.setText("Suzuki ECU: SDS initialised");
                    raw.setText(first);
                    poll.setEnabled(true);
                    init.setEnabled(true);
                    decodeAndShow(first);
                });
            }catch(Exception e){ ui.post(()->{ ecu.setText("SDS init failed: "+e.getMessage()); init.setEnabled(true); }); }
        });
    }

    private void togglePoll(){
        polling=!polling;
        poll.setText(polling?"Stop 2108":"Start 2108");
        if(polling) io.execute(this::pollLoop);
    }

    private void pollLoop(){
        while(polling && sds!=null){
            try{
                String r=sds.read2108();
                ui.post(()->{ raw.setText(r); decodeAndShow(r); });
            }catch(Exception e){
                polling=false;
                ui.post(()->{ poll.setText("Start 2108"); ecu.setText("Polling stopped: "+e.getMessage()); });
            }
        }
    }

    private void decodeAndShow(String r){
        try{
            BanditLiveData d=BanditDecoder.decode(r);
            double q=parse(flow.getText().toString(),220.0);
            double netLatency=parse(latency.getText().toString(),0.600);
            double factor=parse(cal.getText().toString(),1.0);
            double flowingPw=FuelCalculator.effectivePulseMs(d.averageMs,netLatency);
            double rawLph=FuelCalculator.rawLitresPerHour(d.rpm,d.averageMs,q,factor);
            double estimatedLph=FuelCalculator.estimatedLitresPerHour(d.rpm,d.averageMs,q,netLatency,factor);
            live.setText(String.format(Locale.UK,
                    "RPM: %d\nInj1: %.3f ms\nInj2: %.3f ms\nInj3: %.3f ms\nInj4: %.3f ms\nAverage commanded PW: %.3f ms\nEstimated flowing PW: %.3f ms\nPW-only fuel: %.3f L/h\nEstimated fuel: %.3f L/h",
                    d.rpm,d.inj1,d.inj2,d.inj3,d.inj4,d.averageMs,flowingPw,rawLph,estimatedLph));
        }catch(Exception e){
            live.setText("Decoder not yet valid for this frame:\n"+e.getMessage()+"\n\nRaw frame above is still useful.");
        }
    }

    private void appendLog(String s){ ui.post(()->{ String old=log.getText().toString(); if(old.length()>12000)old=old.substring(old.length()-8000); log.setText(old+s+"\n"); }); }
    private double parse(String s,double def){ try{return Double.parseDouble(s.trim());}catch(Exception e){return def;} }
    private String safeName(BluetoothDevice d){ try{return d.getName()==null?d.getAddress():d.getName();}catch(SecurityException e){return "ELM327";} }
    private void toast(String s){ Toast.makeText(this,s,Toast.LENGTH_LONG).show(); }

    @Override protected void onDestroy(){
        polling=false;
        if(elm!=null)elm.close();
        io.shutdownNow();
        super.onDestroy();
    }
}
