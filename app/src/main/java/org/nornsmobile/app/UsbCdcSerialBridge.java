package org.nornsmobile.app;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Conservative USB CDC-ACM transport. Only standard CDC devices with bulk IN/OUT
 * endpoints are supported; FTDI, CP210x, CH340 and arbitrary vendor protocols are not. */
final class UsbCdcSerialBridge {
    interface Listener {
        void onConnected(int deviceId, String name);
        void onData(int deviceId, byte[] bytes);
        void onDisconnected(int deviceId, String reason);
        default void onConnected(int deviceId, long sessionToken, String name) { onConnected(deviceId, name); }
        default void onData(int deviceId, long sessionToken, byte[] bytes) { onData(deviceId, bytes); }
        default void onDisconnected(int deviceId, long sessionToken, String reason) { onDisconnected(deviceId, reason); }
        void onDevicesChanged();
    }
    static final class DeviceInfo {
        final int id, vendorId, productId;
        final String name, vendor, model, serial, interfaceName;
        DeviceInfo(UsbDevice d) {
            id=d.getDeviceId(); vendorId=d.getVendorId(); productId=d.getProductId();
            name=safe(d.getProductName(),"USB Serial "+id);
            vendor=safe(d.getManufacturerName(),String.format("%04x",vendorId));
            model=safe(d.getProductName(),String.format("%04x",productId));
            String value=""; try { value=d.getSerialNumber(); } catch(SecurityException ignored) {}
            serial=safe(value,"");
            UsbInterface control=findControlInterface(d);
            int interfaceId=control!=null?control.getId():0;
            interfaceName=String.format("%02x",interfaceId);
        }
    }
    private static final String ACTION_PERMISSION="org.nornsmobile.app.USB_SERIAL_PERMISSION";
    private static final String EXTRA_PERMISSION_TOKEN="org.nornsmobile.app.extra.USB_PERMISSION_TOKEN";
    private static final AtomicLong SESSION_TOKENS = new AtomicLong(1L);
    private final Context context;
    private final UsbManager manager;
    private final Listener listener;
    private final Map<Integer,Port> ports=new ConcurrentHashMap<>();
    private final UsbPermissionRequestGate<LineCoding> permissionRequests=new UsbPermissionRequestGate<>();
    private final AtomicBoolean closed=new AtomicBoolean(false);
    private final UsbLifecycleGate lifecycleGate=new UsbLifecycleGate();
    private final UsbPortCallbackGate receiverGate=new UsbPortCallbackGate();
    // Prevent a same-ID reconnect from claiming the USB interface while the old
    // connection is still draining callbacks and releasing its interfaces.
    private final UsbPortCloseGate portCloseGate=new UsbPortCloseGate();
    private final BroadcastReceiver receiver=new BroadcastReceiver(){
        @Override public void onReceive(Context c,Intent intent){
            String action=intent.getAction();
            UsbDevice device=intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if(device==null)return;
            int id=device.getDeviceId();
            if(ACTION_PERMISSION.equals(action)){
                handlePermissionResult(device, intent.getBooleanExtra(
                        UsbManager.EXTRA_PERMISSION_GRANTED, false),
                        intent.getIntExtra(EXTRA_PERMISSION_TOKEN, 0));
            }else if(UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)
                    ||UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)){
                // Serialize hotplug with permission/open/close and reject broadcasts
                // that race with shutdown after the receiver has begun dispatching.
                receiverGate.dispatch(() -> lifecycleGate.isActive(), () -> {
                    if(UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)){
                        notifyDevicesChanged();
                    }else{
                        long sessionToken = close(id);
                        if (sessionToken > 0L) notifyDisconnected(id, sessionToken, "dispositivo USB desconectado");
                        else notifyDisconnected(id, "dispositivo USB desconectado");
                        notifyDevicesChanged();
                    }
                });
            }
        }
    };
    UsbCdcSerialBridge(Context context,Listener listener){
        this.context=context.getApplicationContext();
        this.manager=(UsbManager)this.context.getSystemService(Context.USB_SERVICE);
        this.listener=listener;
        IntentFilter filter=new IntentFilter(ACTION_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if(Build.VERSION.SDK_INT>=33)this.context.registerReceiver(receiver,filter,Context.RECEIVER_NOT_EXPORTED);
        else this.context.registerReceiver(receiver,filter);
    }
    static int baudFromTermios(int value){
        switch(value){
            case 0:return 0; case 1:return 50; case 2:return 75; case 3:return 110;
            case 4:return 134; case 5:return 150; case 6:return 200; case 7:return 300;
            case 8:return 600; case 9:return 1200; case 10:return 1800; case 11:return 2400;
            case 12:return 4800; case 13:return 9600; case 14:return 19200; case 15:return 38400;
            case 4097:return 57600; case 4098:return 115200; case 4099:return 230400;
            case 4100:return 460800; case 4101:return 500000; case 4102:return 576000;
            case 4103:return 921600; case 4104:return 1000000; case 4105:return 1152000;
            case 4106:return 1500000; case 4107:return 2000000; case 4108:return 2500000;
            case 4109:return 3000000; case 4110:return 3500000; case 4111:return 4000000;
            default:return value>=50&&value<=4000000?value:-1;
        }
    }
    static final class LineCoding {
        final int baudRate, stopBits, parity, dataBits;
        LineCoding(int baudRate,int stopBits,int parity,int dataBits){
            this.baudRate=baudRate;this.stopBits=stopBits;this.parity=parity;this.dataBits=dataBits;
        }
    }
    /** Maps Linux termios c_cflag to CDC ACM SET_LINE_CODING fields. */
    static LineCoding lineCodingFromTermios(int baud,int cflag){
        if(baud<=0||baud>4000000)return null;
        int size=cflag&0x30;
        int dataBits=size==0?5:size==0x10?6:size==0x20?7:8;
        // CDC bCharFormat: 0=1 stop, 1=1.5 stops, 2=2 stops.
        int stopBits=(cflag&0x40)==0?0:(dataBits==5?1:2);
        // CDC bParityType: 0=none, 1=odd, 2=even, 3=mark, 4=space.
        // Linux CMSPAR selects mark/space; PARODD distinguishes the two.
        int parity=(cflag&0x100)==0?0:((cflag&0x40000000)!=0
                ?((cflag&0x200)!=0?3:4):((cflag&0x200)!=0?1:2));
        return new LineCoding(baud,stopBits,parity,dataBits);
    }
    List<DeviceInfo> devices(){
        if(manager==null||closed.get())return Collections.emptyList();
        List<DeviceInfo> result=new ArrayList<>();
        for(UsbDevice d:manager.getDeviceList().values())if(findDataInterface(d)!=null)result.add(new DeviceInfo(d));
        return result;
    }
    boolean open(int id,int baud){ return open(id,baud,0x30); }
    boolean open(int id,int baud,int cflag){
        return lifecycleGate.runIfActive(() -> {
        LineCoding coding=lineCodingFromTermios(baud,cflag);
        if(closed.get()||manager==null||coding==null||portCloseGate.isClosing(id))return false;
        UsbDevice d=findDevice(id); if(d==null||findDataInterface(d)==null)return false;
        if(ports.containsKey(id))return true;
        UsbPermissionRequestGate.Request<LineCoding> request=null;
        try {
            if(manager.hasPermission(d)) {
                permissionRequests.cancel(id);
                return openGranted(d,coding);
            }
            // Do not display duplicate permission prompts for repeated Lua refresh/open calls.
            if(permissionRequests.isPending(id))return true;
            request=permissionRequests.begin(id,coding);
            Intent intent=new Intent(ACTION_PERMISSION).setPackage(context.getPackageName())
                    .putExtra(EXTRA_PERMISSION_TOKEN,request.token);
            int flags=PendingIntent.FLAG_UPDATE_CURRENT;
            if(Build.VERSION.SDK_INT>=31)flags|=PendingIntent.FLAG_MUTABLE;
            manager.requestPermission(d,PendingIntent.getBroadcast(context,request.token,intent,flags));
            return true;
        } catch(RuntimeException error) {
            if(request!=null)permissionRequests.cancel(id,request.token);
            AppLog.e("USB serial: falha ao solicitar permissão/abrir dispositivo: "+error.getMessage());
            return false;
        }
        }, false);
    }
    boolean write(int id,byte[] bytes){
        final Port p=ports.get(id);
        if(p==null||bytes==null||bytes.length==0||bytes.length>65536)return false;
        synchronized(p.callbackGate.lock){
            if(!p.running.get()||!p.callbackGate.isActive())return false;
            return UsbCdcWritePump.write(bytes,
                    (offset,length)->p.connection.bulkTransfer(p.out,bytes,offset,length,1000),
                    ()->p.running.get()&&p.callbackGate.isActive());
        }
    }
    long close(int id){
        final Port[] removed=new Port[1];
        lifecycleGate.runSerialized(() -> {
            removed[0]=ports.remove(id);
            permissionRequests.cancel(id);
            if(removed[0]!=null)portCloseGate.beginClose(id);
        });
        Port p=removed[0];
        if(p==null)return 0L;
        try {
            p.callbackGate.close(new Runnable(){ @Override public void run(){
                p.running.set(false); if(p.reader!=null)p.reader.interrupt();
                try{p.connection.releaseInterface(p.dataInterface);}catch(RuntimeException ignored){}
                if(p.controlInterface!=null&&p.controlInterface!=p.dataInterface)
                    try{p.connection.releaseInterface(p.controlInterface);}catch(RuntimeException ignored){}
                p.connection.close();
            }});
        } finally {
            portCloseGate.finishClose(id);
        }
        return p.sessionToken;
    }
    void closeAll(){
        // Serialize the snapshot and every close with open()/permission completion.
        // Otherwise an open can insert a port just after the snapshot and survive stop().
        lifecycleGate.runSerialized(() -> {
            for(Integer id:new ArrayList<>(ports.keySet()))close(id);
            permissionRequests.clear();
        });
        // A port is removed from the public map before its callbacks drain. Wait
        // for those in-flight closes too, otherwise shutdown could return while
        // an interface is still claimed by the old connection.
        portCloseGate.awaitIdle();
    }
    void shutdown(){
        lifecycleGate.shutdown(() -> {
            closed.set(true);
            permissionRequests.clear();
            // First drain in-flight hotplug callbacks and unregister the receiver.
            // Only then close ports, so detach cannot race with closeAll().
            receiverGate.close(() -> {
                try{context.unregisterReceiver(receiver);}catch(IllegalArgumentException ignored){}
            });
            closeAll();
        });
    }
    private UsbDevice findDevice(int id){
        if(manager==null)return null;
        for(UsbDevice d:manager.getDeviceList().values())if(d.getDeviceId()==id)return d;
        return null;
    }
    /** Permission completion and close/open are serialized on the lifecycle lock. */
    private void handlePermissionResult(UsbDevice device, boolean granted, int token){
        lifecycleGate.runIfActive(() -> {
            int id=device.getDeviceId();
            UsbPermissionRequestGate.Request<LineCoding> request=permissionRequests.consume(id,token);
            if(request==null||closed.get())return;
            LineCoding coding=request.value;
            if(granted) {
                try {
                    if(!openGranted(device,coding))
                        AppLog.e("USB serial: não foi possível abrir o dispositivo "+id+".");
                } catch(RuntimeException error) {
                    notifyDisconnected(id,"falha ao abrir dispositivo USB: "+error.getMessage());
                    AppLog.e("USB serial: falha ao concluir abertura após permissão: "+error.getMessage());
                }
            } else {
                notifyDisconnected(id,"permissão USB negada");
            }
        });
    }
    private boolean openGranted(UsbDevice d,LineCoding lineCoding){
        int id=d.getDeviceId();
        if(portCloseGate.isClosing(id))return false;
        if(ports.containsKey(id)||closed.get())return ports.containsKey(id);
        UsbInterface data=findDataInterface(d); if(data==null){notifyDisconnected(id,"interface CDC-ACM sem endpoints bulk");return false;}
        UsbInterface control=findControlInterface(d); UsbEndpoint in=null,out=null;
        for(int i=0;i<data.getEndpointCount();i++){
            UsbEndpoint ep=data.getEndpoint(i); if(ep.getType()!=UsbConstants.USB_ENDPOINT_XFER_BULK)continue;
            if(ep.getDirection()==UsbConstants.USB_DIR_IN)in=ep;else if(ep.getDirection()==UsbConstants.USB_DIR_OUT)out=ep;
        }
        if(in==null||out==null){notifyDisconnected(id,"endpoints bulk IN/OUT ausentes");return false;}
        UsbDeviceConnection conn=manager.openDevice(d); if(conn==null){notifyDisconnected(id,"UsbManager.openDevice falhou");return false;}
        boolean claimedControl=control==null||conn.claimInterface(control,true);
        boolean claimedData=claimedControl&&conn.claimInterface(data,true);
        if(!claimedData){if(claimedControl&&control!=null)conn.releaseInterface(control);conn.close();notifyDisconnected(id,"falha ao reservar interface USB");return false;}
        int index=control!=null?control.getId():data.getId();
        byte[] coding=ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN).putInt(lineCoding.baudRate)
                .put((byte)lineCoding.stopBits).put((byte)lineCoding.parity).put((byte)lineCoding.dataBits).array();
        int configured=conn.controlTransfer(0x21,0x20,0,index,coding,coding.length,1000);
        if(configured!=coding.length){conn.releaseInterface(data);if(control!=null)conn.releaseInterface(control);conn.close();notifyDisconnected(id,"CDC SET_LINE_CODING rejeitado");return false;}
        conn.controlTransfer(0x21,0x22,0x03,index,null,0,1000);
        Port p=new Port(d,conn,data,control,in,out,SESSION_TOKENS.getAndIncrement());
        if(ports.putIfAbsent(id,p)!=null){conn.releaseInterface(data);if(control!=null)conn.releaseInterface(control);conn.close();return true;}
        notifyConnected(id,p.sessionToken,new DeviceInfo(d).name);
        p.reader=new Thread(()->readLoop(p),"norns-usb-serial-"+id);p.reader.setDaemon(true);p.reader.start();
        return true;
    }
    private void readLoop(Port p){
        byte[] buffer=new byte[4096];
        while(p.running.get()&&!closed.get()){
            int n;
            try{n=p.connection.bulkTransfer(p.in,buffer,buffer.length,250);}
            catch(RuntimeException e){n=-1;}
            if(n>0){
                byte[] data=new byte[n];
                System.arraycopy(buffer,0,data,0,n);
                boolean delivered=p.callbackGate.dispatch(new java.util.function.BooleanSupplier(){
                    @Override public boolean getAsBoolean(){return p.running.get()&&!closed.get()
                            &&ports.get(p.device.getDeviceId())==p;}
                },new Runnable(){ @Override public void run(){notifyData(p.device.getDeviceId(),p.sessionToken,data);} });
                if(!delivered)break;
            }else if(!p.running.get()||closed.get()){
                break;
            }else{
                // Android returns -1 on a read timeout. Avoid a hot loop when
                // a device is idle or briefly unavailable.
                try{Thread.sleep(10);}
                catch(InterruptedException interrupted){
                    if(!p.running.get()||closed.get())break;
                }
            }
        }
    }
    private void notifyConnected(int id,long token,String name){
        try{listener.onConnected(id,token,name);}
        catch(RuntimeException e){AppLog.e("USB serial connected callback: "+e.getMessage());}
    }
    private void notifyData(int id,long token,byte[] data){
        try{listener.onData(id,token,data);}
        catch(RuntimeException e){AppLog.e("USB serial data callback: "+e.getMessage());}
    }
    private void notifyDisconnected(int id,String reason){
        try{listener.onDisconnected(id,reason);}
        catch(RuntimeException e){AppLog.e("USB serial disconnected callback: "+e.getMessage());}
    }
    private void notifyDisconnected(int id,long token,String reason){
        try{listener.onDisconnected(id,token,reason);}
        catch(RuntimeException e){AppLog.e("USB serial disconnected callback: "+e.getMessage());}
    }
    private void notifyDevicesChanged(){
        try{listener.onDevicesChanged();}
        catch(RuntimeException e){AppLog.e("USB serial devices callback: "+e.getMessage());}
    }
    private static UsbInterface findDataInterface(UsbDevice d){
        for(int i=0;i<d.getInterfaceCount();i++){
            UsbInterface candidate=d.getInterface(i);if(candidate.getInterfaceClass()!=10)continue;
            boolean in=false,out=false;
            for(int e=0;e<candidate.getEndpointCount();e++){
                UsbEndpoint ep=candidate.getEndpoint(e);
                if(ep.getType()==UsbConstants.USB_ENDPOINT_XFER_BULK){
                    if(ep.getDirection()==UsbConstants.USB_DIR_IN)in=true;
                    if(ep.getDirection()==UsbConstants.USB_DIR_OUT)out=true;
                }
            }
            if(in&&out)return candidate;
        }return null;
    }
    private static UsbInterface findControlInterface(UsbDevice d){
        for(int i=0;i<d.getInterfaceCount();i++){
            UsbInterface x=d.getInterface(i);if(x.getInterfaceClass()==2&&x.getInterfaceSubclass()==2)return x;
        }return null;
    }
    private static String safe(String v,String fallback){return v==null||v.length()==0?fallback:v;}
    private static final class Port{
        final UsbDevice device;final UsbDeviceConnection connection;final long sessionToken;
        final UsbInterface dataInterface,controlInterface;final UsbEndpoint in,out;
        final AtomicBoolean running=new AtomicBoolean(true);final UsbPortCallbackGate callbackGate=new UsbPortCallbackGate();Thread reader;
        Port(UsbDevice d,UsbDeviceConnection c,UsbInterface di,UsbInterface ci,UsbEndpoint in,UsbEndpoint out,long token){
            device=d;connection=c;sessionToken=token;dataInterface=di;controlInterface=ci;this.in=in;this.out=out;
        }
    }
}
