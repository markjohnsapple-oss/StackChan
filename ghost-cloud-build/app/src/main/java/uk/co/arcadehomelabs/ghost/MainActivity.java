package uk.co.arcadehomelabs.ghost;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.media.*;
import android.net.Uri;
import android.os.*;
import android.speech.tts.*;
import android.util.Base64;
import android.webkit.*;
import org.json.JSONObject;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
  private static final UUID SVC=UUID.fromString("c9000001-8289-4bda-9d7b-0d3da9e323e1");
  private static final UUID RX=UUID.fromString("c9000002-8289-4bda-9d7b-0d3da9e323e1");
  private static final UUID TX=UUID.fromString("c9000003-8289-4bda-9d7b-0d3da9e323e1");
  private static final UUID AUDIO=UUID.fromString("c9000004-8289-4bda-9d7b-0d3da9e323e1");
  private static final UUID CCC=UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
  private final Handler main=new Handler(Looper.getMainLooper());
  private final ExecutorService worker=Executors.newSingleThreadExecutor();
  private WebView web;
  private BluetoothAdapter adapter;
  private BluetoothLeScanner scanner;
  private BluetoothGatt gatt;
  private BluetoothGattCharacteristic rx, tx, audio;
  private volatile boolean ready=false;
  private volatile long generation=0;
  private boolean scanning=false, writeBusy=false;
  private String pendingId="";
  private final ArrayDeque<Job> queue=new ArrayDeque<>();
  private Job inFlight;
  private TextToSpeech tts;
  private volatile boolean ttsReady=false;
  private final Map<String,Integer> speakIds=new ConcurrentHashMap<>();
  private final Map<String,Long> speakGenerations=new ConcurrentHashMap<>();

  private static class Job {
    BluetoothGattCharacteristic characteristic;
    byte[] data;
    int id;
    boolean response;
    Job(BluetoothGattCharacteristic c,byte[] d,int i,boolean r){characteristic=c;data=d;id=i;response=r;}
  }
  @Override public void onCreate(Bundle state){
    super.onCreate(state);
    web=new WebView(this);setContentView(web);
    WebSettings s=web.getSettings();
    s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setAllowFileAccess(true);s.setMediaPlaybackRequiresUserGesture(false);
    web.setWebChromeClient(new WebChromeClient());
    web.setWebViewClient(new WebViewClient(){
      @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){
        String u=r.getUrl().toString();
        if(u.startsWith("file://"))return false;
        if(r.isForMainFrame()){try{startActivity(new Intent(Intent.ACTION_VIEW,r.getUrl()));}catch(Exception ignored){}return true;}
        return false;
      }
    });
    web.addJavascriptInterface(new Bridge(),"GhostAndroid");
    BluetoothManager m=(BluetoothManager)getSystemService(BLUETOOTH_SERVICE);
    adapter=m==null?null:m.getAdapter();
    requestBtPermissions();
    tts=new TextToSpeech(this,result->{
      ttsReady=result==TextToSpeech.SUCCESS;
      if(ttsReady){tts.setLanguage(Locale.UK);tts.setOnUtteranceProgressListener(ttsListener);}
    });
    openGhostOrPicker();
  }
  private void openGhostOrPicker(){
    try(InputStream in=getAssets().open("index.html")){web.loadUrl("file:///android_asset/index.html");return;}catch(Exception ignored){}
    File f=new File(getFilesDir(),"ghost.html");
    if(f.exists()&&f.length()>50000)web.loadUrl("file://"+f.getAbsolutePath());
    else web.loadDataWithBaseURL("https://appassets.androidplatform.net/",
       "<html><meta name='viewport' content='width=device-width,initial-scale=1'>"+
       "<body style='font:18px system-ui;background:#f6f1e9;color:#222;padding:32px;text-align:center'>"+
       "<h2>Ghost AI Home</h2><p>Choose the GHOST_APP_3_0_4_ANDROID_AUDIO.html file just once.</p>"+
       "<button style='font-size:20px;padding:15px;background:#9ee5b2;border-radius:12px' onclick='GhostAndroid.chooseFile()'>Open Ghost HTML</button></body></html>",
       "text/html","UTF-8",null);
  }
  @Override protected void onActivityResult(int request,int result,Intent data){
    super.onActivityResult(request,result,data);
    if(request==774&&result==RESULT_OK&&data!=null&&data.getData()!=null){
      Uri u=data.getData();File f=new File(getFilesDir(),"ghost.html");
      try(InputStream in=getContentResolver().openInputStream(u);OutputStream out=new FileOutputStream(f)){
        byte[] buf=new byte[65536];int n;while((n=in.read(buf))>0)out.write(buf,0,n);
        web.loadUrl("file://"+f.getAbsolutePath());
      }catch(Exception e){web.loadData("<h2>Could not load HTML</h2>"+e,"text/html","UTF-8");}
    }
  }
  private void requestBtPermissions(){
    if(Build.VERSION.SDK_INT>=31){
      ArrayList<String> missing=new ArrayList<>();
      for(String p:new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT})
        if(checkSelfPermission(p)!=PackageManager.PERMISSION_GRANTED)missing.add(p);
      if(!missing.isEmpty())requestPermissions(missing.toArray(new String[0]),101);
    }else if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)
      requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},101);
  }
  private boolean permitted(){
    if(Build.VERSION.SDK_INT>=31)
      return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED
        &&checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED;
    return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;
  }
  private void js(String s){main.post(()->{if(web!=null)web.evaluateJavascript(s,null);});}
  private void signal(String name,String... args){
    StringBuilder b=new StringBuilder("window.").append(name).append("&&window.").append(name).append('(');
    for(int i=0;i<args.length;i++){if(i>0)b.append(',');b.append(JSONObject.quote(args[i]));}
    js(b.append(");").toString());
  }
  private final ScanCallback scanCb=new ScanCallback(){
    @Override public void onScanResult(int type,ScanResult r){
      BluetoothDevice d=r.getDevice();if(d==null)return;
      String n="";
      try{n=d.getName();}catch(SecurityException ignored){}
      if(n==null&&r.getScanRecord()!=null)n=r.getScanRecord().getDeviceName();
      if(n!=null&&n.startsWith("Ghost-PocketPal")){
        String address=d.getAddress();
        getPreferences(0).edit().putString("device",address).putString("name",n).apply();
        stopScan();signal("__nativeFound",address,n);
      }
    }
    @Override public void onScanFailed(int error){scanning=false;signal("__nativeScanFailed","Scan failed "+error);}
  };
  private void stopScan(){
    if(scanning){scanning=false;try{scanner.stopScan(scanCb);}catch(Exception ignored){}}
  }
  private void scan(){
    if(adapter==null||!permitted()){requestBtPermissions();signal("__nativeScanFailed","Allow Bluetooth, then try again");return;}
    if(!adapter.isEnabled()){signal("__nativeScanFailed","Turn on Bluetooth");return;}
    try{scanner=adapter.getBluetoothLeScanner();stopScan();scanner.startScan(scanCb);scanning=true;
      main.postDelayed(()->{if(scanning){stopScan();signal("__nativeScanFailed","Pocket Pal not found");}},12000);
    }catch(Exception e){signal("__nativeScanFailed",String.valueOf(e.getMessage()));}
  }
  private void connect(String address,String id){
    if(adapter==null||!permitted()){signal("__nativeConnectResult",id,"Bluetooth permission required");return;}
    try{
      if(gatt!=null){gatt.disconnect();gatt.close();}
      ready=false;rx=tx=audio=null;pendingId=id;
      gatt=adapter.getRemoteDevice(address).connectGatt(this,false,gattCb,BluetoothDevice.TRANSPORT_LE);
    }catch(Exception e){signal("__nativeConnectResult",id,String.valueOf(e));}
  }
  private final BluetoothGattCallback gattCb=new BluetoothGattCallback(){
    @Override public void onConnectionStateChange(BluetoothGatt g,int status,int state){
      if(g!=gatt)return;
      if(status==BluetoothGatt.GATT_SUCCESS&&state==BluetoothProfile.STATE_CONNECTED){
        if(permitted()){if(!g.requestMtu(247))g.discoverServices();main.postDelayed(()->{if(g==gatt&&!ready&&permitted())g.discoverServices();},1600);}
      }else{
        ready=false;rx=tx=audio=null;
        main.post(()->{queue.clear();inFlight=null;writeBusy=false;});
        signal("__nativeDisconnected");
        if(!pendingId.isEmpty()){signal("__nativeConnectResult",pendingId,"Disconnected");pendingId="";}
      }
    }
    @Override public void onMtuChanged(BluetoothGatt g,int mtu,int status){if(permitted())g.discoverServices();}
    @Override public void onServicesDiscovered(BluetoothGatt g,int status){
      BluetoothGattService s=g.getService(SVC);
      if(status!=BluetoothGatt.GATT_SUCCESS||s==null){failConnect("Ghost BLE service missing");return;}
      rx=s.getCharacteristic(RX);tx=s.getCharacteristic(TX);audio=s.getCharacteristic(AUDIO);
      if(rx==null||tx==null){failConnect("BLE commands unavailable");return;}
      if(!g.setCharacteristicNotification(tx,true)){failConnect("Notifications unavailable");return;}
      BluetoothGattDescriptor ccc=tx.getDescriptor(CCC);
      if(ccc==null){failConnect("Missing descriptor");return;}
      if(Build.VERSION.SDK_INT>=33)g.writeDescriptor(ccc,BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
      else{ccc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);g.writeDescriptor(ccc);}
    }
    @Override public void onDescriptorWrite(BluetoothGatt g,BluetoothGattDescriptor d,int status){
      if(status==BluetoothGatt.GATT_SUCCESS){ready=true;signal("__nativeConnectResult",pendingId,"");pendingId="";}
      else failConnect("Notification setup failed");
    }
    @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] v){
      if(c.getUuid().equals(TX))signal("__nativeBleNotify",Base64.encodeToString(v,Base64.NO_WRAP));
    }
    @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c){
      if(c.getUuid().equals(TX))signal("__nativeBleNotify",Base64.encodeToString(c.getValue(),Base64.NO_WRAP));
    }
    @Override public void onCharacteristicWrite(BluetoothGatt g,BluetoothGattCharacteristic c,int status){
      main.post(()->{if(inFlight!=null&&inFlight.response&&c.getUuid().equals(inFlight.characteristic.getUuid()))doneWrite(status==BluetoothGatt.GATT_SUCCESS?"":"BLE write failed");});
    }
  };
  private void failConnect(String why){signal("__nativeConnectResult",pendingId,why);pendingId="";}
  private void send(BluetoothGattCharacteristic c,byte[] data,int id,boolean response){
    main.post(()->{
      if(!ready||gatt==null||c==null){if(id>=0)signal("__nativeBleWriteComplete",""+id,"Not connected");return;}
      if(queue.size()>200){if(id>=0)signal("__nativeBleWriteComplete",""+id,"Bluetooth backed up");return;}
      queue.add(new Job(c,data,id,response));pump();
    });
  }
  private void pump(){
    if(writeBusy||queue.isEmpty()||!ready||gatt==null)return;
    Job j=queue.poll();inFlight=j;writeBusy=true;
    int kind=j.response?BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT:BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
    boolean ok=false;
    try{
      if(Build.VERSION.SDK_INT>=33)ok=gatt.writeCharacteristic(j.characteristic,j.data,kind)==BluetoothStatusCodes.SUCCESS;
      else{j.characteristic.setWriteType(kind);j.characteristic.setValue(j.data);ok=gatt.writeCharacteristic(j.characteristic);}
    }catch(Exception ignored){}
    if(!ok){doneWrite("Bluetooth write failed");return;}
    main.postDelayed(()->{if(inFlight==j)doneWrite(j.response?"Write timeout":"");},j.response?1800:32);
  }
  private void doneWrite(String error){
    if(!writeBusy)return;
    Job j=inFlight;inFlight=null;writeBusy=false;
    if(j!=null&&j.id>=0)signal("__nativeBleWriteComplete",""+j.id,error);
    pump();
  }
  public class Bridge {
    @JavascriptInterface public void chooseFile(){
      main.post(()->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("text/html");i.addCategory(Intent.CATEGORY_OPENABLE);
      try{startActivityForResult(i,774);}catch(Exception e){i.setType("*/*");startActivityForResult(i,774);}});
    }
    @JavascriptInterface public void scan(){main.post(()->MainActivity.this.scan());}
    @JavascriptInterface public void connect(String address,String id){main.post(()->MainActivity.this.connect(address,id));}
    @JavascriptInterface public String savedDevice(){
      String a=getPreferences(0).getString("device","");
      if(a.isEmpty())return "";
      try{JSONObject o=new JSONObject();o.put("id",a);o.put("name",getPreferences(0).getString("name","Ghost-PocketPal"));return o.toString();}catch(Exception ignored){return "";}
    }
    @JavascriptInterface public void write(String base64,int id){
      try{send(rx,Base64.decode(base64,Base64.DEFAULT),id,true);}
      catch(Exception e){signal("__nativeBleWriteComplete",""+id,String.valueOf(e.getMessage()));}
    }
    @JavascriptInterface public boolean canStream(){return ready&&audio!=null;}
    @JavascriptInterface public void cancelSpeech(){generation++;if(tts!=null)tts.stop();}
    @JavascriptInterface public void speak(int id,String words,float rate,float pitch,float volume){
      if(!ttsReady){signal("__ghostNativeSpeechSignal",""+id,"error");return;}
      String token="ghost_"+id+"_"+System.nanoTime();
      File f=new File(getCacheDir(),token+".wav");
      long gen=++generation;
      speakIds.put(token,id);speakGenerations.put(token,gen);
      tts.setSpeechRate(Math.max(.5f,Math.min(1.6f,rate)));
      tts.setPitch(Math.max(.7f,Math.min(1.6f,pitch)));
      Bundle params=new Bundle();params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME,Math.max(0,Math.min(1,volume)));
      int rc=tts.synthesizeToFile(words,params,f,token);
      if(rc!=TextToSpeech.SUCCESS){speakIds.remove(token);speakGenerations.remove(token);signal("__ghostNativeSpeechSignal",""+id,"error");}
    }
  }
  private final UtteranceProgressListener ttsListener=new UtteranceProgressListener(){
    @Override public void onStart(String token){}
    @Override public void onDone(String token){
      Integer id=speakIds.remove(token);Long gen=speakGenerations.remove(token);
      File wav=new File(getCacheDir(),token+".wav");
      if(id==null||gen==null||gen!=generation){wav.delete();return;}
      worker.execute(()->{
        try{
          short[] pcm=decode(wav);
          if(pcm.length==0)throw new IllegalStateException("No speech audio");
          signal("__ghostNativeSpeechSignal",""+id,"start");
          playAndStream(pcm,gen);
          signal("__ghostNativeSpeechSignal",""+id,"end");
        }catch(Exception ex){signal("__ghostNativeSpeechSignal",""+id,"error");}
        finally{wav.delete();}
      });
    }
    @Override public void onError(String token){
      Integer id=speakIds.remove(token);speakGenerations.remove(token);
      if(id!=null)signal("__ghostNativeSpeechSignal",""+id,"error");
    }
  };
  private static int u16(byte[] x,int i){return (x[i]&255)|((x[i+1]&255)<<8);}
  private static int u32(byte[] x,int i){return u16(x,i)|(u16(x,i+2)<<16);}
  private static short[] decode(File wav)throws Exception{
    byte[] w=Files.readAllBytes(wav.toPath());
    if(w.length<44||w[0]!='R'||w[1]!='I'||w[2]!='F'||w[3]!='F')throw new IllegalArgumentException("Voice was not WAV");
    int ch=1,rate=0,bits=0,enc=0,offset=-1,length=0;
    for(int p=12;p+8<=w.length;){
      int n=u32(w,p+4);if(n<0||n>w.length-p-8)break;
      String tag=new String(w,p,4,java.nio.charset.StandardCharsets.US_ASCII);
      if(tag.equals("fmt ")&&n>=16){enc=u16(w,p+8);ch=u16(w,p+10);rate=u32(w,p+12);bits=u16(w,p+22);}
      if(tag.equals("data")){offset=p+8;length=n;break;}
      p+=8+n+(n&1);
    }
    if(enc!=1||bits!=16||(ch!=1&&ch!=2)||rate<8000||offset<0)throw new IllegalArgumentException("Unsupported voice audio");
    int frames=length/(ch*2),size=(int)Math.min(240000,Math.round((double)frames*8000/rate));
    short[] out=new short[size];
    for(int i=0;i<size;i++){
      double pos=i*(rate/8000.0);int a=Math.min(frames-1,(int)pos),b=Math.min(frames-1,a+1);
      int va=(short)u16(w,offset+a*ch*2),vb=(short)u16(w,offset+b*ch*2);
      if(ch==2){va=(va+(short)u16(w,offset+a*ch*2+2))/2;vb=(vb+(short)u16(w,offset+b*ch*2+2))/2;}
      out[i]=(short)Math.max(-32768,Math.min(32767,Math.round(va*(1-pos+a)+vb*(pos-a))));
    }
    return out;
  }
  private static final int[] STEP={7,8,9,10,11,12,13,14,16,17,19,21,23,25,28,31,34,37,41,45,50,55,60,66,73,80,88,97,107,118,130,143,157,173,190,209,230,253,279,307,337,371,408,449,494,544,598,658,724,796,876,963,1060,1166,1282,1411,1552,1707,1878,2066,2272,2499,2749,3024,3327,3660,4026,4428,4871,5358,5894,6484,7132,7845,8630,9493,10442,11487,12635,13899,15289,16818,18500,20350,22385,24623,27086,29794,32767};
  private static final int[] INDEX={-1,-1,-1,-1,2,4,6,8,-1,-1,-1,-1,2,4,6,8};
  private static class Adpcm{
    int pred=0,idx=0;
    byte[] pack(short[] samples,int start,int length){
      byte[] out=new byte[(length+1)/2];
      for(int i=0;i<length;i++){
        int step=STEP[idx],diff=samples[start+i]-pred,sign=0;
        if(diff<0){sign=8;diff=-diff;}
        int bits=0,delta=step>>3;
        if(diff>=step){bits|=4;diff-=step;delta+=step;}
        if(diff>=step/2){bits|=2;diff-=step/2;delta+=step/2;}
        if(diff>=step/4){bits|=1;delta+=step/4;}
        int code=bits|sign;
        pred=Math.max(-32768,Math.min(32767,pred+(sign==0?delta:-delta)));
        idx=Math.max(0,Math.min(88,idx+INDEX[code]));
        if((i&1)==0)out[i/2]=(byte)(code<<4);else out[i/2]|=(byte)code;
      }
      return out;
    }
  }
  private void stream(byte[] pkt){if(ready&&audio!=null)send(audio,pkt,-1,false);}
  private void playAndStream(short[] pcm,long gen){
    int min=AudioTrack.getMinBufferSize(8000,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);
    AudioTrack tr=new AudioTrack(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
      new AudioFormat.Builder().setSampleRate(8000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
      Math.max(min,8000),AudioTrack.MODE_STREAM,AudioManager.AUDIO_SESSION_ID_GENERATE);
    boolean active=ready&&audio!=null;
    if(active)stream(new byte[]{(byte)0xB0});
    Adpcm e=new Adpcm();int seq=0;
    try{
      tr.play();
      for(int p=0;p<pcm.length&&gen==generation&&!Thread.currentThread().isInterrupted();){
        int n=Math.min(240,pcm.length-p);
        if(active&&ready){
          int pred=e.pred,idx=e.idx;
          byte[] compressed=e.pack(pcm,p,n);
          byte[] pkt=new byte[6+compressed.length];
          pkt[0]=(byte)0xB1;pkt[1]=(byte)seq;pkt[2]=(byte)(seq>>8);seq++;
          pkt[3]=(byte)pred;pkt[4]=(byte)(pred>>8);pkt[5]=(byte)idx;
          System.arraycopy(compressed,0,pkt,6,compressed.length);
          stream(pkt);
        }
        tr.write(pcm,p,n,AudioTrack.WRITE_BLOCKING);p+=n;
        if(active)Thread.sleep(22);
      }
      if(active)stream(new byte[]{(byte)0xB2});
      Thread.sleep(200);
    }catch(Exception ignored){}
    finally{try{tr.stop();}catch(Exception ignored){}tr.release();}
  }
  @Override public void onDestroy(){
    generation++;
    if(tts!=null){tts.stop();tts.shutdown();}
    if(scanner!=null&&scanning&&permitted())stopScan();
    if(gatt!=null&&permitted()){gatt.disconnect();gatt.close();}
    worker.shutdownNow();
    if(web!=null){web.removeJavascriptInterface("GhostAndroid");web.destroy();}
    super.onDestroy();
  }
}